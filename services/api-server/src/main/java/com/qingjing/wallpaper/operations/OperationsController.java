package com.qingjing.wallpaper.operations;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1")
public class OperationsController {
    private final OperationsService operations;
    public OperationsController(OperationsService operations) { this.operations=operations; }
    @GetMapping("/admin/operations/overview")
    OperationsService.Overview overview(@RequestParam(defaultValue="7") int days,@RequestParam(required=false) String channel) {
        return operations.overview(days,channel);
    }
    @PostMapping("/device/activity")
    ResponseEntity<Void> activity(@Valid @RequestBody OperationsService.Activity body,HttpServletRequest request) {
        DevicePrincipal device=(DevicePrincipal)request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        operations.recordActivity(device.deviceId(),body,request.getHeader("X-App-Version-Name"),request.getHeader("X-App-Version-Code"));
        return ResponseEntity.noContent().build();
    }
    @PutMapping("/admin/devices/{deviceId}/note")
    OperationsService.Note note(@PathVariable String deviceId,@Valid @RequestBody OperationsService.NoteUpdate body,HttpServletRequest request) {
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY,Map.of("note",body.note(),"version",body.version()));
        return operations.updateNote(Ids.parse(deviceId,"deviceId"),body);
    }
    @GetMapping("/admin/devices/{deviceId}/purchases")
    OperationsService.PurchasePage purchases(@PathVariable String deviceId,@RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int pageSize) {
        return operations.purchases(Ids.parse(deviceId,"deviceId"),page,pageSize);
    }
}
