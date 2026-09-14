package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.delivery.DeliveryDtos.*;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.device.DeviceProperties;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class DownloadTicketService {
    private final JdbcTemplate jdbc;
    private final PublicWallpaperViewReader wallpapers;
    private final RedisRateLimiter rateLimiter;
    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;
    private final InstallationEncryptionKeys keys;
    private final ObjectMapper mapper;
    private final FileStorage storage;
    private final DeviceProperties devices;
    private final Semaphore reads = new Semaphore(4);
    private static final Duration TTL = Duration.ofSeconds(90);
    public DownloadTicketService(JdbcTemplate jdbc, PublicWallpaperViewReader wallpapers, RedisRateLimiter rateLimiter,
            StringRedisTemplate redis, SecurityCrypto crypto, InstallationEncryptionKeys keys, ObjectMapper mapper,
            FileStorage storage, DeviceProperties devices) {
        this.jdbc=jdbc; this.wallpapers=wallpapers; this.rateLimiter=rateLimiter; this.redis=redis;
        this.crypto=crypto; this.keys=keys; this.mapper=mapper; this.storage=storage; this.devices=devices;
    }
    public DownloadDescriptor create(DevicePrincipal principal,long wallpaperId,CreateDownloadTicketRequest request) {
        rateLimiter.require("download-ticket",Long.toString(principal.deviceId()),30,Duration.ofMinutes(1));
        if (request.platform()!=principal.platform()) throw new ApiException(HttpStatus.FORBIDDEN,"PLATFORM_MISMATCH","The requested platform does not match the device session");
        if (!entitled(principal.deviceId(),wallpaperId)) throw new ApiException(HttpStatus.FORBIDDEN,"ENTITLEMENT_REQUIRED","An active entitlement is required");
        var wallpaper=wallpapers.summary(wallpaperId);
        if (principal.platform()==DevicePlatform.H5_TEST) return new DownloadDescriptor(DeliveryMode.H5_PLACEHOLDER,Long.toString(wallpaperId),wallpaper.cover(),null,null,null,null,null);
        if (principal.platform()!=DevicePlatform.ANDROID || !devices.isAndroidEnabled()) throw unavailable();
        var encryptionKey=keys.require(principal);
        String fingerprint=SecurePackageCodec.sha256(encryptionKey.getEncoded());
        List<PackageRow> candidates=jdbc.query(SELECT_PACKAGE+" WHERE w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.platform IN ('ANDROID','UNIVERSAL') ORDER BY CASE v.platform WHEN 'ANDROID' THEN 0 ELSE 1 END,r.version_no DESC,r.id",DownloadTicketService::row,wallpaperId);
        PackageRow selected=null;
        for (ResourceType type:request.supportedResourceTypes()) {
            selected=candidates.stream().filter(p -> p.type().equals(type.name()) && compatibleOs(request.osVersion(),p.minimumOs()) && p.requirementCount()==0).findFirst().orElse(null);
            if (selected!=null) break;
        }
        if (selected==null) throw unavailable();
        byte[] contentKey=crypto.decrypt("secure-package-key-v2:"+selected.versionId(),selected.encryptedKey());
        String wrapped;
        try { wrapped=Base64.getUrlEncoder().withoutPadding().encodeToString(SecurePackageCodec.wrapContentKey(contentKey,encryptionKey)); }
        finally { Arrays.fill(contentKey,(byte)0); }
        String token=crypto.randomToken(32); Instant expiry=Instant.now().plus(TTL);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.versionId(),fingerprint,expiry.toString());
        validate(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize download ticket"); }
        return new DownloadDescriptor(DeliveryMode.SECURE_PACKAGE,Long.toString(wallpaperId),wallpaper.cover(),token,"/api/v1/delivery/files",expiry,
                new DownloadResourceVersion(Long.toString(selected.versionId()),Long.toString(selected.variantId()),selected.number(),DeliveryPlatform.valueOf(selected.platform()),ResourceType.valueOf(selected.type()),selected.manifestHash()),
                new SecurePackageMetadata(2,selected.size(),selected.plainSize(),selected.encryptedHash(),selected.plainHash(),selected.signingKeyId(),fingerprint,wrapped,"RSA-OAEP-SHA256-MGF1-SHA1"));
    }
    public ProtectedFile readProtectedFile(String token) {
        Ticket state=readTicket(token);
        rateLimiter.require("download-read",Long.toString(state.deviceId()),12,Duration.ofMinutes(1));
        PackageRow resource=validate(state);
        return new ProtectedFile(resource.size(),resource.encryptedHash(),output -> stream(token,resource,output));
    }
    private void stream(String token,PackageRow expected,OutputStream output) throws IOException {
        if (!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            // Check again when the deferred HTTP stream actually starts.
            PackageRow current=validate(readTicket(token));
            if (current.versionId()!=expected.versionId() || !current.encryptedHash().equals(expected.encryptedHash()) || current.size()!=expected.size()) throw invalid();
            try (var content=storage.open(new StorageKey(current.storageKey()))) {
                if (content.sizeBytes()!=current.size()) throw new IOException("Package length changed");
                byte[] buffer=new byte[32768]; long remaining=current.size();
                while (remaining>0) {
                    int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));
                    if (count<0) throw new IOException("Incomplete package");
                    output.write(buffer,0,count); remaining-=count;
                }
                if (content.inputStream().read()!=-1) throw new IOException("Package length changed");
            }
        } finally { reads.release(); }
    }
    private Ticket readTicket(String token) {
        if (token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String json=redis.opsForValue().get(ticketKey(token));
        if (json==null) throw invalid();
        try {
            Ticket state=mapper.readValue(json,Ticket.class);
            if (!Instant.parse(state.expiresAt()).isAfter(Instant.now())) throw invalid();
            return state;
        } catch (Exception error) { throw invalid(); }
    }
    private PackageRow validate(Ticket state) {
        if (!devices.isAndroidEnabled() || !entitled(state.deviceId(),state.wallpaperId())) throw invalid();
        var credentials=jdbc.queryForList("""
                SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                JOIN device_encryption_key k ON k.credential_id=c.id
                WHERE d.id=? AND d.status='ACTIVE' AND d.platform='ANDROID'
                  AND c.credential_key_id=? AND c.status='ACTIVE' AND k.public_key_sha256=?
                """,String.class,state.deviceId(),state.credentialKeyId(),state.keyHash());
        if (credentials.size()!=1 || !devices.getAllowedAndroidScopes().contains(credentials.get(0))) throw invalid();
        var resources=jdbc.query(SELECT_PACKAGE+" WHERE r.id=? AND w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.platform IN ('ANDROID','UNIVERSAL')",DownloadTicketService::row,state.versionId(),state.wallpaperId());
        if (resources.size()!=1) throw invalid();
        return resources.get(0);
    }
    private boolean entitled(long device,long wallpaper) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement WHERE device_id=? AND wallpaper_id=? AND status='ACTIVE'",Integer.class,device,wallpaper));
    }
    private String ticketKey(String token) { return "download-ticket-v2:"+crypto.hmacHex("download-ticket-v2",token); }
    static boolean compatibleOs(String actual,String minimum) {
        if (minimum==null || minimum.isBlank()) return true;
        if (actual==null || !actual.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}") || !minimum.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")) return false;
        String[] a=actual.split("\\."),b=minimum.split("\\.");
        for(int i=0;i<Math.max(a.length,b.length);i++) { int x=i<a.length?Integer.parseInt(a[i]):0,y=i<b.length?Integer.parseInt(b[i]):0; if(x!=y)return x>y; }
        return true;
    }
    public record Ticket(long deviceId,String credentialKeyId,long wallpaperId,long versionId,String keyHash,String expiresAt) {}
    @FunctionalInterface public interface Writer { void write(OutputStream output) throws IOException; }
    public record ProtectedFile(long sizeBytes,String sha256,Writer writer) {}
    private static final String SELECT_PACKAGE="""
            SELECT r.id,r.version_no,v.id AS variant_id,v.platform,v.resource_type,v.minimum_os_version,
                   JSON_LENGTH(v.capability_requirements) AS requirement_count,p.*
            FROM secure_resource_package p JOIN resource_version r ON r.id=p.resource_version_id
            JOIN wallpaper_variant v ON v.id=r.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
            """;
    private static PackageRow row(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new PackageRow(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("platform"),rs.getString("resource_type"),
                rs.getString("minimum_os_version"),rs.getInt("requirement_count"),rs.getString("storage_key"),rs.getLong("size_bytes"),rs.getLong("plaintext_size_bytes"),
                rs.getString("encrypted_sha256"),rs.getString("plaintext_sha256"),rs.getString("manifest_sha256"),rs.getString("signing_key_id"),rs.getString("content_key_ciphertext"));
    }
    private record PackageRow(long versionId,long variantId,int number,String platform,String type,String minimumOs,int requirementCount,String storageKey,long size,long plainSize,String encryptedHash,String plainHash,String manifestHash,String signingKeyId,String encryptedKey) {}
    private static ApiException unavailable() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"SECURE_PACKAGE_NOT_READY","No compatible secure package is available"); }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED,"DOWNLOAD_TICKET_INVALID","The download ticket is invalid or expired"); }
}
