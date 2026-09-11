package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.catalog.PublicWallpaperViewReader;
import com.qingjing.wallpaper.delivery.DeliveryDtos.CreateDownloadTicketRequest;
import com.qingjing.wallpaper.delivery.DeliveryDtos.DeliveryMode;
import com.qingjing.wallpaper.delivery.DeliveryDtos.DownloadDescriptor;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class DownloadTicketService {

    private final JdbcTemplate jdbc;
    private final PublicWallpaperViewReader wallpapers;
    private final RedisRateLimiter rateLimiter;

    public DownloadTicketService(
            JdbcTemplate jdbc,
            PublicWallpaperViewReader wallpapers,
            RedisRateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.wallpapers = wallpapers;
        this.rateLimiter = rateLimiter;
    }

    public DownloadDescriptor create(
            DevicePrincipal principal,
            long wallpaperId,
            CreateDownloadTicketRequest request) {
        rateLimiter.require("download-ticket", Long.toString(principal.deviceId()), 30, Duration.ofMinutes(1));
        if (request.platform() != principal.platform()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_MISMATCH", "The requested platform does not match the device session");
        }
        Integer entitlement = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM device_entitlement
                WHERE device_id = ? AND wallpaper_id = ? AND status = 'ACTIVE'
                """,
                Integer.class,
                principal.deviceId(), wallpaperId);
        if (entitlement == null || entitlement != 1) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ENTITLEMENT_REQUIRED", "An active entitlement is required");
        }
        var wallpaper = wallpapers.summary(wallpaperId);
        if (principal.platform() == DevicePlatform.H5_TEST) {
            return new DownloadDescriptor(
                    DeliveryMode.H5_PLACEHOLDER,
                    Long.toString(wallpaperId),
                    wallpaper.cover(),
                    null,
                    null,
                    null,
                    null,
                    null);
        }
        throw new ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "SECURE_PACKAGE_NOT_READY",
                "A secure package is not available for this platform");
    }

    public byte[] readProtectedFile(String ticket) {
        throw new ApiException(HttpStatus.UNAUTHORIZED, "DOWNLOAD_TICKET_INVALID", "The download ticket is invalid or expired");
    }
}
