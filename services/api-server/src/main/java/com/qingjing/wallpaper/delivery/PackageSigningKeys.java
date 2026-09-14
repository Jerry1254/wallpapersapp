package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.shared.web.ApiException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Independent resource signing trust root. No automatic production key generation. */
@Component
public final class PackageSigningKeys {
    private final String keyId;
    private final PrivateKey privateKey;
    public PackageSigningKeys(
            @Value("${qingjing.delivery.signing-key-id:}") String keyId,
            @Value("${qingjing.delivery.signing-private-key:}") String encoded) {
        this.keyId = keyId;
        if (keyId.isBlank() && encoded.isBlank()) { privateKey = null; return; }
        try {
            if (!keyId.matches("[a-z0-9-]{1,64}") || encoded.length() > 8192) throw new IllegalArgumentException();
            var key = (RSAPrivateCrtKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
            if (key.getModulus().bitLength() != 2048 || !key.getPublicExponent().equals(java.math.BigInteger.valueOf(65537))) {
                throw new IllegalArgumentException();
            }
            this.privateKey = key;
        } catch (Exception invalid) {
            // Configuration errors must not leak private-key bytes in logs or an API error.
            throw new IllegalStateException("Invalid resource signing configuration");
        }
    }
    public String keyId() { requireReady(); return keyId; }
    public PrivateKey privateKey() { requireReady(); return privateKey; }
    private void requireReady() {
        if (privateKey == null) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SECURE_PACKAGE_NOT_READY", "Resource signing is not configured");
    }
}
