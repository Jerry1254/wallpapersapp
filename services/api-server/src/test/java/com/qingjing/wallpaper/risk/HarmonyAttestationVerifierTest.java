package com.qingjing.wallpaper.risk;

import static org.assertj.core.api.Assertions.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.core.io.DefaultResourceLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

class HarmonyAttestationVerifierTest {
    HarmonyRiskTestEvidence evidence; HarmonyAttestationVerifier verifier;
    String nonce=Base64.getEncoder().encodeToString(new byte[32]);
    @BeforeEach void create() throws Exception { evidence=new HarmonyRiskTestEvidence();verifier=evidence.verifier(); }
    @Test void authenticFactorsAreIndependentAndBothUsbAndWifiAreRisk() throws Exception {
        for(String usb:List.of("0","1","2","3")) {
            var signals=verifier.verify(evidence.jws(nonce,"true",usb),nonce,Instant.now());
            assertThat(signals.get("DEVELOPER_MODE")).isEqualTo(RiskDtos.Signal.RISK);
            assertThat(signals.get("USB_DEBUGGING")).isEqualTo(Set.of("1","2","3").contains(usb)?RiskDtos.Signal.RISK:RiskDtos.Signal.NORMAL);
        }
        assertThat(verifier.verify(evidence.jws(nonce,"false","1"),nonce,Instant.now()).get("DEVELOPER_MODE")).isEqualTo(RiskDtos.Signal.NORMAL);
    }
    @Test void missingFailedOrUnexpectedFactorsAreUnknown() throws Exception {
        var payload=evidence.payload(nonce,"true","1");
        payload.withObject("/isDeveloperMode").put("status",-1);
        payload.withObject("/hdcDebugState").put("result","9");
        assertThat(verifier.verify(evidence.jws(evidence.header().toString(),payload.toString()),nonce,Instant.now()).values()).containsOnly(RiskDtos.Signal.UNKNOWN);
        payload.remove("isDeveloperMode");payload.withObject("/hdcDebugState").put("status","0");
        assertThat(verifier.verify(evidence.jws(evidence.header().toString(),payload.toString()),nonce,Instant.now()).values()).containsOnly(RiskDtos.Signal.UNKNOWN);
    }
    @Test void callerRootWrongLeafNameAndExpiredCertificatesAreRejected() throws Exception {
        for(var invalid:List.of(new HarmonyRiskTestEvidence(),new HarmonyRiskTestEvidence("Other Huawei Service",false),
            new HarmonyRiskTestEvidence("Harmony OS Device Attestation Service",true))) {
            var target=invalid.chain.get(0).getSubjectX500Principal().getName().contains("Other") || invalid.chain.get(0).getNotAfter().before(new Date())
                ? invalid.verifier():verifier;
            assertThatThrownBy(()->target.verify(invalid.jws(nonce,"true","1"),nonce,Instant.now())).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        }
    }
    @Test void tamperingWrongAppWrongNonceStaleFutureAndDuplicateJsonAreRejected() throws Exception {
        String authentic=evidence.jws(nonce,"false","0");
        String[] parts=authentic.split("\\.");
        String changed=Base64.getUrlEncoder().withoutPadding().encodeToString(evidence.payload(nonce,"true","1").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(()->verifier.verify(parts[0]+"."+changed+"."+parts[2],nonce,Instant.now())).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        for(String kind:List.of("app","nonce","stale","future","duplicate")) {
            var payload=evidence.payload(nonce,"true","1");
            switch(kind) {
                case "app" -> payload.put("appId","other-app");
                case "nonce" -> payload.put("nonce","other-nonce");
                case "stale" -> payload.put("timestamp",Instant.now().minusSeconds(600).toEpochMilli());
                case "future" -> payload.put("timestamp",Instant.now().plusSeconds(600).toEpochMilli());
            }
            String raw=kind.equals("duplicate")?payload.toString().replaceFirst("\\{","{\"appId\":\"other-app\","):payload.toString();
            String jws=evidence.jws(evidence.header().toString(),raw);
            assertThatThrownBy(()->verifier.verify(jws,nonce,Instant.now())).as(kind).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        }
    }
    @Test void algorithmsAndChainLengthCannotBeSelectedByCaller() throws Exception {
        for(String kind:List.of("alg","typ","crit","chain")) {
            var header=evidence.header();
            switch(kind) {
                case "alg" -> header.put("alg","none"); case "typ" -> header.put("typ","JWT");
                case "crit" -> header.putArray("crit").add("unknown");
                case "chain" -> header.withArray("/x5c").remove(2);
            }
            String jws=evidence.jws(header.toString(),evidence.payload(nonce,"true","1").toString());
            assertThatThrownBy(()->verifier.verify(jws,nonce,Instant.now())).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
        }
    }
    @Test void productionRootLoadsAndExplicitlyDisabledAdapterHasNoTrustAnchor() throws Exception {
        var properties=new HarmonyRiskProperties();
        assertThat(new HarmonyAttestationVerifier(new ObjectMapper(),properties,new DefaultResourceLoader()).available()).isTrue();
        properties.setEnabled(false);
        assertThat(new HarmonyAttestationVerifier(new ObjectMapper(),properties,new DefaultResourceLoader()).available()).isFalse();
        assertThat(Files.readString(Path.of("src/main/resources/huawei/Huawei_CBG_Root_CA_G2.pem"))).contains("BEGIN CERTIFICATE").doesNotContain("PRIVATE KEY");
    }
}
