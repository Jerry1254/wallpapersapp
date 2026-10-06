package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Distribution follows the registered installation, never a client-supplied channel parameter. */
@Component
public class WallpaperChannelAccess {
    public static final String OFFLINE_SCOPE = "com.jiyi.wallpaper";
    private final JdbcTemplate jdbc;

    public WallpaperChannelAccess(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public String distributionChannel(DevicePrincipal principal) {
        return isOffline(principal) ? "OFFLINE" : "ONLINE";
    }

    public boolean isOffline(DevicePrincipal principal) {
        return principal != null && principal.platform() == DevicePlatform.ANDROID
                && isOfflineDevice(principal.deviceId());
    }

    public boolean isOfflineDevice(long deviceId) {
        return Long.valueOf(1).equals(jdbc.queryForObject("""
            SELECT COUNT(*) FROM anonymous_device
            WHERE id=? AND status='ACTIVE' AND platform='ANDROID' AND app_install_scope=?
            """, Long.class, deviceId, OFFLINE_SCOPE));
    }

    /** Public callers use deviceId=0; existing tickets must recheck this live database fact. */
    public boolean visible(long wallpaperId, long deviceId) {
        return !Long.valueOf(1).equals(jdbc.queryForObject("""
            SELECT COUNT(*) FROM wallpaper w WHERE w.id=? AND w.offline_promotion_only=TRUE
              AND NOT EXISTS (SELECT 1 FROM anonymous_device d WHERE d.id=? AND d.status='ACTIVE'
                AND d.platform='ANDROID' AND d.app_install_scope=?)
            """, Long.class, wallpaperId, deviceId, OFFLINE_SCOPE));
    }

    public void requireVisible(long wallpaperId, long deviceId) {
        if (!visible(wallpaperId, deviceId))
            throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","The wallpaper was not found");
    }
}
