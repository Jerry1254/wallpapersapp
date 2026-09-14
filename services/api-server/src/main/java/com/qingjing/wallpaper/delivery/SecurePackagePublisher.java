package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.delivery.infrastructure.PackageMediaInspector;
import com.qingjing.wallpaper.delivery.infrastructure.PreviewMediaReducer;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
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
    private final PreviewMediaReducer previews;
    private final Semaphore slots = new Semaphore(1);
    public SecurePackagePublisher(JdbcTemplate jdbc, FileStorage storage, AssetContentValidator assetValidator,
            PackageMediaInspector media, PackageSigningKeys signing, SecurityCrypto crypto, ObjectMapper mapper,PreviewMediaReducer previews) {
        this.jdbc=jdbc; this.storage=storage; this.assetValidator=assetValidator; this.media=media;
        this.signing=signing; this.crypto=crypto; this.mapper=mapper;this.previews=previews;
    }
    @Transactional
    public void build(long versionId) {
        if (!slots.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Another resource package is being prepared");
        try { buildLocked(versionId); } finally { slots.release(); }
    }
    private void buildLocked(long versionId) {
        var rows = jdbc.query("""
                SELECT r.id,r.version_no,r.status,v.id AS variant_id,v.wallpaper_id,v.platform,v.resource_type
                FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE r.id=? FOR UPDATE
                """, (rs,n) -> new Version(rs.getLong("id"),rs.getInt("version_no"),rs.getString("status"),rs.getLong("variant_id"),
                rs.getLong("wallpaper_id"),rs.getString("platform"),rs.getString("resource_type")),versionId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_FOUND","Resource version not found");
        Version version = rows.get(0);
        boolean fullExists=jdbc.queryForObject("SELECT COUNT(*) FROM secure_resource_package WHERE resource_version_id=?",Integer.class,versionId)==1;
        if (fullExists && jdbc.queryForObject("SELECT COUNT(*) FROM preview_resource_package WHERE resource_version_id=?",Integer.class,versionId)==1) return;
        if (!version.status().equals("READY") && !(fullExists && version.status().equals("PUBLISHED"))) throw new ApiException(HttpStatus.CONFLICT,"STATE_CONFLICT","Only a ready version or an already packaged published version may be prepared");
        if (!(version.platform().equals("ANDROID") || version.platform().equals("UNIVERSAL")) ||
                !Set.of("STATIC_IMAGE","VIDEO","LAYER_PARALLAX").contains(version.type())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"DOMAIN_RULE_VIOLATION","The variant has no Android secure package format");
        }
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
            total += binding.size();
            if (!binding.ready() || binding.size()<1 || total>SecurePackageCodec.MAX_PAYLOAD_BYTES) throw invalid();
            byte[] bytes;
            try (StoredContent content=storage.open(new StorageKey(binding.key()))) {
                if (content.sizeBytes()!=binding.size()) throw invalid();
                bytes=content.inputStream().readNBytes((int)binding.size()+1);
            } catch (java.io.IOException exception) { throw invalid(); }
            if (bytes.length!=binding.size() || !SecurePackageCodec.sha256(bytes).equals(binding.sha256())) throw invalid();
            StagedObject staged=storage.stage(new ByteArrayInputStream(bytes),binding.size());
            try { assetValidator.validate(staged,AssetPurpose.valueOf(binding.role()),binding.mime()); }
            finally { storage.discard(staged); }
            if (binding.role().equals("PARALLAX_CONFIG")) parallax=bytes;
            else images.put(binding.role()+":"+binding.ordinal(),media.inspect(bytes,binding.role().equals("VIDEO")));
            payloads.add(new SecurePackageCodec.Payload(binding.role(),binding.ordinal(),binding.mime(),bytes));
        }
        if (version.type().equals("LAYER_PARALLAX")) validateParallax(parallax,images);
        if (!fullExists) {
        SecurePackageCodec.Encoded encoded;
        try { encoded=new SecurePackageCodec(mapper).encode(new SecurePackageCodec.Identity(version.wallpaperId(),version.variantId(),version.number(),version.type()),payloads,signing.keyId(),signing.privateKey()); }
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
        buildPreview(version,payloads,images);
    }
    private void buildPreview(Version version,List<SecurePackageCodec.Payload> source,Map<String,PackageMediaInspector.Media> dimensions) {
        var payloads=previews.reduce(version.type(),source,dimensions);
        var encoded=new SecurePackageCodec(mapper).encodePreview(new SecurePackageCodec.Identity(version.wallpaperId(),version.variantId(),version.number(),version.type()),
                payloads,signing.keyId(),signing.privateKey());
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
    private void validateParallax(byte[] bytes,Map<String,PackageMediaInspector.Media> images) {
        try {
            if (bytes==null || bytes.length>65536) throw invalid();
            var config=mapper.reader().with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(bytes);
            exactFields(config,Set.of("canvas","sensor","layers"));
            exactFields(config.path("canvas"),Set.of("width","height"));
            exactFields(config.path("sensor"),Set.of("maxAngle","smoothing","strength"));
            if (!config.path("canvas").path("width").isInt() || !config.path("canvas").path("height").isInt()) throw invalid();
            int width=config.path("canvas").path("width").asInt(),height=config.path("canvas").path("height").asInt();
            if (width<512 || height<512 || width>4096 || height>4096) throw invalid();
            range(config.path("sensor").path("maxAngle"),5,25); range(config.path("sensor").path("smoothing"),0.05,0.5); range(config.path("sensor").path("strength"),0,2);
            var layers=config.path("layers");
            if (!layers.isArray() || layers.size()!=images.size() || layers.size()<2 || layers.size()>12) throw invalid();
            Set<String> seen=new HashSet<>(); double previous=-1;
            for (var layer:layers) {
                exactFields(layer,Set.of("role","ordinal","depth","scale","opacity","blendMode"));
                if (!layer.path("ordinal").isInt()) throw invalid();
                String role=layer.path("role").asText(); int ordinal=layer.path("ordinal").asInt(-1);
                var image=images.get(role+":"+ordinal);
                if (image==null || !seen.add(role+":"+ordinal) || image.width()!=width || image.height()!=height || (role.equals("FOREGROUND") && !image.alpha())) throw invalid();
                double depth=range(layer.path("depth"),0,1); if (depth<previous) throw invalid(); previous=depth;
                range(layer.path("scale"),1,1.5); range(layer.path("opacity"),0,1);
                if (!Set.of("normal","screen","add").contains(layer.path("blendMode").asText())) throw invalid();
            }
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw invalid(); }
    }
    private void exactFields(com.fasterxml.jackson.databind.JsonNode value,Set<String> expected) {
        if (!value.isObject()) throw invalid();
        Set<String> fields=new HashSet<>(); value.fieldNames().forEachRemaining(fields::add);
        if (!fields.equals(expected)) throw invalid();
    }
    private double range(com.fasterxml.jackson.databind.JsonNode value,double min,double max) {
        double number=value.asDouble(Double.NaN);
        if (!value.isNumber() || !Double.isFinite(number) || number<min || number>max) throw invalid();
        return number;
    }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"ASSET_VALIDATION_FAILED","Resource package input validation failed"); }
    private record Version(long id,int number,String status,long variantId,long wallpaperId,String platform,String type) {}
    private record Binding(String role,int ordinal,String key,String mime,String sha256,long size,boolean ready) {}
}
