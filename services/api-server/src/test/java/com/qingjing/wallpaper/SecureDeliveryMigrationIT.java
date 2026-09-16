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
                .withUsername("upgrade_test").withPassword(UUID.randomUUID().toString())
                .withStartupTimeout(java.time.Duration.ofMinutes(5)).withStartupTimeoutSeconds(300)) {
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
            var throughV5=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                    .locations("classpath:db/migration").target("5").load().migrate();
            assertThat(throughV5.migrationsExecuted).isEqualTo(3);
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
                var statements=connection.createStatement()) {
                statements.executeUpdate("INSERT INTO admin_account(singleton_key,username,password_hash,password_changed_at) VALUES(1,'migration-admin',REPEAT('a',60),UTC_TIMESTAMP(6))");
                for(int i=1;i<=4;i++) statements.executeUpdate("""
                        INSERT INTO asset(storage_key,original_filename,mime_type,file_extension,purpose,size_bytes,sha256,width_px,height_px,validation_status,created_by_admin_id)
                        SELECT 'legacy-object-%1$d','legacy-%1$d.png',IF(%1$d=2,'application/json','image/png'),IF(%1$d=2,'json','png'),
                               CASE %1$d WHEN 1 THEN 'WALLPAPER_COVER' WHEN 2 THEN 'PARALLAX_CONFIG' WHEN 3 THEN 'FOREGROUND' ELSE 'BACKGROUND' END,
                               10,REPEAT('%2$s',64),IF(%1$d=2,NULL,512),IF(%1$d=2,NULL,512),'READY',id
                        FROM admin_account WHERE singleton_key=1
                        """.formatted(i,Integer.toHexString(i)));
                statements.executeUpdate("""
                        INSERT INTO parallax_source_package(sha256,original_filename,size_bytes,storage_key,cover_asset_id,config_asset_id,canvas_width,canvas_height,status,created_by_admin_id)
                        SELECT REPEAT('f',64),'legacy-v1.zip',100,'legacy-source-object',
                               (SELECT id FROM asset WHERE storage_key='legacy-object-1'),
                               (SELECT id FROM asset WHERE storage_key='legacy-object-2'),512,512,'READY',id
                        FROM admin_account WHERE singleton_key=1
                        """);
                statements.executeUpdate("""
                        INSERT INTO parallax_source_layer(source_package_id,layer_index,asset_id,original_filename,role,ordinal,depth,scale,opacity,blend_mode)
                        SELECT p.id,1,a.id,'layers/01.png','FOREGROUND',0,1,1.1,1,'normal'
                        FROM parallax_source_package p JOIN asset a ON a.storage_key='legacy-object-3'
                        """);
                statements.executeUpdate("""
                        INSERT INTO parallax_source_layer(source_package_id,layer_index,asset_id,original_filename,role,ordinal,depth,scale,opacity,blend_mode)
                        SELECT p.id,2,a.id,'layers/02.png','BACKGROUND',0,0,1,1,'normal'
                        FROM parallax_source_package p JOIN asset a ON a.storage_key='legacy-object-4'
                        """);
            }
            var v2Passthrough=Flyway.configure().dataSource(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword())
                    .locations("classpath:db/migration").load().migrate();
            assertThat(v2Passthrough.migrationsExecuted).isEqualTo(2);
            try(var connection=DriverManager.getConnection(mysql.getJdbcUrl(),mysql.getUsername(),mysql.getPassword());
                var query=connection.createStatement()) {
                try (var result=query.executeQuery("SELECT COUNT(*) FROM preview_resource_package")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
                }
                try (var result=query.executeQuery("SELECT COUNT(*) FROM wallpaper_setting_tutorial")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(5);
                }
                try (var result=query.executeQuery("SELECT COUNT(*) FROM parallax_source_package")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
                }
                try (var result=query.executeQuery("SELECT COUNT(*) FROM asset WHERE storage_key LIKE 'legacy-object-%'")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isZero();
                }
                try (var result=query.executeQuery("SELECT COUNT(*) FROM parallax_storage_cleanup")) {
                    assertThat(result.next()).isTrue();assertThat(result.getInt(1)).isEqualTo(5);
                }
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
