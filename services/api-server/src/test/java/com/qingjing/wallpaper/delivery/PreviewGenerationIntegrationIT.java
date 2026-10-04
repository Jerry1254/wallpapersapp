package com.qingjing.wallpaper.delivery;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.PublicAssetController;
import com.qingjing.wallpaper.asset.PublicAssetService;
import com.qingjing.wallpaper.asset.PublicPreviewCoverController;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.delivery.infrastructure.PackageMediaInspector;
import com.qingjing.wallpaper.delivery.infrastructure.PreviewWatermarkRenderer;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.parallax.ParallaxConfigEnvelopeValidator;
import com.qingjing.wallpaper.parallax.ParallaxStorageCleanup;
import com.qingjing.wallpaper.shared.security.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Isolated MySQL/Flyway tests: no production database, resource directory, or Apple service is used. */
@Testcontainers(disabledWithoutDocker = true)
class PreviewGenerationIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("preview_generation_test").withUsername("preview_test")
            .withPassword(UUID.randomUUID().toString());
    @TempDir static Path temporary;
    static JdbcTemplate jdbc;
    static FileStorage storage;
    static DataSourceTransactionManager manager;
    static List<Long> migratedWallpaperIds;
    static String migratedVersion;
    static byte[] originalImage, markedImage;
    PreviewWatermarkRenderer renderer;
    SecurePackagePublisher packages;
    PreviewGenerationService generation;
    PublicAssetService publicAssets;

    @BeforeAll static void initialize() throws Exception {
        var datasource = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(datasource);
        manager = new DataSourceTransactionManager(datasource);
        storage = new LocalFileStorage(temporary.resolve("isolated-storage"));
        originalImage = png(new Color(24, 32, 48));
        markedImage = png(new Color(64, 72, 88));
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").target("20").load().migrate();
        StoredObject icon = store(originalImage, "png");
        insertAsset(1, icon, "WALLPAPER_COVER");
        jdbc.update("INSERT INTO category(id,level,name,slug,icon_asset_id) VALUES(1,1,'测试分类','preview-test',1)");
        insertWallpaper(10, 1, "PUBLISHED", "REDEEM", null, false);
        insertWallpaper(11, 1, "DRAFT", "FREE", null, false);
        insertWallpaper(12, 1, "ARCHIVED", "REDEEM", null, false);
        var flyway = Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load();
        flyway.migrate();
        migratedWallpaperIds = jdbc.queryForList("SELECT wallpaper_id FROM wallpaper_preview_state ORDER BY wallpaper_id", Long.class);
        migratedVersion = flyway.info().current().getVersion().toString();
    }

    @BeforeEach void setUp() {
        for (String table : List.of("preview_media", "preview_resource_package", "secure_resource_package",
                "live_photo_package", "moving_photo_package", "resource_binding", "resource_version", "wallpaper_variant",
                "wallpaper_preview_state", "wallpaper", "device_credential", "anonymous_device")) jdbc.update("DELETE FROM " + table);
        jdbc.update("DELETE FROM asset WHERE id<>1");
        renderer = mock(PreviewWatermarkRenderer.class);
        when(renderer.image(any(byte[].class))).thenReturn(markedImage);
        when(renderer.video(any(byte[].class), anyBoolean())).thenAnswer(call -> new byte[] {8, 9, 10, 11});
        packages = mock(SecurePackagePublisher.class);
        var cleanup = new ParallaxStorageCleanup(storage, jdbc, manager);
        generation = new PreviewGenerationService(jdbc, storage, renderer, packages, cleanup, manager);
        publicAssets = new PublicAssetService(jdbc, storage);
    }

    @Test void migrationAndBulkPlanQueueAllNonArchivedWallpapers() {
        assertThat(migratedVersion).isEqualTo("22");
        assertThat(migratedWallpaperIds).containsExactly(10L, 11L);
        createWallpaper(101, "PUBLISHED", "REDEEM", null);
        createWallpaper(102, "DRAFT", "FREE", null);
        createWallpaper(103, "OFFLINE", "REDEEM", false);
        createWallpaper(104, "ARCHIVED", "REDEEM", null);
        addVersion(201, 101, "ANDROID", "VIDEO", "PUBLISHED");
        addVersion(202, 102, "UNIVERSAL", "STATIC_IMAGE", "READY");
        addVersion(203, 103, "IOS", "LIVE_PHOTO", "DRAFT");

        var plan = generation.enqueueAll();
        assertThat(plan.wallpaperCount()).isEqualTo(3);
        assertThat(plan.watermarkedWallpaperCount()).isEqualTo(1);
        assertThat(plan.cleanWallpaperCount()).isEqualTo(2);
        assertThat(plan.resourceVersionCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT wallpaper_id FROM wallpaper_preview_state ORDER BY wallpaper_id", Long.class))
                .containsExactly(101L, 102L, 103L);
        generation.enqueueAll();
        assertThat(jdbc.queryForList("SELECT requested_revision FROM wallpaper_preview_state", Long.class))
                .containsOnly(2L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement", Integer.class)).isZero();
    }

    @Test void paidDefaultsToWatermarkWhileFreeAndOptedOutPreviewsKeepCleanSources() throws Exception {
        var paid = createWallpaper(101, "PUBLISHED", "REDEEM", null);
        var free = createWallpaper(102, "PUBLISHED", "FREE", null);
        var optedOut = createWallpaper(103, "PUBLISHED", "REDEEM", false);
        generation.enqueueAll();
        drain();

        assertThat(jdbc.queryForObject("SELECT preview_watermark_enabled FROM wallpaper WHERE id=101", Boolean.class)).isTrue();
        assertThat(generation.requireReady(101)).isEqualTo(1);
        assertThat(bytes(publicAssets.previewCover(101).storageKey())).isEqualTo(markedImage);
        assertThat(publicAssets.previewCover(101).storageKey()).isNotEqualTo(paid.object().storageKey());
        assertThat(publicAssets.previewCover(102).storageKey()).isEqualTo(free.object().storageKey());
        assertThat(publicAssets.previewCover(103).storageKey()).isEqualTo(optedOut.object().storageKey());
        assertThat(publicAssets.content(paid.assetId()).sha256()).isEqualTo(publicAssets.previewCover(101).sha256());
        assertThat(publicAssets.content(free.assetId()).storageKey()).isEqualTo(free.object().storageKey());
        assertThat(bytes(paid.object().storageKey())).isEqualTo(originalImage);
        assertThat(bytes(free.object().storageKey())).isEqualTo(originalImage);
        verify(renderer, times(1)).image(any(byte[].class));
        verifyNoInteractions(packages);
    }

    @Test void policyChangesAdvancePreviewRevisionAndLegacyUrlsCannotBypassPendingOrPaidPolicy() throws Exception {
        var fixture = createWallpaper(101, "PUBLISHED", "FREE", null);
        generation.enqueue(101); drain();
        assertThat(publicAssets.content(fixture.assetId()).storageKey()).isEqualTo(fixture.object().storageKey());

        policy(101, "REDEEM", true);
        assertApi("PREVIEW_PROCESSING", () -> publicAssets.previewCover(101));
        assertApi("PREVIEW_PROCESSING", () -> publicAssets.content(fixture.assetId()));
        drain();
        var marked = publicAssets.previewCover(101);
        assertThat(generation.requireReady(101)).isEqualTo(2);
        assertThat(marked.sha256()).isNotEqualTo(fixture.object().sha256());

        var mvc = MockMvcBuilders.standaloneSetup(new PublicAssetController(publicAssets),
                new PublicPreviewCoverController(publicAssets)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/public/assets/" + fixture.assetId() + "/content"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(markedImage));
        mvc.perform(get("/api/v1/wallpapers/101/cover?revision=1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().bytes(markedImage));

        policy(101, "REDEEM", false); drain();
        assertThat(generation.requireReady(101)).isEqualTo(3);
        assertThat(publicAssets.previewCover(101).storageKey()).isEqualTo(fixture.object().storageKey());
        assertThat(bytes(fixture.object().storageKey())).isEqualTo(originalImage);
        assertThatThrownBy(() -> storage.open(marked.storageKey())).isInstanceOf(FileStorageException.class);
    }

    @Test void failedGenerationNeverReturnsSourceAndAdminRetryCanRecover() throws Exception {
        var fixture = createWallpaper(101, "PUBLISHED", "REDEEM", null);
        doThrow(new IllegalStateException("test renderer failure")).when(renderer).image(any(byte[].class));
        generation.enqueue(101);
        assertThat(generation.processNext()).isTrue();
        assertThat(state(101)).isEqualTo("FAILED");
        assertApi("PREVIEW_GENERATION_FAILED", () -> generation.requireReady(101));
        assertApi("PREVIEW_GENERATION_FAILED", () -> publicAssets.previewCover(101));
        assertApi("PREVIEW_GENERATION_FAILED", () -> publicAssets.content(fixture.assetId()));
        assertThat(bytes(fixture.object().storageKey())).isEqualTo(originalImage);
        doReturn(markedImage).when(renderer).image(any(byte[].class));
        generation.retry(101); drain();
        assertThat(generation.requireReady(101)).isEqualTo(2);
        assertThat(bytes(publicAssets.previewCover(101).storageKey())).isEqualTo(markedImage);
    }

    @Test void aPolicyChangeDuringEncodingPreventsOldTaskFromPublishingReady() throws Exception {
        var fixture = createWallpaper(101, "PUBLISHED", "REDEEM", null);
        generation.enqueue(101);
        when(renderer.image(any(byte[].class))).thenAnswer(call -> {
            policy(101, "REDEEM", false);
            return markedImage;
        });
        assertThat(generation.processNext()).isTrue();
        assertThat(state(101)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT requested_revision FROM wallpaper_preview_state WHERE wallpaper_id=101", Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT generated_revision FROM wallpaper_preview_state WHERE wallpaper_id=101", Long.class)).isZero();
        assertApi("PREVIEW_PROCESSING", () -> publicAssets.previewCover(101));
        drain();
        assertThat(generation.requireReady(101)).isEqualTo(2);
        assertThat(publicAssets.previewCover(101).storageKey()).isEqualTo(fixture.object().storageKey());
        assertThat(bytes(fixture.object().storageKey())).isEqualTo(originalImage);
    }

    @Test void actualStaticPackageRebuildChangesOnlyPreviewAndPreservesFormalPackageAndResourceVersion() throws Exception {
        var fixture = createWallpaper(101, "PUBLISHED", "REDEEM", null);
        addVersion(201, 101, "UNIVERSAL", "STATIC_IMAGE", "READY");
        jdbc.update("INSERT INTO resource_binding(resource_version_id,asset_id,role) VALUES(201,?,'STATIC_IMAGE')", fixture.assetId());
        var signingPair = KeyPairGenerator.getInstance("RSA"); signingPair.initialize(2048);
        var signing = new PackageSigningKeys("preview-test", Base64.getEncoder().encodeToString(signingPair.generateKeyPair().getPrivate().getEncoded()));
        var mapper = new ObjectMapper();
        var inspector = mock(PackageMediaInspector.class);
        when(inspector.inspect(any(byte[].class), eq(false))).thenReturn(new PackageMediaInspector.Media(8, 8, true));
        var realPublisher = new SecurePackagePublisher(jdbc, storage, new AssetContentValidator(storage, mapper), inspector,
                signing, crypto(), mapper, new ParallaxConfigEnvelopeValidator(mapper),
                new ParallaxStorageCleanup(storage, jdbc, manager), renderer);
        var tx = new TransactionTemplate(manager);
        tx.executeWithoutResult(status -> realPublisher.prepareForPublication(201));
        var originalPackage = jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=201");
        var originalVersion = jdbc.queryForMap("SELECT * FROM resource_version WHERE id=201");
        var formalBytes = bytes(new StorageKey((String) originalPackage.get("storage_key")));
        generation.enqueue(101);
        tx.executeWithoutResult(status -> realPublisher.rebuildPreview(201, true, 1));
        var preview = jdbc.queryForMap("SELECT * FROM preview_resource_package WHERE resource_version_id=201");
        assertThat(preview.get("manifest_sha256")).isNotEqualTo(originalPackage.get("manifest_sha256"));
        assertThat(((Number) preview.get("preview_revision")).longValue()).isEqualTo(1);
        assertThat(jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=201")).isEqualTo(originalPackage);
        assertThat(jdbc.queryForMap("SELECT * FROM resource_version WHERE id=201")).isEqualTo(originalVersion);
        assertThat(bytes(new StorageKey((String) originalPackage.get("storage_key")))).isEqualTo(formalBytes);
        assertThat(bytes(fixture.object().storageKey())).isEqualTo(originalImage);
    }

    @Test void iosPreviewTicketBindsCurrentDerivedMediaAndRejectsPreviousRevisionAfterPolicyChange() throws Exception {
        createWallpaper(101, "PUBLISHED", "REDEEM", null);
        addVersion(201, 101, "IOS", "LIVE_PHOTO", "PUBLISHED");
        var originalVideo = store(new byte[] {1, 2, 3, 4}, "mov");
        var photo = store(originalImage, "png");
        jdbc.update("""
            INSERT INTO live_photo_package(resource_version_id,status,photo_storage_key,photo_size_bytes,photo_sha256,
              video_storage_key,video_size_bytes,video_sha256,asset_identifier,duration_ms,width_px,height_px,
              input_video_codec,output_video_codec,frame_rate,processing_mode)
            VALUES(201,'READY',?,?,?,?,?,?,?,1000,8,8,'h264','h264',30,'TRANSCODE')
            """, photo.storageKey().value(), photo.sizeBytes(), photo.sha256(), originalVideo.storageKey().value(),
                originalVideo.sizeBytes(), originalVideo.sha256(), UUID.randomUUID().toString().toUpperCase(Locale.ROOT));
        generation.enqueue(101); drain();
        jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(301,?,'IOS','preview-test',?,UTC_TIMESTAMP(6))",
                UUID.randomUUID().toString(), "a".repeat(64));
        String credential = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO device_credential(id,device_id,credential_key_id,credential_type,public_key_pem) VALUES(401,301,?,'PLATFORM_PUBLIC_KEY','test-only-public-key')", credential);
        var properties = new DeviceProperties(); properties.setIosEnabled(true); properties.setAllowedIosScopes(List.of("preview-test"));
        var redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked") ValueOperations<String, String> values = mock(ValueOperations.class);
        var redisValues = new HashMap<String, String>();
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(anyString())).thenAnswer(call -> redisValues.get(call.getArgument(0)));
        doAnswer(call -> { redisValues.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(values).set(anyString(), anyString(), any(Duration.class));
        var tickets = new PreviewTicketService(jdbc, redis, crypto(), mock(RedisRateLimiter.class), properties,
                mock(InstallationEncryptionKeys.class), mock(PublicWallpaperViewReader.class), storage,
                new ObjectMapper().findAndRegisterModules(), generation);
        var principal = new DevicePrincipal(301, credential, DeviceDtos.DevicePlatform.IOS, DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
        var descriptor = tickets.create(principal, 101, new PreviewDtos.CreatePreviewTicketRequest(DeliveryPlatform.IOS, ResourceType.LIVE_PHOTO));
        assertThat(descriptor.previewRevision()).isEqualTo(1);
        assertThat(descriptor.video().sha256()).isNotEqualTo(originalVideo.sha256());
        var body = new ByteArrayOutputStream();
        tickets.readLivePhotoVideo(descriptor.ticket()).writer().write(body);
        assertThat(body.toByteArray()).isEqualTo(new byte[] {8, 9, 10, 11});

        policy(101, "REDEEM", false);
        assertApi("PREVIEW_PROCESSING", () -> tickets.readLivePhotoVideo(descriptor.ticket()));
        drain();
        assertApi("PREVIEW_TICKET_INVALID", () -> tickets.readLivePhotoVideo(descriptor.ticket()));
        var next = tickets.create(principal, 101, new PreviewDtos.CreatePreviewTicketRequest(DeliveryPlatform.IOS, ResourceType.LIVE_PHOTO));
        assertThat(next.previewRevision()).isEqualTo(2);
        assertThat(next.video().sha256()).isEqualTo(originalVideo.sha256());
        assertThat(bytes(originalVideo.storageKey())).isEqualTo(new byte[] {1, 2, 3, 4});
        assertThat(jdbc.queryForObject("SELECT video_sha256 FROM live_photo_package WHERE resource_version_id=201", String.class)).isEqualTo(originalVideo.sha256());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement", Integer.class)).isZero();
    }

    private SourceFixture createWallpaper(long id, String status, String access, Boolean marked) {
        StoredObject object = store(originalImage, "png");
        long assetId = id + 1000;
        insertAsset(assetId, object, "WALLPAPER_COVER");
        insertWallpaper(id, assetId, status, access, marked, true);
        return new SourceFixture(assetId, object);
    }
    private static void insertAsset(long id, StoredObject object, String purpose) {
        jdbc.update("INSERT INTO asset(id,storage_key,original_filename,mime_type,file_extension,size_bytes,sha256,validation_status,purpose) VALUES(?,?,'preview-test.png','image/png','png',?,?,'READY',?)",
                id, object.storageKey().value(), object.sizeBytes(), object.sha256(), purpose);
    }
    private static void insertWallpaper(long id, long asset, String status, String access, Boolean marked, boolean hasPolicy) {
        jdbc.update("INSERT INTO wallpaper(id,title,slug,category_id,cover_asset_id,copyright_note,status,published_at,archived_at,access_type) VALUES(?,?,?,1,?,'test fixture',?,IF(?='DRAFT',NULL,UTC_TIMESTAMP(6)),IF(?='ARCHIVED',UTC_TIMESTAMP(6),NULL),?)",
                id, "测试壁纸 " + id, "preview-test-" + id, asset, status, status, status, access);
        if (hasPolicy && marked != null) jdbc.update("UPDATE wallpaper SET preview_watermark_enabled=? WHERE id=?", marked, id);
    }
    private void addVersion(long id, long wallpaperId, String platform, String type, String status) {
        jdbc.update("INSERT INTO wallpaper_variant(id,wallpaper_id,platform,resource_type,capability_requirements) VALUES(?,?,?,?,'[]')", id, wallpaperId, platform, type);
        jdbc.update("INSERT INTO resource_version(id,variant_id,version_no,status,manifest_sha256,published_at) VALUES(?,?,1,?,?,IF(?='PUBLISHED',UTC_TIMESTAMP(6),NULL))", id, id, status, "b".repeat(64), status);
    }
    private void policy(long id, String access, boolean marked) {
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            jdbc.update("UPDATE wallpaper SET access_type=?,preview_watermark_enabled=? WHERE id=?", access, marked, id);
            generation.enqueue(id);
        });
    }
    private void drain() {
        int processed = 0;
        while (generation.processNext()) assertThat(++processed).isLessThan(20);
    }
    private String state(long id) { return jdbc.queryForObject("SELECT status FROM wallpaper_preview_state WHERE wallpaper_id=?", String.class, id); }
    private static void assertApi(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable work) {
        assertThatThrownBy(work).isInstanceOfSatisfying(ApiException.class, error -> assertThat(error.code()).isEqualTo(code));
    }
    private static byte[] png(Color color) throws Exception {
        var image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, 8, 8); graphics.dispose();
        var output = new ByteArrayOutputStream(); ImageIO.write(image, "png", output); return output.toByteArray();
    }
    private static StoredObject store(byte[] bytes, String extension) {
        return storage.commit(storage.stage(new ByteArrayInputStream(bytes), bytes.length), extension);
    }
    private static byte[] bytes(StorageKey key) throws Exception {
        try (var content = storage.open(key)) { return content.inputStream().readAllBytes(); }
    }
    private static SecurityCrypto crypto() {
        var properties = new SecurityProperties(); byte[] key = new byte[32]; new SecureRandom().nextBytes(key);
        properties.setMasterKey(HexFormat.of().formatHex(key)); return new SecurityCrypto(properties);
    }
    private record SourceFixture(long assetId, StoredObject object) { }
}
