package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.parallax.ParallaxStorageCleanup;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable preview-only generation. Sources and formal delivery packages are never replaced. */
@Service
public class PreviewGenerationService {
    private static final Logger LOG = LoggerFactory.getLogger(PreviewGenerationService.class);
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final PreviewWatermarkRenderer renderer;
    private final SecurePackagePublisher packages;
    private final ParallaxStorageCleanup cleanup;
    private final TransactionTemplate tx;

    public PreviewGenerationService(JdbcTemplate jdbc, FileStorage storage, PreviewWatermarkRenderer renderer,
            SecurePackagePublisher packages, ParallaxStorageCleanup cleanup, PlatformTransactionManager manager) {
        this.jdbc=jdbc; this.storage=storage; this.renderer=renderer; this.packages=packages; this.cleanup=cleanup;
        this.tx=new TransactionTemplate(manager);
    }

    /** Call inside the same transaction that changes the wallpaper policy/source/publication. */
    public void enqueue(long wallpaperId) {
        jdbc.update("""
            INSERT INTO wallpaper_preview_state(wallpaper_id) VALUES(?)
            ON DUPLICATE KEY UPDATE requested_revision=requested_revision+1, status='PENDING',
                generation_token=NULL, started_at=NULL, error_code=NULL
            """,wallpaperId);
    }

    public RebuildPlan plan() {
        return jdbc.queryForObject("""
            SELECT COUNT(*) AS wallpapers,
                COALESCE(SUM(access_type='REDEEM' AND preview_watermark_enabled=TRUE),0) AS marked,
                (SELECT COUNT(*) FROM resource_version rv JOIN wallpaper_variant v ON v.id=rv.variant_id
                 JOIN wallpaper x ON x.id=v.wallpaper_id WHERE x.status<>'ARCHIVED'
                   AND (rv.status='PUBLISHED' OR (x.status<>'PUBLISHED' AND rv.status='READY')) AND v.enabled=TRUE) AS versions
            FROM wallpaper WHERE status<>'ARCHIVED'
            """,(rs,n)->new RebuildPlan(rs.getLong("wallpapers"),rs.getLong("versions"),
                rs.getLong("marked"),rs.getLong("wallpapers")-rs.getLong("marked")));
    }

    public RebuildPlan enqueueAll() {
        return tx.execute(status -> {
            RebuildPlan plan=plan();
            jdbc.update("""
                INSERT INTO wallpaper_preview_state(wallpaper_id)
                SELECT id FROM wallpaper WHERE status<>'ARCHIVED'
                ON DUPLICATE KEY UPDATE requested_revision=requested_revision+1, status='PENDING',
                    generation_token=NULL, started_at=NULL, error_code=NULL
                """);
            return plan;
        });
    }

    public void retry(long wallpaperId) {
        tx.executeWithoutResult(status -> {
            var state=jdbc.queryForList("SELECT status FROM wallpaper WHERE id=? FOR UPDATE",String.class,wallpaperId);
            if(state.isEmpty() || state.get(0).equals("ARCHIVED"))
                throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","Wallpaper is unavailable");
            enqueue(wallpaperId);
        });
    }

