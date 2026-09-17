package com.qingjing.wallpaper.catalog;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryCapability;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.DeviceCapabilityProfile;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.EffectiveCapability;
import com.qingjing.wallpaper.device.DeviceCapabilityService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DeviceCatalogVisibility {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final DeviceCapabilityService profiles;

    public DeviceCatalogVisibility(JdbcTemplate jdbc, ObjectMapper objectMapper, DeviceCapabilityService profiles) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.profiles = profiles;
    }

    public VisibleCatalog resolve(long deviceId) {
        DeviceCapabilityProfile profile = profiles.require(deviceId);
        Set<String> featureFlags = Set.copyOf(profile.featureFlags());
        Map<String, EffectiveCapability> deviceCapabilities = new LinkedHashMap<>();
        profile.effectiveCapabilities().forEach(capability -> deviceCapabilities.put(key(
                capability.deliveryPlatform(), capability.resourceType()), capability));

        List<VariantRow> variants = jdbc.query("""
                SELECT v.wallpaper_id, v.platform, v.resource_type,
                       v.minimum_os_version, v.capability_requirements
                FROM wallpaper_variant v
                JOIN resource_version rv ON rv.variant_id = v.id AND rv.status = 'PUBLISHED'
                JOIN wallpaper w ON w.id = v.wallpaper_id AND w.status = 'PUBLISHED'
                WHERE v.enabled = TRUE
                ORDER BY v.wallpaper_id, v.id
                """, (rs, rowNumber) -> new VariantRow(
                rs.getLong("wallpaper_id"), DeliveryPlatform.valueOf(rs.getString("platform")),
                ResourceType.valueOf(rs.getString("resource_type")), rs.getString("minimum_os_version"),
                stringList(rs.getString("capability_requirements"))));

        Map<Long, List<DeliveryCapability>> available = new LinkedHashMap<>();
        for (VariantRow variant : variants) {
            EffectiveCapability capability = deviceCapabilities.get(key(variant.platform(), variant.resourceType()));
            if (capability == null
                    || !OsVersions.compatible(capability.runtimeOsVersion(), variant.minimumOsVersion())
                    || !featureFlags.containsAll(variant.requirements())) continue;
            DeliveryCapability result = new DeliveryCapability(
                    variant.platform(), variant.resourceType(), capability.placements());
            available.computeIfAbsent(variant.wallpaperId(), ignored -> new ArrayList<>()).add(result);
        }
        available.replaceAll((ignored, values) -> values.stream()
                .distinct()
                .sorted(Comparator.comparing((DeliveryCapability value) -> value.deliveryPlatform().name())
                        .thenComparing(value -> value.resourceType().name()))
                .toList());
        return new VisibleCatalog(profile.profileHash(), Map.copyOf(available));
    }

    private String key(DeliveryPlatform platform, ResourceType resourceType) {
        return platform.name() + "/" + resourceType.name();
    }

    private List<String> stringList(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() { });
        } catch (Exception exception) {
            throw new IllegalStateException("Stored capability requirements are invalid", exception);
        }
    }

    public record VisibleCatalog(String profileHash, Map<Long, List<DeliveryCapability>> capabilitiesByWallpaper) {
        public Set<Long> wallpaperIds() {
            return new LinkedHashSet<>(capabilitiesByWallpaper.keySet());
        }

        public List<DeliveryCapability> capabilities(long wallpaperId) {
            return capabilitiesByWallpaper.getOrDefault(wallpaperId, List.of());
        }

        public boolean contains(long wallpaperId) {
            return capabilitiesByWallpaper.containsKey(wallpaperId);
        }
    }

    private record VariantRow(
            long wallpaperId, DeliveryPlatform platform, ResourceType resourceType,
            String minimumOsVersion, List<String> requirements) {
    }
}
