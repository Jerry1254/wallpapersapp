package com.qingjing.wallpaper.iosacquisition;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.cbor.CBORFactory;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.AttestedKey;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.CertPathValidator;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.security.interfaces.ECPublicKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.bouncycastle.asn1.ASN1OctetString;
import org.bouncycastle.asn1.ASN1Primitive;
import org.bouncycastle.asn1.ASN1Sequence;
import org.bouncycastle.asn1.ASN1TaggedObject;
import org.springframework.http.HttpStatus;

/** Implements Apple's WebAuthn attestation and assertion verification rules. */
final class AppAttestVerifier {
    private final ObjectMapper cbor = new ObjectMapper(new CBORFactory());
    private final byte[] rpId;
    private final byte[] aaguid;
    private final String environment;
    private final Set<String> bundleVersions;
    private final X509Certificate root;

    AppAttestVerifier(IosAcquisitionProperties properties, byte[] rootBytes) throws Exception {
        environment = properties.getAppAttestEnvironment();
        bundleVersions = properties.bundleVersions();
        if (!Set.of("DEVELOPMENT", "PRODUCTION").contains(environment)) throw new IllegalArgumentException("Invalid App Attest environment");
        rpId = AppleCrypto.sha256((properties.getAppIdPrefix() + "." + properties.getBundleId()).getBytes(StandardCharsets.UTF_8));
        aaguid = environment.equals("DEVELOPMENT") ? "appattestdevelop".getBytes(StandardCharsets.US_ASCII)
                : Arrays.copyOf("appattest".getBytes(StandardCharsets.US_ASCII), 16);
        root = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(rootBytes));
    }

    AttestedKey attest(String keyId, String encoded, String clientData) {
        try {
            JsonNode object = cbor.readTree(Base64.getDecoder().decode(encoded));
            require("apple-appattest".equals(object.path("fmt").asText()));
            byte[] auth = binary(object, "authData");
            require(auth.length >= 87 && (auth[32] & 0x40) != 0);
            require(MessageDigest.isEqual(rpId, Arrays.copyOf(auth, 32)));
            require(counter(auth) == 0 && MessageDigest.isEqual(aaguid, Arrays.copyOfRange(auth, 37, 53)));
            int length = Short.toUnsignedInt(ByteBuffer.wrap(auth, 53, 2).getShort());
            byte[] id = Base64.getDecoder().decode(keyId);
            require(length == 32 && id.length == 32 && MessageDigest.isEqual(id, Arrays.copyOfRange(auth, 55, 87)));
            JsonNode stmt = object.path("attStmt");
            JsonNode x5c = stmt.path("x5c");
            require(x5c.isArray() && x5c.size() >= 2 && x5c.size() <= 4);
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            List<X509Certificate> chain = new ArrayList<>();
            for (JsonNode node : x5c) chain.add((X509Certificate) factory.generateCertificate(
                    new java.io.ByteArrayInputStream(node.binaryValue())));
            PKIXParameters params = new PKIXParameters(Set.of(new TrustAnchor(root, null)));
            params.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(factory.generateCertPath(chain), params);
            X509Certificate leaf = chain.get(0);
            ECPublicKey key = (ECPublicKey) leaf.getPublicKey();
            require(key.getParams().getCurve().getField().getFieldSize() == 256);
            byte[] point = new byte[65]; point[0] = 4;
            System.arraycopy(unsigned32(key.getW().getAffineX()), 0, point, 1, 32);
            System.arraycopy(unsigned32(key.getW().getAffineY()), 0, point, 33, 32);
            require(MessageDigest.isEqual(id, AppleCrypto.sha256(point)));
            byte[] nonce = AppleCrypto.sha256(AppleCrypto.concat(auth, AppleCrypto.sha256(clientData.getBytes(StandardCharsets.UTF_8))));
            byte[] extension = ASN1OctetString.getInstance(leaf.getExtensionValue("1.2.840.113635.100.8.2")).getOctets();
            require(MessageDigest.isEqual(nonce, findOctets(ASN1Primitive.fromByteArray(extension))));
            // Check the COSE key against the trusted certificate, not just the credential hash.
            try (var parser = cbor.getFactory().createParser(auth, 87, auth.length - 87)) {
                JsonNode cose = cbor.readTree(parser);
                require(cose.path("1").asInt() == 2 && cose.path("3").asInt() == -7 && cose.path("-1").asInt() == 1);
                require(MessageDigest.isEqual(unsigned32(key.getW().getAffineX()), binary(cose, "-2")));
                require(MessageDigest.isEqual(unsigned32(key.getW().getAffineY()), binary(cose, "-3")));
                if ((auth[32] & 0x80) != 0) validateExtensions(cbor.readTree(parser));
            }
            byte[] receipt = binary(stmt, "receipt"); require(receipt.length > 0);
            String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, new byte[]{10}).encodeToString(key.getEncoded())
                    + "\n-----END PUBLIC KEY-----";
            return new AttestedKey(pem, receipt, environment, 0);
        } catch (Exception e) { throw invalid(); }
    }

    long assertion(String encoded, String pem, byte[] rawBody, long previous) {
        try {
            JsonNode object = cbor.readTree(Base64.getDecoder().decode(encoded));
            byte[] auth = binary(object, "authenticatorData");
            require(auth.length >= 37 && MessageDigest.isEqual(rpId, Arrays.copyOf(auth, 32)));
            if ((auth[32] & 0x80) != 0) validateExtensions(cbor.readTree(Arrays.copyOfRange(auth, 37, auth.length)));
            else require(auth.length == 37);
            long next = counter(auth);
            if (next <= previous) throw new ApiException(HttpStatus.FORBIDDEN, "IOS_ASSERTION_REPLAY", "App Attest counter did not advance");
            byte[] clientDataHash = AppleCrypto.sha256(rawBody);
            byte[] nonce = AppleCrypto.sha256(AppleCrypto.concat(auth, clientDataHash));
            Signature verifier = Signature.getInstance("SHA256withECDSA");
            verifier.initVerify(AppleCrypto.publicKey(pem));
            verifier.update(nonce);
            require(verifier.verify(binary(object, "signature")));
            return next;
        } catch (ApiException e) { throw e; }
        catch (Exception e) { throw invalid(); }
    }

    private void validateExtensions(JsonNode ext) {
        int category = validationCategory(ext.path("apple_validation_category_01"));
        require((environment.equals("DEVELOPMENT") ? Set.of(3, 5) : Set.of(2, 4, 5)).contains(category));
        require(ext.path("apple_bundle_version_01").isTextual()
                && bundleVersions.contains(ext.path("apple_bundle_version_01").asText()));
    }

    /** Apple's UInt32 extension is transported as four little-endian bytes. */
    static int validationCategory(JsonNode value) {
        try {
            if (value.isIntegralNumber()) return value.intValue();
            if (!value.isBinary()) return -1;
            byte[] bytes = value.binaryValue();
            return bytes.length == 4 ? ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt() : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    private static byte[] findOctets(ASN1Primitive value) {
        if (value instanceof ASN1OctetString octets) return octets.getOctets();
        if (value instanceof ASN1TaggedObject tagged) return findOctets(tagged.getBaseObject().toASN1Primitive());
        ASN1Sequence sequence = ASN1Sequence.getInstance(value);
        require(sequence.size() == 1);
        return findOctets(sequence.getObjectAt(0).toASN1Primitive());
    }
    private static byte[] unsigned32(BigInteger value) {
        byte[] bytes = value.toByteArray();
        byte[] result = new byte[32];
        int length = Math.min(bytes.length, 32);
        System.arraycopy(bytes, bytes.length - length, result, 32 - length, length);
        return result;
    }
    private static long counter(byte[] auth) { return Integer.toUnsignedLong(ByteBuffer.wrap(auth, 33, 4).getInt()); }
    private static byte[] binary(JsonNode node, String field) throws Exception {
        require(node.path(field).isBinary()); return node.path(field).binaryValue();
    }
    private static void require(boolean value) { if (!value) throw invalid(); }
    private static ApiException invalid() { return new ApiException(HttpStatus.FORBIDDEN, "IOS_ATTESTATION_INVALID", "Apple App Attest verification failed"); }
}
