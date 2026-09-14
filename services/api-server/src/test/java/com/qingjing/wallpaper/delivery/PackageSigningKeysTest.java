package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.shared.web.ApiException;
import java.security.KeyPairGenerator;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PackageSigningKeysTest {
    @Test void absentConfigurationKeepsSecureDeliveryDisabled() {
        var keys = new PackageSigningKeys("", "");
        assertThatThrownBy(keys::privateKey).isInstanceOf(ApiException.class);
    }
    @Test void malformedConfigurationFailsWithoutIncludingItsValue() {
        assertThatThrownBy(() -> new PackageSigningKeys("test", "not-a-real-private-key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Invalid resource signing configuration").hasNoCause();
    }
    @Test void configuredTrustRootPreservesTheExactKey() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var pair = generator.generateKeyPair();
        var keys = new PackageSigningKeys("local-test-1", Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()));
        assertThat(keys.keyId()).isEqualTo("local-test-1");
        assertThat(keys.privateKey().getEncoded()).isEqualTo(pair.getPrivate().getEncoded());
    }
}
