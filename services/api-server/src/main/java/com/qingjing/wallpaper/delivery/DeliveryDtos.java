package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;

public final class DeliveryDtos {

    private DeliveryDtos() {
    }

    public enum DeliveryMode { H5_PLACEHOLDER, SECURE_PACKAGE }

    public record CreateDownloadTicketRequest(
            @NotNull DeliveryPlatform deliveryPlatform,
            @NotNull ResourceType resourceType,
            @Positive Integer installedVersionNo) {
    }

    public record DownloadResourceVersion(
            String id,
            String variantId,
            int versionNo,
            DeliveryPlatform platform,
            ResourceType resourceType,
            String manifestSha256) {
    }

    public record SecurePackageMetadata(
            int formatVersion,
            long sizeBytes,
            long plaintextSizeBytes,
            String encryptedSha256,
            String plaintextSha256,
            String signingKeyId,
            String encryptionKeySha256,
            String wrappedContentKey,
            String keyAlgorithm) {
    }

    public record DownloadDescriptor(
            DeliveryMode deliveryMode,
            String wallpaperId,
            PublicMedia cover,
            String ticket,
            String downloadUrl,
            Instant expiresAt,
            DownloadResourceVersion resourceVersion,
            @JsonProperty("package")
            SecurePackageMetadata packageMetadata) {
    }
}
