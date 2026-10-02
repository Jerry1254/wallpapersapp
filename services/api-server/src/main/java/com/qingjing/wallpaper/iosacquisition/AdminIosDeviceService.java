package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.ResetOperation;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminIosDeviceService {
    private final JdbcTemplate jdbc;
    private final IosAcquisitionProperties properties;

    public AdminIosDeviceService(JdbcTemplate jdbc, IosAcquisitionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional
    public void setTestDevice(long deviceId, boolean testDevice) {
        requireActiveIosDevice(deviceId);
        ensureInstallation(deviceId);
        jdbc.queryForObject("SELECT device_id FROM ios_installation_acquisition WHERE device_id=? FOR UPDATE", Long.class, deviceId);
        if (!testDevice && hasPendingReset(deviceId)) {
            throw conflict("IOS_FREE_RESET_PENDING", "Cancel or finish the pending reset first");
        }
        jdbc.update("UPDATE ios_installation_acquisition SET is_test_device=?,lock_version=lock_version+1 WHERE device_id=?",
                testDevice, deviceId);
    }

    @Transactional
    public ResetOperation createReset(long deviceId, long adminId, String idempotencyKey, long expectedGeneration, String reason) {
        requireUuid(idempotencyKey, "Idempotency-Key");
        requireActiveIosDevice(deviceId);
        ensureInstallation(deviceId);
        jdbc.queryForObject("SELECT device_id FROM ios_installation_acquisition WHERE device_id=? FOR UPDATE", Long.class, deviceId);
        List<ResetOperation> existing = jdbc.query(
                "SELECT id,expected_generation,result_generation,status,error_code,expires_at,created_at,completed_at FROM ios_free_reset WHERE device_id=? AND idempotency_key=?",
                (rs,row)->operation(rs,deviceId),deviceId,idempotencyKey);
        if (!existing.isEmpty()) {
            String originalReason = jdbc.queryForObject("SELECT reason FROM ios_free_reset WHERE device_id=? AND idempotency_key=?", String.class, deviceId, idempotencyKey);
            if (existing.get(0).expectedGeneration() != expectedGeneration || !reason.equals(originalReason)) throw conflict("IDEMPOTENCY_CONFLICT", "The reset request parameters changed");
            return existing.get(0);
        }
        Boolean test = jdbc.queryForObject("SELECT is_test_device FROM ios_installation_acquisition WHERE device_id=? FOR UPDATE",Boolean.class,deviceId);
        if (!Boolean.TRUE.equals(test)) throw new ApiException(HttpStatus.FORBIDDEN,"IOS_TEST_DEVICE_REQUIRED","The device is not marked as an iOS test device");
        if (hasPendingReset(deviceId)) throw conflict("IOS_FREE_RESET_PENDING","Another free reset is pending");
        Long pendingClaims=jdbc.queryForObject("SELECT COUNT(*) FROM ios_free_claim WHERE device_id=? AND status IN ('PROCESSING','APPLE_WRITE_STARTED','RECONCILING','APPLE_CONFIRMED')",Long.class,deviceId);
        if (pendingClaims != null && pendingClaims > 0) {
            throw conflict("IOS_FREE_CLAIM_PENDING", "A free claim is still pending");
        }
        Long generation=jdbc.queryForObject("SELECT free_generation FROM ios_installation_acquisition WHERE device_id=?",Long.class,deviceId);
        if (generation == null || generation != expectedGeneration) {
            throw conflict("IOS_FREE_GENERATION_CONFLICT", "The free generation changed");
        }
        String resetId = UUID.randomUUID().toString();
        Instant expiresAt = Instant.now().plus(properties.getResetTtl());
        jdbc.update("""
                INSERT INTO ios_free_reset
                    (id,device_id,expected_generation,idempotency_key,created_by_admin_id,reason,status,expires_at)
                VALUES (?,?,?,?,?,?,'WAITING_DEVICE',?)
                """,resetId,deviceId,generation,idempotencyKey,adminId,reason,Timestamp.from(expiresAt));
        jdbc.update("UPDATE ios_installation_acquisition SET active_reset_id=?,free_allowance_status='PENDING_RESET',lock_version=lock_version+1 WHERE device_id=?",resetId,deviceId);
        return getReset(deviceId,resetId);
    }

    @Transactional(readOnly=true)
    public ResetOperation getReset(long deviceId, String resetId) {
        List<ResetOperation> rows = jdbc.query(
                "SELECT id,expected_generation,result_generation,status,error_code,expires_at,created_at,completed_at FROM ios_free_reset WHERE id=? AND device_id=?",
                (rs, row) -> operation(rs, deviceId), resetId, deviceId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "IOS_FREE_RESET_NOT_FOUND", "The reset was not found");
        }
        return rows.get(0);
    }

    @Transactional
    public ResetOperation cancel(long deviceId, String resetId) {
        jdbc.queryForObject("SELECT device_id FROM ios_installation_acquisition WHERE device_id=? FOR UPDATE", Long.class, deviceId);
        ResetOperation current = getReset(deviceId, resetId);
        if (!current.status().equals("WAITING_DEVICE")) {
            throw conflict("IOS_FREE_RESET_NOT_CANCELLABLE", "The reset already started Apple processing");
        }
        int updated = jdbc.update(
                "UPDATE ios_free_reset SET status='CANCELLED',cancelled_at=UTC_TIMESTAMP(6) WHERE id=? AND status='WAITING_DEVICE'",
                resetId);
        if (updated != 1) throw conflict("IOS_FREE_RESET_NOT_CANCELLABLE", "The reset already started");
        jdbc.update(
                "UPDATE ios_installation_acquisition SET active_reset_id=NULL,free_allowance_status='UNAVAILABLE',lock_version=lock_version+1 WHERE device_id=? AND active_reset_id=?",
                deviceId, resetId);
        return getReset(deviceId, resetId);
    }

    private void ensureInstallation(long deviceId) {
        jdbc.update(
                "INSERT IGNORE INTO ios_installation_acquisition (device_id,account_token,free_allowance_status) VALUES (?,?,'UNAVAILABLE')",
                deviceId, UUID.randomUUID().toString());
    }

    private void requireActiveIosDevice(long deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM anonymous_device WHERE id=? AND platform='IOS' AND status='ACTIVE'",
                Long.class, deviceId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "An active iOS device was not found");
        }
    }

    private boolean hasPendingReset(long deviceId) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM ios_free_reset WHERE device_id=? AND status IN ('WAITING_DEVICE','PROCESSING','APPLE_RESET_CONFIRMED','RETRYABLE_FAILURE')",
                Long.class, deviceId);
        return count != null && count > 0;
    }

    private ResetOperation operation(java.sql.ResultSet rs, long deviceId) throws java.sql.SQLException {
        return new ResetOperation(
                rs.getString("id"), Long.toString(deviceId), rs.getLong("expected_generation"),
                rs.getObject("result_generation", Long.class), rs.getString("status"), rs.getString("error_code"),
                instant(rs, "expires_at"), instant(rs, "created_at"), instant(rs, "completed_at"));
    }

    private Instant instant(java.sql.ResultSet rs, String name) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toInstant();
    }

    private void requireUuid(String value, String field) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", field + " must be a UUID");
        }
    }

    private ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
