package com.qingjing.wallpaper.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.*;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.*;
import com.qingjing.wallpaper.delivery.*;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.entitlement.*;
import com.qingjing.wallpaper.iosacquisition.*;
import com.qingjing.wallpaper.redemption.RedemptionService;
import com.qingjing.wallpaper.shared.security.*;
import com.qingjing.wallpaper.shared.web.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real live-policy SQL and resource entrances against an isolated migrated MySQL database. */
@Testcontainers(disabledWithoutDocker=true)
class WallpaperChannelIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wallpaper_channel_test").withUsername("channel_test")
            .withPassword(UUID.randomUUID().toString());
    @TempDir static Path temporary;
    static JdbcTemplate jdbc;
    static FileStorage storage;
    static SecurityCrypto crypto;
    static KeyPair key;
    static byte[] image;
    static DataSourceTransactionManager transactions;
    static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules();
    static final Map<Long,DevicePrincipal> devices=new HashMap<>();
    PublishedResourceCatalog resources;
    PublicCatalogService catalog;
    PublicAssetService assets;
    DownloadTicketService downloads;
    com.qingjing.wallpaper.risk.RiskService risk;
    PreviewTicketService previews;
    DeviceEntitlementService entitlements;

    @BeforeAll static void initialize() throws Exception {
        var datasource=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(datasource);transactions=new DataSourceTransactionManager(datasource);
        storage=new LocalFileStorage(temporary.resolve("private-fixture"));
        var security=new SecurityProperties();security.setMasterKey("56".repeat(32));crypto=new SecurityCrypto(security);
        var generator=KeyPairGenerator.getInstance("RSA");generator.initialize(2048);key=generator.generateKeyPair();
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",output);image=output.toByteArray();
        wallpaper(10,false,1);wallpaper(11,true,2);
        device(301,DeviceDtos.DevicePlatform.ANDROID,"com.qingjing.bizhi");
        device(302,DeviceDtos.DevicePlatform.ANDROID,WallpaperChannelAccess.OFFLINE_SCOPE);
        device(303,DeviceDtos.DevicePlatform.IOS,WallpaperChannelAccess.OFFLINE_SCOPE);
        jdbc.update("INSERT INTO device_entitlement(device_id,wallpaper_id,status,granted_at) VALUES(301,10,'ACTIVE',UTC_TIMESTAMP(6)),(301,11,'ACTIVE',UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO admin_account(id,singleton_key,username,password_hash,password_changed_at) VALUES(1,1,'test-admin','test-only-unused-password',UTC_TIMESTAMP(6))");
        jdbc.update("INSERT INTO code_batch(id,batch_no,name,generated_count,quota_per_code_snapshot,created_by_admin_id) VALUES(1,?,'channel fixture',1,3,1)","0".repeat(26));
        jdbc.update("INSERT INTO redemption_code(id,batch_id,code_hash,code_key_version,code_suffix,total_quota) VALUES(1,1,?,1,'AAAAA',3)",crypto.hmacHex("redemption-code-v1","A".repeat(20)));
    }

    @BeforeEach void setUp() {
        jdbc.update("DELETE FROM security_ban");
        risk=new com.qingjing.wallpaper.risk.RiskService(jdbc,mock(com.qingjing.wallpaper.risk.RiskCounter.class),new com.qingjing.wallpaper.risk.ClientAddress(""),transactions);
        jdbc.update("UPDATE wallpaper SET offline_promotion_only=(id=11),access_type='FREE'");
        jdbc.update("UPDATE category SET icon_asset_id=id");
        jdbc.update("UPDATE anonymous_device SET status='ACTIVE',app_install_scope=? WHERE id=302",WallpaperChannelAccess.OFFLINE_SCOPE);
        jdbc.update("UPDATE redemption_code SET used_quota=0 WHERE id=1");
        resources=new PublishedResourceCatalog(jdbc);
        var views=new PublicWallpaperViewReader(jdbc);
        catalog=new PublicCatalogService(jdbc,views,resources);assets=new PublicAssetService(jdbc,storage);
        entitlements=new DeviceEntitlementService(jdbc,views,resources);
        var properties=new DeviceProperties();properties.setAndroidEnabled(true);properties.setOfflineAndroidEnabled(true);
        properties.setAllowedAndroidScopes(List.of("com.qingjing.bizhi"));
        var keys=new InstallationEncryptionKeys(jdbc,new AndroidCredentialProof(MAPPER));
        var redis=mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String,String> values=mock(ValueOperations.class);
        var cached=new HashMap<String,String>();when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call->cached.get(call.getArgument(0)));
        doAnswer(call->{cached.put(call.getArgument(0),call.getArgument(1));return null;})
                .when(values).set(anyString(),anyString(),any(Duration.class));
        downloads=new DownloadTicketService(jdbc,views,mock(RedisRateLimiter.class),redis,crypto,keys,MAPPER,storage,properties,risk);
        var generation=new PreviewGenerationService(jdbc,storage,mock(com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer.class),
                mock(SecurePackagePublisher.class),mock(com.qingjing.wallpaper.parallax.ParallaxStorageCleanup.class),transactions);
        previews=new PreviewTicketService(jdbc,redis,crypto,mock(RedisRateLimiter.class),properties,keys,views,storage,MAPPER,generation,risk);
    }

    @Test void theDatabaseInstallationDefinesTheChannelAndEveryOtherPlatformIsOnline() {
        var access=new WallpaperChannelAccess(jdbc);
        assertThat(access.distributionChannel(devices.get(301L))).isEqualTo("ONLINE");
        assertThat(access.distributionChannel(devices.get(302L))).isEqualTo("OFFLINE");
        assertThat(access.distributionChannel(devices.get(303L))).isEqualTo("ONLINE");
        assertThat(access.isOffline(new DevicePrincipal(301,"forged",DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY))).isFalse();
        jdbc.update("UPDATE anonymous_device SET status='DISABLED' WHERE id=302");
        assertThat(access.isOffline(devices.get(302L))).isFalse();
    }

    @Test void listCountsCategoriesDetailsAndExistingEntitlementsFollowTheLiveFlag() {
        assertThat(resources.resolve(DeviceDtos.DevicePlatform.ANDROID).wallpaperIds()).containsExactly(10L);
        assertThat(resources.resolve(devices.get(301L)).wallpaperIds()).containsExactly(10L);
        assertThat(resources.resolve(devices.get(302L)).wallpaperIds()).containsExactlyInAnyOrder(10L,11L);
        assertThat(resources.resolve(devices.get(303L)).wallpaperIds()).containsExactly(10L);
        assertThat(catalog.categories(DeviceDtos.DevicePlatform.ANDROID,301).items()).hasSize(1);
        assertThat(catalog.categories(DeviceDtos.DevicePlatform.ANDROID,302).items()).hasSize(2);
        assertThat(catalog.wallpapers(DeviceDtos.DevicePlatform.ANDROID,1,20,null,null,null,null,null,null,null,PublicCatalogService.CatalogSort.DEFAULT,301).page().totalItems()).isEqualTo(1);
        assertThat(catalog.wallpaper(DeviceDtos.DevicePlatform.ANDROID,11,302).id()).isEqualTo("11");
        notFound(()->catalog.wallpaper(DeviceDtos.DevicePlatform.ANDROID,11,301));
        assertThat(entitlements.list(devices.get(301L),1,20).items()).hasSize(1);
        jdbc.update("UPDATE wallpaper SET offline_promotion_only=FALSE WHERE id=11");
        assertThat(entitlements.list(devices.get(301L),1,20).items()).hasSize(2);
        assertThat(resources.resolve(devices.get(301L)).wallpaperIds()).containsExactlyInAnyOrder(10L,11L);
    }

    @Test void anonymousAndOnlineImagesCannotReadOfflineCoversOrLegacyAliases() throws Exception {
        notFound(()->assets.previewCover(11));
        notFound(()->assets.previewCover(11,301));
        assertThat(assets.previewCover(11,302).sha256()).isEqualTo(SecurePackageCodec.sha256(image));
        assertThatThrownBy(()->assets.content(2)).isInstanceOfSatisfying(ApiException.class,error->assertThat(error.code()).isEqualTo("ASSET_NOT_FOUND"));
        assertThat(assets.content(2,302).sha256()).isEqualTo(SecurePackageCodec.sha256(image));
        var identity=mock(DeviceIdentityService.class);
        when(identity.requireSession("offline-session")).thenReturn(new DeviceIdentityService.SessionData(302,devices.get(302L).credentialKeyId(),
                DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY,Instant.now().plusSeconds(60)));
        var mvc=MockMvcBuilders.standaloneSetup(new PublicPreviewCoverController(assets,new PublicResourceIdentity(identity)))
                .setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/wallpapers/11/cover")).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/wallpapers/11/cover").header("Authorization","Bearer offline-session"))
                .andExpect(status().isOk()).andExpect(content().bytes(image));
        mvc.perform(get("/api/v1/wallpapers/10/cover")).andExpect(status().isOk()).andExpect(content().bytes(image));
    }

    @Test void aSharedOfflineIconUsesTheVisibleWallpaperPreviewInTheOnlineCategory() {
        jdbc.update("UPDATE category SET icon_asset_id=2 WHERE id=1");
        var onlineIcon=catalog.categories(DeviceDtos.DevicePlatform.ANDROID,301).items().get(0).icon();
        assertThat(onlineIcon.contentUrl()).isEqualTo("/api/v1/wallpapers/10/cover?revision=1");
        assertThat(onlineIcon.assetId()).isEqualTo("1");
        assertThat(assets.previewCover(10,301).sha256()).isEqualTo(SecurePackageCodec.sha256(image));
        assertThatThrownBy(()->assets.content(2,301)).isInstanceOfSatisfying(ApiException.class,
                error->assertThat(error.code()).isEqualTo("ASSET_NOT_FOUND"));
        var offlineIcon=catalog.categories(DeviceDtos.DevicePlatform.ANDROID,302).items().stream()
                .filter(category->category.id().equals("1")).findFirst().orElseThrow().icon();
        assertThat(offlineIcon.contentUrl()).isEqualTo("/api/v1/public/assets/2/content");
        assertThat(assets.content(2,302).sha256()).isEqualTo(SecurePackageCodec.sha256(image));
    }

    @Test void oldPreviewAndDownloadTicketsAndDeferredStreamsStopAfterTheFlagChanges() throws Exception {
        var request=new DeliveryDtos.CreateDownloadTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE,null);
        var previewRequest=new PreviewDtos.CreatePreviewTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE);
        notFound(()->downloads.create(devices.get(301L),11,request));
        notFound(()->previews.create(devices.get(301L),11,previewRequest));
        var formal=downloads.create(devices.get(301L),10,request);
        var preview=previews.create(devices.get(301L),10,previewRequest);
        var deferredFormal=downloads.readProtectedFile(formal.ticket());
        var deferredPreview=previews.read(preview.ticket());
        jdbc.update("UPDATE wallpaper SET offline_promotion_only=TRUE WHERE id=10");
        notFound(()->downloads.readProtectedFile(formal.ticket()));
        notFound(()->previews.read(preview.ticket()));
        notFound(()->deferredFormal.writer().write(new ByteArrayOutputStream()));
        notFound(()->deferredPreview.writer().write(new ByteArrayOutputStream()));
        var offlineFormal=downloads.create(devices.get(302L),10,request);
        var transferred=new ByteArrayOutputStream();downloads.readProtectedFile(offlineFormal.ticket()).writer().write(transferred);
        assertThat(SecurePackageCodec.sha256(transferred.toByteArray())).isEqualTo(offlineFormal.packageMetadata().encryptedSha256());
        jdbc.update("UPDATE anonymous_device SET app_install_scope='com.qingjing.bizhi' WHERE id=302");
        notFound(()->downloads.readProtectedFile(offlineFormal.ticket()));
    }

    @Test void permanentBanRevokesAlreadyIssuedDownloadAndPreviewGrantsIncludingDeferredStreams() throws Exception {
        var download=downloads.create(devices.get(301L),10,new DeliveryDtos.CreateDownloadTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE,null));
        var preview=previews.create(devices.get(301L),10,new PreviewDtos.CreatePreviewTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE));
        var pendingDownload=downloads.readProtectedFile(download.ticket());var pendingPreview=previews.read(preview.ticket());
        risk.manualBan(new com.qingjing.wallpaper.risk.RiskDtos.BanRequest("301","203.0.113.9","测试原有票据撤销"),1);
        for(Runnable operation:List.<Runnable>of(()->downloads.readProtectedFile(download.ticket()),()->previews.read(preview.ticket()))) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ACCESS_UNAVAILABLE"));
        }
        assertThatThrownBy(()->pendingDownload.writer().write(new ByteArrayOutputStream())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->pendingPreview.writer().write(new ByteArrayOutputStream())).isInstanceOf(ApiException.class);
    }

    @Test void deferredStreamsRetainTheIpWhenTheServletThreadHasAlreadyFinished() throws Exception {
        var request=new org.springframework.mock.web.MockHttpServletRequest();request.setRemoteAddr("203.0.113.9");
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(
            new org.springframework.web.context.request.ServletRequestAttributes(request));
        DownloadTicketService.ProtectedFile pendingDownload;
        DownloadTicketService.ProtectedFile pendingPreview;
        try {
            var download=downloads.create(devices.get(301L),10,new DeliveryDtos.CreateDownloadTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE,null));
            var preview=previews.create(devices.get(301L),10,new PreviewDtos.CreatePreviewTicketRequest(DeliveryPlatform.UNIVERSAL,ResourceType.STATIC_IMAGE));
            pendingDownload=downloads.readProtectedFile(download.ticket());pendingPreview=previews.read(preview.ticket());
        } finally { org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes(); }
        risk.manualBan(new com.qingjing.wallpaper.risk.RiskDtos.BanRequest(null,"203.0.113.9","仅封禁当前 IP"),1);
        assertThat(risk.blocked(301L,null)).isFalse();
        assertThatThrownBy(()->pendingDownload.writer().write(new ByteArrayOutputStream())).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->pendingPreview.writer().write(new ByteArrayOutputStream())).isInstanceOf(ApiException.class);
    }

    @Test void hiddenRedemptionDoesNotConsumeCodeQuotaAndSavedResultsAreRechecked() {
        jdbc.update("UPDATE wallpaper SET access_type='REDEEM' WHERE id=11");
        var redemptions=new RedemptionService(jdbc,crypto,mock(RedisRateLimiter.class),entitlements,resources,new EntitlementGrantService(jdbc));
        notFound(()->redemptions.redeem(301,UUID.randomUUID().toString(),11,"A".repeat(20)));
        assertThat(jdbc.queryForObject("SELECT used_quota FROM redemption_code WHERE id=1",Integer.class)).isZero();
        String request=UUID.randomUUID().toString();
        var result=redemptions.redeem(302,request,11,"A".repeat(20));
        assertThat(result.result().result().name()).isEqualTo("GRANTED");
        assertThat(jdbc.queryForObject("SELECT used_quota FROM redemption_code WHERE id=1",Integer.class)).isEqualTo(1);
        jdbc.update("UPDATE anonymous_device SET app_install_scope='com.qingjing.bizhi' WHERE id=302");
        notFound(()->redemptions.find(302,request));
        notFound(()->redemptions.redeem(302,request,11,"A".repeat(20)));
        assertThat(jdbc.queryForObject("SELECT used_quota FROM redemption_code WHERE id=1",Integer.class)).isEqualTo(1);
    }

    private static void notFound(org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class,error->{
            assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(error.code()).isEqualTo("WALLPAPER_NOT_FOUND");
        });
    }
    private static void device(long id,DeviceDtos.DevicePlatform platform,String scope) {
        String credential=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6))",id,UUID.randomUUID().toString(),platform.name(),scope,crypto.sha256Hex("device-"+id));
        String pem=new AndroidCredentialProof(MAPPER).canonicalPem((java.security.interfaces.RSAPublicKey)key.getPublic());
        jdbc.update("INSERT INTO device_credential(id,device_id,credential_key_id,credential_type,public_key_pem) VALUES(?,?,?,'PLATFORM_PUBLIC_KEY',?)",id,id,credential,pem);
        if(platform==DeviceDtos.DevicePlatform.ANDROID)jdbc.update("INSERT INTO device_encryption_key(credential_id,public_key_pem,public_key_sha256) VALUES(?,?,?)",id,pem,SecurePackageCodec.sha256(key.getPublic().getEncoded()));
        devices.put(id,new DevicePrincipal(id,credential,platform,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY));
    }
    private static void wallpaper(long id,boolean offline,long asset) {
        StoredObject cover=store(image,"png");
        jdbc.update("INSERT INTO asset(id,storage_key,original_filename,mime_type,file_extension,size_bytes,sha256,width_px,height_px,validation_status,purpose) VALUES(?,?,'fixture.png','image/png','png',?,?,8,8,'READY','STATIC_IMAGE')",asset,cover.storageKey().value(),cover.sizeBytes(),cover.sha256());
        jdbc.update("INSERT INTO category(id,level,name,slug,icon_asset_id) VALUES(?,1,?,?,?)",asset,"分类 "+asset,"channel-category-"+asset,asset);
        jdbc.update("INSERT INTO wallpaper(id,title,slug,category_id,cover_asset_id,copyright_note,status,published_at,access_type,offline_promotion_only) VALUES(?,?,?,?,?,'isolated fixture','PUBLISHED',UTC_TIMESTAMP(6),'FREE',?)",id,"壁纸 "+id,"channel-wallpaper-"+id,asset,asset,offline);
        jdbc.update("INSERT INTO wallpaper_variant(id,wallpaper_id,platform,resource_type,capability_requirements) VALUES(?,?,'UNIVERSAL','STATIC_IMAGE','[]')",id,id);
        jdbc.update("INSERT INTO resource_version(id,variant_id,version_no,status,manifest_sha256,published_at) VALUES(?,?,1,'PUBLISHED',?,UTC_TIMESTAMP(6))",id,id,"b".repeat(64));
        jdbc.update("INSERT INTO resource_binding(resource_version_id,asset_id,role,ordinal) VALUES(?,?,'STATIC_IMAGE',0)",id,asset);
        jdbc.update("INSERT INTO wallpaper_preview_state(wallpaper_id,generated_revision,status,cover_storage_key,cover_size_bytes,cover_sha256,cover_mime_type) VALUES(?,1,'READY',?,?,?,'image/png')",id,cover.storageKey().value(),cover.sizeBytes(),cover.sha256());
        var codec=new SecurePackageCodec(MAPPER);var identity=new SecurePackageCodec.Identity(id,id,1,"STATIC_IMAGE");
        var payloads=List.of(new SecurePackageCodec.Payload("STATIC_IMAGE",0,"image/png",image));
        for(boolean preview:List.of(false,true)) {
            var encoded=preview?codec.encodePreview(identity,payloads,"fixture-1",key.getPrivate()):codec.encode(identity,payloads,"fixture-1",key.getPrivate());
            StoredObject object=store(encoded.encrypted(),preview?"qjpv":"4dwp");
            String table=preview?"preview_resource_package":"secure_resource_package";
            jdbc.update("INSERT INTO "+table+"(resource_version_id,storage_key,size_bytes,plaintext_size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext) VALUES(?,?,?,?,?,?,?,?,?)",id,object.storageKey().value(),object.sizeBytes(),object.sizeBytes()-36,encoded.encryptedSha256(),encoded.plaintextSha256(),encoded.manifestSha256(),"fixture-1",crypto.encrypt((preview?"preview-package-key-v1:":"secure-package-key-v2:")+id,encoded.contentKey()));
            if(preview)jdbc.update("UPDATE preview_resource_package SET preview_revision=1 WHERE resource_version_id=?",id);
            Arrays.fill(encoded.contentKey(),(byte)0);
        }
    }
    private static StoredObject store(byte[] bytes,String extension) { return storage.commit(storage.stage(new ByteArrayInputStream(bytes),bytes.length),extension); }
}
