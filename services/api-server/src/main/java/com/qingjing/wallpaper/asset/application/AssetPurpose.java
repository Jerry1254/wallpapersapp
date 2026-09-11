package com.qingjing.wallpaper.asset.application;

import java.util.Set;

public enum AssetPurpose {
    CATEGORY_ICON(10L * 1024 * 1024, Set.of(DetectedAssetType.JPEG, DetectedAssetType.PNG, DetectedAssetType.WEBP)),
    WALLPAPER_COVER(20L * 1024 * 1024, Set.of(DetectedAssetType.JPEG, DetectedAssetType.PNG, DetectedAssetType.WEBP)),
    BACKGROUND(50L * 1024 * 1024, Set.of(DetectedAssetType.JPEG, DetectedAssetType.PNG, DetectedAssetType.WEBP)),
    FOREGROUND(50L * 1024 * 1024, Set.of(DetectedAssetType.PNG, DetectedAssetType.WEBP)),
    PARALLAX_CONFIG(2L * 1024 * 1024, Set.of(DetectedAssetType.JSON)),
    VIDEO(200L * 1024 * 1024, Set.of(DetectedAssetType.MP4, DetectedAssetType.QUICKTIME)),
    LIVE_PHOTO_IMAGE(50L * 1024 * 1024, Set.of(DetectedAssetType.JPEG)),
    LIVE_PHOTO_VIDEO(200L * 1024 * 1024, Set.of(DetectedAssetType.MP4, DetectedAssetType.QUICKTIME)),
    STATIC_IMAGE(50L * 1024 * 1024, Set.of(DetectedAssetType.JPEG, DetectedAssetType.PNG, DetectedAssetType.WEBP)),
    THEME_PACKAGE(250L * 1024 * 1024, Set.of(DetectedAssetType.ZIP));

    private final long maximumBytes;
    private final Set<DetectedAssetType> allowedTypes;

    AssetPurpose(long maximumBytes, Set<DetectedAssetType> allowedTypes) {
        this.maximumBytes = maximumBytes;
        this.allowedTypes = allowedTypes;
    }

    public long maximumBytes() {
        return maximumBytes;
    }

    public boolean allows(DetectedAssetType type) {
        return allowedTypes.contains(type);
    }
}
