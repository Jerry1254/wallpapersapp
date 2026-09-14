package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class InstallationEncryptionKeyController {
    private final InstallationEncryptionKeys keys;
    public InstallationEncryptionKeyController(InstallationEncryptionKeys keys) { this.keys = keys; }
    @PutMapping("/api/v1/device/encryption-key")
    public Binding bind(@Valid @RequestBody BindRequest body, HttpServletRequest request) {
        var principal = (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        return new Binding(keys.bind(principal, body.publicKeyPem()), "RSA-OAEP-SHA256-MGF1-SHA1");
    }
    public record BindRequest(@NotBlank @Size(min=400,max=8192) String publicKeyPem) {}
    public record Binding(String publicKeySha256, String keyAlgorithm) {}
}
