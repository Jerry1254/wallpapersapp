package com.qingjing.wallpaper.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Installation key possession; deliberately does not claim remote hardware attestation. */
@Component
public final class AndroidCredentialProof {
    private final ObjectMapper mapper;
    public AndroidCredentialProof(ObjectMapper mapper) { this.mapper = mapper; }

    public RSAPublicKey publicKey(String pem) {
        try {
            if (pem == null || pem.length() > 8192
                    || !pem.startsWith("-----BEGIN PUBLIC KEY-----\n")
                    || !pem.endsWith("-----END PUBLIC KEY-----")) throw new IllegalArgumentException();
            String content = pem.substring(27, pem.length() - 24).replace("\n", "");
            RSAPublicKey key = (RSAPublicKey) KeyFactory.getInstance("RSA")
                    .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(content)));
            if (key.getModulus().bitLength() != 2048 || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) {
                throw new IllegalArgumentException();
            }
            return key;
        } catch (Exception invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_INVALID", "A supported RSA public key is required");
        }
    }

    public String fingerprint(RSAPublicKey key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    public String canonicalPem(RSAPublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(key.getEncoded()) + "\n-----END PUBLIC KEY-----";
    }
    public boolean verify(String pem, String payload, String proof) {
        try {
            if (proof == null || !proof.matches("[A-Za-z0-9_-]{342}")) return false;
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey(pem));
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(Base64.getUrlDecoder().decode(proof));
        } catch (Exception invalid) { return false; }
    }
    public Registration verifyRegistration(String scope, String pem, String token, Instant now, Duration skew) {
        RSAPublicKey key = publicKey(pem);
        try {
            if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException();
            JsonNode evidence = mapper.readTree(Base64.getUrlDecoder().decode(token));
            if (!evidence.isObject() || evidence.size() != 3) throw new IllegalArgumentException();
            String timestamp = evidence.path("timestamp").textValue();
            String nonce = evidence.path("nonce").textValue();
            String proof = evidence.path("proof").textValue();
            Instant instant = Instant.parse(timestamp);
            if (!instant.toString().equals(timestamp) || !UUID.fromString(nonce).toString().equals(nonce)) throw new IllegalArgumentException();
            if (Duration.between(instant, now).abs().compareTo(skew) > 0) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TIMESTAMP_INVALID", "The installation proof has expired");
            }
            String fingerprint = fingerprint(key);
            String payload = "QJ-ANDROID-REGISTER-V1\n" + scope + "\n" + fingerprint + "\n" + timestamp + "\n" + nonce;
            if (!verify(canonicalPem(key), payload, proof)) throw new IllegalArgumentException();
            return new Registration(fingerprint, nonce, canonicalPem(key));
        } catch (ApiException known) { throw known; }
        catch (Exception invalid) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PROOF_INVALID", "The installation proof is invalid");
        }
    }
    public record Registration(String fingerprint, String nonce, String publicKeyPem) { }
}
