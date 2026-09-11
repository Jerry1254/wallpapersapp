package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.entitlement.DeviceEntitlementService;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementPage;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedeemWallpaperRequest;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionAttempt;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResult;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceRedemptionController {

    private final RedemptionService redemptions;
    private final DeviceEntitlementService entitlements;

    public DeviceRedemptionController(RedemptionService redemptions, DeviceEntitlementService entitlements) {
        this.redemptions = redemptions;
        this.entitlements = entitlements;
    }

    @GetMapping("/me/entitlements")
    EntitlementPage entitlements(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            HttpServletRequest request) {
        return entitlements.list(principal(request).deviceId(), page, pageSize);
    }

    @PostMapping("/redemptions")
    ResponseEntity<?> redeem(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody RedeemWallpaperRequest body,
            HttpServletRequest request) {
        RedemptionAttempt attempt = redemptions.redeem(
                principal(request).deviceId(),
                idempotencyKey,
                Ids.parse(body.wallpaperId(), "wallpaperId"),
                body.code());
        if (attempt.result() == null) {
            return ResponseEntity.accepted().body(new RedemptionDtos.ProcessingResult("PROCESSING", idempotencyKey));
        }
        if (attempt.rejected()) {
            return ResponseEntity.unprocessableEntity().body(attempt.result());
        }
        return ResponseEntity.status(attempt.created() ? 201 : 200).body(attempt.result());
    }

    @GetMapping("/redemptions/{idempotencyKey}")
    ResponseEntity<?> result(@PathVariable String idempotencyKey, HttpServletRequest request) {
        Object result = redemptions.find(principal(request).deviceId(), idempotencyKey);
        return ResponseEntity.status(result instanceof RedemptionResult ? 200 : 202).body(result);
    }

    private DevicePrincipal principal(HttpServletRequest request) {
        return (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
    }
}
