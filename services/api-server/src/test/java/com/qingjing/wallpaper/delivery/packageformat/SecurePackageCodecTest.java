package com.qingjing.wallpaper.delivery.packageformat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.util.*;
import java.util.zip.ZipInputStream;
import javax.crypto.*;
import javax.crypto.spec.*;
import static org.assertj.core.api.Assertions.*;

class SecurePackageCodecTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecurePackageCodec codec = new SecurePackageCodec(mapper);
    private static KeyPair key() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA"); generator.initialize(2048); return generator.generateKeyPair();
    }
    private static SecurePackageCodec.Payload payload(String role, String mime) {
        return new SecurePackageCodec.Payload(role, 0, mime, ("test-" + role).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void threeTypesRoundTripBindIdentityAndSignEveryPayloadDigest() throws Exception {
        KeyPair signing = key(), installation = key();
        Map<String, List<SecurePackageCodec.Payload>> cases = Map.of(
                "STATIC_IMAGE", List.of(payload("STATIC_IMAGE", "image/png")),
                "VIDEO", List.of(payload("VIDEO", "video/mp4")),
                "LAYER_PARALLAX", List.of(payload("BACKGROUND", "image/webp"), payload("FOREGROUND", "image/png"), payload("PARALLAX_CONFIG", "application/json")));
        for (var item : cases.entrySet()) {
            var identity = new SecurePackageCodec.Identity(11, 22, 3, item.getKey());
            var encoded = codec.encode(identity, item.getValue(), "test-signing-1", signing.getPrivate());
            byte[] wrapped = SecurePackageCodec.wrapContentKey(encoded.contentKey(), installation.getPublic());
            Cipher unwrap = Cipher.getInstance("RSA/ECB/OAEPPadding");
            unwrap.init(Cipher.DECRYPT_MODE, installation.getPrivate(), new OAEPParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, PSource.PSpecified.DEFAULT));
            byte[] contentKey = unwrap.doFinal(wrapped);
            byte[] plaintext = decrypt(encoded.encrypted(), contentKey, identity);
            assertThat(SecurePackageCodec.sha256(plaintext)).isEqualTo(encoded.plaintextSha256());
            assertThat(SecurePackageCodec.sha256(encoded.encrypted())).isEqualTo(encoded.encryptedSha256());
            Map<String, byte[]> files = new HashMap<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(plaintext))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) files.put(entry.getName(), zip.readAllBytes());
            }
            byte[] manifest = files.remove("manifest.json"), signature = files.remove("manifest.sig");
            Signature verifier = Signature.getInstance("SHA256withRSA"); verifier.initVerify(signing.getPublic()); verifier.update(manifest);
            assertThat(verifier.verify(signature)).isTrue();
            assertThat(SecurePackageCodec.sha256(manifest)).isEqualTo(encoded.manifestSha256());
            var parsed = mapper.readTree(manifest);
            assertThat(parsed.path("resourceType").asText()).isEqualTo(item.getKey());
            assertThat(parsed.path("formatVersion").asInt()).isEqualTo(2);
            for (var file : parsed.path("files")) {
                byte[] contents = files.remove(file.path("path").asText());
                assertThat(contents).hasSize(file.path("sizeBytes").asInt());
                assertThat(SecurePackageCodec.sha256(contents)).isEqualTo(file.path("sha256").asText());
            }
            assertThat(files).isEmpty();
            manifest[0] ^= 1; verifier.initVerify(signing.getPublic()); verifier.update(manifest);
            assertThat(verifier.verify(signature)).isFalse();
            byte[] tampered = encoded.encrypted().clone(); tampered[tampered.length - 1] ^= 1;
            assertThatThrownBy(() -> decrypt(tampered, contentKey, identity)).isInstanceOf(AEADBadTagException.class);
            assertThatThrownBy(() -> decrypt(encoded.encrypted(), contentKey, new SecurePackageCodec.Identity(11,22,4,item.getKey()))).isInstanceOf(AEADBadTagException.class);
        }
    }
    @Test void independentRandomKeysAndNoncesAndWrongInstallationKeyRejected() throws Exception {
        var signing = key(); var identity = new SecurePackageCodec.Identity(1,2,1,"STATIC_IMAGE");
        var payloads = List.of(payload("STATIC_IMAGE", "image/jpeg"));
        var first = codec.encode(identity,payloads,"test",signing.getPrivate());
        var second = codec.encode(identity,payloads,"test",signing.getPrivate());
        assertThat(first.contentKey()).isNotEqualTo(second.contentKey());
        assertThat(first.encrypted()).isNotEqualTo(second.encrypted());
        byte[] wrapped = SecurePackageCodec.wrapContentKey(first.contentKey(),key().getPublic());
        Cipher wrong = Cipher.getInstance("RSA/ECB/OAEPPadding");
        wrong.init(Cipher.DECRYPT_MODE,key().getPrivate(),new OAEPParameterSpec("SHA-256","MGF1",MGF1ParameterSpec.SHA1,PSource.PSpecified.DEFAULT));
        assertThatThrownBy(() -> wrong.doFinal(wrapped)).isInstanceOf(BadPaddingException.class);
    }
    @Test void invalidRolesDuplicateAndMissingPayloadsCannotBePackaged() throws Exception {
        var signing = key(); var identity = new SecurePackageCodec.Identity(1,2,1,"STATIC_IMAGE");
        for (var payloads : List.of(List.<SecurePackageCodec.Payload>of(), List.of(payload("../escape","image/png")),
                List.of(payload("STATIC_IMAGE","video/mp4")), List.of(payload("STATIC_IMAGE","image/png"),payload("STATIC_IMAGE","image/png")))) {
            assertThatThrownBy(() -> codec.encode(identity,payloads,"test",signing.getPrivate())).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> codec.encode(new SecurePackageCodec.Identity(1,2,1,"LAYER_PARALLAX"),
                List.of(payload("BACKGROUND","image/png")),"test",signing.getPrivate())).isInstanceOf(IllegalArgumentException.class);
    }
    private static byte[] decrypt(byte[] envelope, byte[] key, SecurePackageCodec.Identity identity) throws Exception {
        assertThat(Arrays.copyOfRange(envelope,0,8)).isEqualTo("QJWP0002".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,Arrays.copyOfRange(envelope,8,20)));
        cipher.updateAAD(identity.aad()); return cipher.doFinal(Arrays.copyOfRange(envelope,20,envelope.length));
    }
}
