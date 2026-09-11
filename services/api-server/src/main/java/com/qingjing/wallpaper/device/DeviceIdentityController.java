package com.qingjing.wallpaper.device;

import com.qingjing.wallpaper.device.DeviceDtos.CreateChallengeRequest;
import com.qingjing.wallpaper.device.DeviceDtos.CreateDeviceSessionRequest;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceRegistrationRequest;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceRegistrationResponse;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceSession;
import com.qingjing.wallpaper.device.DeviceDtos.DeviceSessionChallenge;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device")
public class DeviceIdentityController {

    private final DeviceIdentityService identity;

    public DeviceIdentityController(DeviceIdentityService identity) {
        this.identity = identity;
    }

    @PostMapping("/registrations")
    ResponseEntity<DeviceRegistrationResponse> register(
            @Valid @RequestBody DeviceRegistrationRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.status(201).body(identity.register(request, servletRequest.getRemoteAddr()));
    }

    @PostMapping("/session-challenges")
    ResponseEntity<DeviceSessionChallenge> challenge(
            @Valid @RequestBody CreateChallengeRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.status(201).body(identity.createChallenge(
                request.credentialKeyId(), servletRequest.getRemoteAddr()));
    }

    @PostMapping("/sessions")
    ResponseEntity<DeviceSession> session(
            @Valid @RequestBody CreateDeviceSessionRequest request,
            HttpServletRequest servletRequest) {
        return ResponseEntity.status(201).body(identity.createSession(request, servletRequest.getRemoteAddr()));
    }
}
