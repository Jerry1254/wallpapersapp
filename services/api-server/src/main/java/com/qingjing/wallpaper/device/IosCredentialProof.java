package com.qingjing.wallpaper.device;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.AlgorithmParameters;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.ECKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Verifies the iOS Secure Enclave P-256 installation-key contract. */
@Component
public final class IosCredentialProof {

    private static final String REGISTRATION_DOMAIN = "QJ-IOS-REGISTER-V1";
    private static final String PEM_BEGIN = "-----BEGIN PUBLIC KEY-----\n";
    private static final String PEM_END = "\n-----END PUBLIC KEY-----";
    private static final String UTC_SECONDS = "\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z";

    private final ObjectMapper mapper;

    public IosCredentialProof(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public Registration verifyRegistration(String scope, String pem, String token, Instant now, Duration skew) {
        ECPublicKey key = publicKey(pem);
        try {
            if (token == null || token.length() > 8192 || !token.matches("[A-Za-z0-9_-]+")) {
                throw new IllegalArgumentException();
            }
            JsonNode evidence = mapper.readTree(Base64.getUrlDecoder().decode(token));
            if (!evidence.isObject() || evidence.size() != 3) throw new IllegalArgumentException();
            String timestamp = evidence.path("timestamp").textValue();
            String nonce = evidence.path("nonce").textValue();
            String proof = evidence.path("proof").textValue();
            if (timestamp == null || !timestamp.matches(UTC_SECONDS)) throw new IllegalArgumentException();
            Instant instant = Instant.parse(timestamp);
            if (!instant.toString().equals(timestamp)
                    || nonce == null
                    || !UUID.fromString(nonce).toString().equals(nonce)) {
                throw new IllegalArgumentException();
            }
            if (Duration.between(instant, now).abs().compareTo(skew) > 0) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "TIMESTAMP_INVALID", "The installation proof has expired");
            }
            String fingerprint = fingerprint(key);
            String payload = REGISTRATION_DOMAIN + "\n" + scope + "\n" + fingerprint + "\n" + timestamp + "\n" + nonce;
            String canonicalPem = canonicalPem(key);
            if (!verify(canonicalPem, payload, proof)) throw new IllegalArgumentException();
            return new Registration(fingerprint, nonce, canonicalPem);
        } catch (ApiException known) {
            throw known;
        } catch (Exception invalid) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PROOF_INVALID", "The iOS installation proof is invalid");
        }
    }

    public ECPublicKey publicKey(String pem) {
        try {
            if (pem == null || pem.length() > 8192 || !pem.startsWith(PEM_BEGIN) || !pem.endsWith(PEM_END)) {
                throw new IllegalArgumentException();
            }
            String content = pem.substring(PEM_BEGIN.length(), pem.length() - PEM_END.length());
            if (content.isBlank() || !content.matches("[A-Za-z0-9+/=\\n]+")) throw new IllegalArgumentException();
            byte[] encoded = Base64.getDecoder().decode(content.replace("\n", ""));
            ECPublicKey key = (ECPublicKey) KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (!Arrays.equals(encoded, key.getEncoded()) || !isP256(key)) throw new IllegalArgumentException();
            return key;
        } catch (ApiException known) {
            throw known;
        } catch (Exception invalid) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CREDENTIAL_INVALID", "A P-256 X.509 public key is required");
        }
    }

    public boolean verify(String pem, String payload, String suppliedSignature) {
        try {
            if (suppliedSignature == null
                    || suppliedSignature.length() > 256
                    || !suppliedSignature.matches("[A-Za-z0-9_-]+")) {
                return false;
            }
            byte[] signature = Base64.getUrlDecoder().decode(suppliedSignature);
            if (signature.length < 8 || signature.length > 80) return false;
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(publicKey(pem));
            verifier.update(payload.getBytes(StandardCharsets.UTF_8));
            return verifier.verify(signature);
        } catch (Exception invalid) {
            return false;
        }
    }

    public String fingerprint(ECPublicKey key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded()));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public String canonicalPem(ECPublicKey key) {
        return PEM_BEGIN + Base64.getMimeEncoder(64, new byte[] {10}).encodeToString(key.getEncoded()) + PEM_END;
    }

    private boolean isP256(ECKey key) throws Exception {
        AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
        parameters.init(new ECGenParameterSpec("secp256r1"));
        ECParameterSpec expected = parameters.getParameterSpec(ECParameterSpec.class);
        ECParameterSpec actual = key.getParams();
        return actual != null
                && actual.getCurve().getField() instanceof ECFieldFp actualField
                && expected.getCurve().getField() instanceof ECFieldFp expectedField
                && actualField.getP().equals(expectedField.getP())
                && actual.getCurve().getA().equals(expected.getCurve().getA())
                && actual.getCurve().getB().equals(expected.getCurve().getB())
                && actual.getGenerator().getAffineX().equals(expected.getGenerator().getAffineX())
                && actual.getGenerator().getAffineY().equals(expected.getGenerator().getAffineY())
                && actual.getOrder().equals(expected.getOrder())
                && actual.getCofactor() == expected.getCofactor();
    }

    public record Registration(String fingerprint, String nonce, String publicKeyPem) {
    }
}
