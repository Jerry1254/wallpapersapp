package com.qingjing.wallpaper.appupdate;

import static org.assertj.core.api.Assertions.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AndroidApkInspectorTest {
    @TempDir Path temp;
    @Test void acceptsTheConfiguredRealReleaseApkWhenSupplied() throws Exception {
        String source = System.getProperty("qj.test.releaseApk");
        org.junit.jupiter.api.Assumptions.assumeTrue(source != null, "Optional locally-built release APK");
        var storage = new LocalFileStorage(temp.resolve("storage"));
        var path = Path.of(source);
        try (var input = Files.newInputStream(path)) {
            var staged = storage.stage(input,260L * 1024 * 1024);
            var result = new AndroidApkInspector(storage,"com.qingjing.bizhi").inspect(staged);
            assertThat(result.packageName()).isEqualTo("com.qingjing.bizhi");
            assertThat(result.versionCode()).isPositive();
            assertThat(result.signerSha256()).matches("[a-f0-9]{64}");
        }
    }
    @Test void validatesTheActualSignatureAndReadsActualManifest() throws Exception {
        var storage = new LocalFileStorage(temp.resolve("storage"));
        var file = ApkTestFixtures.signed(temp,"unsigned-release.apk.fixture",ApkTestFixtures.signer());
        var staged = storage.stage(new ByteArrayInputStream(Files.readAllBytes(file)),10_000_000);
        var result = new AndroidApkInspector(storage,"com.qingjing.bizhi").inspect(staged);
        assertThat(result.packageName()).isEqualTo("com.qingjing.bizhi");
        assertThat(result.versionCode()).isEqualTo(100);
        assertThat(result.versionName()).isEqualTo("1.0.0");
        assertThat(result.minSdkVersion()).isEqualTo(24);
        assertThat(result.signerSha256()).matches("[a-f0-9]{64}");
        assertThat(result.abi()).isEqualTo("universal");
        assertThatThrownBy(() -> new AndroidApkInspector(storage,"different.package").inspect(staged))
                .isInstanceOf(ApiException.class).hasMessageContaining("package name");
    }
    @Test void rejectsUnsignedOrTamperedApksInsteadOfReadingOnlyCertificateFiles() throws Exception {
        var storage = new LocalFileStorage(temp.resolve("storage"));
        byte[] unsigned;
        try (var resource = getClass().getResourceAsStream("/appupdate/unsigned-release.apk.fixture")) { unsigned = resource.readAllBytes(); }
        var staged = storage.stage(new ByteArrayInputStream(unsigned),10_000_000);
        var inspector = new AndroidApkInspector(storage,"com.qingjing.bizhi");
        assertThatThrownBy(() -> inspector.inspect(staged)).isInstanceOf(ApiException.class).hasMessageContaining("signature");
        var signed = ApkTestFixtures.signed(temp,"unsigned-release.apk.fixture",ApkTestFixtures.signer());
        byte[] bytes = Files.readAllBytes(signed);
        bytes[100] ^= 1;
        var tampered = storage.stage(new ByteArrayInputStream(bytes),10_000_000);
        assertThatThrownBy(() -> inspector.inspect(tampered)).isInstanceOf(ApiException.class);
    }
}
