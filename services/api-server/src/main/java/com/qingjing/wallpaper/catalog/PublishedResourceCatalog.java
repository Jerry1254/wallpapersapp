package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryCapability;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.device.DeviceCapabilityDtos.Placement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Published delivery inventory. Device capability probing and filtering belongs to the App. */
@Component
public class PublishedResourceCatalog {

    private static final List<Placement> APP_DECIDES_PLACEMENTS = List.of(Placement.HOME, Placement.LOCK);
    private final JdbcTemplate jdbc;

    public PublishedResourceCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublishedCatalog resolve() {
        List<VariantRow> variants = jdbc.query("""
                SELECT DISTINCT v.wallpaper_id, v.platform, v.resource_type
                FROM wallpaper_variant v
                JOIN resource_version rv ON rv.variant_id = v.id AND rv.status = 'PUBLISHED'
                JOIN wallpaper w ON w.id = v.wallpaper_id AND w.status = 'PUBLISHED'
                WHERE v.enabled = TRUE
                ORDER BY v.wallpaper_id, v.platform, v.resource_type
                """, (rs, rowNumber) -> new VariantRow(
                rs.getLong("wallpaper_id"), DeliveryPlatform.valueOf(rs.getString("platform")),
                ResourceType.valueOf(rs.getString("resource_type"))));

        Map<Long, List<DeliveryCapability>> available = new LinkedHashMap<>();
        for (VariantRow variant : variants) {
            available.computeIfAbsent(variant.wallpaperId(), ignored -> new ArrayList<>()).add(
                    new DeliveryCapability(variant.platform(), variant.resourceType(), APP_DECIDES_PLACEMENTS));
        }
        available.replaceAll((ignored, values) -> values.stream()
                .distinct()
                .sorted(Comparator.comparing((DeliveryCapability value) -> value.deliveryPlatform().name())
                        .thenComparing(value -> value.resourceType().name()))
                .toList());
        return new PublishedCatalog(Map.copyOf(available));
    }

    public record PublishedCatalog(Map<Long, List<DeliveryCapability>> capabilitiesByWallpaper) {
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

    private record VariantRow(long wallpaperId, DeliveryPlatform platform, ResourceType resourceType) {
    }
}
