package com.qingjing.wallpaper.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** HarmonyOS HUKS installation key possession; this is not remote hardware attestation. */
@Component
public final class HarmonyCredentialProof {

    private static final String REGISTRATION_DOMAIN = "QJ-HARMONYOS-REGISTER-V1";

    private final ObjectMapper mapper;
    private final AndroidCredentialProof rsaProof;

    public HarmonyCredentialProof(ObjectMapper mapper, AndroidCredentialProof rsaProof) {
        this.mapper = mapper;
        this.rsaProof = rsaProof;
    }

    public Registration verifyRegistration(String scope, String pem, String token, Instant now, Duration skew) {
        RSAPublicKey key = rsaProof.publicKey(pem);
        try {
            byte[] exportedKey = exportedPublicKey(pem);
            if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            JsonNode evidence = mapper.readTree(Base64.getUrlDecoder().decode(token));
            if (!evidence.isObject() || evidence.size() != 3) throw new IllegalArgumentException();
            String timestamp = evidence.path("timestamp").textValue();
            String nonce = evidence.path("nonce").textValue();
            String proof = evidence.path("proof").textValue();
            Instant instant = Instant.parse(timestamp);
            if (!instant.toString().equals(timestamp) || !UUID.fromString(nonce).toString().equals(nonce)) {
                throw new IllegalArgumentException();
            }
            if (Duration.between(instant, now).abs().compareTo(skew) > 0) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TIMESTAMP_INVALID", "The installation proof has expired");
            }
            String fingerprint = fingerprint(exportedKey);
            String payload = REGISTRATION_DOMAIN + "\n" + scope + "\n" + fingerprint + "\n" + timestamp + "\n" + nonce;
            String canonicalPem = rsaProof.canonicalPem(key);
            if (!rsaProof.verify(canonicalPem, payload, proof)) throw new IllegalArgumentException();
            return new Registration(fingerprint, nonce, canonicalPem);
        } catch (ApiException known) {
            throw known;
        } catch (Exception invalid) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PROOF_INVALID", "The HarmonyOS installation proof is invalid");
        }
    }

    private byte[] exportedPublicKey(String pem) {
        try {
            String content = pem.substring(27, pem.length() - 24).replace("\n", "");
            return Base64.getDecoder().decode(content);
        } catch (RuntimeException invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_INVALID", "A supported RSA public key is required");
        }
    }

    private String fingerprint(byte[] exportedKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(exportedKey));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public record Registration(String fingerprint, String nonce, String publicKeyPem) {
    }
}
