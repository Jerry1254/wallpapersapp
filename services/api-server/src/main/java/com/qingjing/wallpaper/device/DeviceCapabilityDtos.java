package com.qingjing.wallpaper.device;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
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

public final class DeviceCapabilityDtos {

    private DeviceCapabilityDtos() {
    }

    public enum HostOsFamily { ANDROID, EMUI, HARMONY_CLASSIC, HARMONY_NATIVE, IOS }

    public enum ExecutionMode { NATIVE, ANDROID_COMPATIBLE, ANDROID_CONTAINER }

    public enum Placement { HOME, LOCK }

    public enum CapabilityEvidence { SYSTEM_PROBE, SUCCESSFUL_SET }

    public record ReportedCapability(
            @NotNull DeliveryPlatform deliveryPlatform,
            @NotNull ResourceType resourceType,
            @NotBlank @Size(max = 32)
                    @Pattern(regexp = "[0-9]{1,6}(?:\\.[0-9]{1,6}){0,3}") String runtimeOsVersion,
            @NotEmpty @Size(max = 2) List<@NotNull Placement> placements,
            @NotNull CapabilityEvidence evidence) {
    }

    public record DeviceCapabilityReportRequest(
            @NotNull HostOsFamily hostOsFamily,
            @NotBlank @Size(max = 32) String hostOsVersion,
            @Min(1) @Max(10_000) Integer sdkInt,
            @NotBlank @Size(max = 64) String manufacturer,
            @NotBlank @Size(max = 96) String model,
            @NotNull ExecutionMode executionMode,
            @NotNull @Min(1) Integer probeVersion,
            @NotNull Instant probedAt,
            @Size(max = 64) List<@NotBlank @Size(max = 64) String> featureFlags,
            @NotNull @Size(max = 16) List<@Valid ReportedCapability> capabilities) {
    }

    public record EffectiveCapability(
            DeliveryPlatform deliveryPlatform,
            ResourceType resourceType,
            String runtimeOsVersion,
            List<Placement> placements) {
    }

    public record DeviceCapabilityProfile(
            long version,
            String profileHash,
            HostOsFamily hostOsFamily,
            String hostOsVersion,
            Integer sdkInt,
            String manufacturer,
            String model,
            ExecutionMode executionMode,
            int probeVersion,
            Instant probedAt,
            List<String> featureFlags,
            List<EffectiveCapability> effectiveCapabilities,
            boolean recheckRequired) {
    }
}
