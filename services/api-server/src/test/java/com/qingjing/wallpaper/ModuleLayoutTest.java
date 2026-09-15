package com.qingjing.wallpaper;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ModuleLayoutTest {

    private static final Set<String> EXPECTED_MODULES = Set.of(
            "adminidentity",
            "asset",
            "audit",
            "catalog",
            "device",
            "delivery",
            "entitlement",
            "redemption",
            "shared",
            "tutorial");

    @Test
    void modulePackagesRemainExplicit() {
        Path sourceRoot = Path.of("src/main/java/com/qingjing/wallpaper");

        assertThat(EXPECTED_MODULES)
                .allSatisfy(module -> assertThat(Files.isRegularFile(sourceRoot.resolve(module).resolve("package-info.java")))
                        .as("module package %s", module)
                        .isTrue());
    }
}
