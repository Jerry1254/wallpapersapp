package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.entitlement.EntitlementGrantService;
import com.qingjing.wallpaper.entitlement.EntitlementGrantService.SourceType;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.*;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.AttestedKey;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.DeviceBits;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.VerifiedNotification;
import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.VerifiedTransaction;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class IosAcquisitionService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final IosAcquisitionProperties properties;
    private final IosAppleGateway apple;
    private final SecurityCrypto crypto;
    private final EntitlementGrantService grants;

    public IosAcquisitionService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IosAcquisitionProperties properties,
            IosAppleGateway apple,
            SecurityCrypto crypto,
            EntitlementGrantService grants) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.apple = apple;
        this.crypto = crypto;
        this.grants = grants;
    }

    public ChallengeResponse challenge(DevicePrincipal principal, ChallengeRequest request) {
        requireEnabled(principal);
        Installation installation = ensureInstallation(principal.deviceId());
        if (request.action() == Action.FREE_RESET) {
            if (request.resetId() == null || !request.resetId().matches("^[0-9a-fA-F-]{36}$")) {
                throw conflict("IOS_FREE_RESET_NOT_FOUND", "A valid resetId is required");
            }
            requirePendingReset(principal.deviceId(), request.resetId(), installation.freeGeneration());
        } else if (request.resetId() != null) {
            throw validation("resetId is only valid for FREE_RESET");
        }
        Long wallpaperId = parseId(request.wallpaperId(), "wallpaperId");
        String challengeId = UUID.randomUUID().toString();
        String nonce = crypto.randomToken(32);
        Instant expiresAt = Instant.now().plus(properties.getChallengeTtl());
        String clientData = request.action() == Action.ENROLL
                ? "QJ-IOS-APP-ATTEST-V1\n" + challengeId + "\n" + nonce + "\n"
                        + properties.getAppIdPrefix() + "." + properties.getBundleId()
                : null;
        jdbc.update(
                """
                INSERT INTO ios_attestation_challenge
                    (id,device_id,key_id,action,wallpaper_id,reset_id,free_generation,nonce,client_data,expires_at)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,
                challengeId, principal.deviceId(), request.keyId(), request.action().name(), wallpaperId,
                request.resetId(), installation.freeGeneration(), nonce, clientData, Timestamp.from(expiresAt));
        return new ChallengeResponse(challengeId, nonce, clientData, expiresAt);
    }

    public AttestationRegistrationResponse register(
            DevicePrincipal principal, AttestationRegistrationRequest request) {
        requireEnabled(principal);
        Challenge challenge = challengeForUpdate(
                principal.deviceId(), request.challengeId(), request.keyId(), Action.ENROLL, null);
        AttestedKey key = apple.verifyAttestation(
                request.keyId(), request.attestation(), challenge.clientData(), challenge.nonce());
        transactions.executeWithoutResult(status -> {
            jdbc.update(
                    """
                    INSERT INTO ios_app_attest_key
                        (device_id,key_id,public_key_pem,app_id_prefix,bundle_id,environment,receipt,assertion_counter)
                    VALUES (?,?,?,?,?,?,?,?)
                    ON DUPLICATE KEY UPDATE public_key_pem=VALUES(public_key_pem),receipt=VALUES(receipt),
                        assertion_counter=GREATEST(assertion_counter,VALUES(assertion_counter)),status='ACTIVE'
                    """,
                    principal.deviceId(), request.keyId(), key.publicKeyPem(), properties.getAppIdPrefix(),
                    properties.getBundleId(), key.environment(), key.receipt(), key.initialCounter());
            consumeChallenge(principal.deviceId(), request.challengeId());
        });
        return new AttestationRegistrationResponse(true);
    }

    public AcquisitionState status(
            DevicePrincipal principal, String keyId, String assertion, DeviceProofRequest request, byte[] rawBody) {
        requireEnabled(principal);
        verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.STATUS, null, rawBody);
        DeviceBits bits = apple.queryDeviceBits(request.deviceToken());
        transactions.executeWithoutResult(tx -> jdbc.update(
                """
                UPDATE ios_installation_acquisition
                SET free_allowance_status=?,devicecheck_checked_at=UTC_TIMESTAMP(6),lock_version=lock_version+1
                WHERE device_id=? AND active_reset_id IS NULL
                """,
                bits.bit0() ? "USED" : "AVAILABLE", principal.deviceId()));
        return state(principal.deviceId());
    }

    public AcquisitionState claim(
            DevicePrincipal principal, String keyId, String assertion, FreeClaimRequest request, byte[] rawBody) {
        requireEnabled(principal);
        UUID requestId = uuid(request.requestId(), "requestId");
        long wallpaperId = parseId(request.wallpaperId(), "wallpaperId");
        verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.FREE_CLAIM, wallpaperId, rawBody);
        Installation installation = installationForUpdate(principal.deviceId());
        if (installation.activeResetId() != null) throw conflict("IOS_FREE_RESET_PENDING", "A free reset is pending");
        requireEligibleWallpaper(wallpaperId);

        List<ClaimRow> previous = claim(principal.deviceId(), requestId.toString());
        if (!previous.isEmpty()) {
            ClaimRow row = previous.get(0);
            if (row.wallpaperId() != wallpaperId) throw conflict("IDEMPOTENCY_CONFLICT", "The request targets another wallpaper");
            if (row.generation() != installation.freeGeneration()) throw conflict("IOS_FREE_CLAIM_RESET", "The claim belongs to a reset generation");
            if (row.status().equals("COMPLETED")) return state(principal.deviceId());
            if (!row.status().equals("PROCESSING") && !row.status().equals("APPLE_CONFIRMED")) {
                throw conflict("IOS_FREE_ALLOWANCE_USED", "The free allowance is unavailable");
            }
        } else {
            if (!installation.allowance().equals("AVAILABLE")) {
                throw conflict("IOS_FREE_ALLOWANCE_USED", "The free allowance is unavailable");
            }
            jdbc.update(
                    """
                    INSERT INTO ios_free_claim
                        (request_id,device_id,free_generation,wallpaper_id,status,device_token_encrypted,apple_transaction_id)
                    VALUES (?,?,?,?,'PROCESSING',?,?)
                    """,
                    requestId.toString(), principal.deviceId(), installation.freeGeneration(), wallpaperId,
                    crypto.encrypt("ios-devicecheck-token", request.deviceToken().getBytes(StandardCharsets.UTF_8)),
                    UUID.randomUUID().toString());
        }

        DeviceBits bits = apple.queryDeviceBits(request.deviceToken());
        if (bits.bit0()) {
            jdbc.update("UPDATE ios_free_claim SET status='REJECTED',error_code='IOS_FREE_ALLOWANCE_USED',completed_at=UTC_TIMESTAMP(6) WHERE device_id=? AND request_id=?",
                    principal.deviceId(), requestId.toString());
            jdbc.update("UPDATE ios_installation_acquisition SET free_allowance_status='USED' WHERE device_id=?", principal.deviceId());
            throw conflict("IOS_FREE_ALLOWANCE_USED", "The physical device already used its free allowance");
        }
        String appleTransactionId = jdbc.queryForObject(
                "SELECT apple_transaction_id FROM ios_free_claim WHERE device_id=? AND request_id=?",
                String.class, principal.deviceId(), requestId.toString());
        apple.markFirstFreeUsed(request.deviceToken(), appleTransactionId);
        jdbc.update("UPDATE ios_free_claim SET status='APPLE_CONFIRMED' WHERE device_id=? AND request_id=?",
                principal.deviceId(), requestId.toString());
        transactions.executeWithoutResult(tx -> {
            Installation locked = installationForUpdate(principal.deviceId());
            if (locked.freeGeneration() != installation.freeGeneration()) {
                throw conflict("IOS_FREE_GENERATION_CONFLICT", "The free generation changed");
            }
            long entitlementId = grants.grant(principal.deviceId(), wallpaperId, SourceType.IOS_FIRST_FREE,
                    requestId.toString(), installation.freeGeneration(), null);
            jdbc.update(
                    """
                    UPDATE ios_free_claim SET status='COMPLETED',entitlement_id=?,device_token_encrypted=NULL,
                        completed_at=UTC_TIMESTAMP(6),error_code=NULL WHERE device_id=? AND request_id=?
                    """,
                    entitlementId, principal.deviceId(), requestId.toString());
            jdbc.update("UPDATE ios_installation_acquisition SET free_allowance_status='USED',devicecheck_checked_at=UTC_TIMESTAMP(6),lock_version=lock_version+1 WHERE device_id=?",
                    principal.deviceId());
        });
        return state(principal.deviceId());
    }

    public AcquisitionState purchase(
            DevicePrincipal principal, String keyId, String assertion, PurchaseRequest request, byte[] rawBody) {
        requireEnabled(principal);
        verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.PURCHASE_SYNC, null, rawBody);
        VerifiedTransaction verified = apple.verifyTransaction(
                request.signedTransaction(), request.signedAppTransaction(), request.deviceVerificationId());
        requireTrustedTransaction(verified);
        long wallpaperId = mappedWallpaper(verified.productId());
        transactions.executeWithoutResult(tx -> {
            jdbc.update(
                    """
                    INSERT INTO ios_store_transaction
                        (environment,bundle_id,product_id,transaction_id,original_transaction_id,
                         app_account_token,device_verification_id,wallpaper_id,purchased_at,revoked_at,verified_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE revoked_at=VALUES(revoked_at),verified_at=UTC_TIMESTAMP(6),
                        device_verification_id=VALUES(device_verification_id)
                    """,
                    verified.environment(), verified.bundleId(), verified.productId(), verified.transactionId(),
                    verified.originalTransactionId(), verified.appAccountToken(), verified.deviceVerificationId(),
                    wallpaperId, Timestamp.from(verified.purchasedAt()), timestamp(verified.revokedAt()));
            jdbc.update("UPDATE ios_product_mapping SET verified_transaction_at=COALESCE(verified_transaction_at,UTC_TIMESTAMP(6)) WHERE bundle_id=? AND product_id=?",
                    properties.getBundleId(), verified.productId());
            String source = verified.originalTransactionId() + ":" + principal.deviceId();
            if (verified.revokedAt() == null) {
                grants.grant(principal.deviceId(), wallpaperId, SourceType.IOS_IAP, source, null, null);
            } else {
                grants.revoke(SourceType.IOS_IAP, source, "Apple transaction was revoked");
            }
        });
        return state(principal.deviceId());
    }

    public Object completeReset(
            DevicePrincipal principal, String resetId, String keyId, String assertion,
            FreeResetCompleteRequest request, byte[] rawBody) {
        requireEnabled(principal);
        Challenge resetChallenge = verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.FREE_RESET, null, rawBody);
        if (!resetId.equals(resetChallenge.resetId())) {
            throw conflict("IOS_ATTESTATION_INVALID", "The challenge targets another reset");
        }
        ResetRow reset = resetForUpdate(principal.deviceId(), resetId);
        if (reset.status().equals("COMPLETED")) return state(principal.deviceId());
        if (reset.expectedGeneration() != request.expectedGeneration()) {
            throw conflict("IOS_FREE_GENERATION_CONFLICT", "The reset generation changed");
        }
        if (!List.of("WAITING_DEVICE", "RETRYABLE_FAILURE").contains(reset.status())) {
            throw conflict("IOS_FREE_RESET_PENDING", "The reset is already processing");
        }
        String appleTransactionId = reset.appleTransactionId() == null ? UUID.randomUUID().toString() : reset.appleTransactionId();
        jdbc.update(
                """
                UPDATE ios_free_reset SET status='PROCESSING',started_at=COALESCE(started_at,UTC_TIMESTAMP(6)),
                    apple_transaction_id=?,device_token_encrypted=?,error_code=NULL WHERE id=?
                """,
                appleTransactionId,
                crypto.encrypt("ios-devicecheck-token", request.deviceToken().getBytes(StandardCharsets.UTF_8)), resetId);
        apple.resetFirstFreeBit(request.deviceToken(), appleTransactionId);
        jdbc.update("UPDATE ios_free_reset SET status='APPLE_RESET_CONFIRMED' WHERE id=?", resetId);
        transactions.executeWithoutResult(tx -> {
            Installation locked = installationForUpdate(principal.deviceId());
            if (locked.freeGeneration() != reset.expectedGeneration()) {
                throw conflict("IOS_FREE_GENERATION_CONFLICT", "The reset generation changed");
            }
            grants.revokeFirstFreeGeneration(principal.deviceId(), reset.expectedGeneration(), "iOS test free allowance reset");
            jdbc.update("UPDATE ios_free_claim SET status='RESET',error_code='IOS_FREE_CLAIM_RESET',device_token_encrypted=NULL WHERE device_id=? AND free_generation=? AND status IN ('PROCESSING','APPLE_CONFIRMED','COMPLETED')",
                    principal.deviceId(), reset.expectedGeneration());
            jdbc.update(
                    """
                    UPDATE ios_installation_acquisition SET free_generation=free_generation+1,
                        free_allowance_status='AVAILABLE',active_reset_id=NULL,devicecheck_checked_at=UTC_TIMESTAMP(6),
                        lock_version=lock_version+1 WHERE device_id=?
                    """,
                    principal.deviceId());
            jdbc.update(
                    """
                    UPDATE ios_free_reset SET status='COMPLETED',result_generation=expected_generation+1,
                        device_token_encrypted=NULL,completed_at=UTC_TIMESTAMP(6),error_code=NULL WHERE id=?
                    """,
                    resetId);
        });
        return state(principal.deviceId());
    }

    public void notification(String signedPayload) {
        requireFeatureConfigured();
        VerifiedNotification notification = apple.verifyNotification(signedPayload);
        VerifiedTransaction transaction = notification.transaction();
        String inboxId = UUID.randomUUID().toString();
        jdbc.update(
                """
                INSERT INTO apple_notification_inbox
                    (id,notification_uuid,environment,notification_type,subtype,signed_payload,status)
                VALUES (?,?,?,?,?,?,'VERIFIED')
                ON DUPLICATE KEY UPDATE notification_uuid=notification_uuid
                """,
                inboxId, notification.notificationUuid(), notification.environment(),
                notification.notificationType(), notification.subtype(), signedPayload);
        if (transaction != null) {
            requireTrustedTransaction(transaction);
            long wallpaperId = mappedWallpaper(transaction.productId());
            jdbc.update(
                    """
                    INSERT INTO ios_store_transaction
                        (environment,bundle_id,product_id,transaction_id,original_transaction_id,
                         app_account_token,device_verification_id,wallpaper_id,purchased_at,revoked_at,verified_at)
                    VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                    ON DUPLICATE KEY UPDATE revoked_at=VALUES(revoked_at),verified_at=UTC_TIMESTAMP(6)
                    """,
                    transaction.environment(), transaction.bundleId(), transaction.productId(), transaction.transactionId(),
                    transaction.originalTransactionId(), transaction.appAccountToken(), transaction.deviceVerificationId(),
                    wallpaperId, Timestamp.from(transaction.purchasedAt()), timestamp(transaction.revokedAt()));
            if (transaction.revokedAt() != null) {
                List<String> sources = jdbc.query(
                        "SELECT source_reference FROM entitlement_grant WHERE source_type='IOS_IAP' AND source_reference LIKE ? AND status='ACTIVE'",
                        (rs,row) -> rs.getString(1), transaction.originalTransactionId() + ":%");
                sources.forEach(source -> grants.revoke(SourceType.IOS_IAP, source, "Apple transaction was revoked"));
            }
        }
        jdbc.update("UPDATE apple_notification_inbox SET status='PROCESSED',processed_at=UTC_TIMESTAMP(6),attempts=attempts+1 WHERE id=?", inboxId);
    }

    public AcquisitionState state(long deviceId) {
        Installation installation = ensureInstallation(deviceId);
        List<String> free = grantWallpapers(deviceId, "IOS_FIRST_FREE");
        List<String> purchased = grantWallpapers(deviceId, "IOS_IAP");
        List<Product> products = jdbc.query(
                """
                SELECT CAST(m.wallpaper_id AS CHAR) wallpaper_id,m.product_id
                FROM ios_product_mapping m JOIN wallpaper w ON w.id=m.wallpaper_id
                WHERE m.bundle_id=? AND m.enabled=TRUE AND w.status='PUBLISHED'
                ORDER BY m.wallpaper_id
                """,
                (rs,row)->new Product(rs.getString("wallpaper_id"),rs.getString("product_id")),
                properties.getBundleId());
        PendingReset pending = pendingReset(deviceId);
        return new AcquisitionState(
                publicDeviceId(deviceId), installation.accountToken(), installation.freeGeneration(),
                pending == null ? FreeAllowance.valueOf(installation.allowance()) : FreeAllowance.PENDING_RESET,
                free, purchased, products, pending, Instant.now());
    }

    private Challenge verifyProof(long deviceId, String keyId, String assertion, String challengeId,
            String nonce, Action action, Long wallpaperId, byte[] rawBody) {
        Challenge challenge = challengeForUpdate(deviceId, challengeId, keyId, action, wallpaperId);
        if (!challenge.nonce().equals(nonce)) throw conflict("IOS_ATTESTATION_INVALID", "The challenge nonce is invalid");
        List<Long> counters = jdbc.query(
                "SELECT assertion_counter FROM ios_app_attest_key WHERE device_id=? AND key_id=? AND status='ACTIVE' FOR UPDATE",
                (rs,row)->rs.getLong(1), deviceId, keyId);
        if (counters.isEmpty()) throw forbidden("IOS_ATTESTATION_INVALID", "The App Attest key is not registered");
        long next = apple.verifyAssertion(keyId, assertion, rawBody, counters.get(0));
        if (next <= counters.get(0)) throw forbidden("IOS_ASSERTION_REPLAY", "The App Attest assertion was replayed");
        transactions.executeWithoutResult(tx -> {
            jdbc.update("UPDATE ios_app_attest_key SET assertion_counter=? WHERE device_id=? AND key_id=? AND assertion_counter<?",
                    next, deviceId, keyId, next);
            consumeChallenge(deviceId, challengeId);
        });
        return challenge;
    }

    private Challenge challengeForUpdate(long deviceId, String challengeId, String keyId, Action action, Long wallpaperId) {
        List<Challenge> rows = jdbc.query(
                """
                SELECT nonce,client_data,wallpaper_id,reset_id,free_generation,expires_at,consumed_at
                FROM ios_attestation_challenge
                WHERE id=? AND device_id=? AND key_id=? AND action=?
                """,
                (rs,row)->new Challenge(rs.getString("nonce"),rs.getString("client_data"),
                        rs.getObject("wallpaper_id",Long.class),rs.getString("reset_id"),rs.getLong("free_generation"),
                        rs.getTimestamp("expires_at").toInstant(),timestamp(rs.getTimestamp("consumed_at"))),
                challengeId, deviceId, keyId, action.name());
        if (rows.isEmpty()) throw conflict("IOS_CHALLENGE_EXPIRED", "The challenge was not found");
        Challenge challenge = rows.get(0);
        if (challenge.consumedAt()!=null || !challenge.expiresAt().isAfter(Instant.now())) {
            throw conflict("IOS_CHALLENGE_EXPIRED", "The challenge expired or was consumed");
        }
        if ((wallpaperId != null || challenge.wallpaperId()!=null) && !java.util.Objects.equals(wallpaperId, challenge.wallpaperId())) {
            throw conflict("IOS_ATTESTATION_INVALID", "The challenge targets another wallpaper");
        }
        return challenge;
    }

    private void consumeChallenge(long deviceId, String challengeId) {
        int updated=jdbc.update("UPDATE ios_attestation_challenge SET consumed_at=UTC_TIMESTAMP(6) WHERE id=? AND device_id=? AND consumed_at IS NULL AND expires_at>UTC_TIMESTAMP(6)",challengeId,deviceId);
        if(updated!=1) throw conflict("IOS_CHALLENGE_EXPIRED","The challenge expired or was consumed");
    }

    private Installation ensureInstallation(long deviceId) {
        jdbc.update(
                """
                INSERT IGNORE INTO ios_installation_acquisition
                    (device_id,account_token,free_allowance_status)
                VALUES (?,?,'UNAVAILABLE')
                """,
                deviceId, UUID.randomUUID().toString());
        return installation(deviceId, false);
    }
    private Installation installationForUpdate(long deviceId) { return installation(deviceId, true); }
    private Installation installation(long deviceId, boolean lock) {
        return jdbc.queryForObject(
                "SELECT account_token,free_generation,free_allowance_status,is_test_device,active_reset_id FROM ios_installation_acquisition WHERE device_id=?"+(lock?" FOR UPDATE":""),
                (rs,row)->new Installation(rs.getString("account_token"),rs.getLong("free_generation"),
                        rs.getString("free_allowance_status"),rs.getBoolean("is_test_device"),rs.getString("active_reset_id")),deviceId);
    }
    private List<ClaimRow> claim(long deviceId,String requestId){return jdbc.query("SELECT wallpaper_id,free_generation,status FROM ios_free_claim WHERE device_id=? AND request_id=?",(rs,row)->new ClaimRow(rs.getLong(1),rs.getLong(2),rs.getString(3)),deviceId,requestId);}
    private void requireEligibleWallpaper(long wallpaperId){Long count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM ios_product_mapping m JOIN wallpaper w ON w.id=m.wallpaper_id
            JOIN wallpaper_variant v ON v.wallpaper_id=w.id AND v.platform='IOS' AND v.resource_type='LIVE_PHOTO'
            JOIN resource_version rv ON rv.variant_id=v.id AND rv.status='PUBLISHED'
            WHERE m.wallpaper_id=? AND m.bundle_id=? AND m.enabled=TRUE AND m.first_free_eligible=TRUE
              AND w.status='PUBLISHED' AND w.access_type='REDEEM'
            """,Long.class,wallpaperId,properties.getBundleId());if(count==null||count==0)throw conflict("IOS_FREE_WALLPAPER_INELIGIBLE","The wallpaper is not eligible for first free");}
    private long mappedWallpaper(String productId){List<Long> rows=jdbc.query("SELECT wallpaper_id FROM ios_product_mapping WHERE bundle_id=? AND product_id=? AND enabled=TRUE",(rs,row)->rs.getLong(1),properties.getBundleId(),productId);if(rows.isEmpty())throw forbidden("IOS_PURCHASE_INVALID","The Apple product is not mapped");return rows.get(0);}
    private void requireTrustedTransaction(VerifiedTransaction value){if(!properties.getBundleId().equals(value.bundleId())||!properties.getStoreEnvironment().equals(value.environment())||"XCODE".equals(value.environment()))throw forbidden("IOS_PURCHASE_ENVIRONMENT_INVALID","The Apple transaction environment is invalid");}
    private List<String> grantWallpapers(long deviceId,String type){return jdbc.query("SELECT CAST(de.wallpaper_id AS CHAR) FROM entitlement_grant eg JOIN device_entitlement de ON de.id=eg.entitlement_id WHERE de.device_id=? AND eg.source_type=? AND eg.status='ACTIVE' ORDER BY de.wallpaper_id",(rs,row)->rs.getString(1),deviceId,type);}
    private String publicDeviceId(long id){return jdbc.queryForObject("SELECT public_id FROM anonymous_device WHERE id=?",String.class,id);}
    private PendingReset pendingReset(long deviceId){List<PendingReset> rows=jdbc.query("SELECT id,expected_generation,status,expires_at FROM ios_free_reset WHERE device_id=? AND status IN ('WAITING_DEVICE','PROCESSING','APPLE_RESET_CONFIRMED','RETRYABLE_FAILURE') ORDER BY created_at DESC LIMIT 1",(rs,row)->new PendingReset(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant()),deviceId);return rows.isEmpty()?null:rows.get(0);}
    private void requirePendingReset(long deviceId,String resetId,long generation){ResetRow row=resetForUpdate(deviceId,resetId);if(row.expectedGeneration()!=generation||!List.of("WAITING_DEVICE","RETRYABLE_FAILURE").contains(row.status()))throw conflict("IOS_FREE_RESET_PENDING","The reset cannot accept a challenge");}
    private ResetRow resetForUpdate(long deviceId,String resetId){List<ResetRow> rows=jdbc.query("SELECT expected_generation,status,apple_transaction_id FROM ios_free_reset WHERE id=? AND device_id=?",(rs,row)->new ResetRow(rs.getLong(1),rs.getString(2),rs.getString(3)),resetId,deviceId);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"IOS_FREE_RESET_NOT_FOUND","The reset was not found");return rows.get(0);}
    private void requireEnabled(DevicePrincipal principal){if(principal.platform()!=DevicePlatform.IOS)throw forbidden("IOS_DEVICE_REQUIRED","An iOS device session is required");requireFeatureConfigured();}
    private void requireFeatureConfigured(){if(!properties.isEnabled()||!properties.hasAppleCredentials())throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,"IOS_ACQUISITION_UNAVAILABLE","iOS acquisition is disabled or not configured");}
    private Long parseId(String value,String name){if(value==null)return null;try{long id=Long.parseLong(value);if(id<=0)throw new NumberFormatException();return id;}catch(NumberFormatException e){throw validation(name+" must be a positive integer");}}
    private UUID uuid(String value,String name){try{return UUID.fromString(value);}catch(RuntimeException e){throw validation(name+" must be a UUID");}}
    private Timestamp timestamp(Instant value){return value==null?null:Timestamp.from(value);}
    private Instant timestamp(Timestamp value){return value==null?null:value.toInstant();}
    private ApiException validation(String message){return new ApiException(HttpStatus.BAD_REQUEST,"VALIDATION_FAILED",message);}
    private ApiException conflict(String code,String message){return new ApiException(HttpStatus.CONFLICT,code,message);}
    private ApiException forbidden(String code,String message){return new ApiException(HttpStatus.FORBIDDEN,code,message);}
    private record Installation(String accountToken,long freeGeneration,String allowance,boolean testDevice,String activeResetId){}
    private record Challenge(String nonce,String clientData,Long wallpaperId,String resetId,long generation,Instant expiresAt,Instant consumedAt){}
    private record ClaimRow(long wallpaperId,long generation,String status){}
    private record ResetRow(long expectedGeneration,String status,String appleTransactionId){}
}
