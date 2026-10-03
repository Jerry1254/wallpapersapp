package com.qingjing.wallpaper.iosacquisition;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class IosCreditDtos {
    private IosCreditDtos() {}
    public record OrderRequest(@NotBlank String challengeId, @NotBlank String nonce,
            @NotBlank @Pattern(regexp="[1-9][0-9]*") String wallpaperId,
            @NotBlank @Size(max=32768) String signedAppTransaction,
            @NotBlank @Size(max=256) String deviceVerificationId) {}
    public record RestoreRequest(@NotBlank String challengeId, @NotBlank String nonce,
            @NotBlank @Size(max=32768) String signedAppTransaction,
            @NotBlank @Size(max=256) String deviceVerificationId) {}
    public record Order(String orderId, String wallpaperId, String productId, int packCredits,
            int quantity, int credits, String amount, String accountToken, long priceVersion, String status, boolean paymentAllowed) {}
}
