package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class DeliveryDtos {

    private DeliveryDtos() {
    }

    public enum DeliveryMode { H5_PLACEHOLDER, SECURE_PACKAGE }

    public record CreateDownloadTicketRequest(
            @NotNull DevicePlatform platform,
            @Size(max = 32) String osVersion,
            @NotEmpty List<ResourceType> supportedResourceTypes,
            @Positive Integer installedVersionNo) {
    }

    public record DownloadResourceVersion(
            String id,
            int versionNo,
            DeliveryPlatform platform,
            ResourceType resourceType,
            String manifestSha256) {
    }

    public record SecurePackageMetadata(
            long sizeBytes,
            String encryptedSha256,
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
