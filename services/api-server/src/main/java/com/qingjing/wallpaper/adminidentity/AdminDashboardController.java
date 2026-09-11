package com.qingjing.wallpaper.adminidentity;

import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/dashboard")
public class AdminDashboardController {

    private final JdbcTemplate jdbc;

    public AdminDashboardController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    Dashboard dashboard() {
        return new Dashboard(
                count("SELECT COUNT(*) FROM wallpaper WHERE status = 'PUBLISHED'"),
                count("SELECT COUNT(*) FROM anonymous_device WHERE status = 'ACTIVE'"),
                count("SELECT COUNT(*) FROM device_entitlement WHERE status = 'ACTIVE'"),
                count("SELECT COUNT(*) FROM redemption_event WHERE created_at >= UTC_DATE()"),
                Instant.now());
    }

    private long count(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    public record Dashboard(
            long publishedWallpaperCount,
            long activeDeviceCount,
            long entitlementCount,
            long redemptionCountToday,
            Instant generatedAt) {
    }
}
