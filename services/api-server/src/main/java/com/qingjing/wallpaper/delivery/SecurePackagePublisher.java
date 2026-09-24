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
    private final Semaphore slots = new Semaphore(1);
    public SecurePackagePublisher(JdbcTemplate jdbc, FileStorage storage, AssetContentValidator assetValidator,
            PackageMediaInspector media, PackageSigningKeys signing, SecurityCrypto crypto, ObjectMapper mapper,
            ParallaxConfigEnvelopeValidator parallaxConfigs,ParallaxStorageCleanup cleanup) {
        this.jdbc=jdbc; this.storage=storage; this.assetValidator=assetValidator; this.media=media;
        this.signing=signing; this.crypto=crypto; this.mapper=mapper;this.parallaxConfigs=parallaxConfigs;this.cleanup=cleanup;
    }
    @Transactional
    public void build(long versionId) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId, false); } finally { slots.release(); }
    }
    @Transactional
    public void prepareForPublication(long versionId) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId, true); } finally { slots.release(); }
    }
    private void buildLocked(long versionId, boolean skipUnsupported) {
        var rows = jdbc.query("""
                SELECT r.id,r.version_no,r.status,v.id AS variant_id,v.wallpaper_id,v.platform,v.resource_type
                FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE r.id=? FOR UPDATE
                """, (rs,n) -> new Version(rs.getLong("id"),rs.getInt("version_no"),rs.getString("status"),rs.getLong("variant_id"),
                rs.getLong("wallpaper_id"),rs.getString("platform"),rs.getString("resource_type")),versionId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","Resource version not found");
        Version version = rows.get(0);
        boolean fullSupported=(version.platform().equals("ANDROID") || version.platform().equals("UNIVERSAL"))
                && Set.of("STATIC_IMAGE","VIDEO","LAYER_PARALLAX").contains(version.type());
        boolean livePhotoPreview=version.platform().equals("IOS") && version.type().equals("LIVE_PHOTO");
        if (!fullSupported && !livePhotoPreview) {
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
        if (skipUnsupported && existingPreview!=null && (!fullSupported || fullExists)) return;
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
            if (livePhotoPreview && !binding.role().equals("LIVE_PHOTO_VIDEO")) continue;
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
            if (livePhotoPreview) {
                bytes=media.androidPreview(bytes);
                total += bytes.length;
                if (total>SecurePackageCodec.MAX_PAYLOAD_BYTES) throw invalid();
                payloads.add(new SecurePackageCodec.Payload("LIVE_PHOTO_VIDEO",binding.ordinal(),"video/mp4",bytes));
                continue;
            }
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
        if ((!fullSupported || fullExists) && existingPreview!=null && previewMatchesSource(version.id(),identity,existingPreview,payloads)) return;
        if (existingPreview!=null) {
            jdbc.update("DELETE FROM preview_resource_package WHERE resource_version_id=?",version.id());
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { cleanup.delete(existingPreview.object()); }
            });
        }
        if (fullSupported && !fullExists) {
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
        buildPreview(version,identity,payloads);
    }
    private void buildPreview(Version version,SecurePackageCodec.Identity identity,List<SecurePackageCodec.Payload> source) {
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
                    (resource_version_id,storage_key,size_bytes,plaintext_size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,version.id(),stored.storageKey().value(),stored.sizeBytes(),stored.sizeBytes()-36,encoded.encryptedSha256(),encoded.plaintextSha256(),
                    encoded.manifestSha256(),encoded.signingKeyId(),crypto.encrypt("preview-package-key-v1:"+version.id(),encoded.contentKey()));
        } finally { Arrays.fill(encoded.contentKey(),(byte)0); }
    }
    private boolean previewMatchesSource(long versionId,SecurePackageCodec.Identity identity,Preview preview,List<SecurePackageCodec.Payload> source) {
        byte[] key=null;
        try {
            if(preview.object().sizeBytes()<21 || preview.object().sizeBytes()>68157440)return false;
            byte[] encrypted;
            try(var content=storage.open(preview.object().storageKey())) {
                if(content.sizeBytes()!=preview.object().sizeBytes())return false;
                encrypted=content.inputStream().readNBytes((int)preview.object().sizeBytes()+1);
            }
            if(encrypted.length!=preview.object().sizeBytes() || !SecurePackageCodec.sha256(encrypted).equals(preview.object().sha256()) ||
                    !Arrays.equals(Arrays.copyOfRange(encrypted,0,8),"QJPV0001".getBytes(java.nio.charset.StandardCharsets.US_ASCII))) return false;
            key=crypto.decrypt("preview-package-key-v1:"+versionId,preview.encryptedKey());
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),
                    new javax.crypto.spec.GCMParameterSpec(128,Arrays.copyOfRange(encrypted,8,20)));
            cipher.updateAAD(SecurePackageCodec.previewAad(identity));
            byte[] plain=cipher.doFinal(Arrays.copyOfRange(encrypted,20,encrypted.length));
            if(!SecurePackageCodec.sha256(plain).equals(preview.plaintextSha256()))return false;

            try(var zip=new java.util.zip.ZipInputStream(new ByteArrayInputStream(plain),java.nio.charset.StandardCharsets.UTF_8)) {
                var manifestEntry=zip.getNextEntry();
                if(manifestEntry==null || !manifestEntry.getName().equals("manifest.json"))return false;
                byte[] manifest=zip.readNBytes(65537);
                if(manifest.length>65536 || !SecurePackageCodec.sha256(manifest).equals(preview.manifestSha256()))return false;
                var root=mapper.readTree(manifest);
                if(root.path("formatVersion").asInt()!=3 || !root.path("purpose").asText().equals("APP_PREVIEW") ||
                        !root.path("wallpaperId").asText().equals(Long.toString(identity.wallpaperId())) ||
                        !root.path("variantId").asText().equals(Long.toString(identity.variantId())) ||
                        root.path("versionNo").asInt()!=identity.versionNo() || !root.path("resourceType").asText().equals(identity.resourceType()) ||
                        !root.path("signingKeyId").asText().equals(signing.keyId()) || !root.path("files").isArray())return false;

                Map<String,SecurePackageCodec.Payload> expectedById=new HashMap<>();
                for(var payload:source)if(expectedById.putIfAbsent(payload.role()+":"+payload.ordinal(),payload)!=null)return false;
                Map<String,SecurePackageCodec.Payload> expectedByPath=new HashMap<>();
                for(var file:root.path("files")) {
                    String id=file.path("role").asText()+":"+file.path("ordinal").asInt(-1);
                    var payload=expectedById.get(id);
                    String path=file.path("path").asText();
                    if(payload==null || !path.startsWith("payload/") || expectedByPath.putIfAbsent(path,payload)!=null ||
                            !file.path("mimeType").asText().equals(payload.mimeType()) ||
                            file.path("sizeBytes").asLong()!=payload.content().length ||
                            !file.path("sha256").asText().equals(SecurePackageCodec.sha256(payload.content())))return false;
                }
                if(expectedById.size()!=expectedByPath.size())return false;

                var signature=zip.getNextEntry();
                if(signature==null || !signature.getName().equals("manifest.sig") || zip.readNBytes(4097).length!=256)return false;
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry()) {
                    var payload=expectedByPath.remove(entry.getName());
                    if(payload==null || !Arrays.equals(zip.readNBytes(payload.content().length+1),payload.content()))return false;
                }
                return expectedByPath.isEmpty();
            }
        } catch(Exception error) { return false; }
        finally { if(key!=null)Arrays.fill(key,(byte)0); }
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
