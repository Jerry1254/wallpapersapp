package com.qingjing.wallpaper.device;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.CapabilityEvidence;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.DeviceCapabilityProfile;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.DeviceCapabilityReportRequest;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.EffectiveCapability;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.ExecutionMode;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.HostOsFamily;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.Placement;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.ReportedCapability;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceCapabilityService {

    private static final Duration MAX_REPORT_AGE = Duration.ofDays(30);
    private static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(10);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public DeviceCapabilityService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public DeviceCapabilityProfile report(DevicePrincipal principal, DeviceCapabilityReportRequest request) {
        validateHost(principal.platform(), request.hostOsFamily(), request.executionMode());
        Instant now = Instant.now();
        if (request.probedAt().isAfter(now.plus(MAX_CLOCK_SKEW))
                || request.probedAt().isBefore(now.minus(MAX_REPORT_AGE))) {
            throw validation("probedAt is outside the accepted range");
        }

        List<String> featureFlags = canonicalFeatures(request.featureFlags());
        List<ReportedCapability> reported = canonicalReported(request.capabilities());
        List<EffectiveCapability> effective = reported.stream()
                .filter(capability -> allowed(request.hostOsFamily(), request.executionMode(), capability))
                .map(capability -> new EffectiveCapability(
                        capability.deliveryPlatform(),
                        capability.resourceType(),
                        capability.runtimeOsVersion(),
                        capability.placements()))
                .toList();
        String featureJson = json(featureFlags);
        String reportedJson = json(reported);
        String effectiveJson = json(effective);
        java.sql.Timestamp verifiedAt = reported.stream()
                .anyMatch(capability -> capability.evidence() == CapabilityEvidence.SUCCESSFUL_SET)
                ? java.sql.Timestamp.from(now) : null;
        String hash = sha256(String.join("\n",
                request.hostOsFamily().name(), request.hostOsVersion().strip(),
                request.sdkInt() == null ? "" : request.sdkInt().toString(),
                request.manufacturer().strip(), request.model().strip(), request.executionMode().name(),
                Integer.toString(request.probeVersion()), featureJson, effectiveJson));

        jdbc.update("""
                INSERT INTO device_capability_profile
                    (device_id, host_os_family, host_os_version, sdk_int, manufacturer, model,
                     execution_mode, probe_version, feature_flags, reported_capabilities,
                     effective_capabilities, profile_hash, probed_at, last_verified_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS JSON), CAST(? AS JSON), CAST(? AS JSON), ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    host_os_family = VALUES(host_os_family), host_os_version = VALUES(host_os_version),
                    sdk_int = VALUES(sdk_int), manufacturer = VALUES(manufacturer), model = VALUES(model),
                    execution_mode = VALUES(execution_mode), probe_version = VALUES(probe_version),
                    feature_flags = VALUES(feature_flags), reported_capabilities = VALUES(reported_capabilities),
                    effective_capabilities = VALUES(effective_capabilities), profile_hash = VALUES(profile_hash),
                    probed_at = VALUES(probed_at),
                    last_verified_at = COALESCE(VALUES(last_verified_at), last_verified_at),
                    lock_version = lock_version + 1
                """,
                principal.deviceId(), request.hostOsFamily().name(), request.hostOsVersion().strip(),
                request.sdkInt(), request.manufacturer().strip(), request.model().strip(),
                request.executionMode().name(), request.probeVersion(), featureJson, reportedJson,
                effectiveJson, hash, java.sql.Timestamp.from(request.probedAt()), verifiedAt);
        return require(principal.deviceId());
    }

    @Transactional(readOnly = true)
    public DeviceCapabilityProfile require(long deviceId) {
        List<ProfileRow> rows = jdbc.query("""
                SELECT host_os_family, host_os_version, sdk_int, manufacturer, model, execution_mode,
                       probe_version, feature_flags, effective_capabilities, profile_hash, probed_at, lock_version
                FROM device_capability_profile WHERE device_id = ?
                """, (rs, rowNumber) -> new ProfileRow(
                HostOsFamily.valueOf(rs.getString("host_os_family")),
                rs.getString("host_os_version"), rs.getObject("sdk_int", Integer.class),
                rs.getString("manufacturer"), rs.getString("model"),
                ExecutionMode.valueOf(rs.getString("execution_mode")), rs.getInt("probe_version"),
                stringList(rs.getString("feature_flags")),
                objectList(rs.getString("effective_capabilities"), new TypeReference<>() { }),
                rs.getString("profile_hash"), rs.getTimestamp("probed_at").toInstant(),
                rs.getLong("lock_version")), deviceId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.PRECONDITION_REQUIRED, "DEVICE_CAPABILITY_PROFILE_REQUIRED",
                    "The device capability profile must be reported before catalog access");
        }
        ProfileRow row = rows.get(0);
        boolean recheck = row.probedAt().isBefore(Instant.now().minus(MAX_REPORT_AGE));
        return new DeviceCapabilityProfile(row.version(), row.profileHash(), row.hostOsFamily(),
                row.hostOsVersion(), row.sdkInt(), row.manufacturer(), row.model(), row.executionMode(),
                row.probeVersion(), row.probedAt(), row.featureFlags(), row.effectiveCapabilities(), recheck);
    }

    private void validateHost(DevicePlatform platform, HostOsFamily host, ExecutionMode mode) {
        boolean valid = switch (platform) {
            case ANDROID -> (host == HostOsFamily.ANDROID || host == HostOsFamily.EMUI
                    || host == HostOsFamily.HARMONY_CLASSIC) && mode != ExecutionMode.ANDROID_CONTAINER
                    || host == HostOsFamily.HARMONY_NATIVE && mode == ExecutionMode.ANDROID_CONTAINER;
            case HARMONYOS -> host == HostOsFamily.HARMONY_NATIVE && mode == ExecutionMode.NATIVE;
            case IOS -> host == HostOsFamily.IOS && mode == ExecutionMode.NATIVE;
            case H5_TEST -> false;
        };
        if (!valid) {
            throw validation("hostOsFamily and executionMode do not match the device session");
        }
    }

    private boolean allowed(HostOsFamily host, ExecutionMode mode, ReportedCapability capability) {
        DeliveryPlatform platform = capability.deliveryPlatform();
        ResourceType type = capability.resourceType();
        if (platform == DeliveryPlatform.UNIVERSAL) return true;
        if (host == HostOsFamily.HARMONY_NATIVE && mode == ExecutionMode.ANDROID_CONTAINER) return false;
        return switch (host) {
            case ANDROID, EMUI, HARMONY_CLASSIC -> platform == DeliveryPlatform.ANDROID;
            case HARMONY_NATIVE -> platform == DeliveryPlatform.HARMONYOS && mode == ExecutionMode.NATIVE;
            case IOS -> platform == DeliveryPlatform.IOS;
        };
    }

    private List<String> canonicalFeatures(List<String> input) {
        List<String> result = input == null ? new ArrayList<>() : new ArrayList<>(input);
        result.replaceAll(String::strip);
        if (new HashSet<>(result).size() != result.size()) throw validation("featureFlags must be unique");
        result.sort(String::compareTo);
        return List.copyOf(result);
    }

    private List<ReportedCapability> canonicalReported(List<ReportedCapability> input) {
        List<ReportedCapability> result = input.stream().map(capability -> {
            if (!validPair(capability.deliveryPlatform(), capability.resourceType())) {
                throw validation("Unsupported deliveryPlatform/resourceType pair");
            }
            List<Placement> placements = capability.placements().stream().distinct().sorted().toList();
            if (placements.size() != capability.placements().size()) {
                throw validation("capability placements must be unique");
            }
            return new ReportedCapability(capability.deliveryPlatform(), capability.resourceType(),
                    capability.runtimeOsVersion(), placements,
                    capability.evidence() == null ? CapabilityEvidence.SYSTEM_PROBE : capability.evidence());
        }).sorted(Comparator.comparing((ReportedCapability capability) -> capability.deliveryPlatform().name())
                .thenComparing(capability -> capability.resourceType().name())).toList();
        Set<String> pairs = new HashSet<>();
        for (ReportedCapability capability : result) {
            if (!pairs.add(capability.deliveryPlatform() + "/" + capability.resourceType())) {
                throw validation("capabilities must contain unique deliveryPlatform/resourceType pairs");
            }
        }
        return result;
    }

    private boolean validPair(DeliveryPlatform platform, ResourceType type) {
        return (platform == DeliveryPlatform.ANDROID
                && (type == ResourceType.LAYER_PARALLAX || type == ResourceType.VIDEO))
                || (platform == DeliveryPlatform.IOS && type == ResourceType.LIVE_PHOTO)
                || (platform == DeliveryPlatform.HARMONYOS && type == ResourceType.THEME_PACKAGE)
                || (platform == DeliveryPlatform.UNIVERSAL && type == ResourceType.STATIC_IMAGE);
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot serialize device capability profile", exception);
        }
    }

    private List<String> stringList(String value) {
        return objectList(value, new TypeReference<>() { });
    }

    private <T> T objectList(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception exception) {
            throw new IllegalStateException("Stored device capability profile is invalid", exception);
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private record ProfileRow(
            HostOsFamily hostOsFamily, String hostOsVersion, Integer sdkInt, String manufacturer, String model,
            ExecutionMode executionMode, int probeVersion, List<String> featureFlags,
            List<EffectiveCapability> effectiveCapabilities, String profileHash, Instant probedAt, long version) {
    }
}
