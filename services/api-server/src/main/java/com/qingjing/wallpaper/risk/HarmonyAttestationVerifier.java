package com.qingjing.wallpaper.risk;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.*;
import java.security.interfaces.ECPublicKey;
import java.security.spec.*;
import java.time.Instant;
import java.util.*;
import javax.naming.ldap.LdapName;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/** A phone-supplied certificate is never a trust anchor. Only the configured Huawei root is trusted. */
@Component
public class HarmonyAttestationVerifier {
    private final ObjectMapper json;
    private final HarmonyRiskProperties properties;
    private final X509Certificate root;
    public HarmonyAttestationVerifier(ObjectMapper json, HarmonyRiskProperties properties, ResourceLoader resources) throws Exception {
        this.json = json.copy().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        this.properties = properties;
        if (!properties.isEnabled() || properties.getRootCa().isBlank() || properties.getAppId().isBlank()) {
            root = null;
        } else {
            try (var input = resources.getResource(properties.getRootCa()).getInputStream()) {
                root = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
            }
            require(root.getBasicConstraints() >= 0 && root.getSubjectX500Principal().equals(root.getIssuerX500Principal()));
            root.verify(root.getPublicKey());
        }
    }
    public boolean available() { return root != null; }

    Map<String, RiskDtos.Signal> verify(String jws, String nonce, Instant issuedAt) {
        try {
            require(available() && jws != null && jws.length() <= 32768);
            String[] parts = jws.split("\\.", -1);
            require(parts.length == 3);
            JsonNode header = json.readTree(decode(parts[0]));
            require("ES256".equals(header.path("alg").textValue()) && "JWS".equals(header.path("typ").textValue()));
            require(!header.has("crit") && !header.has("b64"));
            JsonNode x5c = header.path("x5c");
            require(x5c.isArray() && x5c.size() == 3);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (JsonNode encoded : x5c) {
                require(encoded.isTextual() && encoded.textValue().length() <= 12000);
                byte[] bytes = Base64.getDecoder().decode(encoded.textValue());
                var input = new ByteArrayInputStream(bytes);
                X509Certificate cert = (X509Certificate) factory.generateCertificate(input);
                require(input.available() == 0);
                cert.checkValidity(); chain.add(cert);
            }
            root.checkValidity();
            require(MessageDigest.isEqual(root.getEncoded(), chain.get(2).getEncoded()));
            PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
            // Verification stays offline: the application never follows a caller-controlled certificate URL.
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain.subList(0, 2)), params);
            X509Certificate leaf = chain.get(0);
            var cns = new LdapName(leaf.getSubjectX500Principal().getName()).getRdns().stream()
                .filter(r -> "CN".equalsIgnoreCase(r.getType())).map(r -> r.getValue().toString()).toList();
            require(cns.equals(List.of("Harmony OS Device Attestation Service")) && leaf.getBasicConstraints() < 0);
            require(leaf.getKeyUsage() == null || leaf.getKeyUsage()[0]);
            require(leaf.getPublicKey() instanceof ECPublicKey);
            ECPublicKey key = (ECPublicKey) leaf.getPublicKey();
            AlgorithmParameters ec = AlgorithmParameters.getInstance("EC");
            ec.init(new ECGenParameterSpec("secp256r1"));
            ECParameterSpec p256 = ec.getParameterSpec(ECParameterSpec.class);
            require(key.getParams().getCurve().equals(p256.getCurve()) && key.getParams().getGenerator().equals(p256.getGenerator())
                && key.getParams().getOrder().equals(p256.getOrder()) && key.getParams().getCofactor() == p256.getCofactor());
            byte[] signature = decode(parts[2]); require(signature.length == 64);
            Signature verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(key);
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            require(verifier.verify(signature));
            JsonNode payload = json.readTree(decode(parts[1]));
            require(properties.getAppId().equals(payload.path("appId").textValue()) && nonce.equals(payload.path("nonce").textValue()));
            JsonNode time = payload.path("timestamp");
            require(time.isIntegralNumber() && time.canConvertToLong());
            Instant timestamp = Instant.ofEpochMilli(time.longValue());
            Instant now = Instant.now();
            require(!timestamp.isBefore(issuedAt.minusSeconds(10)) && !timestamp.isBefore(now.minusSeconds(120))
                && !timestamp.isAfter(now.plusSeconds(30)));
            return Map.of("DEVELOPER_MODE", developer(payload.path("isDeveloperMode")), "USB_DEBUGGING", debugging(payload.path("hdcDebugState")));
        } catch (Exception e) { throw invalid(); }
    }
    private static byte[] decode(String value) {
        require(!value.isEmpty() && value.matches("[A-Za-z0-9_-]+"));
        byte[] decoded = Base64.getUrlDecoder().decode(value);
        require(Base64.getUrlEncoder().withoutPadding().encodeToString(decoded).equals(value));
        return decoded;
    }
    private static String result(JsonNode value) {
        JsonNode status = value.path("status");
        return status.isIntegralNumber() && status.canConvertToInt() && status.intValue() == 0 && value.path("result").isTextual()
            ? value.path("result").textValue() : "";
    }
    private static RiskDtos.Signal developer(JsonNode value) {
        return switch (result(value)) { case "true" -> RiskDtos.Signal.RISK; case "false" -> RiskDtos.Signal.NORMAL; default -> RiskDtos.Signal.UNKNOWN; };
    }
    private static RiskDtos.Signal debugging(JsonNode value) {
        return switch (result(value)) { case "1", "2", "3" -> RiskDtos.Signal.RISK; case "0" -> RiskDtos.Signal.NORMAL; default -> RiskDtos.Signal.UNKNOWN; };
    }
    private static void require(boolean ok) { if (!ok) throw new IllegalArgumentException("Invalid attestation"); }
    static com.qingjing.wallpaper.shared.web.ApiException invalid() {
        return new com.qingjing.wallpaper.shared.web.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST, "SECURITY_INVALID", "安全检测凭证不符合要求");
    }
}
