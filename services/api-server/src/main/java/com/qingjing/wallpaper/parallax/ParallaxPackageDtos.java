package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.asset.AdminAssetView;
import java.util.List;

public final class ParallaxPackageDtos {
    private ParallaxPackageDtos() {}
    public record Canvas(int width, int height) {}
    public record Layer(int index, String originalFilename, String role, int ordinal,
                        double depth, double scale, double opacity, String blendMode) {}
    public record SourcePackage(String id, String originalFilename, String mimeType, long sizeBytes,
                                String sha256, String validationStatus, AdminAssetView cover,
                                Canvas canvas, List<Layer> layers) {}
    public record VersionRequest(Integer versionNo, String sourcePackageId) {}
    public record Result<T>(T value, boolean created) {}
}
