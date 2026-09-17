package com.qingjing.wallpaper.delivery;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

public final class PreviewDtos {
    private PreviewDtos() { }
    public record CreatePreviewTicketRequest(@NotNull DeliveryPlatform deliveryPlatform,@NotNull ResourceType resourceType) { }
    public record PreviewDescriptor(String deliveryMode,String purpose,int durationSeconds,String wallpaperId,String ticket,String downloadUrl,
            Instant expiresAt,DeliveryDtos.DownloadResourceVersion resourceVersion,@JsonProperty("package") DeliveryDtos.SecurePackageMetadata packageMetadata) { }
}
