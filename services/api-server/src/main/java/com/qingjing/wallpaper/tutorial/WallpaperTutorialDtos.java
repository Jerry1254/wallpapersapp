package com.qingjing.wallpaper.tutorial;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;

public final class WallpaperTutorialDtos {

    private WallpaperTutorialDtos() {
    }

    public record AdminTutorialList(List<AdminWallpaperTutorial> items) {
    }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record AdminWallpaperTutorial(
            String key,
            String title,
            String platform,
            String wallpaperKind,
            boolean enabled,
            int sortOrder,
            AdminTutorialVideo video,
            Instant updatedAt,
            long version) {
    }

    public record AdminTutorialVideo(
            String id,
            String originalFilename,
            String mimeType,
            long sizeBytes,
            Long durationMs,
            String validationStatus,
            String previewUrl) {
    }

    public record TutorialWriteRequest(
            @NotBlank String videoAssetId,
            @NotNull Boolean enabled,
            @NotNull @Min(0) @Max(9999) Integer sortOrder) {
    }

    public record PublicTutorialList(List<PublicWallpaperTutorial> items) {
    }

    public record PublicWallpaperTutorial(
            String key,
            String title,
            String platform,
            String wallpaperKind,
            int sortOrder,
            PublicTutorialVideo video,
            Instant updatedAt,
            long version) {
    }

    public record PublicTutorialVideo(
            String contentUrl,
            String mimeType,
            long sizeBytes,
            Long durationMs) {
    }
}
