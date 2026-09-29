package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredObject;
import com.qingjing.wallpaper.delivery.infrastructure.DynamicPhotoMediaProcessor;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Builds the paired HEIC and metadata-bearing MOV for one iOS Live Photo version. */
@Service
public class LivePhotoPublisher {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final DynamicPhotoMediaProcessor media;
    private final Semaphore slots = new Semaphore(1);

    public LivePhotoPublisher(JdbcTemplate jdbc, FileStorage storage, DynamicPhotoMediaProcessor media) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.media = media;
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void buildIfSupported(long versionId) {
        Version version = version(versionId);
        if (!version.platform().equals("IOS") || !version.resourceType().equals("LIVE_PHOTO")) return;
        build(version);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public void prepareForPublication(long versionId) {
        buildIfSupported(versionId);
    }

    private void build(Version version) {
        if (!slots.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Another Live Photo is being prepared");
        }
        StoredObject photoObject = null;
        StoredObject videoObject = null;
        try {
            jdbc.update("INSERT IGNORE INTO live_photo_package (resource_version_id, status) VALUES (?, 'PROCESSING')",
                    version.id());
            PackageRow current = packageRowForUpdate(version.id());
            if (current != null && current.status().equals("READY")) return;
            jdbc.update("""
                    UPDATE live_photo_package SET status='PROCESSING', error_code=NULL,
                        updated_at=UTC_TIMESTAMP(6) WHERE resource_version_id=?
                    """, version.id());
            Binding source = source(version.id());
            byte[] bytes;
            try (var content = storage.open(new StorageKey(source.storageKey()))) {
                if (content.sizeBytes() != source.sizeBytes() || source.sizeBytes() > 200L * 1024 * 1024) {
                    throw invalid();
                }
                bytes = content.inputStream().readNBytes(Math.toIntExact(source.sizeBytes()) + 1);
            } catch (IOException exception) {
                throw invalid();
            }
            if (bytes.length != source.sizeBytes()
                    || !SecurePackageCodec.sha256(bytes).equals(source.sha256())) throw invalid();

            DynamicPhotoMediaProcessor.LivePhoto generated = media.livePhoto(bytes);
            photoObject = store(generated.photo(), "heic", 20L * 1024 * 1024);
            videoObject = store(generated.video(), "mov", 80L * 1024 * 1024);
            deleteOnRollback(photoObject);
            deleteOnRollback(videoObject);
            int updated = jdbc.update("""
                    UPDATE live_photo_package
                    SET status='READY', photo_storage_key=?, photo_size_bytes=?, photo_sha256=?,
                        video_storage_key=?, video_size_bytes=?, video_sha256=?, asset_identifier=?,
                        duration_ms=?, width_px=?, height_px=?, input_video_codec=?, output_video_codec=?,
                        frame_rate=?, processing_mode=?, error_code=NULL
                    WHERE resource_version_id=? AND status='PROCESSING'
                    """,
                    photoObject.storageKey().value(), photoObject.sizeBytes(), photoObject.sha256(),
                    videoObject.storageKey().value(), videoObject.sizeBytes(), videoObject.sha256(),
                    generated.assetIdentifier(), generated.durationMs(), generated.width(), generated.height(),
                    generated.inputVideoCodec(), generated.outputVideoCodec(), generated.frameRate(),
                    generated.processingMode().name(), version.id());
            if (updated != 1) throw invalid();
        } catch (RuntimeException failure) {
            deleteQuietly(photoObject);
            deleteQuietly(videoObject);
            jdbc.update("""
                    UPDATE live_photo_package
                    SET status='REJECTED', photo_storage_key=NULL, photo_size_bytes=NULL, photo_sha256=NULL,
                        video_storage_key=NULL, video_size_bytes=NULL, video_sha256=NULL,
                        asset_identifier=NULL, duration_ms=NULL, width_px=NULL, height_px=NULL,
                        input_video_codec=NULL, output_video_codec=NULL, frame_rate=NULL,
                        processing_mode=NULL, error_code=?
                    WHERE resource_version_id=?
                    """, errorCode(failure), version.id());
            if (failure instanceof ApiException api) throw api;
            throw invalid();
        } finally {
            slots.release();
        }
    }

    private StoredObject store(byte[] bytes, String extension, long maximumBytes) {
        var staged = storage.stage(new ByteArrayInputStream(bytes), maximumBytes);
        try {
            return storage.commit(staged, extension);
        } catch (RuntimeException failure) {
            storage.discard(staged);
            throw failure;
        }
    }

    private Version version(long versionId) {
        List<Version> rows = jdbc.query("""
                SELECT rv.id, rv.status, v.platform, v.resource_type
                FROM resource_version rv JOIN wallpaper_variant v ON v.id=rv.variant_id
                WHERE rv.id=?
                """, (rs, row) -> new Version(
                rs.getLong("id"), rs.getString("status"), rs.getString("platform"),
                rs.getString("resource_type")), versionId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Resource version not found");
        }
        Version version = rows.get(0);
        if (!version.status().equals("READY") && !version.status().equals("PUBLISHED")) {
            throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT",
                    "Only a ready or published resource version can be prepared");
        }
        return version;
    }

    private Binding source(long versionId) {
        List<Binding> rows = jdbc.query("""
                SELECT a.storage_key, a.size_bytes, a.sha256
                FROM resource_binding rb JOIN asset a ON a.id=rb.asset_id
                WHERE rb.resource_version_id=? AND rb.role='LIVE_PHOTO_SOURCE'
                  AND rb.ordinal=0 AND a.deleted_at IS NULL AND a.validation_status='READY'
                  AND a.purpose='LIVE_PHOTO_SOURCE'
                """, (rs, row) -> new Binding(
                rs.getString("storage_key"), rs.getLong("size_bytes"), rs.getString("sha256")), versionId);
        if (rows.size() != 1) throw invalid();
        return rows.get(0);
    }

    private PackageRow packageRowForUpdate(long versionId) {
        return jdbc.query("SELECT status FROM live_photo_package WHERE resource_version_id=? FOR UPDATE",
                (rs, row) -> new PackageRow(rs.getString("status")), versionId)
                .stream().findFirst().orElse(null);
    }

    private void deleteQuietly(StoredObject object) {
        if (object == null) return;
        try { storage.delete(object.storageKey()); } catch (RuntimeException ignored) { }
    }

    private void deleteOnRollback(StoredObject object) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != TransactionSynchronization.STATUS_COMMITTED) deleteQuietly(object);
            }
        });
    }

    private static String errorCode(RuntimeException failure) {
        if (failure instanceof ApiException api
                && List.of("DYNAMIC_SOURCE_FORMAT_INVALID", "DYNAMIC_SOURCE_DURATION_INVALID",
                        "IOS_LIVE_PHOTO_FRAME_COUNT_INVALID").contains(api.code())) {
            return api.code();
        }
        return "LIVE_PHOTO_PROCESSING_FAILED";
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LIVE_PHOTO_PROCESSING_FAILED",
                "The Live Photo source cannot be processed");
    }

    private record Version(long id, String status, String platform, String resourceType) { }
    private record Binding(String storageKey, long sizeBytes, String sha256) { }
    private record PackageRow(String status) { }
}
