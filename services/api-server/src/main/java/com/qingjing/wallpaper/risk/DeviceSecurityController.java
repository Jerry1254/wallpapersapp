package com.qingjing.wallpaper.risk;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/device/security")
public class DeviceSecurityController {
    private final RiskService risk;
    private final ClientAddress address;
    private final HarmonyRiskService harmony;
    public DeviceSecurityController(RiskService risk,ClientAddress address,HarmonyRiskService harmony) { this.risk=risk; this.address=address; this.harmony=harmony; }
    @GetMapping("/state") public ResponseEntity<RiskDtos.State> state(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(risk.state(device(request),address.of(request)));
    }
    @PostMapping("/reports") public ResponseEntity<RiskDtos.State> report(@Valid @RequestBody RiskDtos.Report body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(risk.report(device(request),address.of(request),body));
    }
    @PostMapping("/harmony/challenges") public ResponseEntity<RiskDtos.HarmonyChallenge> harmonyChallenge(HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(harmony.challenge(device(request),address.of(request)));
    }
    @PostMapping("/harmony/reports") public ResponseEntity<RiskDtos.State> harmonyReport(@Valid @RequestBody RiskDtos.HarmonyProof body,HttpServletRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(harmony.report(device(request),address.of(request),body));
    }
    private DevicePrincipal device(HttpServletRequest request) { return (DevicePrincipal)request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL); }
}
