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
        Path input = directory.resolve(java.util.UUID.randomUUID() + ".unsigned.apk");
        try (var resource = ApkTestFixtures.class.getResourceAsStream("/appupdate/" + fixture)) {
            if (resource == null) throw new IllegalStateException("APK fixture missing");
            Files.copy(resource, input);
        }
        Path output = directory.resolve(java.util.UUID.randomUUID() + ".apk");
        new ApkSigner.Builder(List.of(new ApkSigner.SignerConfig.Builder("test", signer.key().getPrivate(), List.of(signer.certificate())).build()))
                .setInputApk(input.toFile()).setOutputApk(output.toFile()).setMinSdkVersion(24)
                .setV1SigningEnabled(true).setV2SigningEnabled(true).setV3SigningEnabled(false).setV4SigningEnabled(false)
                .build().sign();
        return output;
    }
}
