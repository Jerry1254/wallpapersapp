package com.qingjing.wallpaper.asset;

import java.time.Instant;

public record AdminAssetView(
        String id,
        String originalFilename,
        String mimeType,
        String fileExtension,
        long sizeBytes,
        String sha256,
        Integer widthPx,
        Integer heightPx,
        Long durationMs,
        String validationStatus,
        String validationErrorCode,
        String previewUrl,
        Instant createdAt,
        long version) {
}
