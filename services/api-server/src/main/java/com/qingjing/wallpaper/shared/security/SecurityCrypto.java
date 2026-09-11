package com.qingjing.wallpaper.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class SecurityCrypto {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final byte ENCRYPTION_FORMAT_VERSION = 1;
    private final byte[] masterKey;

    public SecurityCrypto(SecurityProperties properties) {
        String configured = properties.getMasterKey();
        if (configured == null || !configured.matches("^[a-fA-F0-9]{64}$")) {
            throw new IllegalStateException("qingjing.security.master-key must be a 64-character hexadecimal secret");
        }
        this.masterKey = HexFormat.of().parseHex(configured);
    }

    public byte[] hmac(String purpose, String value) {
        return hmac(derive(purpose), value.getBytes(StandardCharsets.UTF_8));
    }

    public String hmacBase64Url(String purpose, String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(hmac(purpose, value));
    }

    public String hmacBase64UrlWithKey(String key, String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                hmac(key.getBytes(StandardCharsets.UTF_8), value.getBytes(StandardCharsets.UTF_8)));
    }

    public String hmacHex(String purpose, String value) {
        return HexFormat.of().formatHex(hmac(purpose, value));
    }

    public String sha256Hex(String value) {
        return HexFormat.of().formatHex(sha256(value.getBytes(StandardCharsets.UTF_8)));
    }

    public String randomToken(int bytes) {
        byte[] value = new byte[bytes];
        RANDOM.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public String encrypt(String purpose, byte[] plaintext) {
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(derive(purpose), "AES"), new GCMParameterSpec(128, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext);
            return Base64.getEncoder().encodeToString(ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put(ENCRYPTION_FORMAT_VERSION)
                    .put(nonce)
                    .put(ciphertext)
                    .array());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Sensitive delivery material could not be encrypted", exception);
        }
    }

    public byte[] decrypt(String purpose, String encoded) {
        byte[] envelope;
        try {
            envelope = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Sensitive delivery material has an invalid encoding", exception);
        }
        if (envelope.length < 30 || envelope[0] != ENCRYPTION_FORMAT_VERSION) {
            throw new IllegalStateException("Sensitive delivery material has an unsupported format");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(envelope, 1, 13);
        byte[] ciphertext = java.util.Arrays.copyOfRange(envelope, 13, envelope.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(derive(purpose), "AES"), new GCMParameterSpec(128, nonce));
            return cipher.doFinal(ciphertext);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Sensitive delivery material could not be decrypted", exception);
        }
    }

    public boolean constantTimeEquals(String expected, String supplied) {
        if (supplied == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                supplied.getBytes(StandardCharsets.US_ASCII));
    }

    private byte[] derive(String purpose) {
        return hmac(masterKey, ("QJ-KEY-V1\n" + purpose).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hmac(byte[] key, byte[] value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 is unavailable", exception);
        }
    }

    private static byte[] sha256(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
