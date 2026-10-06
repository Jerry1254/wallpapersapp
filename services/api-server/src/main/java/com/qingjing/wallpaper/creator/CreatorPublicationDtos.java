package com.qingjing.wallpaper.creator;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.qingjing.wallpaper.catalog.AdminContentDtos.*;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;

public final class CreatorPublicationDtos {
    private CreatorPublicationDtos() { }
    public enum Action { SAVE_DRAFT, PUBLISH }

    /** All IDs are management API IDs in the explicitly selected environment. */
    public record Request(
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,80}") String environmentId,
            @NotBlank @Pattern(regexp="[A-Za-z0-9._:-]{1,128}") String clientProjectKey,
            @NotNull Action action,
            @Pattern(regexp="[1-9][0-9]*") String wallpaperId,
            @Min(0) Long expectedWallpaperVersion,
            @NotNull @Valid WallpaperWriteRequest metadata,
            @NotNull @Size(max=5) List<@Valid Resource> resources,
            @NotNull @Size(max=20) List<@NotBlank @Pattern(regexp="[1-9][0-9]*") String> retainResourceVersionIds,
            @Valid UpdateIosProductRequest iosAcquisition,
            @NotBlank String rulesVersion) { }

    public record Resource(
            @NotNull DeliveryPlatform platform,
            @NotNull ResourceType resourceType,
            @Pattern(regexp="[1-9][0-9]*") String assetId,
            @Pattern(regexp="[1-9][0-9]*") String sourcePackageId) { }

    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Task(
            String taskId, String state, String stage, String environmentId,
            String clientProjectKey, Action action, String title,
            String wallpaperId, Long wallpaperVersion, int attempt,
            List<Step> completedSteps, Result result, String errorCode,
            String errorMessage, boolean retryable, Instant createdAt, Instant updatedAt) { }
    public record Step(String key, String id) { }
    public record Result(String wallpaperId, WallpaperStatus status, long version,
                         List<String> resourceVersionIds, String previewGenerationStatus) { }
}