    public long requireReady(long wallpaperId) {
        var state=jdbc.query("""
            SELECT requested_revision,generated_revision,status FROM wallpaper_preview_state WHERE wallpaper_id=?
            """,(rs,n)->new State(rs.getLong(1),rs.getLong(2),rs.getString(3)),wallpaperId);
        if(state.size()!=1 || !state.get(0).status().equals("READY") || state.get(0).requested()!=state.get(0).generated()) {
            boolean failed=state.size()==1 && state.get(0).status().equals("FAILED");
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                failed?"PREVIEW_GENERATION_FAILED":"PREVIEW_PROCESSING",
                failed?"Preview generation failed; please retry later":"Preview resources are being generated");
        }
        return state.get(0).requested();
    }

    @Scheduled(initialDelayString="${qingjing.preview.initial-delay:10000}",
            fixedDelayString="${qingjing.preview.generation-delay:3000}")
    public void scheduled() {
        try { processNext(); }
        catch(RuntimeException error) { LOG.warn("Preview queue unavailable cause={}",error.getClass().getSimpleName()); }
    }

    public boolean processNext() {
        Job job=tx.execute(status -> {
            var jobs=jdbc.query("""
                SELECT s.wallpaper_id,s.requested_revision,w.cover_asset_id,
                       (w.access_type='REDEEM' AND w.preview_watermark_enabled=TRUE) AS marked,
                       s.cover_storage_key,s.cover_size_bytes,s.cover_sha256,s.cover_is_derived
                FROM wallpaper_preview_state s JOIN wallpaper w ON w.id=s.wallpaper_id
                WHERE w.status<>'ARCHIVED' AND (s.status='PENDING' OR
                    (s.status='PROCESSING' AND s.started_at<UTC_TIMESTAMP(6)-INTERVAL 1 HOUR))
                ORDER BY s.updated_at,s.wallpaper_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """,(rs,n)->new Job(rs.getLong(1),rs.getLong(2),rs.getLong(3),rs.getBoolean(4),
                    UUID.randomUUID().toString(),rs.getString(5)==null?null:new StoredObject(
                        new StorageKey(rs.getString(5)),rs.getLong(6),rs.getString(7)),rs.getBoolean(8)));
            if(jobs.isEmpty()) return null;
            Job claim=jobs.get(0);
            jdbc.update("""
                UPDATE wallpaper_preview_state SET status='PROCESSING',generation_token=?,started_at=UTC_TIMESTAMP(6),error_code=NULL
                WHERE wallpaper_id=? AND requested_revision=?
                """,claim.token(),claim.wallpaper(),claim.revision());
            return claim;
        });
        if(job==null) return false;
        StoredObject cover=null; boolean derived=job.marked();
        try {
            Source source=sourceAsset(job.cover());
            cover=derived?store(renderer.image(read(source)),"png"):source.object();
            String coverMime=derived?"image/png":source.mime();
            var versions=jdbc.query("""
                SELECT rv.id,v.platform,v.resource_type FROM resource_version rv
                JOIN wallpaper_variant v ON v.id=rv.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
                WHERE v.wallpaper_id=? AND v.enabled=TRUE AND (rv.status='PUBLISHED' OR (w.status<>'PUBLISHED' AND rv.status='READY'))
                ORDER BY rv.id
                """,(rs,n)->new Version(rs.getLong(1),rs.getString(2),rs.getString(3)),job.wallpaper());
            for(var version:versions) {
                if(!current(job)) throw new Obsolete();
                if((version.platform().equals("ANDROID") || version.platform().equals("UNIVERSAL"))
                        && List.of("STATIC_IMAGE","VIDEO","LAYER_PARALLAX").contains(version.type())) {
                    packages.rebuildPreview(version.id(),job.marked(),job.revision());
                } else if(version.platform().equals("IOS") && version.type().equals("LIVE_PHOTO")) {
                    buildVideo(job,version,true);
                } else if(version.platform().equals("HARMONYOS") && version.type().equals("MOVING_PHOTO")) {
                    buildVideo(job,version,false);
                }
            }
            StoredObject complete=cover;
            boolean applied=Boolean.TRUE.equals(tx.execute(status -> jdbc.update("""
                UPDATE wallpaper_preview_state SET generated_revision=?,status='READY',generation_token=NULL,error_code=NULL,
                    cover_storage_key=?,cover_size_bytes=?,cover_sha256=?,cover_mime_type=?,cover_is_derived=?
                WHERE wallpaper_id=? AND requested_revision=? AND generation_token=? AND status='PROCESSING'
                """,job.revision(),complete.storageKey().value(),complete.sizeBytes(),complete.sha256(),coverMime,
                    derived,job.wallpaper(),job.revision(),job.token())==1));
            if(!applied) throw new Obsolete();
            cover=null; // The new descriptor now owns the object.
            if(job.oldDerived() && job.oldCover()!=null) cleanup.delete(job.oldCover());
            LOG.info("Preview ready wallpaper={} revision={} watermarked={} versions={}",
                job.wallpaper(),job.revision(),job.marked(),versions.size());
        } catch(Obsolete ignored) {
            LOG.info("Preview generation superseded wallpaper={} revision={}",job.wallpaper(),job.revision());
        } catch(RuntimeException failure) {
            String code=failure instanceof ApiException api?api.code():"PREVIEW_GENERATION_FAILED";
            jdbc.update("""
                UPDATE wallpaper_preview_state SET status=?,generation_token=NULL,error_code=?
                WHERE wallpaper_id=? AND requested_revision=? AND generation_token=?
                """,code.equals("RATE_LIMITED")?"PENDING":"FAILED",code,job.wallpaper(),job.revision(),job.token());
            LOG.warn("Preview generation failed wallpaper={} revision={} code={}",job.wallpaper(),job.revision(),code);
        } finally { if(cover!=null && derived) cleanup.delete(cover); }
        return true;
    }

    private void buildVideo(Job job,Version version,boolean ios) {
        String table=ios?"live_photo_package":"moving_photo_package";
        Source source=jdbc.query("SELECT video_storage_key,video_size_bytes,video_sha256,duration_ms FROM "+table+
                " WHERE resource_version_id=? AND status='READY'",(rs,n)->new Source(new StoredObject(
                    new StorageKey(rs.getString(1)),rs.getLong(2),rs.getString(3)),ios?"video/quicktime":"video/mp4",rs.getLong(4)),version.id())
                .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"PREVIEW_SOURCE_NOT_READY","Dynamic preview source is not ready"));
        StoredObject object=job.marked()?store(renderer.video(read(source),ios),ios?"mov":"mp4"):source.object();
        try {
            tx.executeWithoutResult(status -> {
                if(!currentLocked(job)) throw new Obsolete();
                var old=jdbc.query("SELECT storage_key,size_bytes,sha256,is_derived FROM preview_media WHERE resource_version_id=? FOR UPDATE",
                    (rs,n)->new OldObject(new StoredObject(new StorageKey(rs.getString(1)),rs.getLong(2),rs.getString(3)),rs.getBoolean(4)),version.id());
                jdbc.update("""
                    INSERT INTO preview_media(resource_version_id,preview_revision,storage_key,size_bytes,sha256,mime_type,duration_ms,is_derived)
                    VALUES(?,?,?,?,?,?,?,?) ON DUPLICATE KEY UPDATE preview_revision=VALUES(preview_revision),storage_key=VALUES(storage_key),
                      size_bytes=VALUES(size_bytes),sha256=VALUES(sha256),mime_type=VALUES(mime_type),duration_ms=VALUES(duration_ms),is_derived=VALUES(is_derived)
                    """,version.id(),job.revision(),object.storageKey().value(),object.sizeBytes(),object.sha256(),source.mime(),source.duration(),job.marked());
                afterCommit(()->old.stream().filter(OldObject::derived).forEach(item->cleanup.delete(item.object())));
            });
        } catch(RuntimeException failure) { if(job.marked()) cleanup.delete(object); throw failure; }
    }

    private boolean currentLocked(Job job) {
        return jdbc.query("SELECT requested_revision,generation_token,status FROM wallpaper_preview_state WHERE wallpaper_id=? FOR UPDATE",
            (rs,n)->rs.getLong(1)==job.revision() && job.token().equals(rs.getString(2)) && "PROCESSING".equals(rs.getString(3)),job.wallpaper())
            .stream().findFirst().orElse(false);
    }
    private boolean current(Job job) {
        return jdbc.queryForObject("""
            SELECT COUNT(*) FROM wallpaper_preview_state WHERE wallpaper_id=? AND requested_revision=? AND generation_token=? AND status='PROCESSING'
            """,Integer.class,job.wallpaper(),job.revision(),job.token())==1;
    }
    private Source sourceAsset(long assetId) {
        return jdbc.query("SELECT storage_key,size_bytes,sha256,mime_type FROM asset WHERE id=? AND validation_status='READY' AND deleted_at IS NULL",
            (rs,n)->new Source(new StoredObject(new StorageKey(rs.getString(1)),rs.getLong(2),rs.getString(3)),rs.getString(4),0),assetId)
            .stream().findFirst().orElseThrow(()->new ApiException(HttpStatus.CONFLICT,"PREVIEW_SOURCE_NOT_READY","Preview cover is unavailable"));
    }
    private byte[] read(Source source) {
        try(var content=storage.open(source.object().storageKey())) {
            if(source.object().sizeBytes()>200L*1024*1024 || content.sizeBytes()!=source.object().sizeBytes()) throw new IllegalStateException("Preview source size mismatch");
            byte[] bytes=content.inputStream().readNBytes(Math.toIntExact(source.object().sizeBytes())+1);
            if(bytes.length!=source.object().sizeBytes() || !SecurePackageCodec.sha256(bytes).equals(source.object().sha256()))
                throw new IllegalStateException("Preview source hash mismatch");
            return bytes;
        } catch(java.io.IOException error) { throw new IllegalStateException("Cannot read preview source",error); }
    }
    private StoredObject store(byte[] bytes,String extension) {
        StagedObject staged=storage.stage(new ByteArrayInputStream(bytes),200L*1024*1024);
        try { return storage.commit(staged,extension); }
        catch(RuntimeException error) { cleanup.discard(staged); throw error; }
    }
    private static void afterCommit(Runnable action) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() { action.run(); }
        });
    }
    public record RebuildPlan(long wallpaperCount,long resourceVersionCount,long watermarkedWallpaperCount,long cleanWallpaperCount) { }
    private record State(long requested,long generated,String status) { }
    private record Job(long wallpaper,long revision,long cover,boolean marked,String token,StoredObject oldCover,boolean oldDerived) { }
    private record Version(long id,String platform,String type) { }
    private record Source(StoredObject object,String mime,long duration) { }
    private record OldObject(StoredObject object,boolean derived) { }
    private static final class Obsolete extends RuntimeException { }
}
