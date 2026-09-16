package com.qingjing.wallpaper.catalog;

import java.time.Instant;
import java.util.List;

public final class PublicCatalogDtos {

    private PublicCatalogDtos() {
    }

    public enum WallpaperKind { PARALLAX_4D, DYNAMIC, STATIC }

    public enum DeliveryPlatform { ANDROID, IOS, HARMONYOS, UNIVERSAL }

    public enum ResourceType { LAYER_PARALLAX, VIDEO, LIVE_PHOTO, STATIC_IMAGE, THEME_PACKAGE }

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
            WallpaperAccessType accessType,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            PublicMedia cover,
            boolean featured,
            int sortOrder,
            List<DeliveryCapability> capabilities) {
    }

    public record PublicWallpaperDetail(
            String id,
            String title,
            String slug,
            WallpaperKind kind,
            WallpaperAccessType accessType,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            PublicMedia cover,
            boolean featured,
            int sortOrder,
            List<DeliveryCapability> capabilities,
            String copyrightNote,
            Instant publishedAt) {

        public static PublicWallpaperDetail from(
                PublicWallpaperSummary summary,
                String copyrightNote,
                Instant publishedAt) {
            return new PublicWallpaperDetail(
                    summary.id(),
                    summary.title(),
                    summary.slug(),
                    summary.kind(),
                    summary.accessType(),
                    summary.rootCategory(),
                    summary.childCategory(),
                    summary.cover(),
                    summary.featured(),
                    summary.sortOrder(),
                    summary.capabilities(),
                    copyrightNote,
                    publishedAt);
        }
    }

    public record PageMetadata(int page, int pageSize, long totalItems, int totalPages) {
    }

    public record PublicWallpaperPage(List<PublicWallpaperSummary> items, PageMetadata page) {
    }
}
