package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDeviceDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminDevicePage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionPage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.DeviceStatus;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResultCode;
import com.qingjing.wallpaper.shared.web.Ids;
import java.time.Instant;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminRedemptionQueryController {

    private final AdminRedemptionViewService views;

    public AdminRedemptionQueryController(AdminRedemptionViewService views) {
        this.views = views;
    }

    @GetMapping("/redemptions")
    AdminRedemptionPage redemptions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String codeSuffix,
            @RequestParam(required = false) String wallpaperId,
            @RequestParam(required = false) String deviceId,
            @RequestParam(required = false) RedemptionResultCode result,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo) {
        return views.redemptions(
                page,
                pageSize,
                codeSuffix,
                wallpaperId == null ? null : Ids.parse(wallpaperId, "wallpaperId"),
                deviceId == null ? null : Ids.parse(deviceId, "deviceId"),
                result,
                createdFrom,
                createdTo);
    }

    @GetMapping("/redemptions/{redemptionId}")
    AdminRedemptionDetail redemption(@PathVariable String redemptionId) {
        return views.redemption(Ids.parse(redemptionId, "redemptionId"));
    }

    @GetMapping("/devices")
    AdminDevicePage devices(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) DevicePlatform platform,
            @RequestParam(required = false) DeviceStatus status) {
        return views.devices(page, pageSize, platform, status);
    }

    @GetMapping("/devices/{deviceId}")
    AdminDeviceDetail device(@PathVariable String deviceId) {
        return views.device(Ids.parse(deviceId, "deviceId"));
    }
}
