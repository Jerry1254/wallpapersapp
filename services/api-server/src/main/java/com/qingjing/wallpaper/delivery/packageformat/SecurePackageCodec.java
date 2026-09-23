package com.qingjing.wallpaper.delivery.packageformat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAKey;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

/**
 * Pure format boundary: no database, paths, tickets or secret configuration.
 * Callers must supply validated media bytes; MIME/role checks here are structural,
 * not a media decoder. The publication adapter owns image/video/config validation.
 */
public final class SecurePackageCodec {
    public static final int MAX_PAYLOAD_BYTES = 64 * 1024 * 1024;
    private static final byte[] MAGIC = "QJWP0002".getBytes(StandardCharsets.US_ASCII);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Set<String> TYPES = Set.of("STATIC_IMAGE", "VIDEO", "LAYER_PARALLAX", "LIVE_PHOTO");
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/jpeg", "jpg", "image/png", "png", "image/webp", "webp", "video/mp4", "mp4",
            "video/quicktime", "mov", "application/json", "json");
    private final ObjectMapper mapper;
    public SecurePackageCodec(ObjectMapper mapper) { this.mapper = mapper; }

    public record Identity(long wallpaperId, long variantId, int versionNo, String resourceType) {
        public Identity {
            if (wallpaperId <= 0 || variantId <= 0 || versionNo <= 0 || !TYPES.contains(resourceType)) {
                throw new IllegalArgumentException("Invalid package identity");
            }
        }
        public byte[] aad() {
            return ("QJ-PACKAGE-V2\n" + wallpaperId + "\n" + variantId + "\n" + versionNo + "\n" + resourceType)
                    .getBytes(StandardCharsets.UTF_8);
        }
    }
    public record Payload(String role, int ordinal, String mimeType, byte[] content) {}
    public record Encoded(byte[] encrypted, byte[] contentKey, String plaintextSha256, String encryptedSha256,
                          String manifestSha256, String signingKeyId) {}

    public Encoded encode(Identity identity, List<Payload> payloads, String signingKeyId, PrivateKey signingKey) {
        return encodePurpose(identity,payloads,signingKeyId,signingKey,false);
    }
    /** Preview has its own magic, signed purpose and AAD. It cannot be installed as a format-2 paid package. */
    public Encoded encodePreview(Identity identity, List<Payload> previewPayloads, String signingKeyId, PrivateKey signingKey) {
        return encodePurpose(identity,previewPayloads,signingKeyId,signingKey,true);
    }
    public static byte[] previewAad(Identity identity) {
        return ("QJ-PREVIEW-V1\n"+identity.wallpaperId()+"\n"+identity.variantId()+"\n"+identity.versionNo()+"\n"+identity.resourceType())
                .getBytes(StandardCharsets.UTF_8);
    }
    private Encoded encodePurpose(Identity identity,List<Payload> payloads,String signingKeyId,PrivateKey signingKey,boolean preview) {
        if (signingKeyId == null || !signingKeyId.matches("[a-z0-9-]{1,64}") ||
                !(signingKey instanceof RSAKey rsa) || rsa.getModulus().bitLength() != 2048) {
            throw new IllegalArgumentException("Invalid package signing key");
        }
        validate(payloads, identity.resourceType(), preview);
        try {
            Map<String, byte[]> files = new LinkedHashMap<>();
            List<Map<String, Object>> entries = new ArrayList<>();
            for (Payload payload : payloads.stream().sorted(Comparator.comparing(Payload::role).thenComparingInt(Payload::ordinal)).toList()) {
                String name = "payload/" + payload.role().toLowerCase(Locale.ROOT) + "-" + payload.ordinal() + "." + EXTENSIONS.get(payload.mimeType());
                files.put(name, payload.content());
                entries.add(Map.of("path", name, "role", payload.role(), "ordinal", payload.ordinal(),
                        "mimeType", payload.mimeType(), "sizeBytes", payload.content().length, "sha256", sha256(payload.content())));
            }
            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("formatVersion", preview ? 3 : 2);
            if (preview) manifest.put("purpose","APP_PREVIEW");
            manifest.put("wallpaperId", Long.toString(identity.wallpaperId()));
            manifest.put("variantId", Long.toString(identity.variantId())); manifest.put("versionNo", identity.versionNo());
            manifest.put("resourceType", identity.resourceType()); manifest.put("signingKeyId", signingKeyId); manifest.put("files", entries);
            byte[] manifestBytes = mapper.writeValueAsBytes(manifest);
            if (manifestBytes.length > 65536) throw new IllegalArgumentException("Manifest too large");
            Signature signer = Signature.getInstance("SHA256withRSA"); signer.initSign(signingKey); signer.update(manifestBytes);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (ZipOutputStream zip = new ZipOutputStream(output, StandardCharsets.UTF_8)) {
                write(zip, "manifest.json", manifestBytes); write(zip, "manifest.sig", signer.sign());
                for (var file : files.entrySet()) write(zip, file.getKey(), file.getValue());
            }
            byte[] plaintext = output.toByteArray(), key = new byte[32], nonce = new byte[12];
            RANDOM.nextBytes(key); RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(preview ? previewAad(identity) : identity.aad());
            byte[] ciphertext = cipher.doFinal(plaintext);
            byte[] magic = preview ? "QJPV0001".getBytes(StandardCharsets.US_ASCII) : MAGIC;
            byte[] encrypted = ByteBuffer.allocate(magic.length + nonce.length + ciphertext.length).put(magic).put(nonce).put(ciphertext).array();
            return new Encoded(encrypted, key, sha256(plaintext), sha256(encrypted), sha256(manifestBytes), signingKeyId);
        } catch (GeneralSecurityException | java.io.IOException exception) {
            throw new IllegalStateException("Package encoding failed", exception);
        }
    }
    public static byte[] wrapContentKey(byte[] contentKey, PublicKey installationEncryptionKey) {
        if (contentKey.length != 32 || !(installationEncryptionKey instanceof RSAKey rsa) || rsa.getModulus().bitLength() != 2048) {
            throw new IllegalArgumentException("Invalid content wrapping key");
        }
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPPadding");
            cipher.init(Cipher.ENCRYPT_MODE, installationEncryptionKey,
                    new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            return cipher.doFinal(contentKey);
        } catch (GeneralSecurityException exception) { throw new IllegalStateException("Content key wrapping failed", exception); }
    }
    private static void validate(List<Payload> payloads, String type, boolean preview) {
        if (payloads == null || payloads.isEmpty() || payloads.size() > 16) throw new IllegalArgumentException("Invalid payload count");
        if (!preview && type.equals("LIVE_PHOTO")) throw new IllegalArgumentException("Live Photo is preview-only in this package format");
        Set<String> seen = new HashSet<>(), roles = new HashSet<>(); long bytes = 0;
        Set<String> allowed = switch (type) {
            case "STATIC_IMAGE" -> Set.of("STATIC_IMAGE");
            case "VIDEO" -> Set.of("VIDEO");
            case "LIVE_PHOTO" -> Set.of("LIVE_PHOTO_VIDEO");
            default -> Set.of("BACKGROUND", "FOREGROUND", "PARALLAX_CONFIG");
        };
        for (Payload payload : payloads) {
            if (payload == null || !allowed.contains(payload.role()) || payload.ordinal() < 0 || payload.ordinal() > 15 ||
                    !EXTENSIONS.containsKey(payload.mimeType()) || payload.content() == null || payload.content().length == 0 ||
                    !seen.add(payload.role() + ":" + payload.ordinal())) throw new IllegalArgumentException("Invalid payload");
            boolean mimeMatches = switch (payload.role()) {
                case "VIDEO" -> payload.mimeType().equals("video/mp4");
                case "LIVE_PHOTO_VIDEO" -> payload.mimeType().equals("video/mp4") || payload.mimeType().equals("video/quicktime");
                case "PARALLAX_CONFIG" -> payload.mimeType().equals("application/json") && payload.content().length <= 65536;
                default -> payload.mimeType().startsWith("image/");
            };
            if (!mimeMatches) throw new IllegalArgumentException("Payload role and MIME mismatch");
            roles.add(payload.role()); bytes += payload.content().length;
            if (bytes > MAX_PAYLOAD_BYTES) throw new IllegalArgumentException("Package payload too large");
        }
        if (!roles.equals(allowed) || (!type.equals("LAYER_PARALLAX") && payloads.size() != 1) ||
                (type.equals("LAYER_PARALLAX") && payloads.stream().filter(p -> p.role().equals("PARALLAX_CONFIG")).count() != 1)) {
            throw new IllegalArgumentException("Missing or repeated required role");
        }
    }
    private static void write(ZipOutputStream zip, String name, byte[] bytes) throws java.io.IOException {
        ZipEntry entry = new ZipEntry(name); entry.setTime(0); zip.putNextEntry(entry); zip.write(bytes); zip.closeEntry();
    }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
