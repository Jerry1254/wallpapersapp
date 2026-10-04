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
    private final IosProductService products;
    private final IosCreditPurchaseService credits;

    public IosAcquisitionService(
            JdbcTemplate jdbc,
            TransactionTemplate transactions,
            IosAcquisitionProperties properties,
            IosAppleGateway apple,
            SecurityCrypto crypto,
            EntitlementGrantService grants,
            IosProductService products, IosCreditPurchaseService credits) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.properties = properties;
        this.apple = apple;
        this.crypto = crypto;
        this.grants = grants;
        this.products = products;
        this.credits = credits;
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
        if(wallpaperId!=null)new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).requireVisible(wallpaperId,principal.deviceId());
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
            installationForUpdate(principal.deviceId());
            List<Long> owners = jdbc.query("SELECT device_id FROM ios_app_attest_key WHERE key_id=? FOR UPDATE", (rs, n) -> rs.getLong(1), request.keyId());
            if (!owners.isEmpty() && owners.get(0) != principal.deviceId()) throw forbidden("IOS_ATTESTATION_INVALID", "App Attest key belongs to another installation");
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
        Challenge proof = verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.STATUS, null, rawBody);
        DeviceBits bits = apple.queryDeviceBits(request.deviceToken());
        transactions.executeWithoutResult(tx -> jdbc.update(
                """
                UPDATE ios_installation_acquisition
                SET free_allowance_status=?,devicecheck_checked_at=UTC_TIMESTAMP(6),lock_version=lock_version+1
                WHERE device_id=? AND active_reset_id IS NULL AND free_generation=? AND NOT EXISTS (SELECT 1 FROM ios_free_claim c WHERE c.device_id=? AND c.free_generation=ios_installation_acquisition.free_generation AND c.status IN ('PROCESSING','APPLE_WRITE_STARTED','RECONCILING','APPLE_CONFIRMED'))
                """,
                bits.bit0() ? "USED" : "AVAILABLE", principal.deviceId(), proof.generation(), principal.deviceId()));
        return state(principal.deviceId());
    }

    public AcquisitionState claim(
            DevicePrincipal principal, String keyId, String assertion, FreeClaimRequest request, byte[] rawBody) {
        requireEnabled(principal);
        String requestId = uuid(request.requestId(), "requestId").toString();
        long wallpaperId = parseId(request.wallpaperId(), "wallpaperId");
        new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).requireVisible(wallpaperId,principal.deviceId());
        Challenge proof = verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(),
                Action.FREE_CLAIM, wallpaperId, rawBody);
        ClaimRow reserved = transactions.execute(tx -> {
            Installation installation = installationForUpdate(principal.deviceId());
            List<ClaimRow> previous = claim(principal.deviceId(), requestId);
            if (!previous.isEmpty()) {
                ClaimRow row = previous.get(0);
                if (row.wallpaperId() != wallpaperId) throw conflict("IDEMPOTENCY_CONFLICT", "The request targets another wallpaper");
                if (row.generation() != installation.freeGeneration() || row.status().equals("RESET")) {
                    throw conflict("IOS_FREE_CLAIM_RESET", "The claim belongs to a reset generation");
                }
                return row;
            }
            if (proof.generation() != installation.freeGeneration()) throw conflict("IOS_FREE_GENERATION_CONFLICT", "The free generation changed");
            if (installation.activeResetId() != null) throw conflict("IOS_FREE_RESET_PENDING", "A free reset is pending");
            if (hasEntitlement(principal.deviceId(), wallpaperId)) return new ClaimRow(wallpaperId, installation.freeGeneration(), "COMPLETED");
            requireEligibleWallpaper(wallpaperId);
            Long active = jdbc.queryForObject("SELECT COUNT(*) FROM ios_free_claim WHERE device_id=? AND free_generation=? AND active_generation IS NOT NULL", Long.class,
                    principal.deviceId(), installation.freeGeneration());
            if (active != null && active > 0) throw conflict("IOS_FREE_CLAIM_PENDING", "Continue the original free claim");
            if (!installation.allowance().equals("AVAILABLE")) throw conflict("IOS_FREE_ALLOWANCE_USED", "The free allowance is unavailable");
            jdbc.update("""
                    INSERT INTO ios_free_claim (request_id,device_id,free_generation,wallpaper_id,status,apple_transaction_id)
                    VALUES (?,?,?,?,'PROCESSING',?)
                    """, requestId, principal.deviceId(), installation.freeGeneration(), wallpaperId, UUID.randomUUID().toString());
            return new ClaimRow(wallpaperId, installation.freeGeneration(), "PROCESSING");
        });
        if (reserved.status().equals("COMPLETED")) return state(principal.deviceId());
        if (reserved.status().equals("APPLE_CONFIRMED")) {
            finishClaim(principal.deviceId(), requestId);
            return state(principal.deviceId());
        }
        if (!reserved.status().equals("PROCESSING")) throw conflict("IOS_FREE_CLAIM_PENDING", "The original claim requires reconciliation");
        DeviceBits bits = apple.queryDeviceBits(request.deviceToken());
        boolean started = transactions.execute(tx -> {
            Installation installation = installationForUpdate(principal.deviceId());
            if (installation.freeGeneration() != reserved.generation() || installation.activeResetId() != null) throw conflict("IOS_FREE_GENERATION_CONFLICT", "The free generation changed");
            if (bits.bit0()) {
                jdbc.update("UPDATE ios_free_claim SET status='REJECTED',error_code='IOS_FREE_ALLOWANCE_USED',completed_at=UTC_TIMESTAMP(6) WHERE device_id=? AND request_id=? AND status='PROCESSING'", principal.deviceId(), requestId);
                jdbc.update("UPDATE ios_installation_acquisition SET free_allowance_status='USED' WHERE device_id=?", principal.deviceId());
                return false;
            }
            int updated = jdbc.update("UPDATE ios_free_claim SET status='APPLE_WRITE_STARTED' WHERE device_id=? AND request_id=? AND status='PROCESSING'", principal.deviceId(), requestId);
            if (updated != 1) throw conflict("IOS_FREE_CLAIM_PENDING", "The original claim is already processing");
            audit(principal.deviceId(), requestId, "FREE_WRITE_STARTED", null);
            return true;
        });
        if (!started) throw conflict("IOS_FREE_ALLOWANCE_USED", "The physical device already used its free allowance");
        String appleId = jdbc.queryForObject("SELECT apple_transaction_id FROM ios_free_claim WHERE device_id=? AND request_id=?", String.class, principal.deviceId(), requestId);
        try { apple.markFirstFreeUsed(request.deviceToken(), appleId); }
        catch (RuntimeException failure) {
            jdbc.update("UPDATE ios_free_claim SET status='RECONCILING',error_code='IOS_FREE_CLAIM_PENDING' WHERE device_id=? AND request_id=? AND status='APPLE_WRITE_STARTED'", principal.deviceId(), requestId);
            throw conflict("IOS_FREE_CLAIM_PENDING", "Apple write outcome is uncertain; do not start another claim");
        }
        jdbc.update("UPDATE ios_free_claim SET status='APPLE_CONFIRMED' WHERE device_id=? AND request_id=? AND status='APPLE_WRITE_STARTED'", principal.deviceId(), requestId);
        finishClaim(principal.deviceId(), requestId);
        return state(principal.deviceId());
    }

    void finishClaim(long deviceId, String requestId) {
        transactions.executeWithoutResult(tx -> {
            Installation installation = installationForUpdate(deviceId);
            List<ClaimRow> rows = claim(deviceId, requestId);
            if (rows.isEmpty() || !rows.get(0).status().equals("APPLE_CONFIRMED")) return;
            ClaimRow row = rows.get(0);
            if (row.generation() != installation.freeGeneration() || installation.activeResetId() != null) throw conflict("IOS_FREE_GENERATION_CONFLICT", "The free generation changed");
            long entitlement = grants.grant(deviceId, row.wallpaperId(), SourceType.IOS_FIRST_FREE, requestId, row.generation(), null);
            jdbc.update("UPDATE ios_free_claim SET status='COMPLETED',entitlement_id=?,device_token_encrypted=NULL,completed_at=UTC_TIMESTAMP(6),error_code=NULL WHERE device_id=? AND request_id=?", entitlement, deviceId, requestId);
            jdbc.update("UPDATE ios_installation_acquisition SET free_allowance_status='USED',devicecheck_checked_at=UTC_TIMESTAMP(6),lock_version=lock_version+1 WHERE device_id=?", deviceId);
            audit(deviceId, requestId, "FREE_GRANTED", null);
        });
    }

    public AcquisitionState purchase(
            DevicePrincipal principal, String keyId, String assertion, PurchaseRequest request, byte[] rawBody) {
        requireEnabled(principal);
        verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(), Action.PURCHASE_SYNC, null, rawBody);
        VerifiedTransaction verified = apple.verifyTransaction(request.signedTransaction(), request.signedAppTransaction(), request.deviceVerificationId());
        requireTrustedTransaction(verified);
        if("CONSUMABLE".equals(verified.productType())) {
            credits.purchase(principal.deviceId(),verified);
            return state(principal.deviceId());
        }
        // A new purchase must carry our installation token. Older device-verified transactions can restore across installations.
        Installation installation = ensureInstallation(principal.deviceId());
        Instant installedAt = jdbc.queryForObject("SELECT created_at FROM ios_installation_acquisition WHERE device_id=?", Timestamp.class, principal.deviceId()).toInstant();
        if (verified.purchasedAt().isAfter(installedAt) && !installation.accountToken().equals(verified.appAccountToken())) {
            throw forbidden("IOS_PURCHASE_INVALID", "A new purchase must use the issued appAccountToken");
        }
        long wallpaperId = mappedWallpaper(verified.productId());
        new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).requireVisible(wallpaperId,principal.deviceId());
        transactions.executeWithoutResult(tx -> {
            installationForUpdate(principal.deviceId());
            persistTransaction(verified, wallpaperId);
            jdbc.update("UPDATE ios_product_mapping SET verified_transaction_at=COALESCE(verified_transaction_at,UTC_TIMESTAMP(6)) WHERE bundle_id=? AND product_id=?", properties.getBundleId(), verified.productId());
            String source = purchaseSource(verified, principal.deviceId());
            jdbc.update("""
                    INSERT IGNORE INTO ios_purchase_installation (environment,bundle_id,original_transaction_id,device_id,source_reference)
                    VALUES (?,?,?,?,?)
                    """, verified.environment(), verified.bundleId(), verified.originalTransactionId(), principal.deviceId(), source);
            Timestamp revoked = jdbc.queryForObject("SELECT revoked_at FROM ios_store_transaction WHERE environment=? AND bundle_id=? AND transaction_id=?", Timestamp.class,
                    verified.environment(), verified.bundleId(), verified.transactionId());
            if (revoked == null) {
                grants.grant(principal.deviceId(), wallpaperId, SourceType.IOS_IAP, source, null, null);
                jdbc.update("UPDATE entitlement_grant SET apple_environment=? WHERE source_type='IOS_IAP' AND source_reference=?", verified.environment(), source);
            } else revokePurchaseSources(verified);
            audit(principal.deviceId(), source, "PURCHASE_SYNC", verified.environment());
        });
        return state(principal.deviceId());
    }

    public Object completeReset(
            DevicePrincipal principal, String resetId, String keyId, String assertion,
            FreeResetCompleteRequest request, byte[] rawBody) {
        requireEnabled(principal);
        Challenge proof = verifyProof(principal.deviceId(), keyId, assertion, request.challengeId(), request.nonce(), Action.FREE_RESET, null, rawBody);
        if (!resetId.equals(proof.resetId())) throw forbidden("IOS_ATTESTATION_INVALID", "The challenge targets another reset");
        ResetRow row = transactions.execute(tx -> {
            Installation installation = installationForUpdate(principal.deviceId());
            ResetRow reset = resetForUpdate(principal.deviceId(), resetId);
            if (reset.status().equals("COMPLETED")) return reset;
            if (!installation.testDevice()) throw forbidden("IOS_TEST_DEVICE_REQUIRED", "The device is not marked for testing");
            if (reset.expectedGeneration() != request.expectedGeneration() || installation.freeGeneration() != request.expectedGeneration()) throw conflict("IOS_FREE_GENERATION_CONFLICT", "The reset generation changed");
            if (reset.status().equals("APPLE_RESET_CONFIRMED")) return reset;
            if (!reset.status().equals("WAITING_DEVICE")) throw conflict("IOS_FREE_RESET_PENDING", "The original reset requires reconciliation");
            if (!reset.expiresAt().isAfter(Instant.now())) throw conflict("IOS_FREE_RESET_NOT_FOUND", "The waiting reset expired");
            String appleId = UUID.randomUUID().toString();
            if (jdbc.update("UPDATE ios_free_reset SET status='PROCESSING',started_at=UTC_TIMESTAMP(6),apple_transaction_id=? WHERE id=? AND status='WAITING_DEVICE'", appleId, resetId) != 1) throw conflict("IOS_FREE_RESET_PENDING", "Reset already started");
            audit(principal.deviceId(), resetId, "RESET_WRITE_STARTED", null);
            return new ResetRow(reset.expectedGeneration(), "PROCESSING", appleId, reset.expiresAt());
        });
        if (row.status().equals("COMPLETED")) return state(principal.deviceId());
        if (row.status().equals("PROCESSING")) {
            try { apple.resetFirstFreeBit(request.deviceToken(), row.appleTransactionId()); }
            catch (RuntimeException failure) {
                jdbc.update("UPDATE ios_free_reset SET error_code='IOS_DEVICE_PROOF_UNAVAILABLE' WHERE id=? AND status='PROCESSING'", resetId);
                throw conflict("IOS_FREE_RESET_PENDING", "Apple reset outcome is uncertain; keep the original reset");
            }
            jdbc.update("UPDATE ios_free_reset SET status='APPLE_RESET_CONFIRMED',error_code=NULL WHERE id=? AND status='PROCESSING'", resetId);
        }
        finishReset(principal.deviceId(), resetId);
        return state(principal.deviceId());
    }

    public IosCreditDtos.Order creditOrder(DevicePrincipal principal,String keyId,String assertion,IosCreditDtos.OrderRequest request,byte[] rawBody) {
        requireEnabled(principal);
        long wallpaper=parseId(request.wallpaperId(),"wallpaperId");
        new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).requireVisible(wallpaper,principal.deviceId());
        verifyProof(principal.deviceId(),keyId,assertion,request.challengeId(),request.nonce(),Action.CREDIT_ORDER,wallpaper,rawBody);
        return credits.create(principal.deviceId(),wallpaper,request.signedAppTransaction(),request.deviceVerificationId());
    }

    public AcquisitionState cancelCreditOrder(DevicePrincipal principal,String keyId,String assertion,String orderId,IosCreditDtos.RestoreRequest request,byte[] rawBody) {
        requireEnabled(principal);
        verifyProof(principal.deviceId(),keyId,assertion,request.challengeId(),request.nonce(),Action.CREDIT_CANCEL,null,rawBody);
        credits.cancel(orderId,request.signedAppTransaction(),request.deviceVerificationId());
        return state(principal.deviceId());
    }

    public AcquisitionState restoreCredits(DevicePrincipal principal,String keyId,String assertion,IosCreditDtos.RestoreRequest request,byte[] rawBody) {
        requireEnabled(principal);
        verifyProof(principal.deviceId(),keyId,assertion,request.challengeId(),request.nonce(),Action.CREDIT_RESTORE,null,rawBody);
        credits.restore(principal.deviceId(),request.signedAppTransaction(),request.deviceVerificationId());
        return state(principal.deviceId());
    }

    void expireReset(long deviceId, String resetId) {
        transactions.executeWithoutResult(tx -> {
            installationForUpdate(deviceId);
            int expired = jdbc.update("UPDATE ios_free_reset SET status='EXPIRED' WHERE id=? AND device_id=? AND status='WAITING_DEVICE' AND expires_at<UTC_TIMESTAMP(6)", resetId,deviceId);
            if (expired == 1) {
                jdbc.update("UPDATE ios_installation_acquisition SET active_reset_id=NULL,free_allowance_status='UNAVAILABLE',lock_version=lock_version+1 WHERE device_id=? AND active_reset_id=?", deviceId,resetId);
                audit(deviceId,resetId,"RESET_EXPIRED",null);
            }
        });
    }

    void finishReset(long deviceId, String resetId) {
        transactions.executeWithoutResult(tx -> {
            Installation installation = installationForUpdate(deviceId);
            ResetRow reset = resetForUpdate(deviceId, resetId);
            if (!reset.status().equals("APPLE_RESET_CONFIRMED")) return;
            if (installation.freeGeneration() != reset.expectedGeneration()) throw conflict("IOS_FREE_GENERATION_CONFLICT", "The reset generation changed");
            grants.revokeFirstFreeGeneration(deviceId, reset.expectedGeneration(), "iOS test free allowance reset");
            jdbc.update("UPDATE ios_free_claim SET status='RESET',error_code='IOS_FREE_CLAIM_RESET',device_token_encrypted=NULL WHERE device_id=? AND free_generation=? AND status='COMPLETED'", deviceId, reset.expectedGeneration());
            jdbc.update("UPDATE ios_installation_acquisition SET free_generation=free_generation+1,free_allowance_status='AVAILABLE',active_reset_id=NULL,devicecheck_checked_at=UTC_TIMESTAMP(6),lock_version=lock_version+1 WHERE device_id=?", deviceId);
            jdbc.update("UPDATE ios_free_reset SET status='COMPLETED',result_generation=expected_generation+1,device_token_encrypted=NULL,completed_at=UTC_TIMESTAMP(6),error_code=NULL WHERE id=?", resetId);
            audit(deviceId, resetId, "RESET_COMPLETED", null);
        });
    }

    public void notification(String signedPayload) {
        requireFeatureConfigured();
        VerifiedNotification value = apple.verifyNotification(signedPayload);
        jdbc.update("""
                INSERT INTO apple_notification_inbox (id,notification_uuid,environment,notification_type,subtype,signed_payload,status)
                VALUES (?,?,?,?,?,?,'VERIFIED') ON DUPLICATE KEY UPDATE notification_uuid=notification_uuid
                """, UUID.randomUUID().toString(), value.notificationUuid(), value.environment(), value.notificationType(), value.subtype(), signedPayload);
    }

    void processNotification(String id, String payload) {
        VerifiedNotification notification = apple.verifyNotification(payload);
        VerifiedTransaction hint = notification.transaction();
        VerifiedTransaction current = hint == null ? null : apple.latestTransaction(hint.environment(), hint.transactionId());
        transactions.executeWithoutResult(tx -> {
            List<String> status = jdbc.query("SELECT status FROM apple_notification_inbox WHERE id=? FOR UPDATE", (rs, n) -> rs.getString(1), id);
            if (status.isEmpty() || status.get(0).equals("PROCESSED")) return;
            if (current != null) {
                requireTrustedTransaction(current);
                if("CONSUMABLE".equals(current.productType()))credits.notification(current);
                else {
                long wallpaper = mappedWallpaper(current.productId());
                persistTransaction(current, wallpaper);
                Timestamp revoked = jdbc.queryForObject("SELECT revoked_at FROM ios_store_transaction WHERE environment=? AND bundle_id=? AND transaction_id=?", Timestamp.class,
                        current.environment(), current.bundleId(), current.transactionId());
                if (revoked != null) revokePurchaseSources(current);
                }
            }
            jdbc.update("UPDATE apple_notification_inbox SET status='PROCESSED',processed_at=UTC_TIMESTAMP(6),attempts=attempts+1,error_code=NULL WHERE id=?", id);
            audit(null, notification.notificationUuid(), "NOTIFICATION_PROCESSED", notification.environment());
        });
    }

    private void persistTransaction(VerifiedTransaction value, long wallpaper) {
        jdbc.update("""
                INSERT INTO ios_store_transaction
                    (environment,bundle_id,product_id,transaction_id,original_transaction_id,app_account_token,
                     device_verification_id,wallpaper_id,purchased_at,revoked_at,verified_at,signed_at,app_transaction_id)
                VALUES (?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6),?,?)
                ON DUPLICATE KEY UPDATE revoked_at=IF(signed_at IS NULL OR VALUES(signed_at)>=signed_at,VALUES(revoked_at),revoked_at),
                    signed_at=GREATEST(COALESCE(signed_at,VALUES(signed_at)),VALUES(signed_at)),verified_at=UTC_TIMESTAMP(6)
                """, value.environment(), value.bundleId(), value.productId(), value.transactionId(), value.originalTransactionId(),
                value.appAccountToken(), value.deviceVerificationId(), wallpaper, Timestamp.from(value.purchasedAt()), timestamp(value.revokedAt()), timestamp(value.signedAt()), value.appTransactionId());
    }

    private void revokePurchaseSources(VerifiedTransaction value) {
        List<String> sources = jdbc.query("SELECT source_reference FROM ios_purchase_installation WHERE environment=? AND bundle_id=? AND original_transaction_id=? ORDER BY device_id", (rs, n) -> rs.getString(1),
                value.environment(), value.bundleId(), value.originalTransactionId());
        sources.forEach(source -> grants.revoke(SourceType.IOS_IAP, source, "Apple transaction was revoked"));
    }

    private String purchaseSource(VerifiedTransaction value, long device) {
        try {
            return java.util.HexFormat.of().formatHex(AppleCrypto.sha256((value.environment()+"\n"+value.bundleId()+"\n"+value.originalTransactionId()+"\n"+device).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException("Cannot create purchase source"); }
    }

    private void audit(Long deviceId, String operation, String action, String environment) {
        jdbc.update("INSERT INTO ios_acquisition_audit (device_id,operation_id,action,environment) VALUES (?,?,?,?)", deviceId, operation, action, environment);
    }

    private boolean hasEntitlement(long device, long wallpaper) {
        return Long.valueOf(1).equals(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement WHERE device_id=? AND wallpaper_id=? AND status='ACTIVE'", Long.class, device, wallpaper));
    }

    public AcquisitionState state(long deviceId) {
        Installation installation = ensureInstallation(deviceId);
        List<String> free = grantWallpapers(deviceId, "IOS_FIRST_FREE");
        List<String> purchased = grantWallpapers(deviceId, "IOS_IAP");
        PendingReset pending = pendingReset(deviceId);
        return new AcquisitionState(
                publicDeviceId(deviceId), installation.accountToken(), installation.freeGeneration(),
                pending == null ? FreeAllowance.valueOf(installation.allowance()) : FreeAllowance.PENDING_RESET,
                free, purchased, products.catalogue(), pending, Instant.now());
    }

    public ProductCatalogue productCatalogue(DevicePrincipal principal) {
        if (principal.platform() != DevicePlatform.IOS) {
            throw forbidden("IOS_DEVICE_REQUIRED", "An iOS device session is required");
        }
        // Product metadata needs the device session, not a DeviceCheck quota
        // query, App Attest challenge, Apple secret, or purchase restoration.
        return new ProductCatalogue(products.catalogue());
    }

    private Challenge verifyProof(long deviceId, String keyId, String assertion, String challengeId,
            String nonce, Action action, Long wallpaperId, byte[] rawBody) {
        return transactions.execute(tx -> {
            // Serialize per installation. Counters and challenge consumption commit atomically.
            installationForUpdate(deviceId);
            Challenge challenge = challengeForUpdate(deviceId, challengeId, keyId, action, wallpaperId);
            if (!challenge.nonce().equals(nonce)) throw forbidden("IOS_ATTESTATION_INVALID", "The challenge nonce is invalid");
            List<AttestKey> keys = jdbc.query("SELECT public_key_pem,assertion_counter,environment FROM ios_app_attest_key WHERE device_id=? AND key_id=? AND status='ACTIVE' FOR UPDATE",
                    (rs,row)->new AttestKey(rs.getString(1),rs.getLong(2),rs.getString(3)), deviceId,keyId);
            if (keys.isEmpty() || !properties.getAppAttestEnvironment().equals(keys.get(0).environment())) throw forbidden("IOS_ATTESTATION_INVALID", "The App Attest key is not registered for this environment");
            AttestKey key = keys.get(0);
            long next = apple.verifyAssertion(keyId, assertion, key.pem(), rawBody, key.counter());
            if (next <= key.counter()) throw forbidden("IOS_ASSERTION_REPLAY", "The App Attest assertion was replayed");
            if (jdbc.update("UPDATE ios_app_attest_key SET assertion_counter=? WHERE device_id=? AND key_id=? AND assertion_counter=?", next,deviceId,keyId,key.counter()) != 1) throw forbidden("IOS_ASSERTION_REPLAY", "Counter changed");
            consumeChallenge(deviceId,challengeId);
            return challenge;
        });
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
            SELECT COUNT(*) FROM wallpaper w
            JOIN wallpaper_variant v ON v.wallpaper_id=w.id AND v.enabled=TRUE AND v.platform IN ('IOS','UNIVERSAL')
            JOIN resource_version rv ON rv.variant_id=v.id AND rv.status='PUBLISHED'
            WHERE w.id=? AND w.status='PUBLISHED' AND w.access_type='REDEEM' AND w.offline_promotion_only=FALSE AND (
                EXISTS (SELECT 1 FROM ios_wallpaper_credit_price c WHERE c.wallpaper_id=w.id AND c.bundle_id=? AND c.enabled=TRUE AND c.first_free_eligible=TRUE)
                OR (NOT EXISTS (SELECT 1 FROM ios_wallpaper_credit_price c WHERE c.wallpaper_id=w.id AND c.bundle_id=?)
                    AND EXISTS (SELECT 1 FROM ios_product_mapping m WHERE m.wallpaper_id=w.id AND m.bundle_id=? AND m.enabled=TRUE AND m.first_free_eligible=TRUE)))
            """,Long.class,wallpaperId,properties.getBundleId(),properties.getBundleId(),properties.getBundleId());if(count==null||count==0)throw conflict("IOS_FREE_WALLPAPER_INELIGIBLE","The wallpaper is not eligible for first free");}
    private long mappedWallpaper(String productId){List<Long> rows=jdbc.query("SELECT wallpaper_id FROM ios_product_mapping WHERE bundle_id=? AND product_id=?",(rs,row)->rs.getLong(1),properties.getBundleId(),productId);if(rows.isEmpty())throw forbidden("IOS_PURCHASE_INVALID","The Apple product is not mapped");return rows.get(0);}
    private void requireTrustedTransaction(VerifiedTransaction value){if(!properties.getBundleId().equals(value.bundleId())||!properties.storeEnvironments().contains(value.environment())||"XCODE".equals(value.environment()))throw forbidden("IOS_PURCHASE_ENVIRONMENT_INVALID","The Apple transaction environment is invalid");}
    private List<String> grantWallpapers(long deviceId,String type){return jdbc.query("SELECT CAST(de.wallpaper_id AS CHAR) FROM entitlement_grant eg JOIN device_entitlement de ON de.id=eg.entitlement_id JOIN wallpaper w ON w.id=de.wallpaper_id AND w.offline_promotion_only=FALSE WHERE de.device_id=? AND eg.source_type=? AND eg.status='ACTIVE' ORDER BY de.wallpaper_id",(rs,row)->rs.getString(1),deviceId,type);}
    private String publicDeviceId(long id){return jdbc.queryForObject("SELECT public_id FROM anonymous_device WHERE id=?",String.class,id);}
    private PendingReset pendingReset(long deviceId){List<PendingReset> rows=jdbc.query("SELECT id,expected_generation,status,expires_at FROM ios_free_reset WHERE device_id=? AND status IN ('WAITING_DEVICE','PROCESSING','APPLE_RESET_CONFIRMED','RETRYABLE_FAILURE') ORDER BY created_at DESC LIMIT 1",(rs,row)->new PendingReset(rs.getString(1),rs.getLong(2),rs.getString(3),rs.getTimestamp(4).toInstant()),deviceId);return rows.isEmpty()?null:rows.get(0);}
    private void requirePendingReset(long deviceId,String resetId,long generation){ResetRow row=resetForUpdate(deviceId,resetId);if((!row.status().equals("COMPLETED") && row.expectedGeneration()!=generation)||!List.of("WAITING_DEVICE","APPLE_RESET_CONFIRMED","COMPLETED").contains(row.status()))throw conflict("IOS_FREE_RESET_PENDING","The reset cannot accept a challenge");}
    private ResetRow resetForUpdate(long deviceId,String resetId){List<ResetRow> rows=jdbc.query("SELECT expected_generation,status,apple_transaction_id,expires_at FROM ios_free_reset WHERE id=? AND device_id=?",(rs,row)->new ResetRow(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getTimestamp(4).toInstant()),resetId,deviceId);if(rows.isEmpty())throw new ApiException(HttpStatus.NOT_FOUND,"IOS_FREE_RESET_NOT_FOUND","The reset was not found");return rows.get(0);}
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
    private record ResetRow(long expectedGeneration,String status,String appleTransactionId,Instant expiresAt){}
    private record AttestKey(String pem,long counter,String environment){}
}
