package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.DeviceCatalogVisibility;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.Semaphore;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Free in-app previews use distinct grants and storage. This service never writes business entitlement facts. */
@Service
public class PreviewTicketService {
    private final JdbcTemplate jdbc;private final StringRedisTemplate redis;private final SecurityCrypto crypto;
    private final RedisRateLimiter limiter;private final DeviceProperties devices;private final InstallationEncryptionKeys keys;
    private final PublicWallpaperViewReader wallpapers;private final FileStorage storage;private final ObjectMapper mapper;
    private final DeviceCatalogVisibility visibility;
    private final Semaphore reads=new Semaphore(4);
    private static final Duration TTL=Duration.ofSeconds(90);
    public PreviewTicketService(JdbcTemplate jdbc,StringRedisTemplate redis,SecurityCrypto crypto,RedisRateLimiter limiter,
        DeviceProperties devices,InstallationEncryptionKeys keys,PublicWallpaperViewReader wallpapers,FileStorage storage,ObjectMapper mapper,
        DeviceCatalogVisibility visibility) {
        this.jdbc=jdbc;this.redis=redis;this.crypto=crypto;this.limiter=limiter;this.devices=devices;this.keys=keys;this.wallpapers=wallpapers;this.storage=storage;this.mapper=mapper;
        this.visibility=visibility;
    }
    public PreviewDtos.PreviewDescriptor create(DevicePrincipal principal,long wallpaperId,PreviewDtos.CreatePreviewTicketRequest request) {
        limiter.require("preview-ticket",Long.toString(principal.deviceId()),6,Duration.ofMinutes(1));
        if(principal.platform()!=DeviceDtos.DevicePlatform.ANDROID || !devices.isAndroidEnabled()) throw unavailable();
        if((request.deliveryPlatform()!=DeliveryPlatform.ANDROID && request.deliveryPlatform()!=DeliveryPlatform.UNIVERSAL)
                || !Set.of("STATIC_IMAGE","VIDEO","LAYER_PARALLAX").contains(request.resourceType().name())) throw unavailable();
        boolean allowed=visibility.resolve(principal.deviceId()).capabilities(wallpaperId).stream()
                .anyMatch(capability -> capability.deliveryPlatform()==request.deliveryPlatform()
                        && capability.resourceType()==request.resourceType());
        if(!allowed) throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_AVAILABLE_FOR_DEVICE","The requested preview is not available for this device");
        wallpapers.summary(wallpaperId);
        var publicKey=keys.require(principal);String fingerprint=SecurePackageCodec.sha256(publicKey.getEncoded());
        var candidates=jdbc.query(SELECT+" WHERE w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE AND v.platform=? AND v.resource_type=? ORDER BY r.version_no DESC,r.id",PreviewTicketService::row,wallpaperId,request.deliveryPlatform().name(),request.resourceType().name());
        var selected=candidates.stream().findFirst().orElseThrow(PreviewTicketService::unavailable);
        byte[] key=crypto.decrypt("preview-package-key-v1:"+selected.version(),selected.encryptedKey());String wrapped;
        try { wrapped=Base64.getUrlEncoder().withoutPadding().encodeToString(SecurePackageCodec.wrapContentKey(key,publicKey)); }
        finally { Arrays.fill(key,(byte)0); }
        Instant expiry=Instant.now().plus(TTL);String token=crypto.randomToken(32);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.version(),fingerprint,expiry.toString());validate(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot serialize preview grant"); }
        return new PreviewDtos.PreviewDescriptor("APP_PREVIEW","APP_PREVIEW",120,Long.toString(wallpaperId),token,"/api/v1/preview/files",expiry,
            new DeliveryDtos.DownloadResourceVersion(Long.toString(selected.version()),Long.toString(selected.variant()),selected.number(),DeliveryPlatform.valueOf(selected.platform()),request.resourceType(),selected.manifest()),
            new DeliveryDtos.SecurePackageMetadata(3,selected.size(),selected.plainSize(),selected.encryptedHash(),selected.plainHash(),selected.signing(),fingerprint,wrapped,"RSA-OAEP-SHA256-MGF1-SHA1"));
    }
    public DownloadTicketService.ProtectedFile read(String token) {
        var ticket=ticket(token);limiter.require("preview-read",Long.toString(ticket.device()),12,Duration.ofMinutes(1));var expected=validate(ticket);
        return new DownloadTicketService.ProtectedFile(expected.size(),expected.encryptedHash(),output->{
            if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active preview transfers");
            try {
                var current=validate(ticket(token));
                if(current.version()!=expected.version() || !current.encryptedHash().equals(expected.encryptedHash()) || current.size()!=expected.size()) throw invalid();
                try(var file=storage.open(new StorageKey(current.storage()))) {
                    if(file.sizeBytes()!=current.size()) throw new java.io.IOException("Preview length changed");
                    long remaining=current.size();byte[] buffer=new byte[32768];
                    while(remaining>0) { int n=file.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0) throw new java.io.IOException("Incomplete preview");output.write(buffer,0,n);remaining-=n; }
                    if(file.inputStream().read()!=-1) throw new java.io.IOException("Preview length changed");
                }
            } finally { reads.release(); }
        });
    }
    private Ticket ticket(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();String json=redis.opsForValue().get(ticketKey(token));if(json==null) throw invalid();
        try { var ticket=mapper.readValue(json,Ticket.class);if(!Instant.parse(ticket.expires()).isAfter(Instant.now())) throw invalid();return ticket; }
        catch(Exception error) { throw invalid(); }
    }
    private Package validate(Ticket ticket) {
        if(!devices.isAndroidEnabled()) throw invalid();
        var scopes=jdbc.queryForList("""
            SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id JOIN device_encryption_key k ON k.credential_id=c.id
            WHERE d.id=? AND d.status='ACTIVE' AND d.platform='ANDROID' AND c.credential_key_id=? AND c.status='ACTIVE' AND k.public_key_sha256=?
            """,String.class,ticket.device(),ticket.credential(),ticket.fingerprint());
        if(scopes.size()!=1 || !devices.getAllowedAndroidScopes().contains(scopes.get(0))) throw invalid();
        var rows=jdbc.query(SELECT+" WHERE r.id=? AND w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE AND v.platform IN ('ANDROID','UNIVERSAL')",PreviewTicketService::row,ticket.version(),ticket.wallpaper());
        if(rows.size()!=1) throw invalid();
        Package resource=rows.get(0);
        boolean stillAvailable=visibility.resolve(ticket.device()).capabilities(ticket.wallpaper()).stream()
                .anyMatch(capability -> capability.deliveryPlatform().name().equals(resource.platform())
                        && capability.resourceType().name().equals(resource.type()));
        if(!stillAvailable) throw invalid();
        return resource;
    }
    private String ticketKey(String token) { return "preview-ticket-v1:"+crypto.hmacHex("preview-ticket-v1",token); }
    private static final String SELECT="""
        SELECT r.id,r.version_no,v.id AS variant_id,v.platform,v.resource_type,v.minimum_os_version,JSON_LENGTH(v.capability_requirements) AS requirement_count,p.*
        FROM preview_resource_package p JOIN resource_version r ON r.id=p.resource_version_id JOIN wallpaper_variant v ON v.id=r.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
        """;
    private static Package row(java.sql.ResultSet rs,int ignored) throws java.sql.SQLException {
        if(rs.getInt("format_version")!=3 || !rs.getString("purpose").equals("APP_PREVIEW")) throw invalid();
        return new Package(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("platform"),rs.getString("resource_type"),rs.getString("minimum_os_version"),rs.getInt("requirement_count"),
            rs.getString("storage_key"),rs.getLong("size_bytes"),rs.getLong("plaintext_size_bytes"),rs.getString("encrypted_sha256"),rs.getString("plaintext_sha256"),rs.getString("manifest_sha256"),rs.getString("signing_key_id"),rs.getString("content_key_ciphertext"));
    }
    public record Ticket(long device,String credential,long wallpaper,long version,String fingerprint,String expires) { }
    private record Package(long version,long variant,int number,String platform,String type,String minimum,int requirements,String storage,long size,long plainSize,String encryptedHash,String plainHash,String manifest,String signing,String encryptedKey) { }
    private static ApiException unavailable() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"PREVIEW_RESOURCE_NOT_READY","A compatible app preview is unavailable"); }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED,"PREVIEW_TICKET_INVALID","The preview grant is invalid or expired"); }
}
