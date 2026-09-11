package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.asset.AdminAssetView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AdminContentDtos {

    private AdminContentDtos() {
    }

    public record CategoryWriteRequest(
            @Pattern(regexp = "[1-9][0-9]*") String parentId,
            @NotBlank @Size(max = 20) String name,
            @NotBlank @Size(min = 2, max = 32)
                    @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
            @Pattern(regexp = "[1-9][0-9]*") String iconAssetId,
            @NotNull @Min(0) @Max(999_999) Integer sortOrder) {
    }

    public record AdminCategory(
            String id,
            String parentId,
            int level,
            String name,
            String slug,
            AdminAssetView icon,
            int sortOrder,
            long wallpaperCount,
            Instant deletedAt,
            List<AdminCategory> children,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record CategoryList(List<AdminCategory> items) {
    }

    public record WallpaperWriteRequest(
            @NotBlank @Size(max = 40) String title,
            @NotBlank @Size(min = 2, max = 64)
                    @Pattern(regexp = "[a-z0-9]+(?:-[a-z0-9]+)*") String slug,
            @NotNull WallpaperKind kind,
            @NotBlank @Pattern(regexp = "[1-9][0-9]*") String rootCategoryId,
            @Pattern(regexp = "[1-9][0-9]*") String childCategoryId,
            @NotBlank @Pattern(regexp = "[1-9][0-9]*") String coverAssetId,
            @Min(0) @Max(999_999) Integer featuredRank,
            @NotNull @Min(0) @Max(999_999) Integer sortOrder,
            @NotBlank @Size(max = 500) String copyrightNote) {
    }

    public record VariantWriteRequest(
            @NotNull DeliveryPlatform platform,
            @NotNull ResourceType resourceType,
            @Size(max = 32) String minimumOsVersion,
            @Size(max = 20) List<@NotBlank @Size(max = 64) String> capabilityRequirements) {
    }

    public record CreateResourceBindingRequest(
            @NotBlank @Pattern(regexp = "[1-9][0-9]*") String assetId,
            @NotNull AssetRole role,
            @NotNull @Min(0) @Max(32_767) Integer ordinal) {
    }

    public record CreateResourceVersionRequest(
            @NotNull @Min(1) Integer versionNo,
            @Pattern(regexp = "[a-f0-9]{64}") String manifestSha256,
            @NotEmpty @Size(max = 100) List<@Valid CreateResourceBindingRequest> bindings) {
    }

    public record PublishWallpaperRequest(
            @NotEmpty List<@NotBlank @Pattern(regexp = "[1-9][0-9]*") String> resourceVersionIds) {
    }

    public record StateChangeReasonRequest(@NotBlank @Size(max = 300) String reason) {
    }

    public record CategorySummary(String id, String name, String slug) {
    }

    public record DeliveryCapability(
            DeliveryPlatform platform,
            ResourceType resourceType,
            String minimumOsVersion,
            List<String> capabilityRequirements) {
    }

    public record PageMetadata(int page, int pageSize, long totalItems, int totalPages) {
    }

    public record AdminWallpaperSummary(
            String id,
            String title,
            String slug,
            WallpaperKind kind,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            AdminAssetView cover,
            boolean featured,
            Integer featuredRank,
            int sortOrder,
            List<DeliveryCapability> capabilities,
            WallpaperStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            long version) {
    }

    public record AdminWallpaperDetail(
            String id,
            String title,
            String slug,
            WallpaperKind kind,
            CategorySummary rootCategory,
            CategorySummary childCategory,
            AdminAssetView cover,
            boolean featured,
            Integer featuredRank,
            int sortOrder,
            List<DeliveryCapability> capabilities,
            WallpaperStatus status,
            Instant publishedAt,
            Instant createdAt,
            Instant updatedAt,
            long version,
            String copyrightNote,
            List<AdminWallpaperVariant> variants) {
    }

    public record AdminWallpaperPage(List<AdminWallpaperSummary> items, PageMetadata page) {
    }

    public record AdminWallpaperVariant(
            String id,
            DeliveryPlatform platform,
            ResourceType resourceType,
            String minimumOsVersion,
            List<String> capabilityRequirements,
            List<AdminResourceVersion> resourceVersions,
            long version) {
    }

    public record AdminResourceVersion(
            String id,
            int versionNo,
            ResourceVersionStatus status,
            String manifestSha256,
            List<ValidationError> validationErrors,
            List<AdminResourceBinding> bindings,
            Instant publishedAt,
            Instant retiredAt,
            Instant createdAt,
            long version) {
    }

    public record ValidationError(String code, String message) {
    }

    public record AdminResourceBinding(String id, AssetRole role, int ordinal, AdminAssetView asset) {
    }

    public enum WallpaperKind {
        PARALLAX_4D,
        DYNAMIC,
        STATIC
    }

    public enum WallpaperStatus {
        DRAFT,
        PUBLISHED,
        OFFLINE,
        ARCHIVED
    }

    public enum DeliveryPlatform {
        ANDROID,
        IOS,
        HARMONYOS,
        UNIVERSAL
    }

    public enum ResourceType {
        LAYER_PARALLAX,
        VIDEO,
        LIVE_PHOTO,
        STATIC_IMAGE,
        THEME_PACKAGE
    }

    public enum AssetRole {
        COVER,
        BACKGROUND,
        FOREGROUND,
        PARALLAX_CONFIG,
        VIDEO,
        LIVE_PHOTO_IMAGE,
        LIVE_PHOTO_VIDEO,
        STATIC_IMAGE,
        THEME_PACKAGE
    }

    public enum ResourceVersionStatus {
        DRAFT,
        VALIDATING,
        READY,
        PUBLISHED,
        RETIRED,
        REJECTED
    }
}
