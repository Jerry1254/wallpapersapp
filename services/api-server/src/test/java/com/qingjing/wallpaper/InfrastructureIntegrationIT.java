package com.qingjing.wallpaper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
}
