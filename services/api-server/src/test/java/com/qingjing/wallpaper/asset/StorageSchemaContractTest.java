package com.qingjing.wallpaper.asset;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class StorageSchemaContractTest {

    @Test
    void assetTableStoresOnlyRelativeStorageKeyMetadata() throws IOException {
        String migration;
        try (var input = getClass().getResourceAsStream("/db/migration/V1__create_core_schema.sql")) {
            assertThat(input).isNotNull();
            migration = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();
        }

        String assetTable = migration.substring(
                migration.indexOf("create table asset"),
                migration.indexOf("create table category"));
        assertThat(assetTable).contains("storage_key");
        assertThat(assetTable)
                .doesNotContain("absolute_path")
                .doesNotContain("file_path")
                .doesNotContain("filesystem_path");
    }
}
