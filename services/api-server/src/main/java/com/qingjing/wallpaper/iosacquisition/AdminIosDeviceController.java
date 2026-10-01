package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.*;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/devices/{deviceId}")
public class AdminIosDeviceController {
    private final AdminIosDeviceService devices;
    public AdminIosDeviceController(AdminIosDeviceService devices){this.devices=devices;}

    @PutMapping("/ios-test-status")
    ResponseEntity<Void> setTest(
            @PathVariable String deviceId,@Valid @RequestBody SetTestDeviceRequest body,HttpServletRequest request){
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,Map.of("testDevice",body.testDevice(),"reason",body.reason()));
        devices.setTestDevice(Ids.parse(deviceId,"deviceId"),body.testDevice());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/ios-free-resets")
    ResponseEntity<ResetOperation> createReset(
            @PathVariable String deviceId,@RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CreateResetRequest body,HttpServletRequest request){
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,Map.of("reason",body.reason()));
        AdminPrincipal admin=(AdminPrincipal)request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        return ResponseEntity.status(201).body(devices.createReset(
                Ids.parse(deviceId,"deviceId"),admin.id(),idempotencyKey,body.expectedGeneration(),body.reason()));
    }

    @GetMapping("/ios-free-resets/{resetId}")
    ResetOperation getReset(@PathVariable String deviceId,@PathVariable String resetId){return devices.getReset(Ids.parse(deviceId,"deviceId"),resetId);}

    @PostMapping("/ios-free-resets/{resetId}/cancel")
    ResetOperation cancel(@PathVariable String deviceId,@PathVariable String resetId){return devices.cancel(Ids.parse(deviceId,"deviceId"),resetId);}
}
