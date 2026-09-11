package com.qingjing.wallpaper.entitlement;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementPage;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementSummary;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceEntitlementService {

    private final JdbcTemplate jdbc;
    private final PublicWallpaperViewReader wallpapers;

    public DeviceEntitlementService(JdbcTemplate jdbc, PublicWallpaperViewReader wallpapers) {
        this.jdbc = jdbc;
        this.wallpapers = wallpapers;
    }

    @Transactional(readOnly = true)
    public EntitlementPage list(long deviceId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "page and pageSize are outside the accepted range");
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_entitlement WHERE device_id = ? AND status = 'ACTIVE'",
                Long.class,
                deviceId);
        List<EntitlementRow> rows = jdbc.query(
                """
                SELECT id, wallpaper_id, granted_at
                FROM device_entitlement
                WHERE device_id = ? AND status = 'ACTIVE'
                ORDER BY granted_at DESC, id DESC
                LIMIT ? OFFSET ?
                """,
                (resultSet, rowNumber) -> new EntitlementRow(
                        resultSet.getLong("id"),
                        resultSet.getLong("wallpaper_id"),
                        resultSet.getTimestamp("granted_at").toInstant()),
                deviceId, pageSize, (long) (page - 1) * pageSize);
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new EntitlementPage(
                rows.stream().map(row -> summary(row.id(), row.wallpaperId(), row.grantedAt())).toList(),
                new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    public EntitlementSummary summary(long entitlementId, long wallpaperId, Instant grantedAt) {
        return new EntitlementSummary(Long.toString(entitlementId), wallpapers.summary(wallpaperId), grantedAt);
    }

    private record EntitlementRow(long id, long wallpaperId, Instant grantedAt) {
    }
}
