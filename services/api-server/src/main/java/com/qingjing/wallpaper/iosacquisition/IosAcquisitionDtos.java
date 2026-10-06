package com.qingjing.wallpaper.iosacquisition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class IosAcquisitionDtos {
    private IosAcquisitionDtos() {}

    public enum Action { ENROLL, STATUS, FREE_CLAIM, PURCHASE_SYNC, FREE_RESET, CREDIT_ORDER, CREDIT_RESTORE, CREDIT_CANCEL }
    public enum FreeAllowance { AVAILABLE, USED, UNAVAILABLE, PENDING_RESET }

    public record ChallengeRequest(
            @NotBlank @Size(max=256) String keyId,
            @NotNull Action action,
            @Pattern(regexp="[1-9][0-9]*") String wallpaperId,
            String resetId) {}
    public record ChallengeResponse(String challengeId, String nonce, String clientData, Instant expiresAt) {}

    public record AttestationRegistrationRequest(
            @NotBlank @Size(max=256) String keyId,
            @NotBlank String challengeId,
            @NotBlank @Size(max=32768) String attestation) {}
    public record AttestationRegistrationResponse(boolean registered) {}

    public record DeviceProofRequest(
            @NotBlank String challengeId,
            @NotBlank String nonce,
            @NotBlank String deviceToken) {}
    public record FreeClaimRequest(
            @NotBlank String challengeId,
            @NotBlank String nonce,
            @NotBlank String deviceToken,
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String wallpaperId,
            @NotBlank String requestId) {}
    public record PurchaseRequest(
            @NotBlank String challengeId,
            @NotBlank String nonce,
            @NotBlank @Size(max=32768) String signedTransaction,
            @NotBlank @Size(max=32768) String signedAppTransaction,
            @NotBlank @Size(max=256) String deviceVerificationId) {}
    public record FreeResetCompleteRequest(
            @NotBlank String challengeId,
            @NotBlank String nonce,
            @NotBlank String deviceToken,
            @NotNull Long expectedGeneration) {}

    public record Product(String wallpaperId, String productId, String chinaReferencePrice,
            String priceSource, String priceCurrency, String priceSyncStatus, Instant priceSyncedAt,
            String acquisitionMode, Integer credits, Integer packCredits, Integer purchaseQuantity, Boolean firstFreeEligible) {
        public Product(String wallpaperId, String productId, String chinaReferencePrice,
                String priceSource, String priceCurrency, String priceSyncStatus, Instant priceSyncedAt) {
            this(wallpaperId,productId,chinaReferencePrice,priceSource,priceCurrency,priceSyncStatus,priceSyncedAt,
                    "NON_CONSUMABLE",null,null,null,true);
        }
    }
    public record ProductCatalogue(List<Product> items) {}
    public record PendingReset(String resetId, long expectedGeneration, String status, Instant expiresAt) {}
    public record AcquisitionState(
            String installationId,
            String accountToken,
            long freeGeneration,
            FreeAllowance freeAllowance,
            List<String> freeWallpaperIds,
            List<String> purchasedWallpaperIds,
            List<Product> products,
            PendingReset pendingFreeReset,
            Instant checkedAt) {}

    public record AppleNotificationRequest(@NotBlank @Size(max=262144) String signedPayload) {}

    public record IosProductConfiguration(
            String productId,
            String chinaReferencePrice,
            String priceSource,
            String priceCurrency,
            String priceSyncStatus,
            Instant priceSyncedAt,
            String priceSyncError,
            boolean firstFreeEligible,
            boolean enabled,
            boolean productIdLocked,
            Instant verifiedTransactionAt,
            String acquisitionMode, Integer credits, Integer packCredits, Integer purchaseQuantity, Long priceVersion) {
        public IosProductConfiguration(String productId, String chinaReferencePrice, String priceSource,
                String priceCurrency, String priceSyncStatus, Instant priceSyncedAt, String priceSyncError,
                boolean firstFreeEligible, boolean enabled, boolean productIdLocked, Instant verifiedTransactionAt) {
            this(productId,chinaReferencePrice,priceSource,priceCurrency,priceSyncStatus,priceSyncedAt,priceSyncError,
                    firstFreeEligible,enabled,productIdLocked,verifiedTransactionAt,"NON_CONSUMABLE",null,null,null,null);
        }
    }
    public record UpdateIosProductRequest(
            @Size(max=255)
            @Pattern(regexp="[A-Za-z0-9._-]+") String productId,
            @Pattern(regexp="^(?:0|[1-9][0-9]{0,7})\\.[0-9]{2}$")
            @DecimalMin(value="0.00", inclusive=false) String chinaReferencePrice,
            @NotNull Boolean firstFreeEligible,
            @NotNull Boolean enabled,
            @Pattern(regexp="NON_CONSUMABLE|CREDITS") String acquisitionMode,
            @jakarta.validation.constraints.Min(1) @jakarta.validation.constraints.Max(30) Integer credits) {
        public UpdateIosProductRequest(String productId,String chinaReferencePrice,Boolean firstFreeEligible,Boolean enabled) {
            this(productId,chinaReferencePrice,firstFreeEligible,enabled,null,null);
        }
    }

    public record SetTestDeviceRequest(@NotNull Boolean testDevice, @NotBlank @Size(max=300) String reason) {}
    public record CreateResetRequest(@NotNull Long expectedGeneration, @NotBlank @Size(max=300) String reason) {}
    public record ResetOperation(
            String resetId, String deviceId, long expectedGeneration, Long resultGeneration,
            String status, String errorCode, Instant expiresAt, Instant createdAt, Instant completedAt) {}
    public record AdminIosDeviceAcquisition(
            boolean testDevice,
            long freeGeneration,
            String freeAllowance,
            Instant deviceCheckCheckedAt,
            ResetOperation pendingReset) {}
}
