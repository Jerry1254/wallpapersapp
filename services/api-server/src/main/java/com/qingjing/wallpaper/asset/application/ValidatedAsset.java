package com.qingjing.wallpaper.asset.application;

public record ValidatedAsset(
        StorageKey storageKey,
        String originalFilename,
        String mimeType,
        String fileExtension,
        long sizeBytes,
        String sha256,
        Integer widthPixels,
        Integer heightPixels,
        Long durationMs) {
}
