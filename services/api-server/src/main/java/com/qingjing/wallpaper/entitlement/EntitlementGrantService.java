package com.qingjing.wallpaper.entitlement;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;

/** Maintains the entitlement aggregate while preserving each independent grant source. */
@Service
public class EntitlementGrantService {

    private final JdbcTemplate jdbc;

    public EntitlementGrantService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long grant(
            long deviceId,
            long wallpaperId,
            SourceType sourceType,
            String sourceReference,
            Long freeGeneration,
            Long sourceCodeId) {
        List<Long> existingGrant = jdbc.query(
                "SELECT entitlement_id FROM entitlement_grant WHERE source_type=? AND source_reference=?",
                (rs, row) -> rs.getLong(1), sourceType.name(), sourceReference);
        if (!existingGrant.isEmpty()) {
            return existingGrant.get(0);
        }

        List<Long> entitlementIds = jdbc.query(
                "SELECT id FROM device_entitlement WHERE device_id=? AND wallpaper_id=? FOR UPDATE",
                (rs, row) -> rs.getLong(1), deviceId, wallpaperId);
        long entitlementId;
        if (entitlementIds.isEmpty()) {
            GeneratedKeyHolder key = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO device_entitlement
                            (device_id,wallpaper_id,source_code_id,status,granted_at)
                        VALUES (?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, deviceId);
                statement.setLong(2, wallpaperId);
                if (sourceCodeId == null) statement.setNull(3, java.sql.Types.BIGINT);
                else statement.setLong(3, sourceCodeId);
                return statement;
            }, key);
            entitlementId = key.getKey().longValue();
        } else {
            entitlementId = entitlementIds.get(0);
            jdbc.update(
                    """
                    UPDATE device_entitlement
                    SET status='ACTIVE', revoked_at=NULL, revoke_reason=NULL,
                        granted_at=LEAST(granted_at,UTC_TIMESTAMP(6)), lock_version=lock_version+1
                    WHERE id=?
                    """,
                    entitlementId);
        }
        jdbc.update(
                """
                INSERT INTO entitlement_grant
                    (entitlement_id,source_type,source_reference,free_generation,status,granted_at)
                VALUES (?,?,?,?,'ACTIVE',UTC_TIMESTAMP(6))
                """,
                entitlementId, sourceType.name(), sourceReference, freeGeneration);
        return entitlementId;
    }

    public void revoke(SourceType sourceType, String sourceReference, String reason) {
        List<Long> ids = jdbc.query(
                """
                SELECT entitlement_id FROM entitlement_grant
                WHERE source_type=? AND source_reference=? AND status='ACTIVE' FOR UPDATE
                """,
                (rs, row) -> rs.getLong(1), sourceType.name(), sourceReference);
        if (ids.isEmpty()) return;
        long entitlementId = ids.get(0);
        jdbc.update(
                """
                UPDATE entitlement_grant SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6),revoke_reason=?
                WHERE source_type=? AND source_reference=? AND status='ACTIVE'
                """,
                reason, sourceType.name(), sourceReference);
        Long active = jdbc.queryForObject(
                "SELECT COUNT(*) FROM entitlement_grant WHERE entitlement_id=? AND status='ACTIVE'",
                Long.class, entitlementId);
        if (active != null && active == 0) {
            jdbc.update(
                    """
                    UPDATE device_entitlement
                    SET status='REVOKED',revoked_at=UTC_TIMESTAMP(6),revoke_reason=?,lock_version=lock_version+1
                    WHERE id=? AND status='ACTIVE'
                    """,
                    reason, entitlementId);
        }
    }

    public void revokeFirstFreeGeneration(long deviceId, long generation, String reason) {
        List<String> references = jdbc.query(
                """
                SELECT eg.source_reference
                FROM entitlement_grant eg JOIN device_entitlement de ON de.id=eg.entitlement_id
                WHERE de.device_id=? AND eg.source_type='IOS_FIRST_FREE'
                  AND eg.free_generation=? AND eg.status='ACTIVE'
                FOR UPDATE
                """,
                (rs, row) -> rs.getString(1), deviceId, generation);
        references.forEach(reference -> revoke(SourceType.IOS_FIRST_FREE, reference, reason));
    }

    public enum SourceType { REDEMPTION, IOS_FIRST_FREE, IOS_IAP }
}
