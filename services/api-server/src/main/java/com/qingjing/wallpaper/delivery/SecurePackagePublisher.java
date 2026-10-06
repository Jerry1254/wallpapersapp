package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.delivery.infrastructure.PackageMediaInspector;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.parallax.ParallaxConfigEnvelopeValidator;
import com.qingjing.wallpaper.parallax.ParallaxStorageCleanup;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SecurePackagePublisher {
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    private final AssetContentValidator assetValidator;
    private final PackageMediaInspector media;
    private final PackageSigningKeys signing;
    private final SecurityCrypto crypto;
    private final ObjectMapper mapper;
    private final ParallaxConfigEnvelopeValidator parallaxConfigs;
    private final ParallaxStorageCleanup cleanup;
    private final com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer watermark;
    private final Semaphore slots = new Semaphore(1);
    public SecurePackagePublisher(JdbcTemplate jdbc, FileStorage storage, AssetContentValidator assetValidator,
            PackageMediaInspector media, PackageSigningKeys signing, SecurityCrypto crypto, ObjectMapper mapper,
            ParallaxConfigEnvelopeValidator parallaxConfigs,ParallaxStorageCleanup cleanup,
            com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer watermark) {
        this.jdbc=jdbc; this.storage=storage; this.assetValidator=assetValidator; this.media=media;
        this.signing=signing; this.crypto=crypto; this.mapper=mapper;this.parallaxConfigs=parallaxConfigs;this.cleanup=cleanup;this.watermark=watermark;
    }
    @Transactional
    public void build(long versionId) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId, false, null, 0); } finally { slots.release(); }
    }
    @Transactional
    public void prepareForPublication(long versionId) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId, true, null, 0); } finally { slots.release(); }
    }
    @Transactional
    public void rebuildPreview(long versionId, boolean watermarked, long revision) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId, true, watermarked, revision); } finally { slots.release(); }
    }
    private void buildLocked(long versionId, boolean skipUnsupported, Boolean watermarked, long revision) {
        var rows = jdbc.query("""
                SELECT r.id,r.version_no,r.status,v.id AS variant_id,v.wallpaper_id,v.platform,v.resource_type
                FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE r.id=? FOR UPDATE
                """, (rs,n) -> new Version(rs.getLong("id"),rs.getInt("version_no"),rs.getString("status"),rs.getLong("variant_id"),
                rs.getLong("wallpaper_id"),rs.getString("platform"),rs.getString("resource_type")),versionId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","Resource version not found");
        Version version = rows.get(0);
        boolean fullSupported=(version.platform().equals("ANDROID") || version.platform().equals("UNIVERSAL"))
                && Set.of("STATIC_IMAGE","VIDEO","LAYER_PARALLAX").contains(version.type());
        if (!fullSupported) {
            if (skipUnsupported) return;
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"DOMAIN_RULE_VIOLATION","The variant has no supported package format");
        }
        boolean fullExists=jdbc.queryForObject("SELECT COUNT(*) FROM secure_resource_package WHERE resource_version_id=?",Integer.class,versionId)==1;
        Preview existingPreview=jdbc.query("""
                SELECT storage_key,size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,content_key_ciphertext
                FROM preview_resource_package WHERE resource_version_id=? FOR UPDATE
                """,(rs,n)->new Preview(new StoredObject(new StorageKey(rs.getString("storage_key")),rs.getLong("size_bytes"),rs.getString("encrypted_sha256")),
                rs.getString("plaintext_sha256"),rs.getString("manifest_sha256"),rs.getString("content_key_ciphertext")),versionId)
                .stream().findFirst().orElse(null);
        if (!version.status().equals("READY") && !version.status().equals("PUBLISHED")) throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","Only a ready or published version may be prepared");
        if (watermarked==null && fullExists) return;
        signing.privateKey();
        var bindings = jdbc.query("""
                    SELECT b.role,b.ordinal,a.storage_key,a.mime_type,a.sha256,a.size_bytes,a.validation_status,a.deleted_at
                    FROM resource_binding b JOIN asset a ON a.id=b.asset_id
                    WHERE b.resource_version_id=? AND b.role <> 'COVER' ORDER BY b.role,b.ordinal
                    """,(rs,n) -> new Binding(rs.getString("role"),rs.getInt("ordinal"),rs.getString("storage_key"),rs.getString("mime_type"),
                        rs.getString("sha256"),rs.getLong("size_bytes"),rs.getString("validation_status").equals("READY") && rs.getTimestamp("deleted_at")==null),versionId);
        if (bindings.isEmpty() || bindings.size()>16) throw invalid();
        List<SecurePackageCodec.Payload> payloads = new ArrayList<>();
        Map<String,PackageMediaInspector.Media> images = new HashMap<>();
        byte[] parallax = null; long total = 0;
        for (Binding binding : bindings) {
            if (!binding.ready() || binding.size()<1) throw invalid();
            byte[] bytes;
            try (StoredContent content=storage.open(new StorageKey(binding.key()))) {
                if (content.sizeBytes()!=binding.size()) throw invalid();
                bytes=content.inputStream().readNBytes((int)binding.size()+1);
            } catch (java.io.IOException exception) { throw invalid(); }
            if (bytes.length!=binding.size() || !SecurePackageCodec.sha256(bytes).equals(binding.sha256())) throw invalid();
            StagedObject staged=storage.stage(new ByteArrayInputStream(bytes),binding.size());
            try { assetValidator.validate(staged,AssetPurpose.valueOf(binding.role()),binding.mime()); }
            finally { storage.discard(staged); }
            String payloadMime=binding.mime();
            if (version.type().equals("VIDEO") && binding.role().equals("VIDEO")) {
                bytes=media.androidVideo(bytes,binding.mime().equals("video/mp4"));
                payloadMime="video/mp4";
            }
            total += bytes.length;
            if (total>SecurePackageCodec.MAX_PAYLOAD_BYTES) throw invalid();
            if (binding.role().equals("PARALLAX_CONFIG")) parallax=bytes;
            else images.put(binding.role()+":"+binding.ordinal(),media.inspect(bytes,binding.role().equals("VIDEO")));
            payloads.add(new SecurePackageCodec.Payload(binding.role(),binding.ordinal(),payloadMime,bytes));
        }
        if (version.type().equals("LAYER_PARALLAX")) validateParallax(parallax,images);
        var identity=new SecurePackageCodec.Identity(version.wallpaperId(),version.variantId(),version.number(),version.type());
        var previewSource=watermarked==null?null:previewPayloads(version.type(),payloads,watermarked);
        if(watermarked!=null) {
            Long currentRevision=jdbc.queryForObject("SELECT requested_revision FROM wallpaper_preview_state WHERE wallpaper_id=? FOR SHARE",Long.class,version.wallpaperId());
            if(currentRevision==null || currentRevision!=revision) throw new ApiException(HttpStatus.CONFLICT,"PREVIEW_SUPERSEDED","Preview generation was superseded");
        }
        if (watermarked!=null && existingPreview!=null) {
            jdbc.update("DELETE FROM preview_resource_package WHERE resource_version_id=?",version.id());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.delete(existingPreview.object()); }
            });
        }
        if (watermarked==null && !fullExists) {
        SecurePackageCodec.Encoded encoded;
        try { encoded=new SecurePackageCodec(mapper).encode(identity,payloads,signing.keyId(),signing.privateKey()); }
        catch (IllegalArgumentException exception) { throw invalid(); }
        StagedObject staged=storage.stage(new ByteArrayInputStream(encoded.encrypted()),68157440);
        StoredObject stored;
        try { stored=storage.commit(staged,"4dwp"); }
        catch (RuntimeException error) { storage.discard(staged); throw error; }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status!=STATUS_COMMITTED) storage.delete(stored.storageKey());
            }
        });
        try {
            jdbc.update("""
                    INSERT INTO secure_resource_package
                    (resource_version_id,storage_key,size_bytes,plaintext_size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,version.id(),stored.storageKey().value(),stored.sizeBytes(),stored.sizeBytes()-36,encoded.encryptedSha256(),encoded.plaintextSha256(),
                    encoded.manifestSha256(),encoded.signingKeyId(),crypto.encrypt("secure-package-key-v2:"+version.id(),encoded.contentKey()));
            jdbc.update("UPDATE resource_version SET manifest_sha256=?,lock_version=lock_version+1 WHERE id=?",encoded.manifestSha256(),version.id());
        } finally { Arrays.fill(encoded.contentKey(),(byte)0); }
        }
        if(watermarked!=null) buildPreview(version,identity,previewSource,revision);
    }
    private void buildPreview(Version version,SecurePackageCodec.Identity identity,List<SecurePackageCodec.Payload> source,long revision) {
        var encoded=new SecurePackageCodec(mapper).encodePreview(identity,source,signing.keyId(),signing.privateKey());
        try {
            StagedObject staged=storage.stage(new ByteArrayInputStream(encoded.encrypted()),68157440);
            StoredObject stored;
            try { stored=storage.commit(staged,"qjpv"); } catch(RuntimeException error) { storage.discard(staged);throw error; }
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCompletion(int status) { if(status!=STATUS_COMMITTED) storage.delete(stored.storageKey()); }
            });
            jdbc.update("""
                    INSERT INTO preview_resource_package
                    (preview_revision,resource_version_id,storage_key,size_bytes,plaintext_size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """,revision,version.id(),stored.storageKey().value(),stored.sizeBytes(),stored.sizeBytes()-36,encoded.encryptedSha256(),encoded.plaintextSha256(),
                    encoded.manifestSha256(),encoded.signingKeyId(),crypto.encrypt("preview-package-key-v1:"+version.id(),encoded.contentKey()));
        } finally { Arrays.fill(encoded.contentKey(),(byte)0); }
    }
    List<SecurePackageCodec.Payload> previewPayloads(String type,List<SecurePackageCodec.Payload> source,boolean marked) {
        if(!marked) return source;
        return source.stream().map(payload -> {
            boolean image=type.equals("STATIC_IMAGE") && payload.role().equals("STATIC_IMAGE")
                || type.equals("LAYER_PARALLAX") && payload.role().equals("FOREGROUND") && payload.ordinal()==0;
            if(image) return new SecurePackageCodec.Payload(payload.role(),payload.ordinal(),"image/png",watermark.image(payload.content()));
            if(type.equals("VIDEO") && payload.role().equals("VIDEO"))
                return new SecurePackageCodec.Payload(payload.role(),payload.ordinal(),"video/mp4",watermark.video(payload.content(),false));
            return payload;
        }).toList();
    }
    private void validateParallax(byte[] bytes,Map<String,PackageMediaInspector.Media> images) {
        try {
            var envelope=parallaxConfigs.validate(bytes,images.size());
            int width=envelope.width(),height=envelope.height();
            Set<String> seen=new HashSet<>();
            for (int i=0;i<envelope.layerCount();i++) {
                String role=i==envelope.layerCount()-1?"BACKGROUND":"FOREGROUND";
                int ordinal=role.equals("BACKGROUND")?0:i;
                var image=images.get(role+":"+ordinal);
                if (image==null || !seen.add(role+":"+ordinal) || image.width()!=width || image.height()!=height || (role.equals("FOREGROUND") && !image.alpha())) throw invalid();
            }
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"ASSET_VALIDATION_FAILED","Resource package input validation failed"); }
    private record Version(long id,int number,String status,long variantId,long wallpaperId,String platform,String type) {}
    private record Binding(String role,int ordinal,String key,String mime,String sha256,long size,boolean ready) {}
    private record Preview(StoredObject object,String plaintextSha256,String manifestSha256,String encryptedKey) {}
}
