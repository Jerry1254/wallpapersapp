package com.qingjing.wallpaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.imageio.ImageIO;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureIntegrationIT {

    private static final java.security.KeyPair PACKAGE_SIGNER = resourceSigningKey();
    private static java.security.KeyPair resourceSigningKey() {
        try { var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair(); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final String MYSQL_PASSWORD = UUID.randomUUID().toString();
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wallpaper_app")
            .withUsername("wallpaper_test")
            .withPassword(MYSQL_PASSWORD)
            .withCommand("--log-bin-trust-function-creators=1")
            .withStartupTimeout(Duration.ofMinutes(5))
            .withStartupTimeoutSeconds(300);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--requirepass", REDIS_PASSWORD)
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("qingjing.delivery.signing-key-id", () -> "integration-resource-1");
        registry.add("qingjing.delivery.signing-private-key", () -> Base64.getEncoder().encodeToString(PACKAGE_SIGNER.getPrivate().getEncoded()));
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    @Autowired
    TestRestTemplate http;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    SecurityCrypto securityCrypto;

    @Test
    void emptyDatabaseMigratesToDomainAndSecureDeliveryTables() {
        Integer successfulMigrations = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        List<String> tables = jdbc.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name <> 'flyway_schema_history'
                ORDER BY table_name
                """,
                String.class);

        assertThat(successfulMigrations).isEqualTo(7);
        assertThat(tables).containsExactlyInAnyOrder(
                "admin_account",
                "anonymous_device",
                "asset",
                "audit_event",
                "category",
                "code_batch",
                "device_credential",
                "device_encryption_key",
                "secure_resource_package",
                "preview_resource_package",
                "parallax_source_package",
                "parallax_source_layer",
                "parallax_storage_cleanup",
                "device_entitlement",
                "download_event",
                "redemption_code",
                "redemption_event",
                "redemption_request",
                "resource_binding",
                "resource_version",
                "wallpaper",
                "wallpaper_setting_tutorial",
                "wallpaper_variant");
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='parallax_source_layer'
                ORDER BY ordinal_position
                """,String.class)).containsExactly("source_package_id","layer_index","asset_id","original_filename","role","ordinal");
        assertThat(jdbc.queryForObject("""
                SELECT column_default FROM information_schema.columns
                WHERE table_schema=DATABASE() AND table_name='parallax_source_package' AND column_name='format_version'
                """,String.class)).isEqualTo("2");
    }

    @Test
    void coreUniqueConstraintsRejectDuplicateBusinessFacts() {
        jdbc.update("""
                INSERT INTO admin_account
                    (singleton_key, username, password_hash, password_changed_at)
                VALUES (1, 'integration-admin', REPEAT('a', 60), UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE username = VALUES(username)
                """);
        Long adminId = jdbc.queryForObject(
                "SELECT id FROM admin_account WHERE singleton_key = 1", Long.class);

        jdbc.update("""
                INSERT INTO asset
                    (storage_key, original_filename, mime_type, file_extension, size_bytes, sha256,
                     width_px, height_px, validation_status, created_by_admin_id)
                VALUES ('integration/cover.webp', 'cover.webp', 'image/webp', 'webp', 100,
                        REPEAT('a', 64), 1080, 1920, 'READY', ?)
                ON DUPLICATE KEY UPDATE storage_key = VALUES(storage_key)
                """, adminId);
        Long assetId = jdbc.queryForObject(
                "SELECT id FROM asset WHERE storage_key = 'integration/cover.webp'", Long.class);

        jdbc.update("""
                INSERT INTO category
                    (parent_id, level, name, slug, icon_asset_id, sort_order)
                VALUES (NULL, 1, '集成测试分类', 'integration-category', ?, 0)
                ON DUPLICATE KEY UPDATE slug = VALUES(slug)
                """, assetId);
        Long categoryId = jdbc.queryForObject(
                "SELECT id FROM category WHERE slug = 'integration-category'", Long.class);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO category
                    (parent_id, level, name, slug, icon_asset_id, sort_order)
                VALUES (NULL, 1, '集成测试分类', 'integration-category-duplicate', ?, 1)
                """, assetId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                INSERT INTO wallpaper
                    (title, slug, kind, category_id, cover_asset_id, sort_order, copyright_note, status)
                VALUES ('集成测试壁纸', 'integration-wallpaper', 'STATIC', ?, ?, 0, '集成测试素材', 'DRAFT')
                ON DUPLICATE KEY UPDATE slug = VALUES(slug)
                """, categoryId, assetId);
        Long wallpaperId = jdbc.queryForObject(
                "SELECT id FROM wallpaper WHERE slug = 'integration-wallpaper'", Long.class);

        jdbc.update("""
                INSERT INTO wallpaper_variant
                    (wallpaper_id, platform, resource_type, minimum_os_version, capability_requirements)
                VALUES (?, 'UNIVERSAL', 'STATIC_IMAGE', NULL, JSON_ARRAY())
                ON DUPLICATE KEY UPDATE wallpaper_id = VALUES(wallpaper_id)
                """, wallpaperId);
        Long variantId = jdbc.queryForObject(
                """
                SELECT id FROM wallpaper_variant
                WHERE wallpaper_id = ? AND platform = 'UNIVERSAL' AND resource_type = 'STATIC_IMAGE'
                """, Long.class, wallpaperId);

        jdbc.update("""
                INSERT INTO resource_version
                    (variant_id, version_no, status, manifest_sha256, published_at, created_by_admin_id)
                VALUES (?, 1, 'PUBLISHED', REPEAT('b', 64), UTC_TIMESTAMP(6), ?)
                ON DUPLICATE KEY UPDATE version_no = VALUES(version_no)
                """, variantId, adminId);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO resource_version
                    (variant_id, version_no, status, manifest_sha256, published_at, created_by_admin_id)
                VALUES (?, 2, 'PUBLISHED', REPEAT('c', 64), UTC_TIMESTAMP(6), ?)
                """, variantId, adminId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                INSERT INTO anonymous_device
                    (public_id, platform, app_install_scope, evidence_hash, status, last_seen_at)
                VALUES ('00000000-0000-4000-8000-000000000001', 'H5_TEST', 'integration',
                        REPEAT('d', 64), 'ACTIVE', UTC_TIMESTAMP(6))
                """);
        Long deviceId = jdbc.queryForObject(
                "SELECT id FROM anonymous_device WHERE public_id = '00000000-0000-4000-8000-000000000001'",
                Long.class);

        jdbc.update("""
                INSERT INTO code_batch
                    (batch_no, name, generated_count, quota_per_code_snapshot, created_by_admin_id)
                VALUES (REPEAT('B', 26), '集成测试批次', 1, 3, ?)
                """, adminId);
        Long batchId = jdbc.queryForObject(
                "SELECT id FROM code_batch WHERE batch_no = REPEAT('B', 26)", Long.class);

        jdbc.update("""
                INSERT INTO redemption_code
                    (batch_id, code_hash, code_key_version, code_suffix, total_quota, used_quota)
                VALUES (?, REPEAT('e', 64), 1, 'A7K9X', 3, 1)
                """, batchId);
        Long codeId = jdbc.queryForObject(
                "SELECT id FROM redemption_code WHERE code_hash = REPEAT('e', 64)", Long.class);

        jdbc.update("""
                INSERT INTO device_entitlement
                    (device_id, wallpaper_id, source_code_id, status, granted_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, deviceId, wallpaperId, codeId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO device_entitlement
                    (device_id, wallpaper_id, source_code_id, status, granted_at)
                VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                """, deviceId, wallpaperId, codeId))
                .isInstanceOf(DataIntegrityViolationException.class);

        jdbc.update("""
                INSERT INTO redemption_request
                    (device_id, idempotency_key, request_hash, status, completed_at)
                VALUES (?, '00000000-0000-4000-8000-000000000002', REPEAT('f', 64),
                        'SUCCEEDED', UTC_TIMESTAMP(6))
                """, deviceId);
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO redemption_request
                    (device_id, idempotency_key, request_hash, status, completed_at)
                VALUES (?, '00000000-0000-4000-8000-000000000002', REPEAT('1', 64),
                        'REJECTED', UTC_TIMESTAMP(6))
                """, deviceId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void mysqlRedisAndReadinessAreAvailable() {
        redis.opsForValue().set("integration:health", "ok", Duration.ofMinutes(1));
        assertThat(redis.opsForValue().get("integration:health")).isEqualTo("ok");
        assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);

        var response = http.getForEntity("/actuator/health/readiness", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody()).contains("\"status\":\"UP\"");
    }

    @Test
    void adminSessionRequiresCookieAndSessionBoundCsrf() throws Exception {
        ensureAdmin();

        ResponseEntity<JsonNode> anonymous = http.getForEntity("/api/v1/admin/dashboard", JsonNode.class);
        assertThat(anonymous.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(anonymous.getBody().path("error").path("code").asText()).isEqualTo("UNAUTHORIZED");

        ResponseEntity<JsonNode> wrongLogin = http.postForEntity(
                "/api/v1/admin/sessions",
                Map.of("username", "api-admin", "password", "wrong-password-value"),
                JsonNode.class);
        assertThat(wrongLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        AdminTestSession session = login();
        ResponseEntity<JsonNode> dashboard = http.exchange(
                "/api/v1/admin/dashboard",
                HttpMethod.GET,
                new HttpEntity<>(headers(session, false, null)),
                JsonNode.class);
        assertThat(dashboard.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(dashboard.getBody().path("publishedWallpaperCount").isIntegralNumber()).isTrue();

        ResponseEntity<JsonNode> missingCsrf = http.exchange(
                "/api/v1/admin/categories",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(), headers(session, false, null)),
                JsonNode.class);
        assertThat(missingCsrf.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(missingCsrf.getBody().path("error").path("code").asText()).isEqualTo("CSRF_INVALID");

        ResponseEntity<Void> logout = http.exchange(
                "/api/v1/admin/sessions",
                HttpMethod.DELETE,
                new HttpEntity<>(headers(session, true, null)),
                Void.class);
        assertThat(logout.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        ResponseEntity<JsonNode> afterLogout = http.exchange(
                "/api/v1/admin/sessions",
                HttpMethod.GET,
                new HttpEntity<>(headers(session, false, null)),
                JsonNode.class);
        assertThat(afterLogout.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Long loginAudits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_event WHERE action = 'POST_SESSIONS'",
                Long.class);
        assertThat(loginAudits).isGreaterThanOrEqualTo(2);
    }

    @Test
    void adminContentFlowPublishesNewImmutableResourceVersions() throws Exception {
        ensureAdmin();
        AdminTestSession session = login();
        byte[] image = png(4, 3);

        JsonNode icon = uploadAsset(session, "CATEGORY_ICON", "icon.png", image);
        JsonNode cover = uploadAsset(session, "WALLPAPER_COVER", "cover.png", image);
        JsonNode staticAsset = uploadAsset(session, "STATIC_IMAGE", "wallpaper.png", image);
        assertThat(icon.path("sha256").asText()).hasSize(64);
        assertThat(icon.has("storageKey")).isFalse();

        ResponseEntity<byte[]> content = http.exchange(
                icon.path("previewUrl").asText(),
                HttpMethod.GET,
                new HttpEntity<>(headers(session, false, null)),
                byte[].class);
        assertThat(content.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(content.getBody()).isEqualTo(image);

        Map<String, Object> categoryRequest = new LinkedHashMap<>();
        categoryRequest.put("name", "API 集成分类");
        categoryRequest.put("slug", "api-integration-category");
        categoryRequest.put("iconAssetId", icon.path("id").asText());
        categoryRequest.put("sortOrder", 10);
        ResponseEntity<JsonNode> category = jsonExchange(
                "/api/v1/admin/categories",
                HttpMethod.POST,
                categoryRequest,
                session,
                null);
        assertThat(category.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        Map<String, Object> childCategoryRequest = new LinkedHashMap<>();
        childCategoryRequest.put("parentId", category.getBody().path("id").asText());
        childCategoryRequest.put("name", "API 集成子分类");
        childCategoryRequest.put("slug", "api-integration-child-category");
        childCategoryRequest.put("sortOrder", 11);
        ResponseEntity<JsonNode> childCategory = jsonExchange(
                "/api/v1/admin/categories",
                HttpMethod.POST,
                childCategoryRequest,
                session,
                null);
        assertThat(childCategory.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(childCategory.getBody().path("level").asInt()).isEqualTo(2);

        Map<String, Object> wallpaperRequest = new LinkedHashMap<>();
        wallpaperRequest.put("title", "API 集成静态壁纸");
        wallpaperRequest.put("slug", "api-integration-static-wallpaper");
        wallpaperRequest.put("kind", "STATIC");
        wallpaperRequest.put("rootCategoryId", category.getBody().path("id").asText());
        wallpaperRequest.put("childCategoryId", childCategory.getBody().path("id").asText());
        wallpaperRequest.put("coverAssetId", cover.path("id").asText());
        wallpaperRequest.put("sortOrder", 20);
        wallpaperRequest.put("featuredRank", 2);
        wallpaperRequest.put("copyrightNote", "API integration test asset");
        ResponseEntity<JsonNode> wallpaper = jsonExchange(
                "/api/v1/admin/wallpapers",
                HttpMethod.POST,
                wallpaperRequest,
                session,
                null);
        assertThat(wallpaper.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String wallpaperId = wallpaper.getBody().path("id").asText();
        String initialEtag = wallpaper.getHeaders().getETag();
        assertThat(http.getForEntity("/api/v1/public/wallpapers/" + wallpaperId, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/public/assets/" + cover.path("id").asText() + "/content", byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForEntity("/api/v1/public/assets/" + icon.path("id").asText() + "/content", byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        Map<String, Object> variantRequest = Map.of(
                "platform", "UNIVERSAL",
                "resourceType", "STATIC_IMAGE",
                "capabilityRequirements", List.of());
        ResponseEntity<JsonNode> variant = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId + "/variants",
                HttpMethod.POST,
                variantRequest,
                session,
                initialEtag);
        assertThat(variant.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String variantId = variant.getBody().path("id").asText();

        JsonNode currentWallpaper = getJson("/api/v1/admin/wallpapers/" + wallpaperId, session).getBody();
        String etagAfterVariant = "\"" + currentWallpaper.path("version").asLong() + "\"";
        Map<String, Object> versionOneRequest = Map.of(
                "versionNo", 1,
                "bindings", List.of(Map.of(
                        "assetId", staticAsset.path("id").asText(),
                        "role", "STATIC_IMAGE",
                        "ordinal", 0)));
        ResponseEntity<JsonNode> versionOne = jsonExchange(
                "/api/v1/admin/variants/" + variantId + "/resource-versions",
                HttpMethod.POST,
                versionOneRequest,
                session,
                null);
        assertThat(versionOne.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(versionOne.getBody().path("status").asText()).isEqualTo("READY");
        String versionOneId = versionOne.getBody().path("id").asText();

        ResponseEntity<JsonNode> publishedOne = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId + "/publish",
                HttpMethod.POST,
                Map.of("resourceVersionIds", List.of(versionOneId)),
                session,
                etagAfterVariant);
        assertThat(publishedOne.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publishedOne.getBody().path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(publishedOne.getBody().path("capabilities")).hasSize(1);
        assertThat(publishedOne.getBody().path("childCategory").path("id").asText())
                .isEqualTo(childCategory.getBody().path("id").asText());

        String rootId = category.getBody().path("id").asText();
        String childId = childCategory.getBody().path("id").asText();
        JsonNode publicCategories = http.getForObject("/api/v1/public/categories", JsonNode.class);
        JsonNode publicRoot = null;
        for (JsonNode item : publicCategories.path("items")) {
            if (item.path("id").asText().equals(rootId)) publicRoot = item;
        }
        assertThat(publicRoot).isNotNull();
        assertThat(publicRoot.path("wallpaperCount").asLong()).isEqualTo(1);
        assertThat(publicRoot.path("children")).hasSize(1);
        assertThat(publicRoot.path("children").get(0).path("id").asText()).isEqualTo(childId);
        assertThat(publicRoot.path("icon").path("contentUrl").asText())
                .isEqualTo("/api/v1/public/assets/" + icon.path("id").asText() + "/content");

        String publicList = "/api/v1/public/wallpapers?rootCategoryId=" + rootId
                + "&childCategoryId=" + childId + "&view=STATIC&platform=ANDROID"
                + "&q=api-integration-static-wallpaper&pageSize=1";
        JsonNode publicPage = http.getForObject(publicList, JsonNode.class);
        assertThat(publicPage.path("items")).hasSize(1);
        assertThat(publicPage.path("items").get(0).path("id").asText()).isEqualTo(wallpaperId);
        assertThat(publicPage.path("page").path("totalItems").asLong()).isEqualTo(1);
        assertThat(publicPage.path("page").path("totalPages").asInt()).isEqualTo(1);
        assertThat(http.getForObject("/api/v1/public/wallpapers?rootCategoryId=" + rootId + "&q=集成静态",
                JsonNode.class).path("items").get(0).path("id").asText()).isEqualTo(wallpaperId);
        assertThat(http.getForObject(publicList + "&page=2", JsonNode.class).path("items")).isEmpty();
        assertThat(http.getForObject("/api/v1/public/wallpapers?rootCategoryId=" + rootId + "&view=FEATURED",
                JsonNode.class).path("items").get(0).path("featured").asBoolean()).isTrue();
        assertThat(http.getForObject("/api/v1/public/wallpapers?rootCategoryId=" + rootId + "&q=%25",
                JsonNode.class).path("page").path("totalItems").asLong()).isZero();
        assertThat(http.getForEntity("/api/v1/public/wallpapers?childCategoryId=" + childId,
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/public/wallpapers?rootCategoryId=" + rootId + "&childCategoryId=" + rootId,
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/public/wallpapers?pageSize=101",
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(http.getForEntity("/api/v1/public/wallpapers?q= ",
                JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        JsonNode publicDetail = http.getForObject("/api/v1/public/wallpapers/" + wallpaperId + "?platform=ANDROID",
                JsonNode.class);
        assertThat(publicDetail.path("copyrightNote").asText()).isEqualTo("API integration test asset");
        assertThat(publicDetail.path("publishedAt").asText()).isNotBlank();
        assertThat(publicDetail.path("capabilities").get(0).path("platform").asText()).isEqualTo("UNIVERSAL");
        assertThat(publicDetail.toString()).doesNotContain("storageKey", "storage_key", "password", "secret", "bindings");
        ResponseEntity<byte[]> publicCover = http.getForEntity(publicDetail.path("cover").path("contentUrl").asText(),
                byte[].class);
        assertThat(publicCover.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(publicCover.getBody()).isEqualTo(image);
        assertThat(publicCover.getHeaders().getETag()).isEqualTo("\"" + cover.path("sha256").asText() + "\"");
        assertThat(publicCover.getHeaders().getCacheControl()).contains("public", "max-age=3600");
        assertThat(http.getForEntity("/api/v1/public/assets/" + staticAsset.path("id").asText() + "/content",
                byte[].class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);

        ResponseEntity<JsonNode> staleUpdate = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId,
                HttpMethod.PATCH,
                wallpaperRequest,
                session,
                initialEtag);
        assertThat(staleUpdate.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(staleUpdate.getBody().path("error").path("code").asText()).isEqualTo("VERSION_CONFLICT");

        ResponseEntity<JsonNode> immutableVariant = jsonExchange(
                "/api/v1/admin/variants/" + variantId,
                HttpMethod.PATCH,
                variantRequest,
                session,
                "\"0\"");
        assertThat(immutableVariant.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(immutableVariant.getBody().path("error").path("code").asText()).isEqualTo("RESOURCE_IN_USE");

        Map<String, Object> versionTwoRequest = Map.of(
                "versionNo", 2,
                "bindings", List.of(Map.of(
                        "assetId", staticAsset.path("id").asText(),
                        "role", "STATIC_IMAGE",
                        "ordinal", 0)));
        ResponseEntity<JsonNode> versionTwo = jsonExchange(
                "/api/v1/admin/variants/" + variantId + "/resource-versions",
                HttpMethod.POST,
                versionTwoRequest,
                session,
                null);
        String versionTwoId = versionTwo.getBody().path("id").asText();
        String publishedEtag = publishedOne.getHeaders().getETag();
        ResponseEntity<JsonNode> publishedTwo = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId + "/publish",
                HttpMethod.POST,
                Map.of("resourceVersionIds", List.of(versionTwoId)),
                session,
                publishedEtag);
        assertThat(publishedTwo.getStatusCode()).isEqualTo(HttpStatus.OK);

        assertThat(jdbc.queryForObject(
                        "SELECT status FROM resource_version WHERE id = ?",
                        String.class,
                        Long.parseLong(versionOneId)))
                .isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM resource_version WHERE id = ?",
                        String.class,
                        Long.parseLong(versionTwoId)))
                .isEqualTo("PUBLISHED");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM resource_binding WHERE resource_version_id = ?",
                        Long.class,
                        Long.parseLong(versionOneId)))
                .isEqualTo(1);

        ResponseEntity<JsonNode> offline = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId + "/offline",
                HttpMethod.POST,
                Map.of("reason", "integration lifecycle check"),
                session,
                publishedTwo.getHeaders().getETag());
        assertThat(offline.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(offline.getBody().path("status").asText()).isEqualTo("OFFLINE");
        assertThat(http.getForEntity("/api/v1/public/wallpapers/" + wallpaperId, JsonNode.class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(http.getForObject(publicList, JsonNode.class).path("items")).isEmpty();
        assertThat(http.getForEntity("/api/v1/public/assets/" + cover.path("id").asText() + "/content", byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM resource_version WHERE id = ?",
                        String.class,
                        Long.parseLong(versionTwoId)))
                .isEqualTo("PUBLISHED");

        ResponseEntity<JsonNode> archived = jsonExchange(
                "/api/v1/admin/wallpapers/" + wallpaperId + "/archive",
                HttpMethod.POST,
                Map.of("reason", "integration archive check"),
                session,
                offline.getHeaders().getETag());
        assertThat(archived.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(archived.getBody().path("status").asText()).isEqualTo("ARCHIVED");
        assertThat(jdbc.queryForObject(
                        "SELECT status FROM resource_version WHERE id = ?",
                        String.class,
                        Long.parseLong(versionTwoId)))
                .isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_event WHERE aggregate_type IN ('ASSET', 'CATEGORY', 'WALLPAPER', 'WALLPAPER_VARIANT', 'RESOURCE_VERSION') AND result = 'SUCCEEDED'",
                        Long.class))
                .isGreaterThanOrEqualTo(8);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM asset WHERE storage_key LIKE '/%' OR storage_key LIKE '%..%'",
                        Long.class))
                .isZero();
    }

    @Test
    void wallpaperTutorialFlowUploadsConfiguresStreamsAndDisablesVideo() throws Exception {
        ensureAdmin();
        AdminTestSession session = login();

        ResponseEntity<JsonNode> initial = getJson("/api/v1/admin/wallpaper-tutorials", session);
        assertThat(initial.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(initial.getBody().path("items")).hasSize(5);
        JsonNode androidDynamic = java.util.stream.StreamSupport
                .stream(initial.getBody().path("items").spliterator(), false)
                .filter(item -> item.path("key").asText().equals("ANDROID_DYNAMIC"))
                .findFirst()
                .orElseThrow();
        assertThat(androidDynamic.path("enabled").asBoolean()).isFalse();
        assertThat(androidDynamic.path("video").isNull()).isTrue();
        assertThat(androidDynamic.path("version").asLong()).isZero();

        byte[] videoBytes = testVideo();
        JsonNode video = uploadAsset(session, "TUTORIAL_VIDEO", "android-dynamic.mp4", videoBytes);
        assertThat(video.path("mimeType").asText()).isEqualTo("video/mp4");
        assertThat(video.path("durationMs").asLong()).isBetween(900L, 1_100L);
        assertThat(jdbc.queryForObject(
                "SELECT purpose FROM asset WHERE id = ?",
                String.class,
                video.path("id").asLong())).isEqualTo("TUTORIAL_VIDEO");

        String adminPath = "/api/v1/admin/wallpaper-tutorials/ANDROID_DYNAMIC";
        Map<String, Object> request = Map.of(
                "videoAssetId", video.path("id").asText(),
                "enabled", true,
                "sortOrder", 12);
        ResponseEntity<JsonNode> configured = jsonExchange(
                adminPath,
                HttpMethod.PUT,
                request,
                session,
                "\"0\"");
        assertThat(configured.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(configured.getHeaders().getETag()).isEqualTo("\"1\"");
        assertThat(configured.getBody().path("title").asText()).isEqualTo("动态壁纸教程");
        assertThat(configured.getBody().path("video").path("id").asText()).isEqualTo(video.path("id").asText());

        ResponseEntity<JsonNode> stale = jsonExchange(adminPath, HttpMethod.PUT, request, session, "\"0\"");
        assertThat(stale.getStatusCode()).isEqualTo(HttpStatus.PRECONDITION_FAILED);
        assertThat(stale.getBody().path("error").path("code").asText()).isEqualTo("VERSION_CONFLICT");

        JsonNode publicList = http.getForObject("/api/v1/public/wallpaper-tutorials", JsonNode.class);
        assertThat(publicList.path("items")).hasSize(1);
        String videoPath = publicList.path("items").get(0).path("video").path("contentUrl").asText();
        assertThat(videoPath).isEqualTo("/api/v1/public/wallpaper-tutorials/ANDROID_DYNAMIC/video?v=1");

        HttpHeaders rangeHeaders = new HttpHeaders();
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=4-15");
        ResponseEntity<byte[]> range = http.exchange(
                videoPath,
                HttpMethod.GET,
                new HttpEntity<>(rangeHeaders),
                byte[].class);
        assertThat(range.getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);
        assertThat(range.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");
        assertThat(range.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes 4-15/" + videoBytes.length);
        assertThat(range.getHeaders().getContentLength()).isEqualTo(12);
        assertThat(range.getBody()).containsExactly(java.util.Arrays.copyOfRange(videoBytes, 4, 16));

        ResponseEntity<Void> head = http.exchange(videoPath, HttpMethod.HEAD, HttpEntity.EMPTY, Void.class);
        assertThat(head.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(head.getHeaders().getContentLength()).isEqualTo(videoBytes.length);
        assertThat(head.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES)).isEqualTo("bytes");

        ResponseEntity<Void> rangedHead = http.exchange(
                videoPath, HttpMethod.HEAD, new HttpEntity<>(rangeHeaders), Void.class);
        assertThat(rangedHead.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(rangedHead.getHeaders().getContentLength()).isEqualTo(videoBytes.length);

        rangeHeaders.set(HttpHeaders.IF_RANGE, "\"outdated-content\"");
        ResponseEntity<byte[]> changedRange = http.exchange(
                videoPath, HttpMethod.GET, new HttpEntity<>(rangeHeaders), byte[].class);
        assertThat(changedRange.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(changedRange.getBody()).containsExactly(videoBytes);
        rangeHeaders.set(HttpHeaders.IF_RANGE, head.getHeaders().getETag());
        assertThat(http.exchange(videoPath, HttpMethod.GET, new HttpEntity<>(rangeHeaders), byte[].class)
                .getStatusCode()).isEqualTo(HttpStatus.PARTIAL_CONTENT);

        rangeHeaders.remove(HttpHeaders.IF_RANGE);
        rangeHeaders.set(HttpHeaders.RANGE, "bytes=" + videoBytes.length + "-");
        ResponseEntity<JsonNode> invalidRange = http.exchange(
                videoPath, HttpMethod.GET, new HttpEntity<>(rangeHeaders), JsonNode.class);
        assertThat(invalidRange.getStatusCode()).isEqualTo(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE);
        assertThat(invalidRange.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE))
                .isEqualTo("bytes */" + videoBytes.length);

        ResponseEntity<byte[]> staleVersion = http.getForEntity(videoPath.replace("v=1", "v=0"), byte[].class);
        assertThat(staleVersion.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(staleVersion.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(staleVersion.getBody()).containsExactly(videoBytes);

        ResponseEntity<JsonNode> disabled = jsonExchange(
                adminPath,
                HttpMethod.PUT,
                Map.of("videoAssetId", video.path("id").asText(), "enabled", false, "sortOrder", 12),
                session,
                "\"1\"");
        assertThat(disabled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForObject("/api/v1/public/wallpaper-tutorials", JsonNode.class).path("items")).isEmpty();
        assertThat(http.getForEntity(videoPath, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM audit_event
                WHERE aggregate_type = 'WALLPAPER_TUTORIAL'
                  AND aggregate_id = 'ANDROID_DYNAMIC'
                  AND JSON_UNQUOTE(JSON_EXTRACT(change_summary, '$.newVideoAssetId')) = ?
                """,
                Long.class,
                video.path("id").asText())).isGreaterThanOrEqualTo(2);
    }

    @Test
    void deviceRedemptionFlowIsIdempotentQuotaSafeAndRecoverableAfterRedisLoss() throws Exception {
        ensureAdmin();
        AdminTestSession admin = login();
        long wallpaperId = createPublishedWallpaperFixture();

        String batchIdempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> batchRequest = Map.of(
                "name", "WP-P08 integration batch",
                "generatedCount", 1,
                "quotaPerCode", 3);
        HttpHeaders batchHeaders = headers(admin, true, null);
        batchHeaders.setContentType(MediaType.APPLICATION_JSON);
        batchHeaders.set("Idempotency-Key", batchIdempotencyKey);
        ResponseEntity<JsonNode> batch = http.exchange(
                "/api/v1/admin/code-batches",
                HttpMethod.POST,
                new HttpEntity<>(batchRequest, batchHeaders),
                JsonNode.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = batch.getBody().path("batch").path("id").asText();
        String deliveryTicket = batch.getBody().path("deliveryTicket").asText();
        assertThat(deliveryTicket).hasSizeGreaterThanOrEqualTo(32);

        HttpHeaders deliveryHeaders = headers(admin, false, null);
        deliveryHeaders.set("X-Delivery-Ticket", deliveryTicket);
        ResponseEntity<byte[]> delivery = http.exchange(
                "/api/v1/admin/code-batches/" + batchId + "/delivery",
                HttpMethod.GET,
                new HttpEntity<>(deliveryHeaders),
                byte[].class);
        assertThat(delivery.getStatusCode()).isEqualTo(HttpStatus.OK);
        String csv = new String(delivery.getBody(), StandardCharsets.UTF_8);
        String code = csv.lines().skip(1).findFirst().orElseThrow().split(",")[1];
        String normalizedCode = code.replace("-", "");
        assertThat(normalizedCode).matches("^[A-Z0-9]{20}$");

        ResponseEntity<JsonNode> batchRetry = http.exchange(
                "/api/v1/admin/code-batches",
                HttpMethod.POST,
                new HttpEntity<>(batchRequest, batchHeaders),
                JsonNode.class);
        assertThat(batchRetry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(batchRetry.getBody().path("batch").path("id").asText()).isEqualTo(batchId);

        ResponseEntity<JsonNode> batchConflict = http.exchange(
                "/api/v1/admin/code-batches",
                HttpMethod.POST,
                new HttpEntity<>(Map.of(
                        "name", "Different request",
                        "generatedCount", 1,
                        "quotaPerCode", 3), batchHeaders),
                JsonNode.class);
        assertThat(batchConflict.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(batchConflict.getBody().path("error").path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        HttpHeaders concurrentBatchHeaders = headers(admin, true, null);
        concurrentBatchHeaders.setContentType(MediaType.APPLICATION_JSON);
        concurrentBatchHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        Map<String, Object> concurrentBatchRequest = Map.of(
                "name", "WP-P08 concurrent idempotency",
                "generatedCount", 1,
                "quotaPerCode", 1);
        ExecutorService batchExecutor = Executors.newFixedThreadPool(2);
        List<ResponseEntity<JsonNode>> batchRace = new ArrayList<>();
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = List.of(
                    batchExecutor.submit(() -> http.exchange(
                            "/api/v1/admin/code-batches",
                            HttpMethod.POST,
                            new HttpEntity<>(concurrentBatchRequest, concurrentBatchHeaders),
                            JsonNode.class)),
                    batchExecutor.submit(() -> http.exchange(
                            "/api/v1/admin/code-batches",
                            HttpMethod.POST,
                            new HttpEntity<>(concurrentBatchRequest, concurrentBatchHeaders),
                            JsonNode.class)));
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                batchRace.add(future.get());
            }
        } finally {
            batchExecutor.shutdownNow();
        }
        assertThat(batchRace).extracting(ResponseEntity::getStatusCode)
                .containsExactlyInAnyOrder(HttpStatus.CREATED, HttpStatus.OK);
        assertThat(batchRace.get(0).getBody().path("batch").path("id").asText())
                .isEqualTo(batchRace.get(1).getBody().path("batch").path("id").asText());

        ResponseEntity<Void> confirmed = http.exchange(
                "/api/v1/admin/code-batches/" + batchId + "/delivery-confirmation",
                HttpMethod.POST,
                new HttpEntity<>(headers(admin, true, null)),
                Void.class);
        assertThat(confirmed.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        ResponseEntity<JsonNode> expiredDelivery = http.exchange(
                "/api/v1/admin/code-batches/" + batchId + "/delivery",
                HttpMethod.GET,
                new HttpEntity<>(deliveryHeaders),
                JsonNode.class);
        assertThat(expiredDelivery.getStatusCode()).isEqualTo(HttpStatus.GONE);

        Map<String, Object> storedCode = jdbc.queryForMap(
                "SELECT code_hash, code_suffix, total_quota, used_quota FROM redemption_code WHERE batch_id = ?",
                Long.parseLong(batchId));
        assertThat(storedCode.get("code_hash").toString()).hasSize(64).isNotEqualTo(normalizedCode);
        assertThat(storedCode.get("code_suffix").toString()).isEqualTo(normalizedCode.substring(15));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'redemption_code' AND column_name IN ('code', 'plaintext_code')",
                        Long.class))
                .isZero();

        DeviceTestSession owner = registerAndCreateSession("integration-owner-" + UUID.randomUUID());
        String missingWallpaperKey = UUID.randomUUID().toString();
        ResponseEntity<JsonNode> missingWallpaper = signedPost(
                "/api/v1/device/redemptions", orderedMap("wallpaperId", Long.toString(Long.MAX_VALUE), "code", code),
                owner, missingWallpaperKey);
        assertThat(missingWallpaper.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missingWallpaper.getBody().path("error").path("code").asText()).isEqualTo("WALLPAPER_NOT_FOUND");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM redemption_request WHERE idempotency_key = ?", Long.class, missingWallpaperKey)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT SUM(used_quota) FROM redemption_code WHERE batch_id = ?", Long.class, Long.parseLong(batchId))).isZero();
        String firstKey = UUID.randomUUID().toString();
        Map<String, Object> redemptionBody = orderedMap("wallpaperId", Long.toString(wallpaperId), "code", code);
        ResponseEntity<JsonNode> granted = signedPost(
                "/api/v1/device/redemptions", redemptionBody, owner, firstKey);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(granted.getBody().path("result").asText()).isEqualTo("GRANTED");
        assertThat(granted.getBody().path("quotaDelta").asInt()).isEqualTo(1);

        ResponseEntity<JsonNode> replay = signedPost(
                "/api/v1/device/redemptions", redemptionBody, owner, firstKey);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody()).isEqualTo(granted.getBody());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM redemption_event e JOIN redemption_request r ON r.id = e.request_id WHERE r.idempotency_key = ?",
                        Long.class,
                        firstKey))
                .isEqualTo(1);

        ResponseEntity<JsonNode> reusedKey = signedPost(
                "/api/v1/device/redemptions",
                orderedMap("wallpaperId", Long.toString(wallpaperId), "code", "AAAAAAAAAAAAAAAAAAAA"),
                owner,
                firstKey);
        assertThat(reusedKey.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(reusedKey.getBody().path("error").path("code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");

        ResponseEntity<JsonNode> alreadyOwned = signedPost(
                "/api/v1/device/redemptions", redemptionBody, owner, UUID.randomUUID().toString());
        assertThat(alreadyOwned.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(alreadyOwned.getBody().path("result").asText()).isEqualTo("ALREADY_OWNED");
        assertThat(alreadyOwned.getBody().path("quotaDelta").asInt()).isZero();

        List<DeviceTestSession> competitors = List.of(
                registerAndCreateSession("integration-device-a-" + UUID.randomUUID()),
                registerAndCreateSession("integration-device-b-" + UUID.randomUUID()),
                registerAndCreateSession("integration-device-c-" + UUID.randomUUID()));
        ExecutorService executor = Executors.newFixedThreadPool(competitors.size());
        List<ResponseEntity<JsonNode>> concurrentResults = new ArrayList<>();
        try {
            List<Future<ResponseEntity<JsonNode>>> futures = competitors.stream()
                    .map(device -> executor.submit(() -> signedPost(
                            "/api/v1/device/redemptions",
                            redemptionBody,
                            device,
                            UUID.randomUUID().toString())))
                    .toList();
            for (Future<ResponseEntity<JsonNode>> future : futures) {
                concurrentResults.add(future.get());
            }
        } finally {
            executor.shutdownNow();
        }
        assertThat(concurrentResults.stream().filter(response -> response.getStatusCode() == HttpStatus.CREATED))
                .hasSize(2);
        assertThat(concurrentResults.stream().filter(response -> response.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY))
                .singleElement()
                .satisfies(response -> assertThat(response.getBody().path("result").asText()).isEqualTo("CODE_EXHAUSTED"));
        assertThat(jdbc.queryForObject(
                        "SELECT used_quota FROM redemption_code WHERE batch_id = ?",
                        Integer.class,
                        Long.parseLong(batchId)))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM device_entitlement WHERE wallpaper_id = ? AND status = 'ACTIVE'",
                        Long.class,
                        wallpaperId))
                .isEqualTo(3);

        ResponseEntity<JsonNode> entitlements = http.exchange(
                "/api/v1/device/me/entitlements",
                HttpMethod.GET,
                new HttpEntity<>(deviceHeaders(owner)),
                JsonNode.class);
        assertThat(entitlements.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(entitlements.getBody().path("items")).hasSize(1);
        assertThat(entitlements.getBody().path("items").get(0).path("wallpaper").path("id").asText())
                .isEqualTo(Long.toString(wallpaperId));

        ResponseEntity<JsonNode> descriptor = signedPost(
                "/api/v1/device/wallpapers/" + wallpaperId + "/download-tickets",
                orderedMap("platform", "H5_TEST", "supportedResourceTypes", List.of("STATIC_IMAGE")),
                owner,
                null);
        assertThat(descriptor.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(descriptor.getBody().path("deliveryMode").asText()).isEqualTo("H5_PLACEHOLDER");
        assertThat(descriptor.getBody().has("ticket")).isFalse();

        ResponseEntity<JsonNode> adminRedemptions = getJson("/api/v1/admin/redemptions?pageSize=100", admin);
        assertThat(adminRedemptions.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminRedemptions.getBody().path("page").path("totalItems").asLong()).isGreaterThanOrEqualTo(5);
        ResponseEntity<JsonNode> adminDevices = getJson("/api/v1/admin/devices?pageSize=100", admin);
        assertThat(adminDevices.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(adminDevices.getBody().path("page").path("totalItems").asLong()).isGreaterThanOrEqualTo(4);
        String deviceId = adminDevices.getBody().path("items").get(0).path("id").asText();
        ResponseEntity<JsonNode> deviceDetail = getJson("/api/v1/admin/devices/" + deviceId, admin);
        assertThat(deviceDetail.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(deviceDetail.getBody().toString())
                .doesNotContain("secretHash", "credentialSecret", "evidenceHash", "publicKeyPem");

        redis.execute((org.springframework.data.redis.core.RedisCallback<Void>) connection -> {
            connection.serverCommands().flushDb();
            return null;
        });
        DeviceTestSession renewed = createSession(owner.credentialKeyId(), owner.credentialSecret());
        ResponseEntity<JsonNode> recovered = http.exchange(
                "/api/v1/device/redemptions/" + firstKey,
                HttpMethod.GET,
                new HttpEntity<>(deviceHeaders(renewed)),
                JsonNode.class);
        assertThat(recovered.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(recovered.getBody()).isEqualTo(granted.getBody());
    }

    private void ensureAdmin() {
        String hash = passwordEncoder.encode("integration-password-2026");
        jdbc.update(
                """
                INSERT INTO admin_account
                    (singleton_key, username, password_hash, password_changed_at)
                VALUES (1, 'api-admin', ?, UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE
                    username = VALUES(username), password_hash = VALUES(password_hash),
                    password_changed_at = VALUES(password_changed_at)
                """,
                hash);
    }

    @Autowired com.qingjing.wallpaper.delivery.SecurePackagePublisher packagePublisher;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.qingjing.wallpaper.asset.application.FileStorage packageStorage;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired com.qingjing.wallpaper.parallax.ParallaxStorageCleanup parallaxCleanup;

    @Test
    void fixedParallaxZipImportsReusesPublishesAndPreservesEarlierVersions() throws Exception {
        ensureAdmin();var admin=login();
        var unauthorizedHeaders=new HttpHeaders();unauthorizedHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var unauthorized=http.exchange("/api/v1/admin/parallax-packages",HttpMethod.POST,new HttpEntity<>(new LinkedMultiValueMap<>(),unauthorizedHeaders),JsonNode.class);
        assertThat(unauthorized.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var emptyHeaders=headers(admin,true,null);emptyHeaders.setContentType(MediaType.MULTIPART_FORM_DATA);
        var missing=http.exchange("/api/v1/admin/parallax-packages",HttpMethod.POST,new HttpEntity<>(new LinkedMultiValueMap<>(),emptyHeaders),JsonNode.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(missing.getBody().path("error").path("code").asText()).isEqualTo("PARALLAX_PACKAGE_REQUIRED");
        long wallpaper=createPublishedWallpaperFixture();
        long variant=jdbc.queryForObject("SELECT id FROM wallpaper_variant WHERE wallpaper_id=?",Long.class,wallpaper);
        jdbc.update("UPDATE wallpaper_variant SET platform='ANDROID',resource_type='LAYER_PARALLAX' WHERE id=?",variant);
        jdbc.update("UPDATE wallpaper SET kind='PARALLAX_4D',status='DRAFT' WHERE id=?",wallpaper);
        long legacy=jdbc.queryForObject("SELECT id FROM resource_version WHERE variant_id=?",Long.class,variant);
        var legacyRead=getJson("/api/v1/admin/resource-versions/"+legacy,admin).getBody();
        assertThat(legacyRead.has("sourcePackage")).isTrue();assertThat(legacyRead.path("sourcePackage").isNull()).isTrue();
        var unknownFiles=com.qingjing.wallpaper.parallax.ParallaxFixtures.files(2);
        String unknownConfig=new String(unknownFiles.get("config.json"),StandardCharsets.UTF_8)
                .replace("\"formatVersion\":2","\"formatVersion\":37")
                .replace("\"motion\":{","\"futureRoot\":true,\"motion\":{\"futureMotion\":{\"type\":\"spring\"},")
                .replace("\"offsetXPercent\":8","\"futureLayer\":[1,2,3],\"offsetXPercent\":8");
        byte[] unknownConfigBytes=unknownConfig.getBytes(StandardCharsets.UTF_8);
        unknownFiles.put("config.json",unknownConfigBytes);
        var unknownSource=importParallax(admin,com.qingjing.wallpaper.parallax.ParallaxFixtures.zip(unknownFiles),true);
        assertThat(unknownSource.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(unknownSource.getBody().path("configFormatVersion").asInt()).isEqualTo(37);
        var unknownVersion=jsonExchange("/api/v1/admin/variants/"+variant+"/parallax-resource-versions",HttpMethod.POST,
                Map.of("versionNo",99,"sourcePackageId",unknownSource.getBody().path("id").asText()),admin,null);
        assertThat(unknownVersion.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long unknownVersionId=unknownVersion.getBody().path("id").asLong();
        var builtUnknown=jsonExchange("/api/v1/admin/resource-versions/"+unknownVersionId+"/secure-package",
                HttpMethod.POST,Map.of(),admin,null);
        assertThat(builtUnknown.getStatusCode()).as(builtUnknown.getBody().toString()).isEqualTo(HttpStatus.OK);
        assertThat(formalParallaxConfig(unknownVersionId,wallpaper,variant,99)).containsExactly(unknownConfigBytes);
        var v1Files=com.qingjing.wallpaper.parallax.ParallaxFixtures.files(2);
        v1Files.put("config.json",new String(v1Files.get("config.json"),StandardCharsets.UTF_8)
                .replace("\"formatVersion\":2","\"formatVersion\":1").getBytes(StandardCharsets.UTF_8));
        assertThat(importParallax(admin,com.qingjing.wallpaper.parallax.ParallaxFixtures.zip(v1Files),true).getStatusCode())
                .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        String earlierSource=null;long earlierVersion=0;int versionNo=2;
        for(int count:List.of(2,3,12)) {
            var sourceFiles=com.qingjing.wallpaper.parallax.ParallaxFixtures.files(count);
            byte[] sourceConfig=sourceFiles.get("config.json");
            byte[] zip=com.qingjing.wallpaper.parallax.ParallaxFixtures.zip(sourceFiles);
            assertThat(importParallax(admin,zip,false).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
            var first=importParallax(admin,zip,true);
            assertThat(first.getStatusCode()).as(first.getBody().toString()).isEqualTo(HttpStatus.CREATED);
            var source=first.getBody();String sourceId=source.path("id").asText();
            assertThat(source.path("layers").size()).isEqualTo(count);
            assertThat(source.path("configFormatVersion").asInt()).isEqualTo(2);
            assertThat(source.path("validationStatus").asText()).isEqualTo("READY");
            assertThat(source.has("storageKey")).isFalse();assertThat(source.has("url")).isFalse();
            var again=importParallax(admin,zip,true);assertThat(again.getStatusCode()).isEqualTo(HttpStatus.OK);assertThat(again.getBody()).isEqualTo(source);
            var body=Map.of("versionNo",versionNo,"sourcePackageId",sourceId);
            String path="/api/v1/admin/variants/"+variant+"/parallax-resource-versions";
            var version=jsonExchange(path,HttpMethod.POST,body,admin,null);
            assertThat(version.getStatusCode()).as(version.getBody().toString()).isEqualTo(HttpStatus.CREATED);
            long id=version.getBody().path("id").asLong();
            assertThat(version.getBody().path("bindings").size()).isEqualTo(count+1);
            assertThat(version.getBody().path("sourcePackage")).isEqualTo(source);
            assertThat(jsonExchange(path,HttpMethod.POST,body,admin,null).getStatusCode()).isEqualTo(HttpStatus.OK);
            if(earlierSource!=null)assertThat(jsonExchange(path,HttpMethod.POST,Map.of("versionNo",versionNo,"sourcePackageId",earlierSource),admin,null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            var built=jsonExchange("/api/v1/admin/resource-versions/"+id+"/secure-package",HttpMethod.POST,Map.of(),admin,null);
            assertThat(built.getStatusCode()).as(built.getBody().toString()).isEqualTo(HttpStatus.OK);
            assertThat(built.getBody().path("sourcePackage")).isEqualTo(source);
            assertThat(formalParallaxConfig(id,wallpaper,variant,versionNo)).containsExactly(sourceConfig);
            jdbc.update("UPDATE wallpaper SET cover_asset_id=? WHERE id=?",source.path("cover").path("id").asLong(),wallpaper);
            var detail=getJson("/api/v1/admin/wallpapers/"+wallpaper,admin);
            var published=jsonExchange("/api/v1/admin/wallpapers/"+wallpaper+"/publish",HttpMethod.POST,Map.of("resourceVersionIds",List.of(Long.toString(id))),admin,detail.getHeaders().getETag());
            assertThat(published.getStatusCode()).as(published.getBody().toString()).isEqualTo(HttpStatus.OK);
            assertThat(getJson("/api/v1/admin/resource-versions/"+id,admin).getBody().path("sourcePackage")).isEqualTo(source);
            if(earlierSource!=null) {
                var older=getJson("/api/v1/admin/resource-versions/"+earlierVersion,admin).getBody();
                assertThat(older.path("sourcePackage").path("id").asText()).isEqualTo(earlierSource);
                assertThat(older.path("status").asText()).isEqualTo("RETIRED");
            }
            earlierSource=sourceId;earlierVersion=id;versionNo++;
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_event WHERE action='CREATE_PARALLAX_VERSION' AND aggregate_id=?",Integer.class,Long.toString(earlierVersion))).isEqualTo(1);
        jdbc.update("UPDATE wallpaper SET status='ARCHIVED',archived_at=UTC_TIMESTAMP(6) WHERE id=?",wallpaper);
        assertThat(jsonExchange("/api/v1/admin/variants/"+variant+"/parallax-resource-versions",HttpMethod.POST,
                Map.of("versionNo",5,"sourcePackageId",earlierSource),admin,null).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    private byte[] formalParallaxConfig(long versionId,long wallpaperId,long variantId,int versionNo) throws Exception {
        var stored=jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=?",versionId);
        byte[] key=securityCrypto.decrypt("secure-package-key-v2:"+versionId,stored.get("content_key_ciphertext").toString());
        byte[] encrypted;
        try(var content=packageStorage.open(new com.qingjing.wallpaper.asset.application.StorageKey(stored.get("storage_key").toString()))) {
            encrypted=content.inputStream().readAllBytes();
        }
        try {
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),
                    new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOfRange(encrypted,8,20)));
            cipher.updateAAD(new com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Identity(
                    wallpaperId,variantId,versionNo,"LAYER_PARALLAX").aad());
            byte[] plain=cipher.doFinal(java.util.Arrays.copyOfRange(encrypted,20,encrypted.length));
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(plain),StandardCharsets.UTF_8)) {
                java.util.zip.ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(entry.getName().equals("payload/parallax_config-0.json"))return zip.readAllBytes();
                }
            }
            throw new AssertionError("formal package has no parallax config");
        } finally { java.util.Arrays.fill(key,(byte)0); }
    }

    @Test
    void parallaxConcurrentDedupAndFailedImportCleanupAreAtomic() throws Exception {
        ensureAdmin();var admin=login();
        var files=com.qingjing.wallpaper.parallax.ParallaxFixtures.files(3);files.put("__MACOSX/concurrent",UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        byte[] zip=com.qingjing.wallpaper.parallax.ParallaxFixtures.zip(files);
        var pool=Executors.newFixedThreadPool(2);
        try {
            var start=new java.util.concurrent.CountDownLatch(1);
            var a=pool.submit(()->{start.await();return importParallax(admin,zip,true);});
            var b=pool.submit(()->{start.await();return importParallax(admin,zip,true);});start.countDown();
            var first=a.get();var second=b.get();
            assertThat(List.of(first.getStatusCode().value(),second.getStatusCode().value())).containsExactlyInAnyOrder(200,201);
            assertThat(first.getBody()).isEqualTo(second.getBody());
        }finally{pool.shutdownNow();}
        long sourceCount=jdbc.queryForObject("SELECT COUNT(*) FROM parallax_source_package",Long.class);
        long assetCount=jdbc.queryForObject("SELECT COUNT(*) FROM asset",Long.class);
        List<com.qingjing.wallpaper.asset.application.StoredObject> created=new ArrayList<>();
        org.mockito.Mockito.doAnswer(invocation->{var object=(com.qingjing.wallpaper.asset.application.StoredObject)invocation.callRealMethod();created.add(object);return object;})
                .when(packageStorage).commit(org.mockito.ArgumentMatchers.any(),org.mockito.ArgumentMatchers.anyString());
        org.mockito.Mockito.doThrow(new IllegalStateException("simulated rollback deletion failure"))
                .when(packageStorage).delete(org.mockito.ArgumentMatchers.any());
        jdbc.execute("CREATE TRIGGER reject_parallax_audit BEFORE INSERT ON audit_event FOR EACH ROW BEGIN IF NEW.action='IMPORT_PARALLAX_SOURCE' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='injected audit failure'; END IF; END");
        try {
            files.put("__MACOSX/rollback",new byte[]{1});
            var failed=importParallax(admin,com.qingjing.wallpaper.parallax.ParallaxFixtures.zip(files),true);
            assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parallax_source_package",Long.class)).isEqualTo(sourceCount);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM asset",Long.class)).isEqualTo(assetCount);
            assertThat(created).hasSize(6);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parallax_storage_cleanup",Long.class)).isEqualTo(6);
        }finally{jdbc.execute("DROP TRIGGER reject_parallax_audit");org.mockito.Mockito.reset(packageStorage);}
        assertThat(parallaxCleanup.retryPending()).isEqualTo(6);
        for(var object:created)assertThatThrownBy(()->packageStorage.open(object.storageKey())).isInstanceOf(com.qingjing.wallpaper.asset.application.FileStorageException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM parallax_storage_cleanup",Long.class)).isZero();
    }

    private ResponseEntity<JsonNode> importParallax(AdminTestSession session,byte[] zip,boolean csrf) {
        MultiValueMap<String,Object> body=new LinkedMultiValueMap<>();
        body.add("file",new ByteArrayResource(zip){@Override public String getFilename(){return "example.zip";}});
        var headers=headers(session,csrf,null);headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        return http.exchange("/api/v1/admin/parallax-packages",HttpMethod.POST,new HttpEntity<>(body,headers),JsonNode.class);
    }

    @Test
    void realMediaSecurePackagesBuildIdempotentlyAndRollbackRemovesTheObject() throws Exception {
        ensureAdmin(); var admin = login();
        for (String type : List.of("STATIC_IMAGE", "VIDEO", "LAYER_PARALLAX")) {
            long wallpaperId = createPublishedWallpaperFixture();
            long variantId = jdbc.queryForObject("SELECT id FROM wallpaper_variant WHERE wallpaper_id=?", Long.class, wallpaperId);
            jdbc.update("UPDATE wallpaper_variant SET platform=?,resource_type=? WHERE id=?", type.equals("STATIC_IMAGE") ? "UNIVERSAL" : "ANDROID",type,variantId);
            jdbc.update("UPDATE wallpaper SET kind=? WHERE id=?", type.equals("VIDEO") ? "DYNAMIC" : type.equals("LAYER_PARALLAX") ? "PARALLAX_4D" : "STATIC",wallpaperId);
            List<Map<String,Object>> bindings = new ArrayList<>();
            List<String> roles = type.equals("LAYER_PARALLAX") ? List.of("BACKGROUND","FOREGROUND","PARALLAX_CONFIG") : List.of(type);
            for (String role : roles) {
                byte[] bytes;
                String extension = "png";
                if (role.equals("VIDEO")) { bytes = testVideo(); extension = "mp4"; }
                else if (role.equals("PARALLAX_CONFIG")) {
                    extension = "json";
                    bytes = objectMapper.writeValueAsBytes(Map.of("formatVersion",2,"canvas",Map.of("width",512,"height",512),
                            "motion",Map.of("maxAngleX",75,"maxAngleY",75),"layers",List.of(
                            Map.of("index",1,"offsetXPercent",8,"offsetYPercent",6,"initialOffsetXPercent",0,"initialOffsetYPercent",0,"direction","follow","scale",1.18,"opacity",1,"blendMode","normal"),
                            Map.of("index",2,"offsetXPercent",3,"offsetYPercent",2,"initialOffsetXPercent",0,"initialOffsetYPercent",0,"direction","reverse","scale",1,"opacity",1,"blendMode","normal"))));
                } else if (role.equals("FOREGROUND")) {
                    var output = new ByteArrayOutputStream(); ImageIO.write(new BufferedImage(512,512,BufferedImage.TYPE_INT_ARGB),"png",output); bytes=output.toByteArray();
                } else bytes=png(512,512);
                JsonNode asset=uploadAsset(admin,role,role.toLowerCase()+"."+extension,bytes);
                bindings.add(Map.of("role",role,"ordinal",0,"assetId",asset.path("id").asText()));
            }
            ResponseEntity<JsonNode> created=jsonExchange("/api/v1/admin/variants/"+variantId+"/resource-versions",HttpMethod.POST,
                    Map.of("versionNo",2,"bindings",bindings),admin,null);
            assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            long versionId=created.getBody().path("id").asLong();
            String path="/api/v1/admin/resource-versions/"+versionId+"/secure-package";
            assertThat(http.exchange(path,HttpMethod.POST,HttpEntity.EMPTY,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            ResponseEntity<JsonNode> built=jsonExchange(path,HttpMethod.POST,Map.of(),admin,null);
            assertThat(built.getStatusCode()).isEqualTo(HttpStatus.OK);
            Map<String,Object> stored=jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=?",versionId);
            assertThat(stored.get("manifest_sha256")).isEqualTo(built.getBody().path("manifestSha256").asText());
            var identity=new com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Identity(wallpaperId,variantId,2,type);
            var formal=decodeStoredPackage(stored,versionId,identity,false);
            assertThat(formal.manifest().path("formatVersion").asInt()).isEqualTo(2);
            assertThat(jsonExchange(path,HttpMethod.POST,Map.of(),admin,null).getBody()).isEqualTo(built.getBody());
            assertThat(jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=?",versionId)).isEqualTo(stored);
            var previewStored=jdbc.queryForMap("SELECT * FROM preview_resource_package WHERE resource_version_id=?",versionId);
            assertThat(previewStored.get("format_version")).isEqualTo(3);
            assertThat(previewStored.get("purpose")).isEqualTo("APP_PREVIEW");
            assertThat(previewStored.get("encrypted_sha256")).isNotEqualTo(stored.get("encrypted_sha256"));
            var preview=decodeStoredPackage(previewStored,versionId,identity,true);
            assertThat(preview.manifest().path("formatVersion").asInt()).isEqualTo(3);
            assertThat(preview.manifest().path("purpose").asText()).isEqualTo("APP_PREVIEW");
            assertSamePayloadBytes(formal,preview);
            assertThat(jsonExchange(path,HttpMethod.POST,Map.of(),admin,null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForMap("SELECT * FROM preview_resource_package WHERE resource_version_id=?",versionId)).isEqualTo(previewStored);
            ResponseEntity<JsonNode> published=jsonExchange("/api/v1/admin/wallpapers/"+wallpaperId+"/publish",HttpMethod.POST,
                    Map.of("resourceVersionIds",List.of(Long.toString(versionId))),admin,"\"0\"");
            assertThat(published.getStatusCode()).isEqualTo(HttpStatus.OK);
            verifyTicketDelivery(admin,wallpaperId,versionId,type);
            // A legacy/mismatched preview is replaced on the next existing package build while the formal package stays byte-identical.
            jdbc.update("UPDATE preview_resource_package SET manifest_sha256=? WHERE resource_version_id=?","0".repeat(64),versionId);
            assertThat(jsonExchange(path,HttpMethod.POST,Map.of(),admin,null).getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(jdbc.queryForMap("SELECT * FROM secure_resource_package WHERE resource_version_id=?",versionId)).isEqualTo(stored);
            var rebuiltPreview=jdbc.queryForMap("SELECT * FROM preview_resource_package WHERE resource_version_id=?",versionId);
            assertThat(rebuiltPreview.get("storage_key")).isNotEqualTo(previewStored.get("storage_key"));
            assertSamePayloadBytes(formal,decodeStoredPackage(rebuiltPreview,versionId,identity,true));
            assertThatThrownBy(()->packageStorage.open(new com.qingjing.wallpaper.asset.application.StorageKey(previewStored.get("storage_key").toString())))
                    .isInstanceOf(com.qingjing.wallpaper.asset.application.FileStorageException.class);
            long rollbackVersion=jsonExchange("/api/v1/admin/variants/"+variantId+"/resource-versions",HttpMethod.POST,
                    Map.of("versionNo",3,"bindings",bindings),admin,null).getBody().path("id").asLong();
            var orphanKey=new java.util.concurrent.atomic.AtomicReference<String>();
            var previewOrphanKey=new java.util.concurrent.atomic.AtomicReference<String>();
            assertThatThrownBy(() -> new org.springframework.transaction.support.TransactionTemplate(transactionManager).execute(status -> {
                packagePublisher.build(rollbackVersion);
                orphanKey.set(jdbc.queryForObject("SELECT storage_key FROM secure_resource_package WHERE resource_version_id=?",String.class,rollbackVersion));
                previewOrphanKey.set(jdbc.queryForObject("SELECT storage_key FROM preview_resource_package WHERE resource_version_id=?",String.class,rollbackVersion));
                throw new IllegalStateException("rollback-fixture");
            })).isInstanceOf(IllegalStateException.class).hasMessage("rollback-fixture");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM secure_resource_package WHERE resource_version_id=?",Integer.class,rollbackVersion)).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM preview_resource_package WHERE resource_version_id=?",Integer.class,rollbackVersion)).isZero();
            assertThatThrownBy(() -> packageStorage.open(new com.qingjing.wallpaper.asset.application.StorageKey(orphanKey.get())))
                    .isInstanceOf(com.qingjing.wallpaper.asset.application.FileStorageException.class);
            assertThatThrownBy(() -> packageStorage.open(new com.qingjing.wallpaper.asset.application.StorageKey(previewOrphanKey.get())))
                    .isInstanceOf(com.qingjing.wallpaper.asset.application.FileStorageException.class);
            if (!type.equals("STATIC_IMAGE")) {
                byte[] invalidBytes=type.equals("VIDEO") ? new byte[]{0,0,0,12,102,116,121,112,105,115,111,109} :
                        "{\"formatVersion\":1,\"canvas\":{\"width\":512,\"height\":512},\"layers\":[{\"index\":1},{\"index\":2}]}".getBytes(StandardCharsets.UTF_8);
                String badRole=type.equals("VIDEO") ? "VIDEO" : "PARALLAX_CONFIG";
                JsonNode badAsset=uploadAsset(admin,badRole,type.equals("VIDEO") ? "fake.mp4" : "bad.json",invalidBytes);
                List<Map<String,Object>> invalidBindings=bindings.stream().map(binding -> binding.get("role").equals(badRole) ?
                        Map.<String,Object>of("role",badRole,"ordinal",0,"assetId",badAsset.path("id").asText()) : binding).toList();
                long badVersion=jsonExchange("/api/v1/admin/variants/"+variantId+"/resource-versions",HttpMethod.POST,
                        Map.of("versionNo",4,"bindings",invalidBindings),admin,null).getBody().path("id").asLong();
                ResponseEntity<JsonNode> refused=jsonExchange("/api/v1/admin/resource-versions/"+badVersion+"/secure-package",HttpMethod.POST,Map.of(),admin,null);
                assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(refused.getBody().path("error").path("code").asText()).isEqualTo("ASSET_VALIDATION_FAILED");
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM secure_resource_package WHERE resource_version_id=?",Integer.class,badVersion)).isZero();
            }
        }
    }
    private DecodedPackage decodeStoredPackage(Map<String,Object> stored,long versionId,
            com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Identity identity,boolean preview) throws Exception {
        byte[] key=securityCrypto.decrypt((preview?"preview-package-key-v1:":"secure-package-key-v2:")+versionId,
                stored.get("content_key_ciphertext").toString());
        try {
            byte[] encrypted;
            try(var content=packageStorage.open(new com.qingjing.wallpaper.asset.application.StorageKey(stored.get("storage_key").toString()))) {
                assertThat(content.sizeBytes()).isEqualTo(((Number)stored.get("size_bytes")).longValue());
                encrypted=content.inputStream().readAllBytes();
            }
            assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(encrypted)).isEqualTo(stored.get("encrypted_sha256"));
            assertThat(new String(encrypted,0,8,StandardCharsets.US_ASCII)).isEqualTo(preview?"QJPV0001":"QJWP0002");
            var cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(key,"AES"),
                    new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOfRange(encrypted,8,20)));
            cipher.updateAAD(preview?com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.previewAad(identity):identity.aad());
            byte[] plain=cipher.doFinal(java.util.Arrays.copyOfRange(encrypted,20,encrypted.length));
            assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(plain)).isEqualTo(stored.get("plaintext_sha256"));
            Map<String,byte[]> files=new LinkedHashMap<>();
            try(var zip=new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(plain),StandardCharsets.UTF_8)) {
                for(var entry=zip.getNextEntry();entry!=null;entry=zip.getNextEntry())files.put(entry.getName(),zip.readAllBytes());
            }
            byte[] manifestBytes=files.get("manifest.json");
            assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(manifestBytes)).isEqualTo(stored.get("manifest_sha256"));
            JsonNode manifest=objectMapper.readTree(manifestBytes);Map<String,byte[]> payloads=new LinkedHashMap<>();
            for(var file:manifest.path("files")) {
                byte[] content=files.get(file.path("path").asText());
                assertThat(content).hasSize(file.path("sizeBytes").asInt());
                assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(content)).isEqualTo(file.path("sha256").asText());
                payloads.put(file.path("role").asText()+":"+file.path("ordinal").asInt(),content);
            }
            return new DecodedPackage(manifest,payloads);
        } finally { java.util.Arrays.fill(key,(byte)0); }
    }
    private static void assertSamePayloadBytes(DecodedPackage formal,DecodedPackage preview) {
        assertThat(preview.payloads().keySet()).containsExactlyInAnyOrderElementsOf(formal.payloads().keySet());
        for(var entry:formal.payloads().entrySet())assertThat(preview.payloads().get(entry.getKey())).containsExactly(entry.getValue());
    }
    private record DecodedPackage(JsonNode manifest,Map<String,byte[]> payloads) {}
    private void verifyTicketDelivery(AdminTestSession admin,long wallpaperId,long versionId,String type) throws Exception {
        var signing=resourceSigningKey(); var encryption=resourceSigningKey();
        var registered=http.postForEntity("/api/v1/device/registrations",androidRegistration(signing),JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String credential=registered.getBody().path("credentialKeyId").asText();
        JsonNode challenge=http.postForEntity("/api/v1/device/session-challenges",Map.of("credentialKeyId",credential),JsonNode.class).getBody();
        String timestamp=Instant.now().toString();
        String proof=androidSign(signing,"QJ-DEVICE-SESSION-V1\n"+credential+"\n"+challenge.path("challengeId").asText()+"\n"+challenge.path("nonce").asText()+"\n"+timestamp);
        var session=http.postForEntity("/api/v1/device/sessions",Map.of("credentialKeyId",credential,"challengeId",challenge.path("challengeId").asText(),"clientTimestamp",timestamp,"proof",proof),JsonNode.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String token=session.getBody().path("accessToken").asText(); long deviceId=androidIdentity.requireSession(token).deviceId();
        String pem=new com.qingjing.wallpaper.device.AndroidCredentialProof(objectMapper).canonicalPem((java.security.interfaces.RSAPublicKey)encryption.getPublic());
        String bindPath="/api/v1/device/encryption-key";
        assertThat(http.exchange(bindPath,HttpMethod.PUT,androidSignedEntity(signing,token,"PUT",bindPath,objectMapper.writeValueAsString(Map.of("publicKeyPem",pem))),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        var previewHeaders=verifyUnownedPreview(signing,encryption,token,deviceId,wallpaperId,versionId,type);
        String ticketPath="/api/v1/device/wallpapers/"+wallpaperId+"/download-tickets";
        String body=objectMapper.writeValueAsString(Map.of("platform","ANDROID","osVersion","35","supportedResourceTypes",List.of(type)));
        assertThat(http.exchange(ticketPath,HttpMethod.POST,androidSignedEntity(signing,token,"POST",ticketPath,body),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders batchHeaders=headers(admin,true,null);batchHeaders.setContentType(MediaType.APPLICATION_JSON);batchHeaders.set("Idempotency-Key",UUID.randomUUID().toString());
        var batch=http.exchange("/api/v1/admin/code-batches",HttpMethod.POST,new HttpEntity<>(Map.of("name","secure-delivery-fixture","generatedCount",1,"quotaPerCode",1),batchHeaders),JsonNode.class).getBody();
        HttpHeaders delivery=headers(admin,false,null);delivery.set("X-Delivery-Ticket",batch.path("deliveryTicket").asText());
        byte[] csv=http.exchange("/api/v1/admin/code-batches/"+batch.path("batch").path("id").asText()+"/delivery",HttpMethod.GET,new HttpEntity<>(delivery),byte[].class).getBody();
        String code=new String(csv,StandardCharsets.UTF_8).lines().skip(1).findFirst().orElseThrow().split(",")[1];
        assertThat(androidRedemption(signing,token,UUID.randomUUID().toString(),Map.of("wallpaperId",Long.toString(wallpaperId),"code",code)).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String wrongPlatform=body.replace("ANDROID","IOS");
        assertThat(http.exchange(ticketPath,HttpMethod.POST,androidSignedEntity(signing,token,"POST",ticketPath,wrongPlatform),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        jdbc.update("UPDATE wallpaper_variant SET minimum_os_version='36' WHERE wallpaper_id=?",wallpaperId);
        assertThat(http.exchange(ticketPath,HttpMethod.POST,androidSignedEntity(signing,token,"POST",ticketPath,body),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        jdbc.update("UPDATE wallpaper_variant SET minimum_os_version=NULL WHERE wallpaper_id=?",wallpaperId);
        var issued=http.exchange(ticketPath,HttpMethod.POST,androidSignedEntity(signing,token,"POST",ticketPath,body),JsonNode.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        JsonNode descriptor=issued.getBody(), metadata=descriptor.path("package"); String ticket=descriptor.path("ticket").asText();
        assertThat(descriptor.path("deliveryMode").asText()).isEqualTo("SECURE_PACKAGE");
        assertThat(descriptor.path("resourceVersion").path("id").asLong()).isEqualTo(versionId);
        assertThat(descriptor.path("downloadUrl").asText()).isEqualTo("/api/v1/delivery/files");
        assertThat(descriptor.toString()).doesNotContain("storage_key","content_key_ciphertext",".runtime","objects/");
        String redisKey="download-ticket-v2:"+securityCrypto.hmacHex("download-ticket-v2",ticket);
        assertThat(redis.getExpire(redisKey)).isBetween(1L,90L);
        HttpHeaders download=new HttpHeaders();download.setBearerAuth(ticket);
        download.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var bytes=http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),byte[].class);
        assertThat(bytes.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(bytes.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_OCTET_STREAM);
        assertThat(bytes.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(bytes.getBody())).isEqualTo(metadata.path("encryptedSha256").asText());
        var unwrap=javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        unwrap.init(javax.crypto.Cipher.DECRYPT_MODE,encryption.getPrivate(),new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA1,javax.crypto.spec.PSource.PSpecified.DEFAULT));
        byte[] contentKey=unwrap.doFinal(Base64.getUrlDecoder().decode(metadata.path("wrappedContentKey").asText()));
        var decrypt=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        decrypt.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(contentKey,"AES"),new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOfRange(bytes.getBody(),8,20)));
        decrypt.updateAAD(new com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Identity(wallpaperId,descriptor.path("resourceVersion").path("variantId").asLong(),2,type).aad());
        assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(decrypt.doFinal(java.util.Arrays.copyOfRange(bytes.getBody(),20,bytes.getBody().length)))).isEqualTo(metadata.path("plaintextSha256").asText());
        jdbc.update("UPDATE device_entitlement SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6),revoke_reason='fixture' WHERE device_id=? AND wallpaper_id=?",deviceId,wallpaperId);
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(previewHeaders),byte[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        jdbc.update("UPDATE device_entitlement SET status='ACTIVE',revoked_at=NULL,revoke_reason=NULL WHERE device_id=? AND wallpaper_id=?",deviceId,wallpaperId);
        jdbc.update("UPDATE wallpaper SET status='OFFLINE' WHERE id=?",wallpaperId);
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(previewHeaders),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE wallpaper SET status='PUBLISHED' WHERE id=?",wallpaperId);
        jdbc.update("UPDATE device_credential SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6) WHERE credential_key_id=?",credential);
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(previewHeaders),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE device_credential SET status='ACTIVE',revoked_at=NULL WHERE credential_key_id=?",credential);
        jdbc.update("UPDATE resource_version SET status='RETIRED',retired_at=UTC_TIMESTAMP(6) WHERE id=?",versionId);
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(previewHeaders),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        jdbc.update("UPDATE resource_version SET status='PUBLISHED',retired_at=NULL WHERE id=?",versionId);
        if (type.equals("STATIC_IMAGE")) {
            boolean limited=false;
            for(int attempt=0;attempt<13;attempt++) {
                var read=http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),byte[].class);
                if(read.getStatusCode()==HttpStatus.TOO_MANY_REQUESTS) { limited=true; break; }
                assertThat(read.getStatusCode()).isEqualTo(HttpStatus.OK);
            }
            assertThat(limited).isTrue();
            boolean issuanceLimited=false;
            for(int attempt=0;attempt<35;attempt++) {
                var result=http.exchange(ticketPath,HttpMethod.POST,androidSignedEntity(signing,token,"POST",ticketPath,body),JsonNode.class);
                if(result.getStatusCode()==HttpStatus.TOO_MANY_REQUESTS) { issuanceLimited=true; break; }
                assertThat(result.getStatusCode()).isEqualTo(HttpStatus.CREATED);
            }
            assertThat(issuanceLimited).isTrue();
        }
        redis.expire(redisKey,Duration.ZERO);
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(download),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String previewToken=previewHeaders.getFirst(HttpHeaders.AUTHORIZATION).substring(7);
        redis.delete("preview-ticket-v1:"+securityCrypto.hmacHex("preview-ticket-v1",previewToken));
        assertThat(http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(previewHeaders),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    private HttpHeaders verifyUnownedPreview(java.security.KeyPair signing,java.security.KeyPair encryption,String token,
            long deviceId,long wallpaperId,long versionId,String type) throws Exception {
        int entitlementCount=jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement WHERE device_id=?",Integer.class,deviceId);
        int redemptionCount=jdbc.queryForObject("SELECT COUNT(*) FROM redemption_event WHERE device_id=?",Integer.class,deviceId);
        String path="/api/v1/device/wallpapers/"+wallpaperId+"/preview-tickets";
        String json=objectMapper.writeValueAsString(Map.of("platform","ANDROID","osVersion","35","resourceType",type));
        HttpHeaders bearerOnly=new HttpHeaders();bearerOnly.setBearerAuth(token);bearerOnly.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange(path,HttpMethod.POST,new HttpEntity<>(json,bearerOnly),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        var signed=androidSignedEntity(signing,token,"POST",path,json);
        var issued=http.exchange(path,HttpMethod.POST,signed,JsonNode.class);
        assertThat(issued.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(http.exchange(path,HttpMethod.POST,signed,JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        var tampered=androidSignedEntity(signing,token,"POST",path,json);
        assertThat(http.exchange(path,HttpMethod.POST,new HttpEntity<>(json+" ",tampered.getHeaders()),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        JsonNode descriptor=issued.getBody(),metadata=descriptor.path("package");
        assertThat(descriptor.path("purpose").asText()).isEqualTo("APP_PREVIEW");
        assertThat(descriptor.path("deliveryMode").asText()).isEqualTo("APP_PREVIEW");
        assertThat(descriptor.path("durationSeconds").asInt()).isEqualTo(120);
        assertThat(metadata.path("formatVersion").asInt()).isEqualTo(3);
        assertThat(descriptor.path("resourceVersion").path("id").asLong()).isEqualTo(versionId);
        assertThat(descriptor.toString()).doesNotContain("storage_key","content_key_ciphertext",".runtime","objects/");
        String previewToken=descriptor.path("ticket").asText();
        assertThat(redis.getExpire("preview-ticket-v1:"+securityCrypto.hmacHex("preview-ticket-v1",previewToken))).isBetween(1L,90L);
        HttpHeaders preview=new HttpHeaders();preview.setBearerAuth(previewToken);
        preview.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM));
        assertThat(http.exchange("/api/v1/delivery/files",HttpMethod.GET,new HttpEntity<>(preview),JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var response=http.exchange("/api/v1/preview/files",HttpMethod.GET,new HttpEntity<>(preview),byte[].class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");
        byte[] encrypted=response.getBody();
        assertThat(new String(encrypted,0,8,StandardCharsets.US_ASCII)).isEqualTo("QJPV0001");
        assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(encrypted)).isEqualTo(metadata.path("encryptedSha256").asText());
        var unwrap=javax.crypto.Cipher.getInstance("RSA/ECB/OAEPPadding");
        unwrap.init(javax.crypto.Cipher.DECRYPT_MODE,encryption.getPrivate(),new javax.crypto.spec.OAEPParameterSpec("SHA-256","MGF1",java.security.spec.MGF1ParameterSpec.SHA1,javax.crypto.spec.PSource.PSpecified.DEFAULT));
        byte[] contentKey=unwrap.doFinal(Base64.getUrlDecoder().decode(metadata.path("wrappedContentKey").asText()));
        try {
            var identity=new com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.Identity(wallpaperId,descriptor.path("resourceVersion").path("variantId").asLong(),2,type);
            var decrypt=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            decrypt.init(javax.crypto.Cipher.DECRYPT_MODE,new javax.crypto.spec.SecretKeySpec(contentKey,"AES"),new javax.crypto.spec.GCMParameterSpec(128,java.util.Arrays.copyOfRange(encrypted,8,20)));
            decrypt.updateAAD(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.previewAad(identity));
            assertThat(com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec.sha256(decrypt.doFinal(java.util.Arrays.copyOfRange(encrypted,20,encrypted.length)))).isEqualTo(metadata.path("plaintextSha256").asText());
        } finally { java.util.Arrays.fill(contentKey,(byte)0); }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement WHERE device_id=?",Integer.class,deviceId)).isEqualTo(entitlementCount).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM redemption_event WHERE device_id=?",Integer.class,deviceId)).isEqualTo(redemptionCount).isZero();
        return preview;
    }

    private byte[] testVideo() throws Exception {
        var output=java.nio.file.Files.createTempFile("qj-test-video-",".mp4");
        try {
            String executable=System.getenv().getOrDefault("QJ_FFMPEG","ffmpeg");
            Process process=new ProcessBuilder(executable,"-v","error","-y","-f","lavfi","-i","testsrc2=size=64x64:rate=4","-t","1","-c:v","libx264","-pix_fmt","yuv420p","-threads","1",output.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            try { assertThat(process.waitFor(30,java.util.concurrent.TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero(); }
            finally { if(process.isAlive()) process.destroyForcibly(); }
            return java.nio.file.Files.readAllBytes(output);
        } finally { java.nio.file.Files.deleteIfExists(output); }
    }

    private long createPublishedWallpaperFixture() {
        String token = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Long adminId = jdbc.queryForObject("SELECT id FROM admin_account WHERE singleton_key = 1", Long.class);
        jdbc.update(
                """
                INSERT INTO asset
                    (storage_key, original_filename, mime_type, file_extension, size_bytes, sha256,
                     width_px, height_px, validation_status, created_by_admin_id)
                VALUES (?, ?, 'image/png', 'png', 128, ?, 1080, 2160, 'READY', ?)
                """,
                "integration/wp-p08-" + token + ".png",
                "wp-p08-" + token + ".png",
                securityCrypto.sha256Hex(token),
                adminId);
        Long assetId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update(
                """
                INSERT INTO category (parent_id, level, name, slug, icon_asset_id, sort_order)
                VALUES (NULL, 1, ?, ?, ?, 1)
                """,
                "P08-" + token.substring(0, 8), "p08-" + token, assetId);
        Long categoryId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update(
                """
                INSERT INTO wallpaper
                    (title, slug, kind, category_id, cover_asset_id, sort_order, copyright_note,
                     status, published_at)
                VALUES (?, ?, 'STATIC', ?, ?, 1, 'integration fixture', 'PUBLISHED', UTC_TIMESTAMP(6))
                """,
                "P08 wallpaper " + token.substring(0, 6), "p08-wallpaper-" + token, categoryId, assetId);
        Long wallpaperId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update(
                """
                INSERT INTO wallpaper_variant
                    (wallpaper_id, platform, resource_type, capability_requirements)
                VALUES (?, 'UNIVERSAL', 'STATIC_IMAGE', JSON_ARRAY())
                """,
                wallpaperId);
        Long variantId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        jdbc.update(
                """
                INSERT INTO resource_version
                    (variant_id, version_no, status, manifest_sha256, published_at, created_by_admin_id)
                VALUES (?, 1, 'PUBLISHED', ?, UTC_TIMESTAMP(6), ?)
                """,
                variantId, securityCrypto.sha256Hex("manifest-" + token), adminId);
        return wallpaperId;
    }

    private DeviceTestSession registerAndCreateSession(String evidence) throws Exception {
        ResponseEntity<JsonNode> registration = http.postForEntity(
                "/api/v1/device/registrations",
                Map.of(
                        "platform", "H5_TEST",
                        "appInstallScope", "h5-integration",
                        "credentialType", "H5_TEST_SECRET",
                        "evidenceToken", evidence),
                JsonNode.class);
        assertThat(registration.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String credentialKeyId = registration.getBody().path("credentialKeyId").asText();
        String credentialSecret = registration.getBody().path("credentialSecret").asText();
        assertThat(credentialSecret).hasSizeGreaterThanOrEqualTo(32);
        String storedHash = jdbc.queryForObject(
                "SELECT secret_hash FROM device_credential WHERE credential_key_id = ?",
                String.class,
                credentialKeyId);
        assertThat(storedHash).hasSize(64).isNotEqualTo(credentialSecret);
        return createSession(credentialKeyId, credentialSecret);
    }

    private DeviceTestSession createSession(String credentialKeyId, String credentialSecret) throws Exception {
        ResponseEntity<JsonNode> challenge = http.postForEntity(
                "/api/v1/device/session-challenges",
                Map.of("credentialKeyId", credentialKeyId),
                JsonNode.class);
        assertThat(challenge.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String challengeId = challenge.getBody().path("challengeId").asText();
        String nonce = challenge.getBody().path("nonce").asText();
        String timestamp = Instant.now().toString();
        String payload = "QJ-DEVICE-SESSION-V1\n" + credentialKeyId + "\n" + challengeId + "\n" + nonce + "\n" + timestamp;
        String proof = hmac(credentialSecret, payload);
        ResponseEntity<JsonNode> session = http.postForEntity(
                "/api/v1/device/sessions",
                Map.of(
                        "credentialKeyId", credentialKeyId,
                        "challengeId", challengeId,
                        "clientTimestamp", timestamp,
                        "proof", proof),
                JsonNode.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return new DeviceTestSession(
                credentialKeyId,
                credentialSecret,
                session.getBody().path("accessToken").asText());
    }

    private ResponseEntity<JsonNode> signedPost(
            String path,
            Object body,
            DeviceTestSession device,
            String idempotencyKey) throws Exception {
        String json = objectMapper.writeValueAsString(body);
        String timestamp = Instant.now().toString();
        String nonce = UUID.randomUUID().toString();
        String bodyHash = java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        String payload = "QJ-SIGNED-REQUEST-V1\nPOST\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash;
        HttpHeaders headers = deviceHeaders(device);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Timestamp", timestamp);
        headers.set("X-Request-Nonce", nonce);
        headers.set("X-Request-Signature", hmac(device.credentialSecret(), payload));
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private HttpHeaders deviceHeaders(DeviceTestSession device) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(device.accessToken());
        return headers;
    }

    private static String hmac(String key, String value) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static Map<String, Object> orderedMap(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put(values[index].toString(), values[index + 1]);
        }
        return result;
    }

    private AdminTestSession login() throws Exception {
        ResponseEntity<JsonNode> response = http.postForEntity(
                "/api/v1/admin/sessions",
                Map.of("username", "api-admin", "password", "integration-password-2026"),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String setCookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(setCookie).isNotBlank().contains("HttpOnly", "SameSite=Strict");
        return new AdminTestSession(
                setCookie.substring(0, setCookie.indexOf(';')),
                response.getBody().path("csrfToken").asText());
    }

    private JsonNode uploadAsset(
            AdminTestSession session,
            String purpose,
            String filename,
            byte[] bytes) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("purpose", purpose);
        HttpHeaders fileHeaders = new HttpHeaders();
        fileHeaders.setContentType(purpose.contains("VIDEO") ? MediaType.valueOf("video/mp4") : purpose.equals("PARALLAX_CONFIG") ? MediaType.APPLICATION_JSON : MediaType.IMAGE_PNG);
        fileHeaders.setContentDispositionFormData("file", filename);
        ByteArrayResource resource = new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
        body.add("file", new HttpEntity<>(resource, fileHeaders));
        HttpHeaders headers = headers(session, true, null);
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        ResponseEntity<JsonNode> response = http.exchange(
                "/api/v1/admin/assets",
                HttpMethod.POST,
                new HttpEntity<>(body, headers),
                JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private ResponseEntity<JsonNode> jsonExchange(
            String path,
            HttpMethod method,
            Object body,
            AdminTestSession session,
            String etag) {
        HttpHeaders headers = headers(session, true, etag);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private ResponseEntity<JsonNode> getJson(String path, AdminTestSession session) {
        return http.exchange(
                path,
                HttpMethod.GET,
                new HttpEntity<>(headers(session, false, null)),
                JsonNode.class);
    }

    private HttpHeaders headers(AdminTestSession session, boolean csrf, String etag) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.COOKIE, session.cookie());
        if (csrf) {
            headers.add("X-CSRF-Token", session.csrf());
        }
        if (etag != null) {
            headers.setIfMatch(etag);
        }
        return headers;
    }

    private static byte[] png(int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    @Autowired
    com.qingjing.wallpaper.device.DeviceIdentityService androidIdentity;

    @Autowired
    com.qingjing.wallpaper.delivery.InstallationEncryptionKeys encryptionKeys;

    @Test
    void androidInstallationProofSessionReplayAndRevocationUseRealInfrastructure() throws Exception {
        java.security.KeyPairGenerator generator = java.security.KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair key = generator.generateKeyPair();
        Map<String, Object> registration = androidRegistration(key);
        ResponseEntity<JsonNode> registered = http.postForEntity("/api/v1/device/registrations", registration, JsonNode.class);
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String keyId = registered.getBody().path("credentialKeyId").asText();
        assertThat(registered.getBody().path("credentialType").asText()).isEqualTo("PLATFORM_PUBLIC_KEY");
        assertThat(registered.getBody().path("credentialSecret").isNull() || registered.getBody().path("credentialSecret").isMissingNode()).isTrue();
        ResponseEntity<JsonNode> replay = http.postForEntity("/api/v1/device/registrations", registration, JsonNode.class);
        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(replay.getBody().path("error").path("code").asText()).isEqualTo("REQUEST_NONCE_REUSED");
        ResponseEntity<JsonNode> restored = http.postForEntity("/api/v1/device/registrations", androidRegistration(key), JsonNode.class);
        assertThat(restored.getBody().path("credentialKeyId").asText()).isEqualTo(keyId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_credential WHERE credential_key_id=? AND secret_hash IS NULL", Integer.class, keyId)).isEqualTo(1);
        ResponseEntity<JsonNode> challenge = http.postForEntity("/api/v1/device/session-challenges", Map.of("credentialKeyId", keyId), JsonNode.class);
        assertThat(challenge.getBody().path("algorithm").asText()).isEqualTo("RSA_SHA256");
        String timestamp = Instant.now().toString();
        String challengeId = challenge.getBody().path("challengeId").asText();
        String payload = "QJ-DEVICE-SESSION-V1\n" + keyId + "\n" + challengeId + "\n" + challenge.getBody().path("nonce").asText() + "\n" + timestamp;
        Map<String, Object> sessionBody = Map.of("credentialKeyId",keyId,"challengeId",challengeId,"clientTimestamp",timestamp,"proof",androidSign(key,payload));
        ResponseEntity<JsonNode> session = http.postForEntity("/api/v1/device/sessions", sessionBody, JsonNode.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(session.getBody().path("platform").asText()).isEqualTo("ANDROID");
        assertThat(http.postForEntity("/api/v1/device/sessions", sessionBody, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        String token = session.getBody().path("accessToken").asText();
        var principal = androidIdentity.requireSession(token);
        var boundPrincipal = new com.qingjing.wallpaper.device.DevicePrincipal(principal.deviceId(), principal.credentialKeyId(), principal.platform(), principal.credentialType());
        var encryptionPair = generator.generateKeyPair();
        var keyParser = new com.qingjing.wallpaper.device.AndroidCredentialProof(objectMapper);
        String encryptionPem = keyParser.canonicalPem((java.security.interfaces.RSAPublicKey) encryptionPair.getPublic());
        String bindingPath = "/api/v1/device/encryption-key";
        String bindingJson = objectMapper.writeValueAsString(Map.of("publicKeyPem", encryptionPem));
        assertThat(http.exchange(bindingPath, HttpMethod.PUT, new HttpEntity<>(Map.of("publicKeyPem", encryptionPem)), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        HttpHeaders bearerOnly = new HttpHeaders(); bearerOnly.setBearerAuth(token); bearerOnly.setContentType(MediaType.APPLICATION_JSON);
        assertThat(http.exchange(bindingPath, HttpMethod.PUT, new HttpEntity<>(bindingJson, bearerOnly), JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        HttpEntity<String> signedBinding = androidSignedEntity(key, token, "PUT", bindingPath, bindingJson);
        ResponseEntity<JsonNode> bound = http.exchange(bindingPath, HttpMethod.PUT, signedBinding, JsonNode.class);
        assertThat(bound.getStatusCode()).isEqualTo(HttpStatus.OK);
        String encryptionFingerprint = bound.getBody().path("publicKeySha256").asText();
        assertThat(bound.getBody().path("keyAlgorithm").asText()).isEqualTo("RSA-OAEP-SHA256-MGF1-SHA1");
        assertThat(http.exchange(bindingPath, HttpMethod.PUT, signedBinding, JsonNode.class).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        HttpEntity<String> tamperedBinding = androidSignedEntity(key, token, "PUT", bindingPath, bindingJson);
        ResponseEntity<JsonNode> tamperedResponse = http.exchange(bindingPath, HttpMethod.PUT, new HttpEntity<>(bindingJson + " ", tamperedBinding.getHeaders()), JsonNode.class);
        assertThat(tamperedResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(tamperedResponse.getBody().path("error").path("code").asText()).isEqualTo("REQUEST_SIGNATURE_INVALID");
        assertThat(http.exchange(bindingPath, HttpMethod.PUT, androidSignedEntity(key, token, "PUT", bindingPath, bindingJson), JsonNode.class).getBody()).isEqualTo(bound.getBody());
        assertThat(encryptionKeys.bind(boundPrincipal, encryptionPem)).isEqualTo(encryptionFingerprint);
        assertThat(encryptionKeys.require(boundPrincipal).getEncoded()).isEqualTo(encryptionPair.getPublic().getEncoded());
        String replacementPem = keyParser.canonicalPem((java.security.interfaces.RSAPublicKey) generator.generateKeyPair().getPublic());
        assertThatThrownBy(() -> encryptionKeys.bind(boundPrincipal, replacementPem))
                .isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class)
                .extracting(e -> ((com.qingjing.wallpaper.shared.web.ApiException)e).code()).isEqualTo("STATE_CONFLICT");
        assertThat(androidIdentity.requireSession(token).credentialKeyId()).isEqualTo(keyId);

        String nonce = UUID.randomUUID().toString();
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        String signedPayload = "QJ-SIGNED-REQUEST-V1\nPOST\n/api/v1/device/redemptions\n"+timestamp+"\n"+nonce+"\n"+java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
        String signature = androidSign(key,signedPayload);
        assertThatThrownBy(() -> androidIdentity.verifySignedRequest(principal,"POST","/api/v1/device/redemptions",timestamp,nonce,"tampered".getBytes(StandardCharsets.UTF_8),signature))
                .isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        androidIdentity.verifySignedRequest(principal,"POST","/api/v1/device/redemptions",timestamp,nonce,body,signature);
        assertThatThrownBy(() -> androidIdentity.verifySignedRequest(principal,"POST","/api/v1/device/redemptions",timestamp,nonce,body,signature))
                .isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class)
                .extracting(e -> ((com.qingjing.wallpaper.shared.web.ApiException)e).code()).isEqualTo("REQUEST_NONCE_REUSED");
        Long nonceTtl = redis.getExpire("device:signed-nonce:"+keyId+":"+nonce);
        assertThat(nonceTtl).isGreaterThan(590L);
        ensureAdmin();
        AdminTestSession admin = login();
        long wallpaperId = createPublishedWallpaperFixture();
        JsonNode packageAsset=uploadAsset(admin,"STATIC_IMAGE","android-fixture.png",png(4,3));
        long packageVariant=jdbc.queryForObject("SELECT id FROM wallpaper_variant WHERE wallpaper_id=?",Long.class,wallpaperId);
        long packageVersion=jsonExchange("/api/v1/admin/variants/"+packageVariant+"/resource-versions",HttpMethod.POST,
                Map.of("versionNo",2,"bindings",List.of(Map.of("assetId",packageAsset.path("id").asText(),"role","STATIC_IMAGE","ordinal",0))),admin,null).getBody().path("id").asLong();
        assertThat(jsonExchange("/api/v1/admin/resource-versions/"+packageVersion+"/secure-package",HttpMethod.POST,Map.of(),admin,null).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(jsonExchange("/api/v1/admin/wallpapers/"+wallpaperId+"/publish",HttpMethod.POST,Map.of("resourceVersionIds",List.of(Long.toString(packageVersion))),admin,"\"0\"").getStatusCode()).isEqualTo(HttpStatus.OK);
        HttpHeaders batchHeaders = headers(admin, true, null);
        batchHeaders.setContentType(MediaType.APPLICATION_JSON);
        batchHeaders.set("Idempotency-Key", UUID.randomUUID().toString());
        ResponseEntity<JsonNode> batch = http.exchange("/api/v1/admin/code-batches", HttpMethod.POST,
                new HttpEntity<>(Map.of("name", "Android A04", "generatedCount", 1, "quotaPerCode", 1), batchHeaders), JsonNode.class);
        assertThat(batch.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        String batchId = batch.getBody().path("batch").path("id").asText();
        HttpHeaders deliveryHeaders = headers(admin, false, null);
        deliveryHeaders.set("X-Delivery-Ticket", batch.getBody().path("deliveryTicket").asText());
        ResponseEntity<byte[]> delivered = http.exchange("/api/v1/admin/code-batches/" + batchId + "/delivery",
                HttpMethod.GET, new HttpEntity<>(deliveryHeaders), byte[].class);
        assertThat(delivered.getStatusCode()).isEqualTo(HttpStatus.OK);
        String code = new String(delivered.getBody(), StandardCharsets.UTF_8).lines().skip(1).findFirst().orElseThrow().split(",")[1];
        String requestKey = UUID.randomUUID().toString();
        Map<String, Object> redemption = orderedMap("wallpaperId", Long.toString(wallpaperId), "code", code);
        ResponseEntity<JsonNode> granted = androidRedemption(key, token, requestKey, redemption);
        assertThat(granted.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(granted.getBody().path("quotaDelta").asInt()).isEqualTo(1);
        assertThat(androidRedemption(key, token, requestKey, redemption).getBody()).isEqualTo(granted.getBody());
        HttpHeaders auth = new HttpHeaders(); auth.setBearerAuth(token);
        assertThat(http.exchange("/api/v1/device/redemptions/" + requestKey, HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class).getBody()).isEqualTo(granted.getBody());
        assertThat(androidRedemption(key, token, UUID.randomUUID().toString(), redemption).getBody().path("result").asText()).isEqualTo("ALREADY_OWNED");
        assertThat(jdbc.queryForObject("SELECT used_quota FROM redemption_code WHERE batch_id = ?", Integer.class, Long.parseLong(batchId))).isEqualTo(1);
        assertThat(http.exchange("/api/v1/device/me/entitlements", HttpMethod.GET, new HttpEntity<>(auth), JsonNode.class).getBody().path("items")).hasSize(1);
        long unpackaged = createPublishedWallpaperFixture();
        ResponseEntity<JsonNode> noPackage=androidRedemption(key,token,UUID.randomUUID().toString(),orderedMap("wallpaperId",Long.toString(unpackaged),"code",code));
        assertThat(noPackage.getBody().path("result").asText()).isEqualTo("WALLPAPER_UNAVAILABLE");
        assertThat(noPackage.getBody().path("quotaDelta").asInt()).isZero();
        long unavailable = createPublishedWallpaperFixture();
        jdbc.update("UPDATE wallpaper_variant SET platform='IOS',resource_type='LIVE_PHOTO' WHERE wallpaper_id=?", unavailable);
        ResponseEntity<JsonNode> rejected = androidRedemption(key, token, UUID.randomUUID().toString(), orderedMap("wallpaperId", Long.toString(unavailable), "code", code));
        assertThat(rejected.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(rejected.getBody().path("result").asText()).isEqualTo("WALLPAPER_UNAVAILABLE");
        assertThat(rejected.getBody().path("quotaDelta").asInt()).isZero();
        jdbc.update("UPDATE device_credential SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6) WHERE credential_key_id=?", keyId);
        assertThatThrownBy(() -> androidIdentity.requireSession(token)).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        ResponseEntity<JsonNode> revoked = http.postForEntity("/api/v1/device/registrations", androidRegistration(key), JsonNode.class);
        assertThat(revoked.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(revoked.getBody().path("error").path("code").asText()).isEqualTo("CREDENTIAL_REVOKED");
        ResponseEntity<JsonNode> newInstallation = http.postForEntity("/api/v1/device/registrations", androidRegistration(generator.generateKeyPair()), JsonNode.class);
        assertThat(newInstallation.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(newInstallation.getBody().path("credentialKeyId").asText()).isNotEqualTo(keyId);
    }

    private HttpEntity<String> androidSignedEntity(java.security.KeyPair key, String token, String method, String path, String json) throws Exception {
        String timestamp = Instant.now().toString(), nonce = UUID.randomUUID().toString();
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Request-Timestamp", timestamp); headers.set("X-Request-Nonce", nonce);
        headers.set("X-Request-Signature", androidSign(key, "QJ-SIGNED-REQUEST-V1\n" + method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + hash));
        return new HttpEntity<>(json, headers);
    }

    private ResponseEntity<JsonNode> androidRedemption(java.security.KeyPair key, String token, String requestKey, Map<String, Object> body) throws Exception {
        String json = objectMapper.writeValueAsString(body), timestamp = Instant.now().toString(), nonce = UUID.randomUUID().toString();
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.getBytes(StandardCharsets.UTF_8)));
        HttpHeaders headers = new HttpHeaders(); headers.setBearerAuth(token); headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", requestKey); headers.set("X-Request-Timestamp", timestamp); headers.set("X-Request-Nonce", nonce);
        headers.set("X-Request-Signature", androidSign(key, "QJ-SIGNED-REQUEST-V1\nPOST\n/api/v1/device/redemptions\n" + timestamp + "\n" + nonce + "\n" + hash));
        return http.exchange("/api/v1/device/redemptions", HttpMethod.POST, new HttpEntity<>(json, headers), JsonNode.class);
    }

    private Map<String, Object> androidRegistration(java.security.KeyPair key) throws Exception {
        String scope="android-integration", timestamp=Instant.now().toString(), nonce=UUID.randomUUID().toString();
        byte[] encoded=key.getPublic().getEncoded();
        String fingerprint=java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
        String payload="QJ-ANDROID-REGISTER-V1\n"+scope+"\n"+fingerprint+"\n"+timestamp+"\n"+nonce;
        String evidence=Base64.getUrlEncoder().withoutPadding().encodeToString(objectMapper.writeValueAsBytes(Map.of("timestamp",timestamp,"nonce",nonce,"proof",androidSign(key,payload))));
        String pem="-----BEGIN PUBLIC KEY-----\n"+Base64.getMimeEncoder(64,new byte[]{10}).encodeToString(encoded)+"\n-----END PUBLIC KEY-----";
        return Map.of("platform","ANDROID","appInstallScope",scope,"credentialType","PLATFORM_PUBLIC_KEY","publicKeyPem",pem,"evidenceToken",evidence);
    }
    private static String androidSign(java.security.KeyPair key, String payload) throws Exception {
        java.security.Signature signer=java.security.Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate()); signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }

    private record AdminTestSession(String cookie, String csrf) {
    }

    private record DeviceTestSession(String credentialKeyId, String credentialSecret, String accessToken) {
    }
}
