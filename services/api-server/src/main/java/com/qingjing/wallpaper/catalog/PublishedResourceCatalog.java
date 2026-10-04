package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryCapability;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.Placement;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Published delivery inventory cropped only by the authenticated App package platform. */
@Component
public class PublishedResourceCatalog {

    private static final List<Placement> APP_DECIDES_PLACEMENTS = List.of(Placement.HOME, Placement.LOCK);
    private final JdbcTemplate jdbc;

    public PublishedResourceCatalog(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublishedCatalog resolve(DevicePlatform appPlatform) {
        return resolve(appPlatform, false);
    }

    public PublishedCatalog resolve(DevicePrincipal principal) {
        return resolve(principal.platform(), new WallpaperChannelAccess(jdbc).isOffline(principal));
    }

    public PublishedCatalog resolve(DevicePlatform appPlatform, long deviceId) {
        return resolve(appPlatform, appPlatform==DevicePlatform.ANDROID
                && new WallpaperChannelAccess(jdbc).isOfflineDevice(deviceId));
    }

    private PublishedCatalog resolve(DevicePlatform appPlatform, boolean offline) {
        List<VariantRow> variants = jdbc.query("""
                SELECT DISTINCT v.wallpaper_id, v.platform, v.resource_type
                FROM wallpaper_variant v
                JOIN resource_version rv ON rv.variant_id = v.id AND rv.status = 'PUBLISHED'
                JOIN wallpaper w ON w.id = v.wallpaper_id AND w.status = 'PUBLISHED'
                WHERE v.enabled = TRUE AND (w.offline_promotion_only=FALSE OR ?=TRUE)
                  AND (
                    (v.platform='ANDROID'
                      AND EXISTS (SELECT 1 FROM secure_resource_package sp WHERE sp.resource_version_id=rv.id))
                    OR
                    (v.platform='UNIVERSAL' AND v.resource_type='STATIC_IMAGE'
                      AND EXISTS (SELECT 1 FROM resource_binding rb JOIN asset a ON a.id=rb.asset_id
                                  WHERE rb.resource_version_id=rv.id AND rb.role='STATIC_IMAGE' AND rb.ordinal=0
                                    AND a.validation_status='READY' AND a.deleted_at IS NULL
                                    AND a.mime_type IN ('image/jpeg','image/png','image/webp'))
                      AND (? <> 'ANDROID'
                           OR EXISTS (SELECT 1 FROM secure_resource_package sp WHERE sp.resource_version_id=rv.id)))
                    OR
                    (v.platform='IOS' AND v.resource_type='LIVE_PHOTO'
                      AND EXISTS (SELECT 1 FROM live_photo_package lp
                                  WHERE lp.resource_version_id=rv.id AND lp.status='READY'))
                    OR
                    (v.platform='HARMONYOS' AND v.resource_type='MOVING_PHOTO'
                      AND EXISTS (SELECT 1 FROM moving_photo_package mp
                                  WHERE mp.resource_version_id=rv.id AND mp.status='READY'))
                  )
                ORDER BY v.wallpaper_id, v.platform, v.resource_type
                """, (rs, rowNumber) -> new VariantRow(
                rs.getLong("wallpaper_id"), DeliveryPlatform.valueOf(rs.getString("platform")),
                ResourceType.valueOf(rs.getString("resource_type"))), offline, appPlatform.name());

        Map<Long, List<DeliveryCapability>> available = new LinkedHashMap<>();
        for (VariantRow variant : variants) {
            if (!PlatformResourceScope.visibleTo(appPlatform, variant.platform(), variant.resourceType())) continue;
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
