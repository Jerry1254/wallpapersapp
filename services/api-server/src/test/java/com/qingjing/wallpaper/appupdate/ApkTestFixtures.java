package com.qingjing.wallpaper.appupdate;

import com.android.apksig.ApkSigner;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

final class ApkTestFixtures {
    private ApkTestFixtures() {}
    record Signer(KeyPair key, X509Certificate certificate) {}
    static Signer signer() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var key = generator.generateKeyPair();
        var name = new X500Name("CN=Ephemeral App Update Test");
        var certificate = new JcaX509CertificateConverter().getCertificate(new JcaX509v3CertificateBuilder(
                name,BigInteger.ONE,Date.from(Instant.now().minusSeconds(60)),Date.from(Instant.now().plusSeconds(86400)),name,key.getPublic())
                .build(new JcaContentSignerBuilder("SHA256withRSA").build(key.getPrivate())));
        return new Signer(key, certificate);
    }
    static Path signed(Path directory, String fixture, Signer signer) throws Exception {
        return signed(directory, fixture, signer, "com.qingjing.bizhi");
    }
    static Path signed(Path directory, String fixture, Signer signer, String packageName) throws Exception {
        Path input = directory.resolve(java.util.UUID.randomUUID() + ".unsigned.apk");
        try (var resource = ApkTestFixtures.class.getResourceAsStream("/appupdate/" + fixture)) {
            if (resource == null) throw new IllegalStateException("APK fixture missing");
            Files.copy(resource, input);
        }
        if (!packageName.equals("com.qingjing.bizhi")) {
            // Both fixed App IDs have the same UTF-16 length, preserving compiled string-pool offsets.
            byte[] from = "com.qingjing.bizhi".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            byte[] to = packageName.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
            if (from.length != to.length) throw new IllegalArgumentException("Fixture package must preserve string length");
            Path rewritten = directory.resolve(java.util.UUID.randomUUID() + ".unsigned.apk");
            try (var zip = new java.util.zip.ZipFile(input.toFile());
                    var output = new java.util.zip.ZipOutputStream(Files.newOutputStream(rewritten))) {
                var entries = zip.entries();
                while (entries.hasMoreElements()) {
                    var entry = entries.nextElement();
                    byte[] bytes;
                    try (var stream = zip.getInputStream(entry)) { bytes = stream.readAllBytes(); }
                    if (entry.getName().equals("AndroidManifest.xml") || entry.getName().equals("resources.arsc")) {
                        for (int index = 0; index <= bytes.length - from.length; index++) {
                            if (java.util.Arrays.equals(bytes, index, index + from.length, from, 0, from.length)) {
                                System.arraycopy(to, 0, bytes, index, to.length);
                                index += from.length - 1;
                            }
                        }
                    }
                    output.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                    output.write(bytes);
                    output.closeEntry();
                }
            }
            Files.delete(input);
            input = rewritten;
        }
        Path output = directory.resolve(java.util.UUID.randomUUID() + ".apk");
        new ApkSigner.Builder(List.of(new ApkSigner.SignerConfig.Builder("test", signer.key().getPrivate(), List.of(signer.certificate())).build()))
                .setInputApk(input.toFile()).setOutputApk(output.toFile()).setMinSdkVersion(24)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false)
                .build().sign();
        return output;
    }
}
