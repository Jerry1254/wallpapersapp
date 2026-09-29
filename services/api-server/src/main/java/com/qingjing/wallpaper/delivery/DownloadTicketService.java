package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.catalog.PlatformResourceScope;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.WallpaperAccessType;
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
        if (!PlatformResourceScope.visibleTo(principal.platform(), request.deliveryPlatform(), request.resourceType())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,"RESOURCE_PLATFORM_MISMATCH",
                    "The requested resource does not belong to the authenticated App platform");
        }
        Access access=access(wallpaperId);
        Authorization authorization=access.type()==WallpaperAccessType.FREE?Authorization.FREE:Authorization.ENTITLEMENT;
        var wallpaper=wallpapers.summary(wallpaperId);
        if (principal.platform()==DevicePlatform.HARMONYOS
                && request.deliveryPlatform()==DeliveryPlatform.HARMONYOS
                && request.resourceType()==ResourceType.MOVING_PHOTO) {
            return createMovingPhoto(principal, wallpaperId, authorization, wallpaper.cover());
        }
        if (principal.platform()==DevicePlatform.IOS
                && request.deliveryPlatform()==DeliveryPlatform.IOS
                && request.resourceType()==ResourceType.LIVE_PHOTO) {
            return createLivePhoto(principal, wallpaperId, authorization, wallpaper.cover());
        }
        if (principal.platform()!=DevicePlatform.ANDROID
                && request.deliveryPlatform()==DeliveryPlatform.UNIVERSAL
                && request.resourceType()==ResourceType.STATIC_IMAGE) {
            return createStaticImage(principal, wallpaperId, authorization, wallpaper.cover());
        }
        if (principal.platform()!=DevicePlatform.ANDROID || !devices.isAndroidEnabled()) throw unavailable();
        var encryptionKey=keys.require(principal);
        String fingerprint=SecurePackageCodec.sha256(encryptionKey.getEncoded());
        List<PackageRow> candidates=jdbc.query(SELECT_PACKAGE+" WHERE w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE AND v.platform=? AND v.resource_type=? ORDER BY r.version_no DESC,r.id",DownloadTicketService::row,wallpaperId,request.deliveryPlatform().name(),request.resourceType().name());
        PackageRow selected=candidates.stream().findFirst().orElse(null);
        if (selected==null) throw unavailable();
        if (authorization==Authorization.ENTITLEMENT && !entitled(principal.deviceId(),wallpaperId)) throw new ApiException(HttpStatus.FORBIDDEN,"ENTITLEMENT_REQUIRED","An active entitlement is required");
        byte[] contentKey=crypto.decrypt("secure-package-key-v2:"+selected.versionId(),selected.encryptedKey());
        String wrapped;
        try { wrapped=Base64.getUrlEncoder().withoutPadding().encodeToString(SecurePackageCodec.wrapContentKey(contentKey,encryptionKey)); }
        finally { Arrays.fill(contentKey,(byte)0); }
        String token=crypto.randomToken(32); Instant expiry=Instant.now().plus(TTL);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.versionId(),fingerprint,
                principal.platform(),request.deliveryPlatform(),request.resourceType(),DeliveryMode.SECURE_PACKAGE,
                authorization,expiry.toString());
        validateSecurePackage(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch (com.fasterxml.jackson.core.JsonProcessingException exception) { throw new IllegalStateException("Cannot serialize download ticket"); }
        return new DownloadDescriptor(DeliveryMode.SECURE_PACKAGE,Long.toString(wallpaperId),wallpaper.cover(),token,"/api/v1/delivery/files",expiry,
                new DownloadResourceVersion(Long.toString(selected.versionId()),Long.toString(selected.variantId()),selected.number(),DeliveryPlatform.valueOf(selected.platform()),ResourceType.valueOf(selected.type()),selected.manifestHash()),
                new SecurePackageMetadata(2,selected.size(),selected.plainSize(),selected.encryptedHash(),selected.plainHash(),selected.signingKeyId(),fingerprint,wrapped,"RSA-OAEP-SHA256-MGF1-SHA1"),
                null,null,null,null,null);
    }
    public ProtectedFile readProtectedFile(String token) {
        Ticket state=readTicket(token);
        rateLimiter.require("download-read",Long.toString(state.deviceId()),12,Duration.ofMinutes(1));
        if (state.deliveryMode()!=DeliveryMode.SECURE_PACKAGE) throw invalid();
        PackageRow resource=validateSecurePackage(state);
        return new ProtectedFile(resource.size(),resource.encryptedHash(),output -> stream(token,resource,output));
    }
    private void stream(String token,PackageRow expected,OutputStream output) throws IOException {
        if (!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            // Check again when the deferred HTTP stream actually starts.
            PackageRow current=validateSecurePackage(readTicket(token));
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
    public ProtectedFile readMovingPhotoFile(String token, MovingPhotoPart part) {
        Ticket state=readTicket(token);
        if(state.deliveryMode()!=DeliveryMode.MOVING_PHOTO || state.platform()!=DevicePlatform.HARMONYOS) throw invalid();
        rateLimiter.require("moving-photo-read",Long.toString(state.deviceId()),20,Duration.ofMinutes(1));
        MovingPhotoRow resource=validateMovingPhoto(state);
        String storageKey=part==MovingPhotoPart.POSTER?resource.posterStorage():resource.videoStorage();
        long size=part==MovingPhotoPart.POSTER?resource.posterSize():resource.videoSize();
        String hash=part==MovingPhotoPart.POSTER?resource.posterHash():resource.videoHash();
        return new ProtectedFile(size,hash,output->streamMovingPhoto(token,part,storageKey,size,hash,output));
    }
    public StaticImageFile readStaticImageFile(String token) {
        Ticket state=readTicket(token);
        if(state.deliveryMode()!=DeliveryMode.STATIC_IMAGE) throw invalid();
        rateLimiter.require("static-image-read",Long.toString(state.deviceId()),12,Duration.ofMinutes(1));
        StaticImageRow resource=validateStaticImage(state);
        return new StaticImageFile(resource.size(),resource.hash(),resource.mimeType(),
                output->streamStaticImage(token,resource,output));
    }
    public ProtectedFile readLivePhotoFile(String token, LivePhotoPart part) {
        Ticket state=readTicket(token);
        if(state.deliveryMode()!=DeliveryMode.LIVE_PHOTO || state.platform()!=DevicePlatform.IOS) throw invalid();
        rateLimiter.require("live-photo-read",Long.toString(state.deviceId()),20,Duration.ofMinutes(1));
        LivePhotoRow resource=validateLivePhoto(state);
        String storageKey=part==LivePhotoPart.PHOTO?resource.photoStorage():resource.videoStorage();
        long size=part==LivePhotoPart.PHOTO?resource.photoSize():resource.videoSize();
        String hash=part==LivePhotoPart.PHOTO?resource.photoHash():resource.videoHash();
        return new ProtectedFile(size,hash,output->streamLivePhoto(token,part,storageKey,size,hash,output));
    }
    public ProtectedFile readLivePhotoSourceFile(String token) {
        Ticket state=readTicket(token);
        if(state.deliveryMode()!=DeliveryMode.LIVE_PHOTO || state.platform()!=DevicePlatform.IOS) throw invalid();
        rateLimiter.require("live-photo-source-read",Long.toString(state.deviceId()),12,Duration.ofMinutes(1));
        LivePhotoRow resource=validateLivePhoto(state);
        return new ProtectedFile(resource.sourceSize(),resource.sourceHash(),
                output->streamLivePhotoSource(token,resource,output));
    }
    private void streamMovingPhoto(String token,MovingPhotoPart part,String key,long size,String hash,OutputStream output) throws IOException {
        if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            MovingPhotoRow current=validateMovingPhoto(readTicket(token));
            String currentKey=part==MovingPhotoPart.POSTER?current.posterStorage():current.videoStorage();
            long currentSize=part==MovingPhotoPart.POSTER?current.posterSize():current.videoSize();
            String currentHash=part==MovingPhotoPart.POSTER?current.posterHash():current.videoHash();
            if(!currentKey.equals(key)||currentSize!=size||!currentHash.equals(hash)) throw invalid();
            try(var content=storage.open(new StorageKey(key))) {
                if(content.sizeBytes()!=size) throw new IOException("Moving Photo length changed");
                byte[] buffer=new byte[32768];long remaining=size;
                while(remaining>0){int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Incomplete Moving Photo");output.write(buffer,0,count);remaining-=count;}
                if(content.inputStream().read()!=-1)throw new IOException("Moving Photo length changed");
            }
        } finally { reads.release(); }
    }
    private void streamStaticImage(String token,StaticImageRow expected,OutputStream output) throws IOException {
        if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            StaticImageRow current=validateStaticImage(readTicket(token));
            if(current.versionId()!=expected.versionId() || !current.storageKey().equals(expected.storageKey())
                    || current.size()!=expected.size() || !current.hash().equals(expected.hash())
                    || !current.mimeType().equals(expected.mimeType())) throw invalid();
            try(var content=storage.open(new StorageKey(current.storageKey()))) {
                if(content.sizeBytes()!=current.size()) throw new IOException("Static image length changed");
                byte[] buffer=new byte[32768];long remaining=current.size();
                while(remaining>0){int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Incomplete static image");output.write(buffer,0,count);remaining-=count;}
                if(content.inputStream().read()!=-1)throw new IOException("Static image length changed");
            }
        } finally { reads.release(); }
    }
    private void streamLivePhoto(String token,LivePhotoPart part,String key,long size,String hash,OutputStream output) throws IOException {
        if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            LivePhotoRow current=validateLivePhoto(readTicket(token));
            String currentKey=part==LivePhotoPart.PHOTO?current.photoStorage():current.videoStorage();
            long currentSize=part==LivePhotoPart.PHOTO?current.photoSize():current.videoSize();
            String currentHash=part==LivePhotoPart.PHOTO?current.photoHash():current.videoHash();
            if(!currentKey.equals(key)||currentSize!=size||!currentHash.equals(hash)) throw invalid();
            try(var content=storage.open(new StorageKey(key))) {
                if(content.sizeBytes()!=size) throw new IOException("Live Photo length changed");
                byte[] buffer=new byte[32768];long remaining=size;
                while(remaining>0){int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Incomplete Live Photo");output.write(buffer,0,count);remaining-=count;}
                if(content.inputStream().read()!=-1)throw new IOException("Live Photo length changed");
            }
        } finally { reads.release(); }
    }
    private void streamLivePhotoSource(String token,LivePhotoRow expected,OutputStream output) throws IOException {
        if(!reads.tryAcquire()) throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","Too many active downloads");
        try {
            LivePhotoRow current=validateLivePhoto(readTicket(token));
            if(current.versionId()!=expected.versionId() || !current.sourceStorage().equals(expected.sourceStorage())
                    || current.sourceSize()!=expected.sourceSize() || !current.sourceHash().equals(expected.sourceHash())) {
                throw invalid();
            }
            requireSourceIntegrity(current);
            try(var content=storage.open(new StorageKey(current.sourceStorage()))) {
                if(content.sizeBytes()!=current.sourceSize()) throw new IOException("Live Photo source length changed");
                byte[] buffer=new byte[32768];long remaining=current.sourceSize();
                while(remaining>0){int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw new IOException("Incomplete Live Photo source");output.write(buffer,0,count);remaining-=count;}
                if(content.inputStream().read()!=-1)throw new IOException("Live Photo source length changed");
            }
        } finally { reads.release(); }
    }
    private PackageRow validateSecurePackage(Ticket state) {
        if (!devices.isAndroidEnabled() || state.authorization()==null ||
                state.platform()!=DevicePlatform.ANDROID || state.deliveryMode()!=DeliveryMode.SECURE_PACKAGE ||
                !PlatformResourceScope.visibleTo(state.platform(),state.resourcePlatform(),state.resourceType()) ||
                (state.authorization()==Authorization.ENTITLEMENT && !entitled(state.deviceId(),state.wallpaperId()))) throw invalid();
        var credentials=jdbc.queryForList("""
                SELECT d.app_install_scope FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                JOIN device_encryption_key k ON k.credential_id=c.id
                WHERE d.id=? AND d.status='ACTIVE' AND d.platform='ANDROID'
                  AND c.credential_key_id=? AND c.status='ACTIVE' AND k.public_key_sha256=?
                """,String.class,state.deviceId(),state.credentialKeyId(),state.keyHash());
        if (credentials.size()!=1 || !devices.isAndroidScopeAllowed(credentials.get(0))) throw invalid();
        var resources=jdbc.query(SELECT_PACKAGE+" WHERE r.id=? AND w.id=? AND w.status='PUBLISHED' AND r.status='PUBLISHED' AND v.enabled=TRUE",DownloadTicketService::row,state.versionId(),state.wallpaperId());
        if (resources.size()!=1) throw invalid();
        PackageRow resource=resources.get(0);
        if (!resource.platform().equals(state.resourcePlatform().name())
                || !resource.type().equals(state.resourceType().name())) throw invalid();
        return resource;
    }
    private DownloadDescriptor createMovingPhoto(DevicePrincipal principal,long wallpaperId,Authorization authorization,
            com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia cover) {
        List<MovingPhotoRow> candidates=jdbc.query(SELECT_MOVING_PHOTO+"""
                WHERE w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
                  AND v.platform='HARMONYOS' AND v.resource_type='MOVING_PHOTO' AND mp.status='READY'
                ORDER BY rv.version_no DESC,rv.id DESC
                """,DownloadTicketService::movingPhotoRow,wallpaperId);
        MovingPhotoRow selected=candidates.stream().findFirst().orElseThrow(DownloadTicketService::resourceUnavailable);
        if(authorization==Authorization.ENTITLEMENT&&!entitled(principal.deviceId(),wallpaperId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,"ENTITLEMENT_REQUIRED","An active entitlement is required");
        }
        String token=crypto.randomToken(32);Instant expiry=Instant.now().plus(TTL);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.versionId(),null,
                DevicePlatform.HARMONYOS,DeliveryPlatform.HARMONYOS,ResourceType.MOVING_PHOTO,
                DeliveryMode.MOVING_PHOTO,authorization,expiry.toString());
        validateMovingPhoto(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot serialize download ticket");}
        return new DownloadDescriptor(DeliveryMode.MOVING_PHOTO,Long.toString(wallpaperId),cover,token,null,expiry,
                new DownloadResourceVersion(Long.toString(selected.versionId()),Long.toString(selected.variantId()),selected.number(),
                        DeliveryPlatform.HARMONYOS,ResourceType.MOVING_PHOTO,selected.manifestHash()),null,
                new DeliveryFile("/api/v1/delivery/moving-photo/poster",selected.posterHash(),selected.posterSize(),"image/jpeg"),null,
                new DeliveryFile("/api/v1/delivery/moving-photo/video",selected.videoHash(),selected.videoSize(),"video/mp4"),null,null);
    }
    private DownloadDescriptor createLivePhoto(DevicePrincipal principal,long wallpaperId,Authorization authorization,
            com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia cover) {
        List<LivePhotoRow> candidates=jdbc.query(SELECT_LIVE_PHOTO+"""
                WHERE w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
                  AND v.platform='IOS' AND v.resource_type='LIVE_PHOTO' AND lp.status='READY'
                ORDER BY rv.version_no DESC,rv.id DESC
                """,DownloadTicketService::livePhotoRow,wallpaperId);
        LivePhotoRow selected=candidates.stream().findFirst().orElseThrow(DownloadTicketService::resourceUnavailable);
        if(authorization==Authorization.ENTITLEMENT&&!entitled(principal.deviceId(),wallpaperId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,"ENTITLEMENT_REQUIRED","An active entitlement is required");
        }
        String token=crypto.randomToken(32);Instant expiry=Instant.now().plus(TTL);
        String sourceFingerprint=livePhotoSourceFingerprint(selected);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.versionId(),sourceFingerprint,
                DevicePlatform.IOS,DeliveryPlatform.IOS,ResourceType.LIVE_PHOTO,
                DeliveryMode.LIVE_PHOTO,authorization,expiry.toString());
        LivePhotoRow current=validateLivePhoto(state);
        requireSourceIntegrity(current);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot serialize download ticket");}
        return new DownloadDescriptor(DeliveryMode.LIVE_PHOTO,Long.toString(wallpaperId),cover,token,null,expiry,
                new DownloadResourceVersion(Long.toString(selected.versionId()),Long.toString(selected.variantId()),selected.number(),
                        DeliveryPlatform.IOS,ResourceType.LIVE_PHOTO,selected.manifestHash()),null,null,null,null,
                new DeliveryFile("/api/v1/delivery/live-photo/source",current.sourceHash(),current.sourceSize(),"video/mp4"),null);
    }
    private DownloadDescriptor createStaticImage(DevicePrincipal principal,long wallpaperId,Authorization authorization,
            com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia cover) {
        List<StaticImageRow> candidates=jdbc.query(SELECT_STATIC_IMAGE+"""
                WHERE w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED' AND v.enabled=TRUE
                  AND v.platform='UNIVERSAL' AND v.resource_type='STATIC_IMAGE'
                  AND a.validation_status='READY' AND a.deleted_at IS NULL
                  AND a.mime_type IN ('image/jpeg','image/png','image/webp')
                ORDER BY rv.version_no DESC,rv.id DESC
                """,DownloadTicketService::staticImageRow,wallpaperId);
        StaticImageRow selected=candidates.stream().findFirst().orElseThrow(DownloadTicketService::resourceUnavailable);
        if(authorization==Authorization.ENTITLEMENT&&!entitled(principal.deviceId(),wallpaperId)) {
            throw new ApiException(HttpStatus.FORBIDDEN,"ENTITLEMENT_REQUIRED","An active entitlement is required");
        }
        String token=crypto.randomToken(32);Instant expiry=Instant.now().plus(TTL);
        Ticket state=new Ticket(principal.deviceId(),principal.credentialKeyId(),wallpaperId,selected.versionId(),null,
                principal.platform(),DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE,
                DeliveryMode.STATIC_IMAGE,authorization,expiry.toString());
        validateStaticImage(state);
        try { redis.opsForValue().set(ticketKey(token),mapper.writeValueAsString(state),TTL); }
        catch(com.fasterxml.jackson.core.JsonProcessingException error){throw new IllegalStateException("Cannot serialize download ticket");}
        return new DownloadDescriptor(DeliveryMode.STATIC_IMAGE,Long.toString(wallpaperId),cover,token,null,expiry,
                new DownloadResourceVersion(Long.toString(selected.versionId()),Long.toString(selected.variantId()),selected.number(),
                        DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE,selected.manifestHash()),null,null,null,null,null,
                new DeliveryFile("/api/v1/delivery/static-image",selected.hash(),selected.size(),selected.mimeType()));
    }
    private MovingPhotoRow validateMovingPhoto(Ticket state) {
        if(state.authorization()==null || state.platform()!=DevicePlatform.HARMONYOS
                || state.resourcePlatform()!=DeliveryPlatform.HARMONYOS || state.resourceType()!=ResourceType.MOVING_PHOTO
                || state.deliveryMode()!=DeliveryMode.MOVING_PHOTO ||
                (state.authorization()==Authorization.ENTITLEMENT&&!entitled(state.deviceId(),state.wallpaperId())))throw invalid();
        Integer credentials=jdbc.queryForObject("""
                SELECT COUNT(*) FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                WHERE d.id=? AND d.status='ACTIVE' AND d.platform='HARMONYOS'
                  AND c.credential_key_id=? AND c.status='ACTIVE'
                """,Integer.class,state.deviceId(),state.credentialKeyId());
        if(credentials==null||credentials!=1)throw invalid();
        List<MovingPhotoRow> rows=jdbc.query(SELECT_MOVING_PHOTO+"""
                WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED'
                  AND v.enabled=TRUE AND v.platform='HARMONYOS' AND v.resource_type='MOVING_PHOTO' AND mp.status='READY'
                """,DownloadTicketService::movingPhotoRow,state.versionId(),state.wallpaperId());
        if(rows.size()!=1)throw invalid();
        return rows.get(0);
    }
    private StaticImageRow validateStaticImage(Ticket state) {
        if(state.authorization()==null || state.platform()==DevicePlatform.ANDROID
                || state.resourcePlatform()!=DeliveryPlatform.UNIVERSAL || state.resourceType()!=ResourceType.STATIC_IMAGE
                || state.deliveryMode()!=DeliveryMode.STATIC_IMAGE
                || !PlatformResourceScope.visibleTo(state.platform(),state.resourcePlatform(),state.resourceType())
                || (state.authorization()==Authorization.ENTITLEMENT&&!entitled(state.deviceId(),state.wallpaperId())))throw invalid();
        Integer credentials=jdbc.queryForObject("""
                SELECT COUNT(*) FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                WHERE d.id=? AND d.status='ACTIVE' AND d.platform=?
                  AND c.credential_key_id=? AND c.status='ACTIVE'
                """,Integer.class,state.deviceId(),state.platform().name(),state.credentialKeyId());
        if(credentials==null||credentials!=1)throw invalid();
        List<StaticImageRow> rows=jdbc.query(SELECT_STATIC_IMAGE+"""
                WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED'
                  AND v.enabled=TRUE AND v.platform='UNIVERSAL' AND v.resource_type='STATIC_IMAGE'
                  AND a.validation_status='READY' AND a.deleted_at IS NULL
                  AND a.mime_type IN ('image/jpeg','image/png','image/webp')
                """,DownloadTicketService::staticImageRow,state.versionId(),state.wallpaperId());
        if(rows.size()!=1)throw invalid();
        return rows.get(0);
    }
    private LivePhotoRow validateLivePhoto(Ticket state) {
        if(state.authorization()==null || state.platform()!=DevicePlatform.IOS
                || state.resourcePlatform()!=DeliveryPlatform.IOS || state.resourceType()!=ResourceType.LIVE_PHOTO
                || state.deliveryMode()!=DeliveryMode.LIVE_PHOTO
                || (state.authorization()==Authorization.ENTITLEMENT&&!entitled(state.deviceId(),state.wallpaperId())))throw invalid();
        Integer credentials=jdbc.queryForObject("""
                SELECT COUNT(*) FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                WHERE d.id=? AND d.status='ACTIVE' AND d.platform='IOS'
                  AND c.credential_key_id=? AND c.status='ACTIVE'
                """,Integer.class,state.deviceId(),state.credentialKeyId());
        if(credentials==null||credentials!=1)throw invalid();
        List<LivePhotoRow> rows=jdbc.query(SELECT_LIVE_PHOTO+"""
                WHERE rv.id=? AND w.id=? AND w.status='PUBLISHED' AND rv.status='PUBLISHED'
                  AND v.enabled=TRUE AND v.platform='IOS' AND v.resource_type='LIVE_PHOTO' AND lp.status='READY'
                """,DownloadTicketService::livePhotoRow,state.versionId(),state.wallpaperId());
        if(rows.size()!=1)throw invalid();
        LivePhotoRow resource=rows.get(0);
        if(state.keyHash()==null || !state.keyHash().equals(livePhotoSourceFingerprint(resource)))throw invalid();
        return resource;
    }
    private String livePhotoSourceFingerprint(LivePhotoRow resource) {
        return crypto.hmacHex("live-photo-source-ticket-v1",resource.sourceStorage()+"\n"+resource.sourceSize()+"\n"+resource.sourceHash());
    }
    private void requireSourceIntegrity(LivePhotoRow resource) {
        try(var content=storage.open(new StorageKey(resource.sourceStorage()))) {
            if(content.sizeBytes()!=resource.sourceSize()) throw invalid();
            var digest=java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[32768];long remaining=resource.sourceSize();
            while(remaining>0){int count=content.inputStream().read(buffer,0,(int)Math.min(buffer.length,remaining));if(count<0)throw invalid();digest.update(buffer,0,count);remaining-=count;}
            if(content.inputStream().read()!=-1
                    || !java.util.HexFormat.of().formatHex(digest.digest()).equals(resource.sourceHash())) throw invalid();
        } catch(ApiException failure) {
            throw failure;
        } catch(Exception failure) {
            throw invalid();
        }
    }
    private boolean entitled(long device,long wallpaper) {
        return Integer.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement WHERE device_id=? AND wallpaper_id=? AND status='ACTIVE'",Integer.class,device,wallpaper));
    }
    private Access access(long wallpaperId) {
        var rows=jdbc.query("SELECT status,access_type FROM wallpaper WHERE id=?",
                (rs,n)->new Access(rs.getString("status"),WallpaperAccessType.valueOf(rs.getString("access_type"))),wallpaperId);
        if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","The wallpaper was not found");
        if(!rows.get(0).status().equals("PUBLISHED"))throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,"WALLPAPER_UNAVAILABLE","The wallpaper is not available");
        return rows.get(0);
    }
    static boolean compatibleOs(String actual,String minimum) {
        return com.qingjing.wallpaper.catalog.OsVersions.compatible(actual, minimum);
    }
    private String ticketKey(String token) { return "download-ticket-v2:"+crypto.hmacHex("download-ticket-v2",token); }
    public record Ticket(long deviceId,String credentialKeyId,long wallpaperId,long versionId,String keyHash,
            DevicePlatform platform,DeliveryPlatform resourcePlatform,ResourceType resourceType,
            DeliveryMode deliveryMode,Authorization authorization,String expiresAt) {}
    public enum MovingPhotoPart { POSTER, VIDEO }
    public enum LivePhotoPart { PHOTO, VIDEO }
    private enum Authorization { FREE, ENTITLEMENT }
    private record Access(String status,WallpaperAccessType type) {}
    @FunctionalInterface public interface Writer { void write(OutputStream output) throws IOException; }
    public record ProtectedFile(long sizeBytes,String sha256,Writer writer) {}
    public record StaticImageFile(long sizeBytes,String sha256,String mimeType,Writer writer) {}
    private static final String SELECT_PACKAGE="""
            SELECT r.id,r.version_no,v.id AS variant_id,v.platform,v.resource_type,v.minimum_os_version,
                   JSON_LENGTH(v.capability_requirements) AS requirement_count,p.*
            FROM secure_resource_package p JOIN resource_version r ON r.id=p.resource_version_id
            JOIN wallpaper_variant v ON v.id=r.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
            """;
    private static final String SELECT_MOVING_PHOTO="""
            SELECT rv.id,rv.version_no,rv.manifest_sha256,v.id AS variant_id,
                   mp.video_storage_key,mp.video_size_bytes,mp.video_sha256,
                   mp.poster_storage_key,mp.poster_size_bytes,mp.poster_sha256
            FROM moving_photo_package mp JOIN resource_version rv ON rv.id=mp.resource_version_id
            JOIN wallpaper_variant v ON v.id=rv.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
            """;
    private static final String SELECT_LIVE_PHOTO="""
            SELECT rv.id,rv.version_no,rv.manifest_sha256,v.id AS variant_id,
                   lp.photo_storage_key,lp.photo_size_bytes,lp.photo_sha256,
                   lp.video_storage_key,lp.video_size_bytes,lp.video_sha256,
                   source.storage_key AS source_storage_key,source.size_bytes AS source_size_bytes,
                   source.sha256 AS source_sha256
            FROM live_photo_package lp JOIN resource_version rv ON rv.id=lp.resource_version_id
            JOIN wallpaper_variant v ON v.id=rv.variant_id JOIN wallpaper w ON w.id=v.wallpaper_id
            JOIN resource_binding source_binding ON source_binding.resource_version_id=rv.id
              AND source_binding.role='LIVE_PHOTO_SOURCE' AND source_binding.ordinal=0
            JOIN asset source ON source.id=source_binding.asset_id
              AND source.purpose='LIVE_PHOTO_SOURCE' AND source.validation_status='READY'
              AND source.deleted_at IS NULL AND source.mime_type='video/mp4'
            """;
    private static final String SELECT_STATIC_IMAGE="""
            SELECT rv.id,rv.version_no,rv.manifest_sha256,v.id AS variant_id,
                   a.storage_key,a.mime_type,a.size_bytes,a.sha256
            FROM resource_version rv JOIN wallpaper_variant v ON v.id=rv.variant_id
            JOIN wallpaper w ON w.id=v.wallpaper_id
            JOIN resource_binding rb ON rb.resource_version_id=rv.id AND rb.role='STATIC_IMAGE' AND rb.ordinal=0
            JOIN asset a ON a.id=rb.asset_id
            """;
    private static PackageRow row(java.sql.ResultSet rs,int n) throws java.sql.SQLException {
        return new PackageRow(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("platform"),rs.getString("resource_type"),
                rs.getString("minimum_os_version"),rs.getInt("requirement_count"),rs.getString("storage_key"),rs.getLong("size_bytes"),rs.getLong("plaintext_size_bytes"),
                rs.getString("encrypted_sha256"),rs.getString("plaintext_sha256"),rs.getString("manifest_sha256"),rs.getString("signing_key_id"),rs.getString("content_key_ciphertext"));
    }
    private record PackageRow(long versionId,long variantId,int number,String platform,String type,String minimumOs,int requirementCount,String storageKey,long size,long plainSize,String encryptedHash,String plainHash,String manifestHash,String signingKeyId,String encryptedKey) {}
    private static MovingPhotoRow movingPhotoRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{
        return new MovingPhotoRow(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("manifest_sha256"),
                rs.getString("video_storage_key"),rs.getLong("video_size_bytes"),rs.getString("video_sha256"),
                rs.getString("poster_storage_key"),rs.getLong("poster_size_bytes"),rs.getString("poster_sha256"));
    }
    private record MovingPhotoRow(long versionId,long variantId,int number,String manifestHash,String videoStorage,long videoSize,
            String videoHash,String posterStorage,long posterSize,String posterHash) {}
    private static LivePhotoRow livePhotoRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{
        return new LivePhotoRow(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("manifest_sha256"),
                rs.getString("photo_storage_key"),rs.getLong("photo_size_bytes"),rs.getString("photo_sha256"),
                rs.getString("video_storage_key"),rs.getLong("video_size_bytes"),rs.getString("video_sha256"),
                rs.getString("source_storage_key"),rs.getLong("source_size_bytes"),rs.getString("source_sha256"));
    }
    private record LivePhotoRow(long versionId,long variantId,int number,String manifestHash,String photoStorage,long photoSize,
            String photoHash,String videoStorage,long videoSize,String videoHash,String sourceStorage,long sourceSize,
            String sourceHash) {}
    private static StaticImageRow staticImageRow(java.sql.ResultSet rs,int n)throws java.sql.SQLException{
        return new StaticImageRow(rs.getLong("id"),rs.getLong("variant_id"),rs.getInt("version_no"),rs.getString("manifest_sha256"),
                rs.getString("storage_key"),rs.getString("mime_type"),rs.getLong("size_bytes"),rs.getString("sha256"));
    }
    private record StaticImageRow(long versionId,long variantId,int number,String manifestHash,String storageKey,
            String mimeType,long size,String hash) {}
    private static ApiException unavailable() { return resourceUnavailable(); }
    private static ApiException resourceUnavailable(){return new ApiException(HttpStatus.NOT_FOUND,"RESOURCE_NOT_AVAILABLE","The requested resource is not available");}
    private static ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED,"DOWNLOAD_TICKET_INVALID","The download ticket is invalid or expired"); }
}
