package com.qingjing.wallpaper.entitlement;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.catalog.DeviceCatalogVisibility;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementPage;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementSummary;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Instant;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceEntitlementService {

    private final JdbcTemplate jdbc;
    private final PublicWallpaperViewReader wallpapers;
    private final DeviceCatalogVisibility visibility;
    private final NamedParameterJdbcTemplate namedJdbc;

    public DeviceEntitlementService(
            JdbcTemplate jdbc, PublicWallpaperViewReader wallpapers, DeviceCatalogVisibility visibility) {
        this.jdbc = jdbc;
        this.wallpapers = wallpapers;
        this.visibility = visibility;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
    }

    @Transactional(readOnly = true)
    public EntitlementPage list(long deviceId, int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "page and pageSize are outside the accepted range");
        }
        var visible = visibility.resolve(deviceId);
        if (visible.wallpaperIds().isEmpty()) {
            return new EntitlementPage(List.of(), new PageMetadata(page, pageSize, 0, 0));
        }
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("deviceId", deviceId)
                .addValue("wallpaperIds", visible.wallpaperIds());
        Long total = namedJdbc.queryForObject(
                "SELECT COUNT(*) FROM device_entitlement WHERE device_id = :deviceId AND status = 'ACTIVE' AND wallpaper_id IN (:wallpaperIds)",
                parameters, Long.class);
        parameters.addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize);
        List<EntitlementRow> rows = namedJdbc.query(
                """
                SELECT id, wallpaper_id, granted_at
                FROM device_entitlement
                WHERE device_id = :deviceId AND status = 'ACTIVE' AND wallpaper_id IN (:wallpaperIds)
                ORDER BY granted_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """,
                parameters,
                (resultSet, rowNumber) -> new EntitlementRow(
                        resultSet.getLong("id"),
                        resultSet.getLong("wallpaper_id"),
                        resultSet.getTimestamp("granted_at").toInstant()));
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new EntitlementPage(
                rows.stream().map(row -> new EntitlementSummary(
                        Long.toString(row.id()),
                        wallpapers.summary(row.wallpaperId(), visible.capabilities(row.wallpaperId())),
                        row.grantedAt())).toList(),
                new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    public EntitlementSummary summary(long deviceId, long entitlementId, long wallpaperId, Instant grantedAt) {
        var visible = visibility.resolve(deviceId);
        if (!visible.contains(wallpaperId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_AVAILABLE_FOR_DEVICE",
                    "The wallpaper is not available for this device");
        }
        return new EntitlementSummary(Long.toString(entitlementId),
                wallpapers.summary(wallpaperId, visible.capabilities(wallpaperId)), grantedAt);
    }

    private record EntitlementRow(long id, long wallpaperId, Instant grantedAt) {
    }
}
