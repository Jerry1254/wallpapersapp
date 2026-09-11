package com.qingjing.wallpaper.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.device.DeviceDtos.ChallengeAlgorithm;
import com.qingjing.wallpaper.device.DeviceDtos.CreateDeviceSessionRequest;
import com.qingjing.wallpaper.device.DeviceDtos.CredentialType;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceRegistrationRequest;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceRegistrationResponse;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceSession;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceSessionChallenge;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceIdentityService {

    private static final String CHALLENGE_PREFIX = "device:challenge:";
    private static final String SESSION_PREFIX = "device:session:";

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final SecurityCrypto crypto;
    private final RedisRateLimiter rateLimiter;
    private final DeviceProperties properties;

    public DeviceIdentityService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            SecurityCrypto crypto,
            RedisRateLimiter rateLimiter,
            DeviceProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    @Transactional
    public DeviceRegistrationResponse register(DeviceRegistrationRequest request, String remoteAddress) {
        validateRegistration(request);
        String scope = request.appInstallScope().strip();
        String evidenceHash = crypto.hmacHex("device-evidence-v1", request.evidenceToken());
        rateLimiter.require("device-registration", remoteAddress + "\n" + evidenceHash, 10, Duration.ofMinutes(1));

        String publicId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO anonymous_device
                    (public_id, platform, app_install_scope, evidence_hash, status, last_seen_at)
                VALUES (?, ?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id), last_seen_at = UTC_TIMESTAMP(6)
                """,
                publicId, request.platform().name(), scope, evidenceHash);
        Long deviceId = jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (deviceId == null || deviceId == 0) {
            deviceId = jdbc.queryForObject(
                    "SELECT id FROM anonymous_device WHERE platform = ? AND app_install_scope = ? AND evidence_hash = ?",
                    Long.class,
                    request.platform().name(), scope, evidenceHash);
        }
        String deviceStatus = jdbc.queryForObject(
                "SELECT status FROM anonymous_device WHERE id = ? FOR UPDATE", String.class, deviceId);
        if (!"ACTIVE".equals(deviceStatus)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_DISABLED", "The device is not active");
        }

        jdbc.update(
                """
                UPDATE device_credential
                SET status = 'REVOKED', revoked_at = UTC_TIMESTAMP(6)
                WHERE device_id = ? AND credential_type = 'H5_TEST_SECRET' AND status = 'ACTIVE'
                """,
                deviceId);
        String credentialKeyId = UUID.randomUUID().toString();
        String secret = credentialSecret(credentialKeyId);
        String storedHash = crypto.hmacHex("device-credential-record-v1", secret);
        jdbc.update(
                """
                INSERT INTO device_credential
                    (device_id, credential_key_id, credential_type, public_key_pem, secret_hash, status)
                VALUES (?, ?, 'H5_TEST_SECRET', NULL, ?, 'ACTIVE')
                """,
                deviceId, credentialKeyId, storedHash);
        java.sql.Timestamp createdAtValue = jdbc.queryForObject(
                "SELECT created_at FROM device_credential WHERE credential_key_id = ?", java.sql.Timestamp.class, credentialKeyId);
        Instant createdAt = createdAtValue == null ? Instant.now() : createdAtValue.toInstant();
        return new DeviceRegistrationResponse(credentialKeyId, CredentialType.H5_TEST_SECRET, secret, createdAt);
    }

    public DeviceSessionChallenge createChallenge(String credentialKeyId, String remoteAddress) {
        requireUuid(credentialKeyId, "credentialKeyId");
        CredentialRow credential = requireCredential(credentialKeyId);
        rateLimiter.require("device-challenge", remoteAddress + "\n" + credentialKeyId, 20, Duration.ofMinutes(1));
        if (credential.credentialType() != CredentialType.H5_TEST_SECRET) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_PROVIDER_UNAVAILABLE", "The credential provider is not available");
        }
        String challengeId = UUID.randomUUID().toString();
        String nonce = crypto.randomToken(32);
        Instant expiresAt = Instant.now().plus(properties.getChallengeTtl());
        ChallengeData data = new ChallengeData(credentialKeyId, nonce, expiresAt);
        redis.opsForValue().set(CHALLENGE_PREFIX + challengeId, write(data), properties.getChallengeTtl());
        return new DeviceSessionChallenge(challengeId, nonce, ChallengeAlgorithm.HMAC_SHA256, expiresAt);
    }

    @Transactional
    public DeviceSession createSession(CreateDeviceSessionRequest request, String remoteAddress) {
        requireUuid(request.credentialKeyId(), "credentialKeyId");
        requireUuid(request.challengeId(), "challengeId");
        rateLimiter.require(
                "device-session", remoteAddress + "\n" + request.credentialKeyId(), 10, Duration.ofMinutes(1));
        CredentialRow credential = requireCredential(request.credentialKeyId());
        validateTimestamp(request.clientTimestamp());

        String stored = redis.opsForValue().getAndDelete(CHALLENGE_PREFIX + request.challengeId());
        if (stored == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CHALLENGE_INVALID", "The challenge is invalid or expired");
        }
        ChallengeData challenge = read(stored, ChallengeData.class);
        if (!challenge.credentialKeyId().equals(request.credentialKeyId()) || challenge.expiresAt().isBefore(Instant.now())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CHALLENGE_INVALID", "The challenge is invalid or expired");
        }
        if (credential.credentialType() != CredentialType.H5_TEST_SECRET) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_PROVIDER_UNAVAILABLE", "The credential provider is not available");
        }
        String secret = credentialSecret(request.credentialKeyId());
        String expectedStoredHash = crypto.hmacHex("device-credential-record-v1", secret);
        if (!crypto.constantTimeEquals(expectedStoredHash, credential.secretHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CREDENTIAL_INVALID", "The credential is invalid");
        }
        String payload = "QJ-DEVICE-SESSION-V1\n"
                + request.credentialKeyId() + "\n"
                + request.challengeId() + "\n"
                + challenge.nonce() + "\n"
                + request.clientTimestamp();
        String expectedProof = crypto.hmacBase64UrlWithKey(secret, payload);
        if (!crypto.constantTimeEquals(expectedProof, request.proof())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "PROOF_INVALID", "The device proof is invalid");
        }

        Instant expiresAt = Instant.now().plus(properties.getSessionTtl());
        String accessToken = crypto.randomToken(32);
        SessionData session = new SessionData(
                credential.deviceId(),
                request.credentialKeyId(),
                credential.platform(),
                credential.credentialType(),
                expiresAt);
        redis.opsForValue().set(sessionKey(accessToken), write(session), properties.getSessionTtl());
        jdbc.update(
                "UPDATE device_credential SET last_used_at = UTC_TIMESTAMP(6) WHERE credential_key_id = ?",
                request.credentialKeyId());
        jdbc.update(
                "UPDATE anonymous_device SET last_seen_at = UTC_TIMESTAMP(6), lock_version = lock_version + 1 WHERE id = ?",
                credential.deviceId());
        return new DeviceSession(accessToken, "Bearer", expiresAt, credential.platform());
    }

    public SessionData requireSession(String accessToken) {
        if (accessToken == null || accessToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A device session is required");
        }
        String stored = redis.opsForValue().get(sessionKey(accessToken));
        if (stored == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "The device session has expired");
        }
        SessionData session = read(stored, SessionData.class);
        if (session.expiresAt().isBefore(Instant.now())) {
            redis.delete(sessionKey(accessToken));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "The device session has expired");
        }
        Integer active = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM anonymous_device d
                JOIN device_credential c ON c.device_id = d.id
                WHERE d.id = ? AND d.status = 'ACTIVE'
                  AND c.credential_key_id = ? AND c.status = 'ACTIVE'
                """,
                Integer.class,
                session.deviceId(), session.credentialKeyId());
        if (active == null || active != 1) {
            redis.delete(sessionKey(accessToken));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "CREDENTIAL_REVOKED", "The device credential is no longer active");
        }
        return session;
    }

    public void verifySignedRequest(
            SessionData session,
            String method,
            String canonicalPath,
            String timestampValue,
            String nonce,
            byte[] body,
            String suppliedSignature) {
        Instant timestamp;
        try {
            timestamp = Instant.parse(timestampValue);
            UUID.fromString(nonce);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNED_REQUEST_INVALID", "The signed request headers are invalid");
        }
        validateTimestamp(timestamp);
        String bodyHash = java.util.HexFormat.of().formatHex(sha256(body));
        String payload = "QJ-SIGNED-REQUEST-V1\n"
                + method.toUpperCase(java.util.Locale.ROOT) + "\n"
                + canonicalPath + "\n"
                + timestampValue + "\n"
                + nonce + "\n"
                + bodyHash;
        if (session.credentialType() != CredentialType.H5_TEST_SECRET) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_PROVIDER_UNAVAILABLE", "The credential provider is not available");
        }
        String expected = crypto.hmacBase64UrlWithKey(credentialSecret(session.credentialKeyId()), payload);
        if (!crypto.constantTimeEquals(expected, suppliedSignature)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "REQUEST_SIGNATURE_INVALID", "The request signature is invalid");
        }
        Boolean accepted = redis.opsForValue().setIfAbsent(
                "device:signed-nonce:" + session.credentialKeyId() + ":" + nonce,
                "1",
                properties.getNonceTtl());
        if (!Boolean.TRUE.equals(accepted)) {
            throw new ApiException(HttpStatus.CONFLICT, "REQUEST_NONCE_REUSED", "The request nonce has already been used");
        }
    }

    public DevicePrincipal principal(SessionData session) {
        return new DevicePrincipal(
                session.deviceId(),
                session.credentialKeyId(),
                session.platform(),
                session.credentialType());
    }

    private void validateRegistration(DeviceRegistrationRequest request) {
        String scope = request.appInstallScope().strip();
        if (!properties.isH5TestEnabled()
                || request.platform() != DevicePlatform.H5_TEST
                || request.credentialType() != CredentialType.H5_TEST_SECRET
                || request.publicKeyPem() != null
                || !properties.getAllowedH5Scopes().contains(scope)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_PROVIDER_NOT_ALLOWED", "The requested device provider is not allowed");
        }
    }

    private CredentialRow requireCredential(String credentialKeyId) {
        List<CredentialRow> rows = jdbc.query(
                """
                SELECT c.device_id, c.credential_type, c.secret_hash, d.platform
                FROM device_credential c
                JOIN anonymous_device d ON d.id = c.device_id
                WHERE c.credential_key_id = ? AND c.status = 'ACTIVE' AND d.status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new CredentialRow(
                        resultSet.getLong("device_id"),
                        CredentialType.valueOf(resultSet.getString("credential_type")),
                        resultSet.getString("secret_hash"),
                        DevicePlatform.valueOf(resultSet.getString("platform"))),
                credentialKeyId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CREDENTIAL_NOT_FOUND", "The device credential was not found");
        }
        return rows.get(0);
    }

    private void validateTimestamp(Instant timestamp) {
        Duration difference = Duration.between(timestamp, Instant.now()).abs();
        if (difference.compareTo(properties.getClockSkew()) > 0) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "TIMESTAMP_INVALID", "The client timestamp is outside the accepted window");
        }
    }

    private String credentialSecret(String credentialKeyId) {
        return crypto.hmacBase64Url("device-credential-secret-v1", credentialKeyId);
    }

    private String sessionKey(String accessToken) {
        return SESSION_PREFIX + crypto.sha256Hex(accessToken);
    }

    private void requireUuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "The request contains invalid fields",
                    List.of(new ApiException.ErrorDetail(field, "must be a UUID")));
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Device session data cannot be serialized", exception);
        }
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Device session data cannot be deserialized", exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(value);
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record CredentialRow(
            long deviceId,
            CredentialType credentialType,
            String secretHash,
            DevicePlatform platform) {
    }

    private record ChallengeData(String credentialKeyId, String nonce, Instant expiresAt) {
    }

    public record SessionData(
            long deviceId,
            String credentialKeyId,
            DevicePlatform platform,
            CredentialType credentialType,
            Instant expiresAt) {
    }
}
