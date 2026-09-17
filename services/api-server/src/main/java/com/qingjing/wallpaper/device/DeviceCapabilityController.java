package com.qingjing.wallpaper.device;

import com.qingjing.wallpaper.device.DeviceCapabilityDtos.DeviceCapabilityProfile;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.DeviceCapabilityReportRequest;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device/me/capabilities")
public class DeviceCapabilityController {

    private final DeviceCapabilityService capabilities;

    public DeviceCapabilityController(DeviceCapabilityService capabilities) {
        this.capabilities = capabilities;
    }

    @PutMapping
    DeviceCapabilityProfile report(
            @Valid @RequestBody DeviceCapabilityReportRequest body,
            HttpServletRequest request) {
        return capabilities.report(principal(request), body);
    }

    @GetMapping
    DeviceCapabilityProfile get(HttpServletRequest request) {
        return capabilities.require(principal(request).deviceId());
    }

    private DevicePrincipal principal(HttpServletRequest request) {
        return (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
    }
}
