package com.qingjing.wallpaper.catalog;

import java.util.List;

public final class PublicCatalogDtos {

    private PublicCatalogDtos() {
    }

    public enum WallpaperKind { PARALLAX_4D, DYNAMIC, STATIC }

    public enum DeliveryPlatform { ANDROID, IOS, HARMONYOS, UNIVERSAL }

    public enum ResourceType { LAYER_PARALLAX, VIDEO, LIVE_PHOTO, STATIC_IMAGE, THEME_PACKAGE }

    public record CategorySummary(String id, String name, String slug) {
    }

    public record PublicMedia(
            String assetId,
            String contentUrl,
            String mimeType,
            Integer widthPx,
            Integer heightPx) {
    }

    public record DeliveryCapability(
            DeliveryPlatform platform,
            ResourceType resourceType,
            String minimumOsVersion,
            List<String> capabilityRequirements) {
    }

    public record PublicWallpaperSummary(
            String id,
            String title,
            String slug,
            WallpaperKind kind,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            PublicMedia cover,
            boolean featured,
            int sortOrder,
            List<DeliveryCapability> capabilities) {
    }

    public record PageMetadata(int page, int pageSize, long totalItems, int totalPages) {
    }
}
