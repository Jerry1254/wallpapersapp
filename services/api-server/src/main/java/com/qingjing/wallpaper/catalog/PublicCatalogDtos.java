package com.qingjing.wallpaper.catalog;

import java.time.Instant;
import java.util.List;

public final class PublicCatalogDtos {

    private PublicCatalogDtos() {
    }

    public enum DeliveryPlatform { ANDROID, IOS, HARMONYOS, UNIVERSAL }

    public enum ResourceType { LAYER_PARALLAX, VIDEO, LIVE_PHOTO, STATIC_IMAGE, MOVING_PHOTO }

    public enum Placement { HOME, LOCK }

    public record CategorySummary(String id, String name, String slug) {
    }

    public record PublicChildCategory(
            String id,
            String name,
            String slug,
            int sortOrder,
            long wallpaperCount) {
    }

    public record PublicRootCategory(
            String id,
            String name,
            String slug,
            PublicMedia icon,
            int sortOrder,
            long wallpaperCount,
            List<PublicChildCategory> children) {
    }

    public record PublicCategoryList(List<PublicRootCategory> items) {
    }

    public record PublicMedia(
            String assetId,
            String contentUrl,
            String mimeType,
            Integer widthPx,
            Integer heightPx) {
    }

    public record DeliveryCapability(
            DeliveryPlatform deliveryPlatform,
            ResourceType resourceType,
            List<Placement> placements) {
    }

    public record PublicWallpaperSummary(
            String id,
            String title,
            String slug,
            WallpaperAccessType accessType,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            PublicMedia cover,
            boolean featured,
            int sortOrder,
            List<DeliveryCapability> availableCapabilities,
            long previewRevision, String previewGenerationStatus) {
    }

    public record PublicWallpaperDetail(
            String id,
            String title,
            String slug,
            WallpaperAccessType accessType,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            PublicMedia cover,
            boolean featured,
            int sortOrder,
            List<DeliveryCapability> availableCapabilities,
            String copyrightNote,
            Instant publishedAt,
            long previewRevision, String previewGenerationStatus) {

        public static PublicWallpaperDetail from(
                PublicWallpaperSummary summary,
                String copyrightNote,
                Instant publishedAt) {
            return new PublicWallpaperDetail(
                    summary.id(),
                    summary.title(),
                    summary.slug(),
                    summary.accessType(),
                    summary.rootCategory(),
                    summary.childCategory(),
                    summary.cover(),
                    summary.featured(),
                    summary.sortOrder(),
                    summary.availableCapabilities(),
                    copyrightNote,
                    publishedAt,summary.previewRevision(),summary.previewGenerationStatus());
        }
    }

    public record PageMetadata(int page, int pageSize, long totalItems, int totalPages) {
    }

    public record PublicWallpaperPage(List<PublicWallpaperSummary> items, PageMetadata page) {
    }
}
