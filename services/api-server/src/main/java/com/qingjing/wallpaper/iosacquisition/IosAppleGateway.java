package com.qingjing.wallpaper.iosacquisition;

import java.time.Instant;

/** Trust boundary for Apple App Attest, DeviceCheck, StoreKit and notification verification. */
public interface IosAppleGateway {
    AttestedKey verifyAttestation(String keyId, String attestation, String clientData, String nonce);
    long verifyAssertion(String keyId, String assertion, String publicKeyPem, byte[] rawBody, long previousCounter);
    DeviceBits queryDeviceBits(String deviceToken);
    void markFirstFreeUsed(String deviceToken, String transactionId);
    void resetFirstFreeBit(String deviceToken, String transactionId);
    VerifiedTransaction verifyTransaction(
            String signedTransaction, String signedAppTransaction, String deviceVerificationId);
    VerifiedTransaction latestTransaction(String environment, String transactionId);
    default VerifiedAppIdentity verifyAppTransaction(String signedAppTransaction,String deviceVerificationId) {
        throw new com.qingjing.wallpaper.shared.web.ApiException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "IOS_ACQUISITION_UNAVAILABLE","Apple account verification is not configured");
    }
    VerifiedNotification verifyNotification(String signedPayload);

    record AttestedKey(String publicKeyPem, byte[] receipt, String environment, long initialCounter) {}
    record DeviceBits(boolean bit0, boolean bit1, Instant checkedAt) {}
    record VerifiedTransaction(
            String environment, String bundleId, String productId, String transactionId,
            String originalTransactionId, String appAccountToken, String deviceVerificationId,
            Instant purchasedAt, Instant revokedAt, Instant signedAt, String appTransactionId,
            String productType, int quantity, String currency, Long priceMilliunits, String storefront) {
        public VerifiedTransaction(String environment,String bundleId,String productId,String transactionId,String originalTransactionId,
                String appAccountToken,String deviceVerificationId,Instant purchasedAt,Instant revokedAt,Instant signedAt,String appTransactionId) {
            this(environment,bundleId,productId,transactionId,originalTransactionId,appAccountToken,deviceVerificationId,purchasedAt,
                    revokedAt,signedAt,appTransactionId,"NON_CONSUMABLE",1,null,null,null);
        }
    }
    record VerifiedAppIdentity(String environment,String bundleId,String appTransactionId) {}
    record VerifiedNotification(
            String notificationUuid, String environment, String notificationType, String subtype,
            VerifiedTransaction transaction) {}
}
