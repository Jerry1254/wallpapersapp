package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.AppleNotificationRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/integrations/apple")
public class AppleNotificationController {
    private final IosAcquisitionService acquisition;
    public AppleNotificationController(IosAcquisitionService acquisition) { this.acquisition = acquisition; }

    @PostMapping("/app-store-notifications")
    ResponseEntity<Void> notification(@Valid @RequestBody AppleNotificationRequest body) {
        acquisition.notification(body.signedPayload());
        return ResponseEntity.ok().build();
    }
}
