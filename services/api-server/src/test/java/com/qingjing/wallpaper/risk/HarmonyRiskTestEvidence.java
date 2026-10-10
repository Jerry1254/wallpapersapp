package com.qingjing.wallpaper.risk;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.*;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.*;
import org.bouncycastle.cert.jcajce.*;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ResourceLoader;
import static org.mockito.Mockito.*;

/** Test-only keys are generated in memory. No signing keys or fake roots enter production resources. */
final class HarmonyRiskTestEvidence {
    final ObjectMapper json = new ObjectMapper();
    final KeyPair leafKey;
    final List<X509Certificate> chain;
    HarmonyRiskTestEvidence() throws Exception { this("Harmony OS Device Attestation Service",false); }
    HarmonyRiskTestEvidence(String cn, boolean expired) throws Exception {
        KeyPair rootKey=key(), issuerKey=key(); leafKey=key();
        X509Certificate root=cert("Test Root","Test Root",rootKey,rootKey,2,false);
        X509Certificate issuer=cert("Test Root","Test Issuer",issuerKey,rootKey,0,false);
        X509Certificate leaf=cert("Test Issuer",cn,leafKey,issuerKey,-1,expired);
        chain=List.of(leaf,issuer,root);
    }
    HarmonyAttestationVerifier verifier() throws Exception {
        ResourceLoader resources=mock(ResourceLoader.class);
        when(resources.getResource(anyString())).thenReturn(new ByteArrayResource(chain.get(2).getEncoded()));
        return new HarmonyAttestationVerifier(json,new HarmonyRiskProperties(),resources);
    }
    ObjectNode payload(String nonce,String developer,String usb) {
        ObjectNode payload=json.createObjectNode();
        payload.put("appId","6917617603491246393").put("nonce",nonce).put("timestamp",Instant.now().toEpochMilli());
        payload.putObject("isDeveloperMode").put("status",0).put("result",developer);
        payload.putObject("hdcDebugState").put("status",0).put("result",usb);
        return payload;
    }
    ObjectNode header() throws Exception {
        ObjectNode header=json.createObjectNode().put("alg","ES256").put("typ","JWS");
        var certificates=header.putArray("x5c");
        for(var cert:chain) certificates.add(Base64.getEncoder().encodeToString(cert.getEncoded()));
        return header;
    }
    String jws(String nonce,String developer,String usb) throws Exception { return jws(header().toString(),payload(nonce,developer,usb).toString()); }
    String jws(String header,String payload) throws Exception {
        String body=url(header.getBytes(StandardCharsets.UTF_8))+"."+url(payload.getBytes(StandardCharsets.UTF_8));
        Signature signer=Signature.getInstance("SHA256withECDSAinP1363Format");
        signer.initSign(leafKey.getPrivate()); signer.update(body.getBytes(StandardCharsets.US_ASCII));
        return body+"."+url(signer.sign());
    }
    private static String url(byte[] bytes) { return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes); }
    private static KeyPair key() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));return generator.generateKeyPair();
    }
    private static X509Certificate cert(String issuer,String subject,KeyPair key,KeyPair signer,int ca,boolean expired) throws Exception {
        Instant now=Instant.now();
        var builder=new JcaX509v3CertificateBuilder(new X500Name("CN="+issuer),new BigInteger(120,new SecureRandom()),
            Date.from(now.minusSeconds(86400)),Date.from(expired?now.minusSeconds(60):now.plusSeconds(86400)),new X500Name("CN="+subject),key.getPublic());
        builder.addExtension(Extension.basicConstraints,true,ca<0?new BasicConstraints(false):new BasicConstraints(ca));
        builder.addExtension(Extension.keyUsage,true,new KeyUsage(ca<0?KeyUsage.digitalSignature:KeyUsage.keyCertSign));
        return new JcaX509CertificateConverter().getCertificate(builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(signer.getPrivate())));
    }
}
