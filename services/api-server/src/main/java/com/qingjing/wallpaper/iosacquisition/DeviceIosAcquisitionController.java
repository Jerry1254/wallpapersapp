package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.*;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping("/products")
    ProductCatalogue products(HttpServletRequest request, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return acquisition.productCatalogue(principal(request));
    }

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

    @PostMapping("/acquisition/credit-orders")
    IosCreditDtos.Order creditOrder(@RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody IosCreditDtos.OrderRequest body,HttpServletRequest request) {
        return acquisition.creditOrder(principal(request),keyId,assertion,body,rawBody(request));
    }

    @PostMapping("/acquisition/credit-orders/{orderId}/cancel")
    public AcquisitionState cancelCreditOrder(
            @RequestHeader("X-App-Attest-Key-Id") String keyId,@RequestHeader("X-App-Attest-Assertion") String assertion,
            @PathVariable String orderId,@Valid @RequestBody IosCreditDtos.RestoreRequest request,HttpServletRequest servlet) {
        return acquisition.cancelCreditOrder(principal(servlet),keyId,assertion,orderId,request,rawBody(servlet));
    }

    @PostMapping("/acquisition/credit-restores")
    AcquisitionState restoreCredits(@RequestHeader("X-App-Attest-Key-Id") String keyId,
            @RequestHeader("X-App-Attest-Assertion") String assertion,
            @Valid @RequestBody IosCreditDtos.RestoreRequest body,HttpServletRequest request) {
        return acquisition.restoreCredits(principal(request),keyId,assertion,body,rawBody(request));
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
