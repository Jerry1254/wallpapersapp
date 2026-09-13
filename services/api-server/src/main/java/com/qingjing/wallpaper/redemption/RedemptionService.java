package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.entitlement.DeviceEntitlementService;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementSummary;
import com.qingjing.wallpaper.redemption.RedemptionDtos.ProcessingResult;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionAttempt;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResult;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResultCode;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RedemptionService {

    private final JdbcTemplate jdbc;
    private final SecurityCrypto crypto;
    private final RedisRateLimiter rateLimiter;
    private final DeviceEntitlementService entitlements;

    public RedemptionService(
            JdbcTemplate jdbc,
            SecurityCrypto crypto,
            RedisRateLimiter rateLimiter,
            DeviceEntitlementService entitlements) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
        this.entitlements = entitlements;
    }

    @Transactional
    public RedemptionAttempt redeem(long deviceId, String idempotencyKey, long wallpaperId, String suppliedCode) {
        requireUuid(idempotencyKey);
        String normalizedCode = normalizeCode(suppliedCode);
        String codeHash = crypto.hmacHex("redemption-code-v1", normalizedCode);
        String requestHash = crypto.sha256Hex(wallpaperId + "\n" + codeHash);
        rateLimiter.require("redemption-device", Long.toString(deviceId), 20, Duration.ofMinutes(1));
        rateLimiter.require("redemption-code", codeHash, 60, Duration.ofMinutes(1));

        String deviceStatus = jdbc.queryForObject(
                "SELECT status FROM anonymous_device WHERE id = ? FOR UPDATE", String.class, deviceId);
        if (!"ACTIVE".equals(deviceStatus)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DEVICE_DISABLED", "The device is not active");
        }
        // The device row serializes requests from the same device. A locking read on a
        // missing idempotency row would take an InnoDB gap lock and can deadlock when
        // several new devices redeem the same code concurrently.
        List<RequestRow> requests = requestRows(deviceId, idempotencyKey);
        if (!requests.isEmpty()) {
            RequestRow existing = requests.get(0);
            if (!existing.requestHash().equals(requestHash)) {
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED", "The idempotency key was reused with another request");
            }
            if (existing.status().equals("PROCESSING")) {
                return new RedemptionAttempt(null, false, false);
            }
            RedemptionResult result = result(existing.id(), idempotencyKey);
            return new RedemptionAttempt(result, false, isRejected(result.result()));
        }

        KeyHolder requestKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO redemption_request (device_id, idempotency_key, request_hash) VALUES (?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, deviceId);
            statement.setString(2, idempotencyKey);
            statement.setString(3, requestHash);
            return statement;
        }, requestKey);
        long requestId = requiredKey(requestKey, "Redemption request");

        List<EntitlementRow> owned = jdbc.query(
                """
                SELECT id, granted_at FROM device_entitlement
                WHERE device_id = ? AND wallpaper_id = ? AND status = 'ACTIVE'
                """,
                (resultSet, rowNumber) -> new EntitlementRow(
                        resultSet.getLong("id"), resultSet.getTimestamp("granted_at").toInstant()),
                deviceId, wallpaperId);
        if (!owned.isEmpty()) {
            EntitlementRow entitlement = owned.get(0);
            complete(
                    requestId, deviceId, wallpaperId, null, suffix(normalizedCode), entitlement.id(),
                    RedemptionResultCode.ALREADY_OWNED, 0, null, "SUCCEEDED");
            return new RedemptionAttempt(result(requestId, idempotencyKey), false, false);
        }

        List<String> wallpaperStatuses = jdbc.queryForList(
                "SELECT status FROM wallpaper WHERE id = ?", String.class, wallpaperId);
        if (wallpaperStatuses.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
        if (!wallpaperStatuses.get(0).equals("PUBLISHED")) {
            complete(
                    requestId, deviceId, wallpaperId, null, suffix(normalizedCode), null,
                    RedemptionResultCode.WALLPAPER_UNAVAILABLE, 0, "WALLPAPER_UNAVAILABLE", "REJECTED");
            return new RedemptionAttempt(result(requestId, idempotencyKey), false, true);
        }

        List<CodeRow> codes = jdbc.query(
                """
                SELECT id, code_suffix, total_quota, used_quota
                FROM redemption_code WHERE code_hash = ? FOR UPDATE
                """,
                (resultSet, rowNumber) -> new CodeRow(
                        resultSet.getLong("id"),
                        resultSet.getString("code_suffix"),
                        resultSet.getInt("total_quota"),
                        resultSet.getInt("used_quota")),
                codeHash);
        if (codes.isEmpty()) {
            complete(
                    requestId, deviceId, wallpaperId, null, suffix(normalizedCode), null,
                    RedemptionResultCode.CODE_NOT_FOUND, 0, "CODE_NOT_FOUND", "REJECTED");
            return new RedemptionAttempt(result(requestId, idempotencyKey), false, true);
        }
        CodeRow code = codes.get(0);
        if (code.usedQuota() >= code.totalQuota()) {
            complete(
                    requestId, deviceId, wallpaperId, code.id(), code.suffix(), null,
                    RedemptionResultCode.CODE_EXHAUSTED, 0, "CODE_EXHAUSTED", "REJECTED");
            return new RedemptionAttempt(result(requestId, idempotencyKey), false, true);
        }

        int consumed = jdbc.update(
                """
                UPDATE redemption_code
                SET used_quota = used_quota + 1, lock_version = lock_version + 1
                WHERE id = ? AND used_quota < total_quota
                """,
                code.id());
        if (consumed != 1) {
            complete(
                    requestId, deviceId, wallpaperId, code.id(), code.suffix(), null,
                    RedemptionResultCode.CODE_EXHAUSTED, 0, "CODE_EXHAUSTED", "REJECTED");
            return new RedemptionAttempt(result(requestId, idempotencyKey), false, true);
        }

        KeyHolder entitlementKey = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    """
                    INSERT INTO device_entitlement
                        (device_id, wallpaper_id, source_code_id, status, granted_at)
                    VALUES (?, ?, ?, 'ACTIVE', UTC_TIMESTAMP(6))
                    """,
                    Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, deviceId);
            statement.setLong(2, wallpaperId);
            statement.setLong(3, code.id());
            return statement;
        }, entitlementKey);
        long entitlementId = requiredKey(entitlementKey, "Device entitlement");
        complete(
                requestId, deviceId, wallpaperId, code.id(), code.suffix(), entitlementId,
                RedemptionResultCode.GRANTED, 1, null, "SUCCEEDED");
        return new RedemptionAttempt(result(requestId, idempotencyKey), true, false);
    }

    @Transactional(readOnly = true)
    public Object find(long deviceId, String idempotencyKey) {
        requireUuid(idempotencyKey);
        List<RequestRow> requests = requestRows(deviceId, idempotencyKey);
        if (requests.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REDEMPTION_NOT_FOUND", "The redemption result was not found");
        }
        RequestRow request = requests.get(0);
        if (request.status().equals("PROCESSING")) {
            return new ProcessingResult("PROCESSING", idempotencyKey);
        }
        return result(request.id(), idempotencyKey);
    }

    private void complete(
            long requestId,
            long deviceId,
            long wallpaperId,
            Long codeId,
            String codeSuffix,
            Long entitlementId,
            RedemptionResultCode result,
            int quotaDelta,
            String errorCode,
            String requestStatus) {
        jdbc.update(
                """
                INSERT INTO redemption_event
                    (request_id, device_id, wallpaper_id, code_id, code_suffix,
                     entitlement_id, result, quota_delta, error_code)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                requestId, deviceId, wallpaperId, codeId, codeSuffix, entitlementId,
                result.name(), quotaDelta, errorCode);
        jdbc.update(
                """
                UPDATE redemption_request
                SET status = ?, completed_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND status = 'PROCESSING'
                """,
                requestStatus, requestId);
    }

    private RedemptionResult result(long requestId, String idempotencyKey) {
        List<EventRow> events = jdbc.query(
                """
                SELECT e.result, e.quota_delta, e.entitlement_id, e.error_code, e.created_at,
                       de.wallpaper_id, de.granted_at
                FROM redemption_event e
                LEFT JOIN device_entitlement de ON de.id = e.entitlement_id
                WHERE e.request_id = ?
                """,
                (resultSet, rowNumber) -> new EventRow(
                        RedemptionResultCode.valueOf(resultSet.getString("result")),
                        resultSet.getInt("quota_delta"),
                        resultSet.getObject("entitlement_id", Long.class),
                        resultSet.getString("error_code"),
                        resultSet.getTimestamp("created_at").toInstant(),
                        resultSet.getObject("wallpaper_id", Long.class),
                        resultSet.getTimestamp("granted_at") == null ? null : resultSet.getTimestamp("granted_at").toInstant()),
                requestId);
        if (events.isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, "REDEMPTION_PROCESSING", "The redemption result is not complete");
        }
        EventRow event = events.get(0);
        EntitlementSummary entitlement = event.entitlementId() == null
                ? null
                : entitlements.summary(event.entitlementId(), event.wallpaperId(), event.grantedAt());
        return new RedemptionResult(
                idempotencyKey,
                event.result(),
                event.quotaDelta(),
                entitlement,
                event.errorCode(),
                event.createdAt());
    }

    private List<RequestRow> requestRows(long deviceId, String idempotencyKey) {
        return jdbc.query(
                """
                SELECT id, request_hash, status
                FROM redemption_request
                WHERE device_id = ? AND idempotency_key = ?
                """,
                (resultSet, rowNumber) -> new RequestRow(
                        resultSet.getLong("id"),
                        resultSet.getString("request_hash"),
                        resultSet.getString("status")),
                deviceId, idempotencyKey);
    }

    private String normalizeCode(String supplied) {
        String normalized = supplied.replace("-", "").replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{20}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The redemption code format is invalid");
        }
        return normalized;
    }

    private String suffix(String code) {
        return code.substring(code.length() - 5);
    }

    private void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key must be a UUID");
        }
    }

    private boolean isRejected(RedemptionResultCode result) {
        return result != RedemptionResultCode.GRANTED && result != RedemptionResultCode.ALREADY_OWNED;
    }

    private long requiredKey(KeyHolder keyHolder, String aggregate) {
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException(aggregate + " insert returned no identifier");
        }
        return key.longValue();
    }

    private record RequestRow(long id, String requestHash, String status) {
    }

    private record CodeRow(long id, String suffix, int totalQuota, int usedQuota) {
    }

    private record EntitlementRow(long id, Instant grantedAt) {
    }

    private record EventRow(
            RedemptionResultCode result,
            int quotaDelta,
            Long entitlementId,
            String errorCode,
            Instant createdAt,
            Long wallpaperId,
            Instant grantedAt) {
    }
}
