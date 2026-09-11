package com.qingjing.wallpaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
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

    private static final String MYSQL_PASSWORD = UUID.randomUUID().toString();
    private static final String REDIS_PASSWORD = UUID.randomUUID().toString();

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("wallpaper_app")
            .withUsername("wallpaper_test")
            .withPassword(MYSQL_PASSWORD);

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--requirepass", REDIS_PASSWORD)
            .withStartupTimeout(Duration.ofMinutes(2));

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
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

    @Test
    void emptyDatabaseMigratesToTheSixteenDomainTables() {
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

        assertThat(successfulMigrations).isEqualTo(1);
        assertThat(tables).containsExactlyInAnyOrder(
                "admin_account",
                "anonymous_device",
                "asset",
                "audit_event",
                "category",
                "code_batch",
                "device_credential",
                "device_entitlement",
                "download_event",
                "redemption_code",
                "redemption_event",
                "redemption_request",
                "resource_binding",
                "resource_version",
                "wallpaper",
                "wallpaper_variant");
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
        fileHeaders.setContentType(MediaType.IMAGE_PNG);
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

    private record AdminTestSession(String cookie, String csrf) {
    }
}
