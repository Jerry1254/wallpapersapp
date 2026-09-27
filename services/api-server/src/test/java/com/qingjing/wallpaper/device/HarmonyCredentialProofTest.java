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
import java.security.interfaces.RSAPublicKey;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HarmonyCredentialProofTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AndroidCredentialProof rsaProof = new AndroidCredentialProof(mapper);
    private final HarmonyCredentialProof verifier = new HarmonyCredentialProof(mapper, rsaProof);

    @Test
    void validatesHuksRsaRegistrationContractAndRawExportFingerprint() throws Exception {
        KeyPair key = rsaKey();
        String pem = rsaProof.canonicalPem((RSAPublicKey) key.getPublic());
        Instant now = Instant.now();
        String nonce = UUID.randomUUID().toString();
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(key.getPublic().getEncoded()));
        String payload = "QJ-HARMONYOS-REGISTER-V1\ncom.qingjing.bizhi\n"
                + fingerprint + "\n" + now + "\n" + nonce;
        String proof = sign(key, payload);
        String evidence = Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(Map.of("timestamp", now.toString(), "nonce", nonce, "proof", proof)));

        HarmonyCredentialProof.Registration registration = verifier.verifyRegistration(
                "com.qingjing.bizhi", pem, evidence, now, Duration.ofMinutes(5));

        assertThat(registration.fingerprint()).isEqualTo(fingerprint);
        assertThat(registration.nonce()).isEqualTo(nonce);
        assertThat(registration.publicKeyPem()).isEqualTo(pem);
    }

    @Test
    void rejectsWrongScopeKeyExpiredEvidenceAndNonPkcs1Signature() throws Exception {
        KeyPair key = rsaKey();
        KeyPair other = rsaKey();
        String pem = rsaProof.canonicalPem((RSAPublicKey) key.getPublic());
        Instant now = Instant.now();
        String nonce = UUID.randomUUID().toString();
        String fingerprint = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(key.getPublic().getEncoded()));
        String payload = "QJ-HARMONYOS-REGISTER-V1\ncom.qingjing.bizhi\n"
                + fingerprint + "\n" + now + "\n" + nonce;
        String evidence = evidence(now, nonce, sign(key, payload));

        assertThatThrownBy(() -> verifier.verifyRegistration(
                "wrong.scope", pem, evidence, now, Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> verifier.verifyRegistration(
                "com.qingjing.bizhi", rsaProof.canonicalPem((RSAPublicKey) other.getPublic()), evidence,
                now, Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> verifier.verifyRegistration(
                "com.qingjing.bizhi", pem, evidence, now.plusSeconds(301), Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("TIMESTAMP_INVALID");

        Signature pss = Signature.getInstance("RSASSA-PSS");
        pss.setParameter(new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1));
        pss.initSign(key.getPrivate());
        pss.update(payload.getBytes(StandardCharsets.UTF_8));
        String pssEvidence = evidence(now, nonce,
                Base64.getUrlEncoder().withoutPadding().encodeToString(pss.sign()));
        assertThatThrownBy(() -> verifier.verifyRegistration(
                "com.qingjing.bizhi", pem, pssEvidence, now, Duration.ofMinutes(5)))
                .isInstanceOf(ApiException.class)
                .extracting(error -> ((ApiException) error).code()).isEqualTo("PROOF_INVALID");
    }

    private String evidence(Instant timestamp, String nonce, String proof) throws Exception {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                mapper.writeValueAsBytes(Map.of("timestamp", timestamp.toString(), "nonce", nonce, "proof", proof)));
    }

    private static KeyPair rsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    private static String sign(KeyPair key, String payload) throws Exception {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(key.getPrivate());
        signer.update(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
    }
}
