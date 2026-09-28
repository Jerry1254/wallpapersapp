package com.qingjing.wallpaper.device;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class IosCredentialProofTest {

    private static final String TEST_PEM = """
            -----BEGIN PUBLIC KEY-----
            MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0At0cIFeoeu0IWxtKDUMAVVIyPsc
            CCGH2+i84oDGdhHC4SiRF+MqKo3nwtOagCcCkVh0Wx8XePikNxH/29WVxw==
            -----END PUBLIC KEY-----""";
    private static final String TEST_FINGERPRINT =
            "fb1cbfec0d7cb62275413fee43cd40cb272787bf690faf22e14c3465062494ce";
    private static final String REGISTER_PAYLOAD = """
            QJ-IOS-REGISTER-V1
            com.qingjing.livephotolab
            fb1cbfec0d7cb62275413fee43cd40cb272787bf690faf22e14c3465062494ce
            2026-09-28T08:30:00Z
            123e4567-e89b-12d3-a456-426614174000""";
    private static final String REGISTER_SIGNATURE =
            "MEQCIC0CfShI5S4na7dgT8BJ4KLgye0CkZir8DAkSIodh0WVAiAIwgDJI84OS5ewA9ouQoDiatF2b1XP-q3DmRuDW0EZyg";
    private static final String SESSION_PAYLOAD = """
            QJ-DEVICE-SESSION-V1
            11111111-1111-4111-8111-111111111111
            22222222-2222-4222-8222-222222222222
            qj-test-challenge-nonce-v1
            2026-09-28T08:31:00Z""";
    private static final String SESSION_SIGNATURE =
            "MEYCIQC0TdP0M2Onixi4NwzDx1iQh9PHnAd7Z6613ODxBDBOggIhALzLHRrRTXaSw4fVdo27u-0JvxiYqjp-ToAQZFDufdDV";
    private static final String REQUEST_PAYLOAD = """
            QJ-SIGNED-REQUEST-V1
            POST
            /api/v1/device/wallpapers/42/download-tickets
            2026-09-28T08:32:00Z
            33333333-3333-4333-8333-333333333333
            4944d90113fa0a1c413a35f6af4989a21d5204620865b05519ee525e7aabc34c""";
    private static final String REQUEST_SIGNATURE =
            "MEUCICRuxj0YbitVxnPY-27_UtKJO4CEi8daOCuPPXQIKbJuAiEAnsZrCItIKEXk_ClJD92piHZLdMOuUI9nxeTOWqCHv00";

    private final ObjectMapper mapper = new ObjectMapper();
    private final IosCredentialProof verifier = new IosCredentialProof(mapper);

    @Test
    void verifiesAllIosTeamContractVectors() throws Exception {
        ECPublicKey key = verifier.publicKey(TEST_PEM);
        assertThat(verifier.fingerprint(key)).isEqualTo(TEST_FINGERPRINT);
        assertThat(verifier.canonicalPem(key)).isEqualTo(TEST_PEM);
        assertThat(verifier.verify(TEST_PEM, REGISTER_PAYLOAD, REGISTER_SIGNATURE)).isTrue();
        assertThat(verifier.verify(TEST_PEM, SESSION_PAYLOAD, SESSION_SIGNATURE)).isTrue();
        assertThat(verifier.verify(TEST_PEM, REQUEST_PAYLOAD, REQUEST_SIGNATURE)).isTrue();
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "{\"deliveryPlatform\":\"IOS\",\"resourceType\":\"LIVE_PHOTO\"}"
                        .getBytes(StandardCharsets.UTF_8))))
                .isEqualTo("4944d90113fa0a1c413a35f6af4989a21d5204620865b05519ee525e7aabc34c");

        String evidence = evidence(
                "2026-09-28T08:30:00Z",
                "123e4567-e89b-12d3-a456-426614174000",
                REGISTER_SIGNATURE);
        IosCredentialProof.Registration registration = verifier.verifyRegistration(
                "com.qingjing.livephotolab",
                TEST_PEM,
                evidence,
                Instant.parse("2026-09-28T08:30:00Z"),
                Duration.ofMinutes(5));
        assertThat(registration.fingerprint()).isEqualTo(TEST_FINGERPRINT);
        assertThat(registration.nonce()).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(registration.publicKeyPem()).isEqualTo(TEST_PEM);
        assertThatThrownBy(() -> verifier.verifyRegistration(
                "com.qingjing.bizhi",
                TEST_PEM,
                evidence,
                Instant.parse("2026-09-28T08:30:00Z"),
                Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("PROOF_INVALID");
    }

    @Test
    void rejectsTamperingWrongCurvePaddingAndNonCanonicalTime() throws Exception {
        assertThat(verifier.verify(TEST_PEM, REGISTER_PAYLOAD + "x", REGISTER_SIGNATURE)).isFalse();
        assertThat(verifier.verify(TEST_PEM, REGISTER_PAYLOAD, REGISTER_SIGNATURE + "=")).isFalse();

        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"));
        assertThatThrownBy(() -> verifier.publicKey(pem(generator.generateKeyPair())))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("CREDENTIAL_INVALID");

        String fractional = "2026-09-28T08:30:00.000Z";
        String evidence = evidence(fractional, UUID.randomUUID().toString(), REGISTER_SIGNATURE);
        assertThatThrownBy(() -> verifier.verifyRegistration(
                "com.qingjing.livephotolab", TEST_PEM, evidence,
                Instant.parse("2026-09-28T08:30:00Z"), Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("PROOF_INVALID");
    }

    @Test
    void verifiesFreshP256SignaturesAndRejectsAnotherPublicKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        KeyPair key = generator.generateKeyPair();
        KeyPair other = generator.generateKeyPair();
        String pem = pem(key);
        String payload = "QJ-DEVICE-SESSION-V1\nkey\nchallenge\nnonce\n2026-09-28T08:31:00Z";
        String signature = sign(key, payload);

        assertThat(verifier.verify(pem, payload, signature)).isTrue();
        assertThat(verifier.verify(pem(other), payload, signature)).isFalse();
    }

    private String evidence(String timestamp, String nonce, String proof) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(Map.of("timestamp", timestamp, "nonce", nonce, "proof", proof)));
    }

    private static String pem(KeyPair key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(key.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----";
    }

    private static String sign(KeyPair key, String payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withECDSA");
        signer.initSign(key.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
}
