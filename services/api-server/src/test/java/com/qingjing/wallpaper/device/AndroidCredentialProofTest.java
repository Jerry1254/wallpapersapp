package com.qingjing.wallpaper.device;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.security.*;
import java.security.interfaces.RSAPublicKey;
import java.time.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class AndroidCredentialProofTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final AndroidCredentialProof verifier = new AndroidCredentialProof(mapper);
    @Test void validatesPossessionAndRejectsWrongKeyScopePayloadAndTime() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        KeyPair key=generator.generateKeyPair(), other=generator.generateKeyPair();
        String pem=verifier.canonicalPem((RSAPublicKey) key.getPublic());
        Instant now=Instant.now(); String nonce=UUID.randomUUID().toString();
        String payload="QJ-ANDROID-REGISTER-V1\nandroid\n"+verifier.fingerprint((RSAPublicKey) key.getPublic())+"\n"+now+"\n"+nonce;
        Signature signer=Signature.getInstance("SHA256withRSA"); signer.initSign(key.getPrivate()); signer.update(payload.getBytes(StandardCharsets.UTF_8));
        String proof=Base64.getUrlEncoder().withoutPadding().encodeToString(signer.sign());
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(mapper.writeValueAsBytes(Map.of("timestamp",now.toString(),"nonce",nonce,"proof",proof)));
        assertThat(verifier.verifyRegistration("android",pem,token,now,Duration.ofMinutes(5)).fingerprint()).isEqualTo(verifier.fingerprint((RSAPublicKey) key.getPublic()));
        assertThatThrownBy(()->verifier.verifyRegistration("wrong",pem,token,now,Duration.ofMinutes(5))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->verifier.verifyRegistration("android",verifier.canonicalPem((RSAPublicKey) other.getPublic()),token,now,Duration.ofMinutes(5))).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->verifier.verifyRegistration("android",pem,token,now.plusSeconds(301),Duration.ofMinutes(5))).isInstanceOf(ApiException.class).extracting(e->((ApiException)e).code()).isEqualTo("TIMESTAMP_INVALID");
        assertThat(verifier.verify(pem,payload+"tamper",proof)).isFalse();
        assertThat(verifier.verify(pem,payload,proof+"=")).isFalse();
    }
    @Test void rejectsUnsupportedKeyAndMalformedEvidence() throws Exception {
        KeyPairGenerator generator=KeyPairGenerator.getInstance("RSA"); generator.initialize(1024);
        String pem=verifier.canonicalPem((RSAPublicKey)generator.generateKeyPair().getPublic());
        assertThatThrownBy(()->verifier.publicKey(pem)).isInstanceOf(ApiException.class);
        generator.initialize(2048); String good=verifier.canonicalPem((RSAPublicKey)generator.generateKeyPair().getPublic());
        assertThatThrownBy(()->verifier.verifyRegistration("android",good,"not-a-json-token",Instant.now(),Duration.ofMinutes(5))).isInstanceOf(ApiException.class);
    }
}
