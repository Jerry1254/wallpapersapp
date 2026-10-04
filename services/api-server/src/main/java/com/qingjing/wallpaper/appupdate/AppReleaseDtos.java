package com.qingjing.wallpaper.appupdate;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

public final class AppReleaseDtos {
    private AppReleaseDtos() {}

    public record Envelope<T>(T data) {}
    public record ReleaseList(List<ReleaseView> items) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record ReleaseView(
            String id, String platform, String versionName, long versionCode,
            String releaseNotes, boolean forceUpdate, String status,
            String deliveryType, String storeUrl, String packageName,
            String sha256, Long fileSize, String abi, String signerSha256,
            Integer minSdkVersion, String downloadUrl, Instant createdAt, Instant publishedAt) {}
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record CheckResult(
            String platform, boolean updateAvailable, boolean mandatory,
            ReleaseView minimumVersion, ReleaseView latestVersion, Instant checkedAt) {}
    public record StoreReleaseRequest(
            @NotBlank @Pattern(regexp = "ios|harmony") String platform,
            @NotBlank @Size(max = 64) @Pattern(regexp = "[0-9]+(?:\\.[0-9]+){1,2}") String versionName,
            @Min(1) @Max(9007199254740991L) long versionCode,
            @NotBlank @Size(max = 1000) String releaseNotes,
            @NotBlank @Size(max = 1000) String storeUrl) {}
    public record UpdateReleaseRequest(
            @NotBlank @Size(max = 1000) String releaseNotes,
            @NotNull Boolean forceUpdate,
            @Size(max = 1000) String storeUrl) {}
}
