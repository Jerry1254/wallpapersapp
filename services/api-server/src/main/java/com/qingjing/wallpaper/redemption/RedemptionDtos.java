package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementSummary;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class RedemptionDtos {

    private RedemptionDtos() {
    }

    public enum RedemptionResultCode {
        GRANTED, ALREADY_OWNED, CODE_NOT_FOUND, CODE_EXHAUSTED, WALLPAPER_UNAVAILABLE, WALLPAPER_FREE, FAILED
    }

    public record RedeemWallpaperRequest(
            @NotBlank String wallpaperId,
            @NotBlank @Size(min = 20, max = 32) String code) {
    }

    public record RedemptionResult(
            String idempotencyKey,
            RedemptionResultCode result,
            int quotaDelta,
            EntitlementSummary entitlement,
            String errorCode,
            Instant createdAt) {
    }

    public record ProcessingResult(String status, String idempotencyKey) {
    }

    public record RedemptionAttempt(RedemptionResult result, boolean created, boolean rejected) {
    }
}
