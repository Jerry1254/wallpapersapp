package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.device.DeviceDtos.CredentialType;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AdminRedemptionDtos {

    private AdminRedemptionDtos() {
    }

    public enum DeliveryStatus { AVAILABLE, CONFIRMED, EXPIRED }

    public enum RedemptionCodeStatus { AVAILABLE, EXHAUSTED }

    public record AdminCodeBatchSummary(
            String id,
            String batchNo,
            String name,
            int generatedCount,
            int quotaPerCodeSnapshot,
            long totalQuota,
            long usedQuota,
            double usagePercent,
            DeliveryStatus deliveryStatus,
            Instant deliveryConfirmedAt,
            Instant createdAt) {
    }

    public record AdminCodeBatchDetail(
            String id,
            String batchNo,
            String name,
            int generatedCount,
            int quotaPerCodeSnapshot,
            long totalQuota,
            long usedQuota,
            double usagePercent,
            DeliveryStatus deliveryStatus,
            Instant deliveryConfirmedAt,
            Instant createdAt,
            int availableCodeCount,
            int exhaustedCodeCount) {
    }

    public record CodeBatchPage(List<AdminCodeBatchSummary> items, PageMetadata page) {
    }

    public record CreateCodeBatchRequest(
            @NotBlank @Size(max = 50) String name,
            @Min(1) @Max(10000) int generatedCount,
            @Min(1) @Max(100) int quotaPerCode) {
    }

    public record CreateCodeBatchResponse(
            AdminCodeBatchDetail batch,
            String deliveryTicket,
            String deliveryUrl,
            Instant deliveryExpiresAt) {
    }

    public record AdminRedemptionCode(
            String id,
            String code,
            String maskedCode,
            String codeSuffix,
            int totalQuota,
            int usedQuota,
            int remainingQuota,
            RedemptionCodeStatus status) {
    }

    public record RedemptionCodePage(List<AdminRedemptionCode> items, PageMetadata page) {
    }

    public enum DeviceStatus { ACTIVE, REVIEW, DISABLED }

    public enum CredentialStatus { ACTIVE, REVOKED }

    public enum EntitlementStatus { ACTIVE, REVOKED }

    public record WallpaperRef(String id, String title, String slug) {
    }

    public record AdminRedemptionSummary(
            String id,
            String redemptionRequestId,
            String idempotencyKey,
            String deviceId,
            String codeId,
            String maskedCode,
            String codeSuffix,
            WallpaperRef wallpaper,
            RedemptionDtos.RedemptionResultCode result,
            int quotaDelta,
            String errorCode,
            Instant createdAt) {
    }

    public record AdminRedemptionPage(List<AdminRedemptionSummary> items, PageMetadata page) {
    }

    public record AdminDeviceSummary(
            String id,
            DevicePlatform platform,
            String appInstallScope,
            DeviceStatus status,
            long entitlementCount,
            Instant lastSeenAt,
            Instant createdAt) {
    }

    public record AdminDeviceCredential(
            String credentialKeyId,
            CredentialType credentialType,
            CredentialStatus status,
            Instant lastUsedAt,
            Instant revokedAt) {
    }

    public record AdminEntitlement(
            String id,
            WallpaperRef wallpaper,
            EntitlementStatus status,
            Instant grantedAt,
            Instant revokedAt) {
    }

    public record AdminDeviceDetail(
            String id,
            DevicePlatform platform,
            String appInstallScope,
            DeviceStatus status,
            long entitlementCount,
            Instant lastSeenAt,
            Instant createdAt,
            List<AdminDeviceCredential> credentials,
            List<AdminEntitlement> entitlements) {
    }

    public record AdminDevicePage(List<AdminDeviceSummary> items, PageMetadata page) {
    }

    public record AdminRedemptionDetail(
            String id,
            String redemptionRequestId,
            String idempotencyKey,
            String deviceId,
            String codeId,
            String maskedCode,
            String codeSuffix,
            WallpaperRef wallpaper,
            RedemptionDtos.RedemptionResultCode result,
            int quotaDelta,
            String errorCode,
            Instant createdAt,
            AdminDeviceSummary device,
            AdminEntitlement entitlement) {
    }
}
