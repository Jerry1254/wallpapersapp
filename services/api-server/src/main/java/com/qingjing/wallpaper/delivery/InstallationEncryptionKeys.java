package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.device.AndroidCredentialProof;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.security.PublicKey;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Binding is called only after the existing signing credential authenticates the exact request body. */
@Service
public class InstallationEncryptionKeys {
    private final JdbcTemplate jdbc;
    private final AndroidCredentialProof publicKeys;
    public InstallationEncryptionKeys(JdbcTemplate jdbc, AndroidCredentialProof publicKeys) {
        this.jdbc = jdbc; this.publicKeys = publicKeys;
    }
    @Transactional
    public String bind(DevicePrincipal principal, String pem) {
        if (principal.platform() != DevicePlatform.ANDROID) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PLATFORM_MISMATCH", "Encryption key binding requires Android");
        }
        var publicKey = publicKeys.publicKey(pem);
        String fingerprint = publicKeys.fingerprint(publicKey);
        var credentials = jdbc.query("""
                SELECT c.id FROM device_credential c JOIN anonymous_device d ON d.id=c.device_id
                WHERE c.credential_key_id=? AND c.device_id=? AND c.status='ACTIVE' AND d.status='ACTIVE'
                FOR UPDATE
                """, (rs, n) -> rs.getLong(1), principal.credentialKeyId(), principal.deviceId());
        if (credentials.size() != 1) throw new ApiException(HttpStatus.UNAUTHORIZED, "CREDENTIAL_INVALID", "An active credential is required");
        long credentialId = credentials.get(0);
        var previous = jdbc.queryForList("SELECT public_key_sha256 FROM device_encryption_key WHERE credential_id=?", String.class, credentialId);
        if (!previous.isEmpty()) {
            if (!previous.get(0).equals(fingerprint)) {
                throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "The installation already has a different encryption key");
            }
            return fingerprint;
        }
        jdbc.update("INSERT INTO device_encryption_key (credential_id,public_key_pem,public_key_sha256) VALUES (?,?,?)",
                credentialId, publicKeys.canonicalPem(publicKey), fingerprint);
        return fingerprint;
    }
    public PublicKey require(DevicePrincipal principal) {
        var keys = jdbc.queryForList("""
                SELECT k.public_key_pem FROM device_encryption_key k
                JOIN device_credential c ON c.id=k.credential_id JOIN anonymous_device d ON d.id=c.device_id
                WHERE c.credential_key_id=? AND c.device_id=? AND c.status='ACTIVE' AND d.status='ACTIVE'
                """, String.class, principal.credentialKeyId(), principal.deviceId());
        if (keys.size() != 1) throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "SECURE_PACKAGE_NOT_READY", "Bind an installation encryption key first");
        return publicKeys.publicKey(keys.get(0));
    }
}
