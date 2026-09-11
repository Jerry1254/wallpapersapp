package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.device.DeviceDtos.CredentialType;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDeviceCredential;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDeviceDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDevicePage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDeviceSummary;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminEntitlement;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionPage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionSummary;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CredentialStatus;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.DeviceStatus;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.EntitlementStatus;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.WallpaperRef;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResultCode;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminRedemptionViewService {

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    public AdminRedemptionViewService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Transactional(readOnly = true)
    public AdminRedemptionPage redemptions(
            int page,
            int pageSize,
            String codeSuffix,
            Long wallpaperId,
            Long deviceId,
            RedemptionResultCode result,
            Instant createdFrom,
            Instant createdTo) {
        validatePage(page, pageSize);
        String suffix = normalizeSuffix(codeSuffix);
        if (createdFrom != null && createdTo != null && createdFrom.isAfter(createdTo)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "createdFrom must not be after createdTo");
        }
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (suffix != null) {
            where.append(" AND e.code_suffix LIKE :suffix");
            parameters.addValue("suffix", "%" + suffix);
        }
        if (wallpaperId != null) {
            where.append(" AND e.wallpaper_id = :wallpaperId");
            parameters.addValue("wallpaperId", wallpaperId);
        }
        if (deviceId != null) {
            where.append(" AND e.device_id = :deviceId");
            parameters.addValue("deviceId", deviceId);
        }
        if (result != null) {
            where.append(" AND e.result = :result");
            parameters.addValue("result", result.name());
        }
        if (createdFrom != null) {
            where.append(" AND e.created_at >= :createdFrom");
            parameters.addValue("createdFrom", java.sql.Timestamp.from(createdFrom));
        }
        if (createdTo != null) {
            where.append(" AND e.created_at <= :createdTo");
            parameters.addValue("createdTo", java.sql.Timestamp.from(createdTo));
        }
        String from = redemptionFrom();
        Long total = namedJdbc.queryForObject("SELECT COUNT(*)" + from + where, parameters, Long.class);
        parameters.addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize);
        List<RedemptionRow> rows = namedJdbc.query(
                redemptionSelect() + from + where + " ORDER BY e.created_at DESC, e.id DESC LIMIT :limit OFFSET :offset",
                parameters,
                (resultSet, rowNumber) -> redemptionRow(resultSet));
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new AdminRedemptionPage(
                rows.stream().map(this::redemptionSummary).toList(),
                new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public AdminRedemptionDetail redemption(long redemptionId) {
        List<RedemptionRow> rows = jdbc.query(
                redemptionSelect() + redemptionFrom() + " WHERE e.id = ?",
                (resultSet, rowNumber) -> redemptionRow(resultSet),
                redemptionId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "REDEMPTION_NOT_FOUND", "The redemption event was not found");
        }
        RedemptionRow row = rows.get(0);
        AdminRedemptionSummary summary = redemptionSummary(row);
        AdminDeviceSummary device = deviceSummary(row.deviceId());
        AdminEntitlement entitlement = row.entitlementId() == null ? null : entitlement(row.entitlementId());
        return new AdminRedemptionDetail(
                summary.id(), summary.redemptionRequestId(), summary.idempotencyKey(), summary.deviceId(),
                summary.codeId(), summary.maskedCode(), summary.codeSuffix(), summary.wallpaper(), summary.result(),
                summary.quotaDelta(), summary.errorCode(), summary.createdAt(), device, entitlement);
    }

    @Transactional(readOnly = true)
    public AdminDevicePage devices(
            int page,
            int pageSize,
            DevicePlatform platform,
            DeviceStatus status) {
        validatePage(page, pageSize);
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> arguments = new ArrayList<>();
        if (platform != null) {
            where.append(" AND d.platform = ?");
            arguments.add(platform.name());
        }
        if (status != null) {
            where.append(" AND d.status = ?");
            arguments.add(status.name());
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM anonymous_device d" + where,
                Long.class,
                arguments.toArray());
        arguments.add(pageSize);
        arguments.add((long) (page - 1) * pageSize);
        List<AdminDeviceSummary> items = jdbc.query(
                deviceSelect() + where + " GROUP BY d.id ORDER BY d.last_seen_at DESC, d.id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> deviceSummaryRow(resultSet),
                arguments.toArray());
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new AdminDevicePage(items, new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public AdminDeviceDetail device(long deviceId) {
        AdminDeviceSummary summary = deviceSummary(deviceId);
        List<AdminDeviceCredential> credentials = jdbc.query(
                """
                SELECT credential_key_id, credential_type, status, last_used_at, revoked_at
                FROM device_credential
                WHERE device_id = ?
                ORDER BY created_at DESC, id DESC
                """,
                (resultSet, rowNumber) -> new AdminDeviceCredential(
                        resultSet.getString("credential_key_id"),
                        CredentialType.valueOf(resultSet.getString("credential_type")),
                        CredentialStatus.valueOf(resultSet.getString("status")),
                        timestamp(resultSet, "last_used_at"),
                        timestamp(resultSet, "revoked_at")),
                deviceId);
        List<AdminEntitlement> entitlements = jdbc.query(
                """
                SELECT de.id, de.status, de.granted_at, de.revoked_at,
                       w.id AS wallpaper_id, w.title, w.slug
                FROM device_entitlement de
                JOIN wallpaper w ON w.id = de.wallpaper_id
                WHERE de.device_id = ?
                ORDER BY de.granted_at DESC, de.id DESC
                """,
                (resultSet, rowNumber) -> new AdminEntitlement(
                        Long.toString(resultSet.getLong("id")),
                        new WallpaperRef(
                                Long.toString(resultSet.getLong("wallpaper_id")),
                                resultSet.getString("title"),
                                resultSet.getString("slug")),
                        EntitlementStatus.valueOf(resultSet.getString("status")),
                        timestamp(resultSet, "granted_at"),
                        timestamp(resultSet, "revoked_at")),
                deviceId);
        return new AdminDeviceDetail(
                summary.id(), summary.platform(), summary.appInstallScope(), summary.status(),
                summary.entitlementCount(), summary.lastSeenAt(), summary.createdAt(), credentials, entitlements);
    }

    private AdminDeviceSummary deviceSummary(long deviceId) {
        List<AdminDeviceSummary> rows = jdbc.query(
                deviceSelect() + " WHERE d.id = ? GROUP BY d.id",
                (resultSet, rowNumber) -> deviceSummaryRow(resultSet),
                deviceId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "The device was not found");
        }
        return rows.get(0);
    }

    private AdminEntitlement entitlement(long entitlementId) {
        return jdbc.queryForObject(
                """
                SELECT de.id, de.status, de.granted_at, de.revoked_at,
                       w.id AS wallpaper_id, w.title, w.slug
                FROM device_entitlement de
                JOIN wallpaper w ON w.id = de.wallpaper_id
                WHERE de.id = ?
                """,
                (resultSet, rowNumber) -> new AdminEntitlement(
                        Long.toString(resultSet.getLong("id")),
                        new WallpaperRef(
                                Long.toString(resultSet.getLong("wallpaper_id")),
                                resultSet.getString("title"),
                                resultSet.getString("slug")),
                        EntitlementStatus.valueOf(resultSet.getString("status")),
                        timestamp(resultSet, "granted_at"),
                        timestamp(resultSet, "revoked_at")),
                entitlementId);
    }

    private String redemptionSelect() {
        return """
                SELECT e.id, e.request_id, rr.idempotency_key, e.device_id,
                       e.code_id, e.code_suffix, e.entitlement_id, e.result,
                       e.quota_delta, e.error_code, e.created_at,
                       w.id AS wallpaper_id, w.title, w.slug
                """;
    }

    private String redemptionFrom() {
        return """
                 FROM redemption_event e
                 JOIN redemption_request rr ON rr.id = e.request_id
                 JOIN wallpaper w ON w.id = e.wallpaper_id
                """;
    }

    private RedemptionRow redemptionRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new RedemptionRow(
                resultSet.getLong("id"),
                resultSet.getLong("request_id"),
                resultSet.getString("idempotency_key"),
                resultSet.getLong("device_id"),
                resultSet.getObject("code_id", Long.class),
                resultSet.getString("code_suffix"),
                resultSet.getObject("entitlement_id", Long.class),
                RedemptionResultCode.valueOf(resultSet.getString("result")),
                resultSet.getInt("quota_delta"),
                resultSet.getString("error_code"),
                timestamp(resultSet, "created_at"),
                resultSet.getLong("wallpaper_id"),
                resultSet.getString("title"),
                resultSet.getString("slug"));
    }

    private AdminRedemptionSummary redemptionSummary(RedemptionRow row) {
        return new AdminRedemptionSummary(
                Long.toString(row.id()),
                Long.toString(row.requestId()),
                row.idempotencyKey(),
                Long.toString(row.deviceId()),
                row.codeId() == null ? null : Long.toString(row.codeId()),
                masked(row.codeSuffix()),
                row.codeSuffix(),
                new WallpaperRef(Long.toString(row.wallpaperId()), row.wallpaperTitle(), row.wallpaperSlug()),
                row.result(),
                row.quotaDelta(),
                row.errorCode(),
                row.createdAt());
    }

    private String deviceSelect() {
        return """
                SELECT d.id, d.platform, d.app_install_scope, d.status, d.last_seen_at, d.created_at,
                       COUNT(CASE WHEN de.status = 'ACTIVE' THEN 1 END) AS entitlement_count
                FROM anonymous_device d
                LEFT JOIN device_entitlement de ON de.device_id = d.id
                """;
    }

    private AdminDeviceSummary deviceSummaryRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new AdminDeviceSummary(
                Long.toString(resultSet.getLong("id")),
                DevicePlatform.valueOf(resultSet.getString("platform")),
                resultSet.getString("app_install_scope"),
                DeviceStatus.valueOf(resultSet.getString("status")),
                resultSet.getLong("entitlement_count"),
                timestamp(resultSet, "last_seen_at"),
                timestamp(resultSet, "created_at"));
    }

    private Instant timestamp(java.sql.ResultSet resultSet, String column) throws java.sql.SQLException {
        java.sql.Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private String normalizeSuffix(String suffix) {
        if (suffix == null || suffix.isBlank()) {
            return null;
        }
        String normalized = suffix.strip().toUpperCase(java.util.Locale.ROOT);
        if (!normalized.matches("^[A-Z0-9]{4,8}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The code suffix is invalid");
        }
        return normalized;
    }

    private String masked(String suffix) {
        return suffix == null ? null : "*****-*****-*****-" + suffix;
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "page and pageSize are outside the accepted range");
        }
    }

    private record RedemptionRow(
            long id,
            long requestId,
            String idempotencyKey,
            long deviceId,
            Long codeId,
            String codeSuffix,
            Long entitlementId,
            RedemptionResultCode result,
            int quotaDelta,
            String errorCode,
            Instant createdAt,
            long wallpaperId,
            String wallpaperTitle,
            String wallpaperSlug) {
    }
}
