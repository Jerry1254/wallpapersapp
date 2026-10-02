package com.qingjing.wallpaper.iosacquisition;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;
import java.util.UUID;

final class AppleCrypto {
    private AppleCrypto() {}

    static String secret(String pem, String file) throws Exception {
        if (!pem.isBlank() && !file.isBlank()) throw new IllegalArgumentException("Configure PEM or file, not both");
        if (!file.isBlank()) {
            Path path = Path.of(file);
            if (!Files.isRegularFile(path) || Files.size(path) > 8192) throw new IllegalArgumentException("Invalid key file");
            pem = Files.readString(path, StandardCharsets.US_ASCII);
        }
        if (!pem.contains("-----BEGIN PRIVATE KEY-----")) throw new IllegalArgumentException("A PKCS8 PEM key is required");
        return pem;
    }

    static ECPrivateKey privateKey(String pem) throws Exception {
        ECPrivateKey key = (ECPrivateKey) KeyFactory.getInstance("EC").generatePrivate(
                new PKCS8EncodedKeySpec(pemBytes(pem)));
        if (key.getParams().getCurve().getField().getFieldSize() != 256) throw new IllegalArgumentException("P256 key required");
        return key;
    }

    static ECPublicKey publicKey(String pem) throws Exception {
        return (ECPublicKey) KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(pemBytes(pem)));
    }

    static byte[] pemBytes(String pem) {
        return Base64.getDecoder().decode(pem.replaceAll("-----[^-]+-----", "").replaceAll("\\s", ""));
    }

    static byte[] sha256(byte[] input) throws Exception { return MessageDigest.getInstance("SHA-256").digest(input); }

    static byte[] deviceDigest(String nonce, String id) throws Exception {
        String combined = UUID.fromString(nonce).toString().toLowerCase(Locale.ROOT)
                + UUID.fromString(id).toString().toLowerCase(Locale.ROOT);
        return MessageDigest.getInstance("SHA-384").digest(combined.getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] concat(byte[] a, byte[] b) {
        byte[] result = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }
}
