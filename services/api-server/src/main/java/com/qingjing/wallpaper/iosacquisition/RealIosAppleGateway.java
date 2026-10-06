package com.qingjing.wallpaper.iosacquisition;

import com.apple.itunes.storekit.model.AppTransaction;
import com.apple.itunes.storekit.model.Environment;
import com.apple.itunes.storekit.model.JWSTransactionDecodedPayload;
import com.apple.itunes.storekit.model.Type;
import com.apple.itunes.storekit.verification.SignedDataVerifier;
import com.apple.itunes.storekit.verification.VerificationException;
import com.apple.itunes.storekit.verification.VerificationStatus;
import com.auth0.jwt.JWT;
import com.auth0.jwt.algorithms.Algorithm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Real Apple verification. No client-controlled endpoint or unsigned authorization fallback. */
@Component
@ConditionalOnProperty(prefix="qingjing.ios-acquisition", name="enabled", havingValue="true")
public class RealIosAppleGateway implements IosAppleGateway {
    private final IosAcquisitionProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private final Map<Environment, SignedDataVerifier> verifiers = new LinkedHashMap<>();
    private final AppAttestVerifier attest;
    private final ECPrivateKey deviceKey;
    private final ECPrivateKey storeKey;
    private final String deviceBase;

    public RealIosAppleGateway(IosAcquisitionProperties properties, ObjectMapper json, ResourceLoader resources) {
        this.properties = properties;
        this.json = json;
        try {
            if (!properties.hasAppleCredentials()) throw new IllegalArgumentException("Missing Apple configuration");
            if (properties.getConnectTimeout().isNegative() || properties.getConnectTimeout().isZero()
                    || properties.getRequestTimeout().isNegative() || properties.getRequestTimeout().isZero()) {
                throw new IllegalArgumentException("Positive Apple timeouts required");
            }
            deviceKey = AppleCrypto.privateKey(AppleCrypto.secret(properties.getDeviceCheckPrivateKey(), properties.getDeviceCheckPrivateKeyFile()));
            storeKey = AppleCrypto.privateKey(AppleCrypto.secret(properties.getStorePrivateKey(), properties.getStorePrivateKeyFile()));
            http = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).followRedirects(HttpClient.Redirect.NEVER).build();
            deviceBase = switch (properties.getDeviceCheckEnvironment()) {
                case "DEVELOPMENT" -> "https://api.development.devicecheck.apple.com";
                case "PRODUCTION" -> "https://api.devicecheck.apple.com";
                default -> throw new IllegalArgumentException("Invalid DeviceCheck environment");
            };
            try (var root = resources.getResource(properties.getAppAttestRoot()).getInputStream()) {
                attest = new AppAttestVerifier(properties, root.readAllBytes());
            }
            byte[] storeRoot;
            try (var root = resources.getResource(properties.getStoreRoot()).getInputStream()) { storeRoot = root.readAllBytes(); }
            for (String configured : properties.storeEnvironments()) {
                Environment environment = Environment.valueOf(configured);
                if (environment != Environment.SANDBOX && environment != Environment.PRODUCTION) throw new IllegalArgumentException("Unsupported store environment");
                verifiers.put(environment, new SignedDataVerifier(Set.of(new ByteArrayInputStream(storeRoot)),
                        properties.getBundleId(), properties.getAppAppleId(), environment, properties.getOnlineCertificateChecks()));
            }
        } catch (Exception e) {
            // Never include a PEM or provider exception payload in startup diagnostics.
            throw new IllegalStateException("Apple acquisition configuration is incomplete or invalid; check IDs, key files, roots and environments");
        }
    }

    @Override public AttestedKey verifyAttestation(String keyId, String attestation, String clientData, String nonce) {
        return attest.attest(keyId, attestation, clientData);
    }
    @Override public long verifyAssertion(String keyId, String assertion, String publicKeyPem, byte[] rawBody, long previousCounter) {
        return attest.assertion(assertion, publicKeyPem, rawBody, previousCounter);
    }

    @Override public DeviceBits queryDeviceBits(String deviceToken) {
        HttpResponse<String> response = deviceCall("/v1/query_two_bits", deviceToken, UUID.randomUUID().toString(), null);
        if (isMissingBitState(response.body())) return new DeviceBits(false, false, Instant.now());
        try {
            JsonNode body = json.readTree(response.body());
            if (!body.path("bit0").isBoolean() || !body.path("bit1").isBoolean()) throw unavailable();
            return new DeviceBits(body.path("bit0").asBoolean(), body.path("bit1").asBoolean(), Instant.now());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }

    static boolean isMissingBitState(String body) {
        if (body == null) return false;
        String value = body.strip();
        return value.equals("Bit State Not Found") || value.equals("Failed to find bit state");
    }
    @Override public void markFirstFreeUsed(String token, String id) { deviceCall("/v1/update_two_bits", token, id, true); }
    @Override public void resetFirstFreeBit(String token, String id) { deviceCall("/v1/update_two_bits", token, id, false); }

    private HttpResponse<String> deviceCall(String path, String token, String id, Boolean bit0) {
        if (token == null || token.isBlank() || token.length() > 16384 || token.contains("\n") || token.contains("\r")) {
            throw new ApiException(HttpStatus.FORBIDDEN, "IOS_DEVICE_PROOF_UNAVAILABLE", "A fresh DeviceCheck token is required");
        }
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("device_token", token); body.put("transaction_id", id); body.put("timestamp", System.currentTimeMillis());
            if (bit0 != null) body.put("bit0", bit0); // Omit bit1: another app may own it.
            Instant now = Instant.now();
            String jwt = JWT.create().withKeyId(properties.getDeviceCheckKeyId()).withIssuer(properties.getTeamId())
                    .withIssuedAt(Date.from(now)).sign(Algorithm.ECDSA256(null, deviceKey));
            return send(HttpRequest.newBuilder(URI.create(deviceBase + path))
                    .timeout(properties.getRequestTimeout()).header("Authorization", "Bearer " + jwt)
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build());
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw unavailable(); }
    }

    @Override public VerifiedTransaction verifyTransaction(String transactionJws, String appJws, String deviceId) {
        for (var entry : verifiers.entrySet()) {
            try {
                JWSTransactionDecodedPayload transaction = entry.getValue().verifyAndDecodeTransaction(transactionJws);
                AppTransaction app = entry.getValue().verifyAndDecodeAppTransaction(appJws);
                JsonNode payload = payload(transactionJws); // Inspect only after signature verification.
                verifyDeviceHash(payload.path("deviceVerificationNonce").asText(), payload.path("deviceVerification").asText(), deviceId);
                verifyDeviceHash(app.getDeviceVerificationNonce().toString(), app.getDeviceVerification(), deviceId);
                if (transaction.getAppTransactionId() != null && !transaction.getAppTransactionId().equals(app.getAppTransactionId())) throw invalidPurchase();
                if (app.getReceiptCreationDate() == null || app.getReceiptCreationDate() > System.currentTimeMillis() + 60000) throw invalidPurchase();
                VerifiedTransaction original = transaction(transaction, deviceId);
                // Always fetch current facts: a copied old JWS must not undo a refund.
                VerifiedTransaction current = latestTransaction(original.environment(), original.transactionId());
                if (!original.originalTransactionId().equals(current.originalTransactionId())
                        || !original.productId().equals(current.productId()) || !original.productType().equals(current.productType())
                        || original.quantity()!=current.quantity()
                        || !java.util.Objects.equals(original.appAccountToken(),current.appAccountToken())) throw invalidPurchase();
                return new VerifiedTransaction(current.environment(), current.bundleId(), current.productId(), current.transactionId(),
                        current.originalTransactionId(), original.appAccountToken(), deviceId, current.purchasedAt(),
                        current.revokedAt(), current.signedAt(), app.getAppTransactionId(),current.productType(),current.quantity(),
                        current.currency(),current.priceMilliunits(),current.storefront());
            } catch (VerificationException e) {
                if (e.getStatus() != VerificationStatus.INVALID_ENVIRONMENT) throw invalidPurchase();
            } catch (ApiException e) { throw e; }
            catch (Exception e) { throw invalidPurchase(); }
        }
        throw new ApiException(HttpStatus.FORBIDDEN, "IOS_PURCHASE_ENVIRONMENT_INVALID", "Apple transaction environment is not accepted");
    }

    @Override public VerifiedAppIdentity verifyAppTransaction(String signedAppTransaction,String deviceVerificationId) {
        for(var entry:verifiers.entrySet()) {
            try {
                AppTransaction app=entry.getValue().verifyAndDecodeAppTransaction(signedAppTransaction);
                verifyDeviceHash(app.getDeviceVerificationNonce().toString(),app.getDeviceVerification(),deviceVerificationId);
                if(app.getAppTransactionId()==null || !app.getAppTransactionId().matches("[A-Za-z0-9._:-]{1,128}")
                        || app.getReceiptCreationDate()==null || app.getReceiptCreationDate()>System.currentTimeMillis()+60000)throw invalidPurchase();
                return new VerifiedAppIdentity(entry.getKey().name(),app.getBundleId(),app.getAppTransactionId());
            } catch(VerificationException failure) {
                if(failure.getStatus()!=VerificationStatus.INVALID_ENVIRONMENT)throw invalidPurchase();
            } catch(ApiException failure) {throw failure;}
            catch(Exception failure) {throw invalidPurchase();}
        }
        throw invalidPurchase();
    }

    @Override public VerifiedTransaction latestTransaction(String environment, String transactionId) {
        if (transactionId == null || !transactionId.matches("[0-9]{1,128}")) throw invalidPurchase();
        Environment env = Environment.valueOf(environment);
        SignedDataVerifier verifier = verifiers.get(env);
        if (verifier == null) throw invalidPurchase();
        try {
            Instant now = Instant.now();
            String jwt = JWT.create().withKeyId(properties.getStoreKeyId()).withIssuer(properties.getStoreIssuerId())
                    .withAudience("appstoreconnect-v1").withClaim("bid", properties.getBundleId())
                    .withIssuedAt(Date.from(now)).withExpiresAt(Date.from(now.plusSeconds(300)))
                    .sign(Algorithm.ECDSA256(null, storeKey));
            String base = env == Environment.PRODUCTION ? "https://api.storekit.itunes.apple.com" : "https://api.storekit-sandbox.itunes.apple.com";
            HttpResponse<String> response = send(HttpRequest.newBuilder(URI.create(base + "/inApps/v1/transactions/" + transactionId))
                    .timeout(properties.getRequestTimeout()).header("Authorization", "Bearer " + jwt).GET().build());
            JWSTransactionDecodedPayload decoded = verifier.verifyAndDecodeTransaction(json.readTree(response.body()).path("signedTransactionInfo").asText());
            if (!transactionId.equals(decoded.getTransactionId())) throw invalidPurchase();
            return transaction(decoded, null);
        } catch (ApiException e) { throw e; }
        catch (VerificationException e) { throw invalidPurchase(); }
        catch (Exception e) { throw unavailable(); }
    }

    @Override public VerifiedNotification verifyNotification(String signedPayload) {
        for (var entry : verifiers.entrySet()) {
            try {
                var notification = entry.getValue().verifyAndDecodeNotification(signedPayload);
                UUID.fromString(notification.getNotificationUUID());
                VerifiedTransaction transaction = null;
                if (notification.getData() != null && notification.getData().getSignedTransactionInfo() != null) {
                    transaction = transaction(entry.getValue().verifyAndDecodeTransaction(notification.getData().getSignedTransactionInfo()), null);
                }
                return new VerifiedNotification(notification.getNotificationUUID(), entry.getKey().name(),
                        notification.getRawNotificationType(), notification.getRawSubtype(), transaction);
            } catch (VerificationException e) {
                if (e.getStatus() != VerificationStatus.INVALID_ENVIRONMENT) throw invalidPurchase();
            } catch (ApiException e) { throw e; }
            catch (Exception e) { throw invalidPurchase(); }
        }
        throw invalidPurchase();
    }

    private VerifiedTransaction transaction(JWSTransactionDecodedPayload value, String deviceId) {
        if ((value.getType() != Type.NON_CONSUMABLE && value.getType()!=Type.CONSUMABLE) || value.getOriginalTransactionId() == null
                || value.getPurchaseDate() == null || value.getSignedDate() == null || value.getProductId() == null) throw invalidPurchase();
        return new VerifiedTransaction(value.getEnvironment().name(), value.getBundleId(), value.getProductId(), value.getTransactionId(),
                value.getOriginalTransactionId(), value.getAppAccountToken() == null ? null : value.getAppAccountToken().toString(),
                deviceId, Instant.ofEpochMilli(value.getPurchaseDate()),
                value.getRevocationDate() == null ? null : Instant.ofEpochMilli(value.getRevocationDate()),
                Instant.ofEpochMilli(value.getSignedDate()), value.getAppTransactionId(),value.getType().name(),
                value.getQuantity()==null?0:value.getQuantity(),value.getCurrency(),value.getPrice(),value.getStorefront());
    }

    private JsonNode payload(String jws) throws Exception { return json.readTree(Base64.getUrlDecoder().decode(jws.split("\\.")[1])); }
    static void verifyDeviceHash(String nonce, String encodedHash, String id) throws Exception {
        if (!MessageDigest.isEqual(AppleCrypto.deviceDigest(nonce, id), Base64.getDecoder().decode(encodedHash))) throw invalidPurchase();
    }
    private HttpResponse<String> send(HttpRequest request) {
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200 || response.body().length() > 262144) throw unavailable();
            return response;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw unavailable(); }
        catch (java.io.IOException e) { throw unavailable(); }
    }
    private static ApiException invalidPurchase() { return new ApiException(HttpStatus.FORBIDDEN, "IOS_PURCHASE_INVALID", "Apple purchase proof verification failed"); }
    private static ApiException unavailable() { return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IOS_DEVICE_PROOF_UNAVAILABLE", "Apple verification is temporarily unavailable"); }
}
