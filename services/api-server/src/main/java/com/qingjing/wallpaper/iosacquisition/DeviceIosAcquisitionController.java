package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.*;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/device/ios")
public class DeviceIosAcquisitionController {
    private final IosAcquisitionService acquisition;
    public DeviceIosAcquisitionController(IosAcquisitionService acquisition) { this.acquisition = acquisition; }

    @PostMapping("/attestation/challenges")
    ChallengeResponse challenge(
            @Valid @RequestBody ChallengeRequest body, HttpServletRequest request) {
        return acquisition.challenge(principal(request), body);
    }

    @PostMapping("/attestation/registrations")
    AttestationRegistrationResponse register(
            @Valid @RequestBody AttestationRegistrationRequest body, HttpServletRequest request) {
        return acquisition.register(principal(request), body);
    }

    @PostMapping("/acquisition/status")
    AcquisitionState status(
            @RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody DeviceProofRequest body, HttpServletRequest request) {
        return acquisition.status(principal(request), keyId, assertion, body, rawBody(request));
    }

    @PostMapping("/acquisition/free-claims")
    AcquisitionState claim(
            @RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody FreeClaimRequest body, HttpServletRequest request) {
        return acquisition.claim(principal(request), keyId, assertion, body, rawBody(request));
    }

    @PostMapping("/acquisition/purchases")
    AcquisitionState purchase(
            @RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody PurchaseRequest body, HttpServletRequest request) {
        return acquisition.purchase(principal(request), keyId, assertion, body, rawBody(request));
    }

    @PostMapping("/acquisition/free-resets/{resetId}/complete")
    Object completeReset(
            @PathVariable String resetId,
            @RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody FreeResetCompleteRequest body, HttpServletRequest request) {
        return acquisition.completeReset(principal(request), resetId, keyId, assertion, body, rawBody(request));
    }

    private DevicePrincipal principal(HttpServletRequest request) {
        return (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
    }
    private byte[] rawBody(HttpServletRequest request) {
        Object value = request.getAttribute(RequestAttributes.SIGNED_BODY_BYTES);
        return value instanceof byte[] bytes ? bytes.clone() : new byte[0];
    }
}
