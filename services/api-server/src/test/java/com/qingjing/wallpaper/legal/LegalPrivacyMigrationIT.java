package com.qingjing.wallpaper.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LegalPrivacyMigrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("privacy_migration_test").withUsername("privacy_test")
            .withPassword(UUID.randomUUID().toString());
    private JdbcTemplate jdbc;
    private final ObjectMapper json = new ObjectMapper();

    @BeforeEach void beforeMigration() {
        var flyway = migrations(null);
        flyway.clean(); // This is the isolated Testcontainers database, never an application database.
        migrations("27").migrate();
        jdbc = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()));
    }

    @Test void updatesTheUntouchedDraftAndRequiresConsentWithoutPublishing() throws Exception {
        String published = publishedPrivacy();
        migrations(null).migrate();
        var draft = json.readTree(jdbc.queryForObject("SELECT draft_json FROM legal_document WHERE document_key='privacy'", String.class));
        try (var stream = getClass().getResourceAsStream("/legal/privacy-2026-10-10.json")) {
            assertThat(stream).isNotNull();
            assertThat(draft).isEqualTo(json.readTree(new String(stream.readAllBytes(), StandardCharsets.UTF_8)));
        }
        assertThat(draft.toString()).contains("吴美涵", "qingjingwallpaper@126.com", "吉意", "设备活跃", "SafetyDetect", "REQUEST_INSTALL_PACKAGES", "人工复核");
        assertDraftOnly(published);
    }

    @Test void preservesAdministratorEditsAndAppendsTheCurrentAppDisclosure() throws Exception {
        String published = publishedPrivacy();
        jdbc.update("""
                UPDATE legal_document SET lock_version=8,
                draft_json=JSON_SET(draft_json,'$.introduction','管理员保留的说明')
                WHERE document_key='privacy'
                """);
        migrations(null).migrate();
        var draft = json.readTree(jdbc.queryForObject("SELECT draft_json FROM legal_document WHERE document_key='privacy'", String.class));
        assertThat(draft.path("introduction").asText()).isEqualTo("管理员保留的说明");
        var sections = draft.path("sections");
        assertThat(sections.get(sections.size()-1).path("title").asText()).isEqualTo("当前 App 版本补充说明");
        assertThat(sections.get(sections.size()-1).path("body").asText()).contains("吴美涵", "按日去重", "人工复核");
        assertThat(jdbc.queryForObject("SELECT lock_version FROM legal_document WHERE document_key='privacy'", Long.class)).isEqualTo(9);
        assertDraftOnly(published);
    }

    private Flyway migrations(String target) {
        var configuration = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }
    private String publishedPrivacy() {
        return jdbc.queryForObject("SELECT content_json FROM legal_document_publication WHERE document_key='privacy' AND revision=1", String.class);
    }
    private void assertDraftOnly(String original) {
        assertThat(publishedPrivacy()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT published_revision FROM legal_document WHERE document_key='privacy'", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legal_document_publication WHERE document_key='privacy'", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT requires_reconsent FROM legal_document WHERE document_key='privacy'", Boolean.class)).isTrue();
    }
}
