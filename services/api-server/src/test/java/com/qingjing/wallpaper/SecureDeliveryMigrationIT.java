package com.qingjing.wallpaper;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import java.sql.DriverManager;
import java.util.UUID;
import static org.assertj.core.api.Assertions.assertThat;

class SecureDeliveryMigrationIT {
    @Test void additiveMigrationPreservesAnExistingInstallation() throws Exception {
        try (var mysql = new MySQLContainer<>("mysql:8.4").withDatabaseName("upgrade_test")
                .withUsername("upgrade_test").withPassword(UUID.randomUUID().toString())) {
            mysql.start();
            Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target("1").load().migrate();
            String publicId = UUID.randomUUID().toString(), credentialId = UUID.randomUUID().toString();
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())) {
                try (var insert = connection.prepareStatement("""
                        INSERT INTO anonymous_device (public_id,platform,app_install_scope,evidence_hash,status,last_seen_at)
                        VALUES (?,'ANDROID','upgrade-test',?,'ACTIVE',UTC_TIMESTAMP(6))
                        """)) {
                    insert.setString(1, publicId); insert.setString(2, "a".repeat(64)); insert.executeUpdate();
                }
                try (var insert = connection.prepareStatement("""
                        INSERT INTO device_credential (device_id,credential_key_id,credential_type,public_key_pem,status)
                        SELECT id,?,'PLATFORM_PUBLIC_KEY','migration-preservation-sentinel','ACTIVE' FROM anonymous_device WHERE public_id=?
                        """)) {
                    insert.setString(1, credentialId); insert.setString(2, publicId); insert.executeUpdate();
                }
            }
            var migrated = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword())
                    .locations("classpath:db/migration").target("2").load().migrate();
            assertThat(migrated.migrationsExecuted).isEqualTo(1);
            var previewMigration=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            assertThat(previewMigration.migrationsExecuted).isEqualTo(1);
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
                var query=connection.createStatement();var result=query.executeQuery("SELECT COUNT(*) FROM preview_resource_package")) {
                assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
            }
            try (var connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
                 var query = connection.prepareStatement("""
                         SELECT d.public_id,c.credential_key_id,c.public_key_pem FROM device_credential c
                         JOIN anonymous_device d ON d.id=c.device_id WHERE c.credential_key_id=?
                         """)) {
                query.setString(1, credentialId);
                try (var result = query.executeQuery()) {
                    assertThat(result.next()).isTrue();
                    assertThat(result.getString(1)).isEqualTo(publicId);
                    assertThat(result.getString(2)).isEqualTo(credentialId);
                    assertThat(result.getString(3)).isEqualTo("migration-preservation-sentinel");
                    assertThat(result.next()).isFalse();
                }
            }
        }
    }
}
