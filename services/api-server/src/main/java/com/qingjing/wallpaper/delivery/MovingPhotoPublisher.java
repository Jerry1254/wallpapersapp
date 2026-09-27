package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredObject;
import com.qingjing.wallpaper.delivery.infrastructure.PackageMediaInspector;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Builds the two derived delivery files for one HarmonyOS Moving Photo resource version. */
@Service
public final class MovingPhotoPublisher {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final PackageMediaInspector media;
    private final Semaphore slots = new Semaphore(1);

    public MovingPhotoPublisher(JdbcTemplate jdbc, FileStorage storage, PackageMediaInspector media) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.media = media;
    }

    public void buildIfSupported(long versionId) {
        Version version = version(versionId);
        if (!version.platform().equals("HARMONYOS") || !version.resourceType().equals("MOVING_PHOTO")) return;
        build(version);
    }

    public void prepareForPublication(long versionId) {
        buildIfSupported(versionId);
    }

    private void build(Version version) {
        PackageRow existing = packageRow(version.id());
        if (existing != null && existing.status().equals("READY")) return;
        if (!slots.tryAcquire()) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED",
                    "Another Moving Photo is being prepared");
        }
        StoredObject videoObject = null;
        StoredObject posterObject = null;
        try {
            jdbc.update("""
                    INSERT INTO moving_photo_package (resource_version_id, status)
                    VALUES (?, 'PROCESSING')
                    ON DUPLICATE KEY UPDATE status='PROCESSING', error_code=NULL, updated_at=UTC_TIMESTAMP(6)
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

            PackageMediaInspector.MovingPhoto generated = media.movingPhoto(bytes);
            videoObject = store(generated.video(), "mp4", 60L * 1024 * 1024);
            posterObject = store(generated.poster(), "jpg", 10L * 1024 * 1024);
            int updated = jdbc.update("""
                    UPDATE moving_photo_package
                    SET status='READY', video_storage_key=?, video_size_bytes=?, video_sha256=?,
                        poster_storage_key=?, poster_size_bytes=?, poster_sha256=?,
                        duration_ms=?, width_px=?, height_px=?, error_code=NULL
                    WHERE resource_version_id=? AND status='PROCESSING'
                    """,
                    videoObject.storageKey().value(), videoObject.sizeBytes(), videoObject.sha256(),
                    posterObject.storageKey().value(), posterObject.sizeBytes(), posterObject.sha256(),
                    generated.durationMs(), generated.width(), generated.height(), version.id());
            if (updated != 1) throw invalid();
        } catch (RuntimeException failure) {
            deleteQuietly(videoObject);
            deleteQuietly(posterObject);
            jdbc.update("""
                    UPDATE moving_photo_package
                    SET status='REJECTED', video_storage_key=NULL, video_size_bytes=NULL, video_sha256=NULL,
                        poster_storage_key=NULL, poster_size_bytes=NULL, poster_sha256=NULL,
                        duration_ms=NULL, width_px=NULL, height_px=NULL, error_code='MOVING_PHOTO_PROCESSING_FAILED'
                    WHERE resource_version_id=?
                    """, version.id());
            throw failure;
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
                WHERE rb.resource_version_id=? AND rb.role='MOVING_PHOTO_SOURCE'
                  AND rb.ordinal=0 AND a.deleted_at IS NULL AND a.validation_status='READY'
                  AND a.purpose='MOVING_PHOTO_SOURCE'
                """, (rs, row) -> new Binding(
                rs.getString("storage_key"), rs.getLong("size_bytes"), rs.getString("sha256")), versionId);
        if (rows.size() != 1) throw invalid();
        return rows.get(0);
    }

    private PackageRow packageRow(long versionId) {
        return jdbc.query("SELECT status FROM moving_photo_package WHERE resource_version_id=?",
                (rs, row) -> new PackageRow(rs.getString("status")), versionId)
                .stream().findFirst().orElse(null);
    }

    private void deleteQuietly(StoredObject object) {
        if (object == null) return;
        try { storage.delete(object.storageKey()); } catch (RuntimeException ignored) { }
    }

    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "MOVING_PHOTO_PROCESSING_FAILED",
                "The Moving Photo source cannot be processed");
    }

    private record Version(long id, String status, String platform, String resourceType) { }
    private record Binding(String storageKey, long sizeBytes, String sha256) { }
    private record PackageRow(String status) { }
}
