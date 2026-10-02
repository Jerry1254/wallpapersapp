package com.qingjing.wallpaper.iosacquisition;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qingjing.wallpaper.shared.web.ApiException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

class RealIosAppleGatewayTest {
    @Test
    void loadsBundledAppleRootsAndP256Credentials() throws Exception {
        var generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new java.security.spec.ECGenParameterSpec("secp256r1"));
        String key = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(generator.generateKeyPair().getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        var properties = new IosAcquisitionProperties();
        properties.setEnabled(true);
        properties.setTeamId("NFXT4L28FU");
        properties.setAppIdPrefix("NFXT4L28FU");
        properties.setBundleId("com.qingjing.bizhi");
        properties.setAppAppleId(6818362193L);
        properties.setDeviceCheckKeyId("XRWS489HC8");
        properties.setDeviceCheckPrivateKey(key);
        properties.setStoreIssuerId("5183ef0f-1fb4-4748-b150-bc7b29dfbc40");
        properties.setStoreKeyId("FB8P8L4QX2");
        properties.setStorePrivateKey(key);
        properties.setAcceptedStoreEnvironments("SANDBOX");
        properties.setOnlineCertificateChecks(false);

        assertThat(new RealIosAppleGateway(properties, new ObjectMapper(), new DefaultResourceLoader()))
                .isNotNull();
    }

    @Test
    void acceptsStoreKitDeviceVerificationDigestCaseInsensitively() throws Exception {
        String nonce = "A2D037AA-D6A2-4A2E-BD2C-6A38079839EF";
        String deviceId = "77E0D508-17A4-4ED2-92B7-837183CFD3A7";
        byte[] expected = MessageDigest.getInstance("SHA-384")
                .digest((nonce.toLowerCase() + deviceId.toLowerCase()).getBytes(StandardCharsets.UTF_8));

        RealIosAppleGateway.verifyDeviceHash(nonce, Base64.getEncoder().encodeToString(expected), deviceId);
        assertThat(expected).isEqualTo(AppleCrypto.deviceDigest(nonce, deviceId));
    }

    @Test
    void rejectsDigestCopiedFromAnotherDevice() throws Exception {
        String nonce = "a2d037aa-d6a2-4a2e-bd2c-6a38079839ef";
        byte[] other = AppleCrypto.deviceDigest(nonce, "77e0d508-17a4-4ed2-92b7-837183cfd3a7");

        assertThatThrownBy(() -> RealIosAppleGateway.verifyDeviceHash(
                nonce,
                Base64.getEncoder().encodeToString(other),
                "35f336ef-9df5-4fba-85dc-5c2640c44cbe"))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void acceptsDocumentedAndObservedMissingBitStateResponses() {
        assertThat(RealIosAppleGateway.isMissingBitState("Bit State Not Found")).isTrue();
        assertThat(RealIosAppleGateway.isMissingBitState("  Failed to find bit state\n")).isTrue();
        assertThat(RealIosAppleGateway.isMissingBitState("Failed to find bit state later")).isFalse();
    }
}
