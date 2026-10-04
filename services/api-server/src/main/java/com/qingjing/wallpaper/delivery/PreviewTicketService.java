package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.catalog.PlatformResourceScope;
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
    private final PreviewGenerationService previews;
    private final Semaphore reads=new Semaphore(4);
    private static final Duration TTL=Duration.ofSeconds(90);
    public PreviewTicketService(JdbcTemplate jdbc,StringRedisTemplate redis,SecurityCrypto crypto,RedisRateLimiter limiter,
        DeviceProperties devices,InstallationEncryptionKeys keys,PublicWallpaperViewReader wallpapers,FileStorage storage,ObjectMapper mapper,PreviewGenerationService previews) {
        this.jdbc=jdbc;this.redis=redis;this.crypto=crypto;this.limiter=limiter;this.devices=devices;this.keys=keys;this.wallpapers=wallpapers;this.storage=storage;this.mapper=mapper;this.previews=previews;
    }
    public PreviewDtos.PreviewDescriptor create(DevicePrincipal principal,long wallpaperId,PreviewDtos.CreatePreviewTicketRequest request) {
        limiter.require("preview-ticket",Long.toString(principal.deviceId()),6,Duration.ofMinutes(1));
        if(!PlatformResourceScope.visibleTo(principal.platform(),request.deliveryPlatform(),request.resourceType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"RESOURCE_PLATFORM_MISMATCH",
                    "The requested resource does not belong to the authenticated App platform");
        }
        wallpapers.summary(wallpaperId);
        long revision=previews.requireReady(wallpaperId);
        if(principal.platform()==DeviceDtos.DevicePlatform.HARMONYOS
                && request.deliveryPlatform()==DeliveryPlatform.HARMONYOS
                && request.resourceType()==ResourceType.MOVING_PHOTO) {
            return createMovingPhoto(principal,wallpaperId,revision);
        }
        if(principal.platform()==DeviceDtos.DevicePlatform.IOS
                && request.deliveryPlatform()==DeliveryPlatform.IOS
                && request.resourceType()==ResourceType.LIVE_PHOTO) {
            return createLivePhoto(principal,wallpaperId,revision);
        }
        if(principal.platform()!=DeviceDtos.DevicePlatform.ANDROID || !devices.isAndroidEnabled()) throw unavailable();
        var publicKey=keys.require(principal);String fingerprint=SecurePackageCodec.sha256(publicKey.getEncoded());
        var candidates=jdbc.query(SELECT+" WHERE w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE AND v.platform=? AND v.resource_type=? ORDER BY r.version_no DESC,r.id",PreviewTicketService::row,wallpaperId,request.deliveryPlatform().name(),request.resourceType().name());
        var selected=candidates.stream().findFirst().orElseThrow(PreviewTicketService::unavailable);
        byte[] key=crypto.decrypt("preview-package-key-v1:"+selected.version(),selected.encryptedKey());String wrapped;
        try { wrapped=Base64.getUrlEncoder().withoutPadding().encodeToString(SecurePackageCodec.wrapContentKey(key,publicKey)); }
        finally { Arrays.fill(key,(byte)0); }
        Instant expiry=Instant.now().plus(TTL);String token=crypto.randomToken(32);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.version(),fingerprint,
                principal.platform(),request.deliveryPlatform(),request.resourceType(),PreviewMode.SECURE_PACKAGE,
                selected.storage(),selected.size(),selected.encryptedHash(),expiry.toString(),revision);validatePackage(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException("Cannot serialize preview grant"); }
        return new PreviewDtos.PreviewDescriptor("APP_PREVIEW","APP_PREVIEW",120,Long.toString(wallpaperId),token,"/api/v1/preview/files",expiry,
            new DeliveryDtos.DownloadResourceVersion(Long.toString(selected.version()),Long.toString(selected.variant()),selected.number(),DeliveryPlatform.valueOf(selected.platform()),request.resourceType(),selected.manifest()),
            new DeliveryDtos.SecurePackageMetadata(3,selected.size(),selected.plainSize(),selected.encryptedHash(),selected.plainHash(),selected.signing(),fingerprint,wrapped,"RSA-OAEP-SHA256-MGF1-SHA1"),null,revision);
    }
    public DownloadTicketService.ProtectedFile read(String token) {
        var ticket=ticket(token);if(ticket.mode()!=PreviewMode.SECURE_PACKAGE)throw invalid();
        limiter.require("preview-read",Long.toString(ticket.device()),12,Duration.ofMinutes(1));var expected=validatePackage(ticket);
        return new DownloadTicketService.ProtectedFile(expected.size(),expected.encryptedHash(),output->{
            if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active preview transfers");
            try {
                var current=validatePackage(ticket(token));
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
    public DownloadTicketService.ProtectedFile readMovingPhotoVideo(String token) {
        var ticket=ticket(token);if(ticket.mode()!=PreviewMode.MOVING_PHOTO || ticket.platform()!=DeviceDtos.DevicePlatform.HARMONYOS)throw invalid();
        limiter.require("preview-moving-photo-read",Long.toString(ticket.device()),12,Duration.ofMinutes(1));
        var expected=validateMovingPhoto(ticket);
        return new DownloadTicketService.ProtectedFile(expected.videoSize(),expected.videoHash(),output->{
            if(!reads.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active preview transfers");
            try {
                var current=validateMovingPhoto(ticket(token));
                if(current.version()!=expected.version() || current.videoSize()!=expected.videoSize()
                        || !current.videoHash().equals(expected.videoHash()) || !current.videoStorage().equals(expected.videoStorage()))throw invalid();
                try(var file=storage.open(new StorageKey(current.videoStorage()))) {
                    if(file.sizeBytes()!=current.videoSize())throw new java.io.IOException("Moving Photo preview length changed");
                    long remaining=current.videoSize();byte[] buffer=new byte[32768];
                    while(remaining>0){int n=file.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.IOException("Incomplete Moving Photo preview");output.write(buffer,0,n);remaining-=n;}
                    if(file.inputStream().read()!=-1)throw new java.io.IOException("Moving Photo preview length changed");
                }
            } finally { reads.release(); }
        });
    }
    public DownloadTicketService.ProtectedFile readLivePhotoVideo(String token) {
        var ticket=ticket(token);if(ticket.mode()!=PreviewMode.LIVE_PHOTO || ticket.platform()!=DeviceDtos.DevicePlatform.IOS)throw invalid();
        limiter.require("preview-live-photo-read",Long.toString(ticket.device()),12,Duration.ofMinutes(1));
        var expected=validateLivePhoto(ticket);
        return new DownloadTicketService.ProtectedFile(expected.videoSize(),expected.videoHash(),output->{
            if(!reads.tryAcquire())throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active preview transfers");
            try {
                var current=validateLivePhoto(ticket(token));
                if(current.version()!=expected.version() || current.videoSize()!=expected.videoSize()
                        || !current.videoHash().equals(expected.videoHash()) || !current.videoStorage().equals(expected.videoStorage()))throw invalid();
                try(var file=storage.open(new StorageKey(current.videoStorage()))) {
                    if(file.sizeBytes()!=current.videoSize())throw new java.io.IOException("Live Photo preview length changed");
                    long remaining=current.videoSize();byte[] buffer=new byte[32768];
                    while(remaining>0){int n=file.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(n<0)throw new java.io.IOException("Incomplete Live Photo preview");output.write(buffer,0,n);remaining-=n;}
                    if(file.inputStream().read()!=-1)throw new java.io.IOException("Live Photo preview length changed");
                }
            } finally { reads.release(); }
        });
    }
    private Ticket ticket(String token) {
        if(token==null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();String json=redis.opsForValue().get(ticketKey(token));if(json==null) throw invalid();
        try { var ticket=mapper.readValue(json,Ticket.class);if(!Instant.parse(ticket.expires()).isAfter(Instant.now())) throw invalid();return ticket; }
        catch(Exception error) { throw invalid(); }
    }
    private Package validatePackage(Ticket ticket) {
        requireRevision(ticket);
        if(!devices.isAndroidEnabled()
                || ticket.platform()!=DeviceDtos.DevicePlatform.ANDROID || ticket.mode()!=PreviewMode.SECURE_PACKAGE
                || !PlatformResourceScope.visibleTo(ticket.platform(),ticket.resourcePlatform(),ticket.resourceType())) throw invalid();
        var scopes=jdbc.queryForList("""
            SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id JOIN device_encryption_key k ON k.credential_id=c.id
            WHERE d.id=? AND d.status='ACTIVE' AND d.platform='ANDROID' AND c.credential_key_id=? AND c.status='ACTIVE' AND k.public_key_sha256=?
            """,String.class,ticket.device(),ticket.credential(),ticket.fingerprint());
        if(scopes.size()!=1 || !devices.isAndroidScopeAllowed(scopes.get(0))) throw invalid();
        var rows=jdbc.query(SELECT+" WHERE r.id=? AND w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE",PreviewTicketService::row,ticket.version(),ticket.wallpaper());
        if(rows.size()!=1) throw invalid();
        Package selected=rows.get(0);
        if(!selected.platform().equals(ticket.resourcePlatform().name())
                || !selected.type().equals(ticket.resourceType().name()) || !Objects.equals(ticket.resourceStorage(),selected.storage())
                || ticket.resourceSize()!=selected.size() || !Objects.equals(ticket.resourceHash(),selected.encryptedHash())) throw invalid();
        return selected;
    }
    private PreviewDtos.PreviewDescriptor createMovingPhoto(DevicePrincipal principal,long wallpaperId,long revision) {
        if(!devices.isHarmonyEnabled())throw unavailable();
        var candidates=jdbc.query(SELECT_MOVING_PHOTO+"""
            WHERE w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
              AND v.platform='HARMONYOS' AND v.resource_type='MOVING_PHOTO' AND mp.status='READY'
            ORDER BY rv.version_no DESC,rv.id DESC
            """,PreviewTicketService::movingPhotoRow,wallpaperId);
        var selected=candidates.stream().findFirst().orElseThrow(PreviewTicketService::unavailable);
        Instant expiry=Instant.now().plus(TTL);String token=crypto.randomToken(32);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.version(),null,
                principal.platform(),DeliveryPlatform.HARMONYOS,ResourceType.MOVING_PHOTO,PreviewMode.MOVING_PHOTO,
                selected.videoStorage(),selected.videoSize(),selected.videoHash(),expiry.toString(),revision);
        validateMovingPhoto(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot serialize preview grant");}
        int durationSeconds=(int)Math.max(1,Math.ceil(selected.durationMs()/1000.0));
        return new PreviewDtos.PreviewDescriptor("MOVING_PHOTO_PREVIEW","APP_PREVIEW",durationSeconds,Long.toString(wallpaperId),token,null,expiry,
                new DeliveryDtos.DownloadResourceVersion(Long.toString(selected.version()),Long.toString(selected.variant()),selected.number(),
                        DeliveryPlatform.HARMONYOS,ResourceType.MOVING_PHOTO,selected.manifest()),null,
                new DeliveryDtos.DeliveryFile("/api/v1/preview/moving-photo/video",selected.videoHash(),selected.videoSize(),"video/mp4"),revision);
    }
    private PreviewDtos.PreviewDescriptor createLivePhoto(DevicePrincipal principal,long wallpaperId,long revision) {
        if(!devices.isIosEnabled())throw unavailable();
        var candidates=jdbc.query(SELECT_LIVE_PHOTO+"""
            WHERE w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
              AND v.platform='IOS' AND v.resource_type='LIVE_PHOTO' AND lp.status='READY' AND lp.duration_ms=1000
            ORDER BY rv.version_no DESC,rv.id DESC
            """,PreviewTicketService::livePhotoRow,wallpaperId);
        var selected=candidates.stream().findFirst().orElseThrow(PreviewTicketService::unavailable);
        Instant expiry=Instant.now().plus(TTL);String token=crypto.randomToken(32);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.version(),null,
                principal.platform(),DeliveryPlatform.IOS,ResourceType.LIVE_PHOTO,PreviewMode.LIVE_PHOTO,
                selected.videoStorage(),selected.videoSize(),selected.videoHash(),expiry.toString(),revision);
        validateLivePhoto(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot serialize preview grant");}
        return new PreviewDtos.PreviewDescriptor("LIVE_PHOTO_PREVIEW","APP_PREVIEW",1,Long.toString(wallpaperId),token,null,expiry,
                new DeliveryDtos.DownloadResourceVersion(Long.toString(selected.version()),Long.toString(selected.variant()),selected.number(),
                        DeliveryPlatform.IOS,ResourceType.LIVE_PHOTO,selected.manifest()),null,
                new DeliveryDtos.DeliveryFile("/api/v1/preview/live-photo/video",selected.videoHash(),selected.videoSize(),"video/quicktime"),revision);
    }
    private MovingPhoto validateMovingPhoto(Ticket ticket) {
        requireRevision(ticket);
        if(!devices.isHarmonyEnabled() || ticket.platform()!=DeviceDtos.DevicePlatform.HARMONYOS
                || ticket.resourcePlatform()!=DeliveryPlatform.HARMONYOS || ticket.resourceType()!=ResourceType.MOVING_PHOTO
                || ticket.mode()!=PreviewMode.MOVING_PHOTO)throw invalid();
        var scopes=jdbc.queryForList("""
            SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
            WHERE d.id=? AND d.status='ACTIVE' AND d.platform='HARMONYOS' AND c.credential_key_id=? AND c.status='ACTIVE'
            """,String.class,ticket.device(),ticket.credential());
        if(scopes.size()!=1 || !devices.isHarmonyScopeAllowed(scopes.get(0)))throw invalid();
        var rows=jdbc.query(SELECT_MOVING_PHOTO+"""
            WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
              AND v.platform='HARMONYOS' AND v.resource_type='MOVING_PHOTO' AND mp.status='READY'
            """,PreviewTicketService::movingPhotoRow,ticket.version(),ticket.wallpaper());
        if(rows.size()!=1)throw invalid();
        MovingPhoto selected=rows.get(0);
        if(!Objects.equals(ticket.resourceStorage(),selected.videoStorage()) || ticket.resourceSize()!=selected.videoSize()
                || !Objects.equals(ticket.resourceHash(),selected.videoHash())) throw invalid();
        return selected;
    }
    private LivePhoto validateLivePhoto(Ticket ticket) {
        requireRevision(ticket);
        if(!devices.isIosEnabled() || ticket.platform()!=DeviceDtos.DevicePlatform.IOS
                || ticket.resourcePlatform()!=DeliveryPlatform.IOS || ticket.resourceType()!=ResourceType.LIVE_PHOTO
                || ticket.mode()!=PreviewMode.LIVE_PHOTO)throw invalid();
        var scopes=jdbc.queryForList("""
            SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
            WHERE d.id=? AND d.status='ACTIVE' AND d.platform='IOS' AND c.credential_key_id=? AND c.status='ACTIVE'
            """,String.class,ticket.device(),ticket.credential());
        if(scopes.size()!=1 || !devices.isIosScopeAllowed(scopes.get(0)))throw invalid();
        var rows=jdbc.query(SELECT_LIVE_PHOTO+"""
            WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
              AND v.platform='IOS' AND v.resource_type='LIVE_PHOTO' AND lp.status='READY' AND lp.duration_ms=1000
            """,PreviewTicketService::livePhotoRow,ticket.version(),ticket.wallpaper());
        if(rows.size()!=1)throw invalid();
        LivePhoto selected=rows.get(0);
        if(!Objects.equals(ticket.resourceStorage(),selected.videoStorage())
                || ticket.resourceSize()!=selected.videoSize()
                || !Objects.equals(ticket.resourceHash(),selected.videoHash()))throw invalid();
        return selected;
    }
    private void requireRevision(Ticket ticket) {
        if(ticket.previewRevision()!=previews.requireReady(ticket.wallpaper())) throw invalid();
    }
    private String ticketKey(String token) { return "preview-ticket-v3:"+crypto.hmacHex("preview-ticket-v3",token); }
    private static final String SELECT="""
        SELECT r.id,r.version_no,v.id AS variant_id,v.platform,v.resource_type,v.minimum_os_version,JSON_LENGTH(v.capability_requirements) AS requirement_count,p.*
        FROM preview_resource_package p JOIN resource_version r ON r.id=p.resource_version_id JOIN wallpaper_variant v ON v.id=r.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
        JOIN wallpaper_preview_state ps ON ps.wallpaper_id=w.id AND ps.status='READY'
            AND ps.generated_revision=ps.requested_revision AND p.preview_revision=ps.requested_revision
        """;
    private static final String SELECT_MOVING_PHOTO="""
        SELECT rv.id,rv.version_no,rv.manifest_sha256,v.id AS variant_id,
               pm.storage_key AS video_storage_key,pm.size_bytes AS video_size_bytes,pm.sha256 AS video_sha256,pm.duration_ms
        FROM moving_photo_package mp JOIN resource_version rv ON rv.id=mp.resource_version_id
        JOIN wallpaper_variant v ON v.id=rv.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
        JOIN preview_media pm ON pm.resource_version_id=rv.id
        JOIN wallpaper_preview_state ps ON ps.wallpaper_id=w.id AND ps.status='READY'
            AND ps.generated_revision=ps.requested_revision AND pm.preview_revision=ps.requested_revision
        """;
    private static final String SELECT_LIVE_PHOTO="""
        SELECT rv.id,rv.version_no,rv.manifest_sha256,v.id AS variant_id,
               pm.storage_key AS video_storage_key,pm.size_bytes AS video_size_bytes,pm.sha256 AS video_sha256,pm.duration_ms
        FROM live_photo_package lp JOIN resource_version rv ON rv.id=lp.resource_version_id
        JOIN wallpaper_variant v ON v.id=rv.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
        JOIN preview_media pm ON pm.resource_version_id=rv.id
        JOIN wallpaper_preview_state ps ON ps.wallpaper_id=w.id AND ps.status='READY'
            AND ps.generated_revision=ps.requested_revision AND pm.preview_revision=ps.requested_revision
        """;
    private static Package row(java.sql.ResultSet rs,int ignored) throws java.sql.SQLException {
        if(rs.getInt("format_version")!=3 || !rs.getString("purpose").equals("APP_PREVIEW")) throw invalid();
        return new Package(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("platform"),rs.getString("resource_type"),rs.getString("minimum_os_version"),rs.getInt("requirement_count"),
            rs.getString("storage_key"),rs.getLong("size_bytes"),rs.getLong("plaintext_size_bytes"),rs.getString("encrypted_sha256"),rs.getString("plaintext_sha256"),rs.getString("manifest_sha256"),rs.getString("signing_key_id"),rs.getString("content_key_ciphertext"));
    }
    private static MovingPhoto movingPhotoRow(java.sql.ResultSet rs,int ignored)throws java.sql.SQLException {
        return new MovingPhoto(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("manifest_sha256"),
                rs.getString("video_storage_key"),rs.getLong("video_size_bytes"),rs.getString("video_sha256"),rs.getLong("duration_ms"));
    }
    private static LivePhoto livePhotoRow(java.sql.ResultSet rs,int ignored)throws java.sql.SQLException {
        return new LivePhoto(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("manifest_sha256"),
                rs.getString("video_storage_key"),rs.getLong("video_size_bytes"),rs.getString("video_sha256"),rs.getLong("duration_ms"));
    }
    public record Ticket(long device,String credential,long wallpaper,long version,String fingerprint,DeviceDtos.DevicePlatform platform,
            DeliveryPlatform resourcePlatform,ResourceType resourceType,PreviewMode mode,
            String resourceStorage,long resourceSize,String resourceHash,String expires,long previewRevision) { }
    public enum PreviewMode { SECURE_PACKAGE, MOVING_PHOTO, LIVE_PHOTO }
    private record Package(long version,long variant,int number,String platform,String type,String minimum,int requirements,String storage,long size,long plainSize,String encryptedHash,String plainHash,String manifest,String signing,String encryptedKey) { }
    private record MovingPhoto(long version,long variant,int number,String manifest,String videoStorage,long videoSize,String videoHash,long durationMs) { }
    private record LivePhoto(long version,long variant,int number,String manifest,String videoStorage,long videoSize,String videoHash,long durationMs) { }
    private static ApiException unavailable() { return new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_AVAILABLE","The requested resource is not available"); }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED,"PREVIEW_TICKET_INVALID","The preview grant is invalid or expired"); }
}
