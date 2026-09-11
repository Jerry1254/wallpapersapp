package com.qingjing.wallpaper.asset.application;

public enum DetectedAssetType {
    JPEG("image/jpeg", "jpg"),
    PNG("image/png", "png"),
    WEBP("image/webp", "webp"),
    MP4("video/mp4", "mp4"),
    QUICKTIME("video/quicktime", "mov"),
    JSON("application/json", "json"),
    ZIP("application/zip", "zip");

    private final String mimeType;
    private final String extension;

    DetectedAssetType(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }
}
