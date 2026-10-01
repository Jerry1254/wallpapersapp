package com.qingjing.wallpaper.iosacquisition;

import java.time.Instant;

/** Trust boundary for Apple App Attest, DeviceCheck, StoreKit and notification verification. */
public interface IosAppleGateway {
    AttestedKey verifyAttestation(String keyId, String attestation, String clientData, String nonce);
    long verifyAssertion(String keyId, String assertion, byte[] rawBody, long previousCounter);
    DeviceBits queryDeviceBits(String deviceToken);
    void markFirstFreeUsed(String deviceToken, String transactionId);
    void resetFirstFreeBit(String deviceToken, String transactionId);
    VerifiedTransaction verifyTransaction(
            String signedTransaction, String signedAppTransaction, String deviceVerificationId);
    VerifiedNotification verifyNotification(String signedPayload);

    record AttestedKey(String publicKeyPem, byte[] receipt, String environment, long initialCounter) {}
    record DeviceBits(boolean bit0, boolean bit1, Instant checkedAt) {}
    record VerifiedTransaction(
            String environment, String bundleId, String productId, String transactionId,
            String originalTransactionId, String appAccountToken, String deviceVerificationId,
            Instant purchasedAt, Instant revokedAt) {}
    record VerifiedNotification(
            String notificationUuid, String environment, String notificationType, String subtype,
            VerifiedTransaction transaction) {}
}
