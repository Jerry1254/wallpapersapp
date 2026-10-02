package com.qingjing.wallpaper.iosacquisition;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bouncycastle.asn1.DEROctetString;
import org.bouncycastle.asn1.DERSequence;
import org.bouncycastle.asn1.DERTaggedObject;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;

class AppAttestVerifierTest {
    private static final ObjectMapper CBOR = new ObjectMapper(new CBORFactory());
    private static final String APP = "NFXT4L28FU.com.qingjing.bizhi";

    @Test void validatesTrustedAttestationAndPublicKey() throws Exception {
        Fixture f = fixture("DEVELOPMENT");
        var result = f.verifier.attest(f.keyId,f.attestation,"challenge");
        assertThat(result.environment()).isEqualTo("DEVELOPMENT");
        assertThat(AppleCrypto.publicKey(result.publicKeyPem()).getEncoded()).isEqualTo(f.key.getPublic().getEncoded());
    }
    @Test void validatesProductionAaguid() throws Exception {
        Fixture f=fixture("PRODUCTION");
        assertThat(f.verifier.attest(f.keyId,f.attestation,"challenge").environment()).isEqualTo("PRODUCTION");
    }
    @Test void rejectsUntrustedChain() throws Exception {
        Fixture f=fixture("DEVELOPMENT"), other=fixture("DEVELOPMENT");
        assertThatThrownBy(()->other.verifier.attest(f.keyId,f.attestation,"challenge")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsChangedChallenge() throws Exception {
        Fixture f=fixture("DEVELOPMENT");
        assertThatThrownBy(()->f.verifier.attest(f.keyId,f.attestation,"other")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsWrongKeyIdentifier() throws Exception {
        Fixture f=fixture("DEVELOPMENT");
        assertThatThrownBy(()->f.verifier.attest(Base64.getEncoder().encodeToString(new byte[32]),f.attestation,"challenge")).isInstanceOf(ApiException.class);
    }
    @Test void rejectsWrongEnvironment() throws Exception {
        Fixture f=fixture("DEVELOPMENT");
        IosAcquisitionProperties p=properties("PRODUCTION");
        AppAttestVerifier verifier=new AppAttestVerifier(p,f.root);
        assertThatThrownBy(()->verifier.attest(f.keyId,f.attestation,"challenge")).isInstanceOf(ApiException.class);
    }
    @Test void assertionAllowsCounterGaps() throws Exception {
        Fixture f=fixture("DEVELOPMENT"); byte[] body="{\"nonce\":\"one\"}".getBytes(StandardCharsets.UTF_8);
        String pem=f.verifier.attest(f.keyId,f.attestation,"challenge").publicKeyPem();
        assertThat(f.verifier.assertion(assertion(f.key,body,7,APP),pem,body,2)).isEqualTo(7);
    }
    @Test void assertionAcceptsAttestedCredentialFlagFromRealDevice() throws Exception {
        Fixture f=fixture("DEVELOPMENT"); byte[] body="{\"nonce\":\"status\"}".getBytes(StandardCharsets.UTF_8);
        String pem=f.verifier.attest(f.keyId,f.attestation,"challenge").publicKeyPem();
        assertThat(f.verifier.assertion(assertion(f.key,body,1,APP,0x40),pem,body,0)).isEqualTo(1);
    }
    @Test void assertionRejectsBodyTampering() throws Exception {
        Fixture f=fixture("DEVELOPMENT"); byte[] body="original".getBytes(StandardCharsets.UTF_8);
        String proof=assertion(f.key,body,7,APP),pem=f.verifier.attest(f.keyId,f.attestation,"challenge").publicKeyPem();
        assertThatThrownBy(()->f.verifier.assertion(proof,pem,"changed".getBytes(StandardCharsets.UTF_8),2)).isInstanceOf(ApiException.class);
    }
    @Test void assertionRejectsReplay() throws Exception {
        Fixture f=fixture("DEVELOPMENT"); byte[] body={1,2};String proof=assertion(f.key,body,7,APP);
        String pem=f.verifier.attest(f.keyId,f.attestation,"challenge").publicKeyPem();
        assertThatThrownBy(()->f.verifier.assertion(proof,pem,body,7)).isInstanceOf(ApiException.class).hasMessageContaining("counter");
    }
    @Test void assertionRejectsAnotherApp() throws Exception {
        Fixture f=fixture("DEVELOPMENT"); byte[] body={1,2};String proof=assertion(f.key,body,7,"OTHER.com.qingjing.bizhi");
        String pem=f.verifier.attest(f.keyId,f.attestation,"challenge").publicKeyPem();
        assertThatThrownBy(()->f.verifier.assertion(proof,pem,body,2)).isInstanceOf(ApiException.class);
    }
    @Test void parsesAppleLittleEndianValidationCategory() throws Exception {
        JsonNode binary = CBOR.readTree(CBOR.writeValueAsBytes(new byte[]{4,0,0,0}));
        assertThat(AppAttestVerifier.validationCategory(binary)).isEqualTo(4);
        assertThat(AppAttestVerifier.validationCategory(CBOR.valueToTree(2))).isEqualTo(2);
    }

    private static String assertion(KeyPair key,byte[] body,int count,String app) throws Exception {
        return assertion(key,body,count,app,1);
    }

    private static String assertion(KeyPair key,byte[] body,int count,String app,int flags) throws Exception {
        byte[] auth=ByteBuffer.allocate(37).put(AppleCrypto.sha256(app.getBytes(StandardCharsets.UTF_8))).put((byte)flags).putInt(count).array();
        Signature signature=Signature.getInstance("SHA256withECDSA");signature.initSign(key.getPrivate());
        signature.update(AppleCrypto.concat(auth,AppleCrypto.sha256(body)));
        return Base64.getEncoder().encodeToString(CBOR.writeValueAsBytes(Map.of("authenticatorData",auth,"signature",signature.sign())));
    }

    private static Fixture fixture(String env) throws Exception {
        KeyPair rootKey=key(), intermediateKey=key(), leafKey=key();
        X500Name rootName=new X500Name("CN=Test Root"),intermediateName=new X500Name("CN=Test Intermediate"),leafName=new X500Name("CN=App Attest");
        byte[] root=cert(rootName,rootName,rootKey,rootKey,true,null);
        byte[] intermediate=cert(rootName,intermediateName,intermediateKey,rootKey,true,null);
        ECPublicKey publicKey=(ECPublicKey)leafKey.getPublic();
        byte[] x=unsigned32(publicKey.getW().getAffineX()),y=unsigned32(publicKey.getW().getAffineY());
        byte[] id=AppleCrypto.sha256(AppleCrypto.concat(new byte[]{4},AppleCrypto.concat(x,y)));
        byte[] aaguid=env.equals("DEVELOPMENT")?"appattestdevelop".getBytes(StandardCharsets.US_ASCII):Arrays.copyOf("appattest".getBytes(StandardCharsets.US_ASCII),16);
        byte[] authPrefix=ByteBuffer.allocate(87).put(AppleCrypto.sha256(APP.getBytes(StandardCharsets.UTF_8))).put((byte)0x41).putInt(0).put(aaguid).putShort((short)32).put(id).array();
        byte[] auth=AppleCrypto.concat(authPrefix,CBOR.writeValueAsBytes(Map.of(1,2,3,-7,-1,1,-2,x,-3,y)));
        byte[] nonce=AppleCrypto.sha256(AppleCrypto.concat(auth,AppleCrypto.sha256("challenge".getBytes(StandardCharsets.UTF_8))));
        byte[] leaf=cert(intermediateName,leafName,leafKey,intermediateKey,false,nonce);
        String attestation=Base64.getEncoder().encodeToString(CBOR.writeValueAsBytes(Map.of("fmt","apple-appattest","authData",auth,
                "attStmt",Map.of("x5c",List.of(leaf,intermediate),"receipt",new byte[]{1,2,3}))));
        return new Fixture(new AppAttestVerifier(properties(env),root),Base64.getEncoder().encodeToString(id),attestation,leafKey,root);
    }

    private static byte[] cert(X500Name issuer,X500Name subject,KeyPair subjectKey,KeyPair issuerKey,boolean ca,byte[] nonce) throws Exception {
        Instant now=Instant.now();
        var builder=new JcaX509v3CertificateBuilder(issuer,new BigInteger(100,new java.security.SecureRandom()),Date.from(now.minusSeconds(60)),Date.from(now.plusSeconds(3600)),subject,subjectKey.getPublic());
        builder.addExtension(Extension.basicConstraints,true,new BasicConstraints(ca));
        if(nonce!=null) builder.addExtension(new org.bouncycastle.asn1.ASN1ObjectIdentifier("1.2.840.113635.100.8.2"),false,
                new DERSequence(new DERTaggedObject(true,1,new DEROctetString(nonce))));
        return builder.build(new JcaContentSignerBuilder("SHA256withECDSA").build(issuerKey.getPrivate())).getEncoded();
    }
    private static KeyPair key() throws Exception { var generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));return generator.generateKeyPair(); }
    private static byte[] unsigned32(BigInteger value) { byte[] bytes=value.toByteArray(), result=new byte[32];int length=Math.min(bytes.length,32);System.arraycopy(bytes,bytes.length-length,result,32-length,length);return result; }
    private static IosAcquisitionProperties properties(String env) { var p=new IosAcquisitionProperties();p.setAppIdPrefix("NFXT4L28FU");p.setAppAttestEnvironment(env);return p; }
    private record Fixture(AppAttestVerifier verifier,String keyId,String attestation,KeyPair key,byte[] root) {}
}
