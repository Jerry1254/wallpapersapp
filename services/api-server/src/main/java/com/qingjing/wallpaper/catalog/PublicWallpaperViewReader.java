package com.qingjing.wallpaper.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.CategorySummary;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryCapability;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperSummary;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.WallpaperKind;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicWallpaperViewReader {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public PublicWallpaperViewReader(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public PublicWallpaperSummary summary(long wallpaperId) {
        return summary(wallpaperId, null);
    }

    public PublicWallpaperSummary summary(long wallpaperId, DeliveryPlatform preferredPlatform) {
        List<WallpaperRow> rows = jdbc.query(
                """
                SELECT w.id, w.title, w.slug, w.kind, w.featured_rank, w.sort_order,
                       selected.id AS selected_id, selected.name AS selected_name,
                       selected.slug AS selected_slug, selected.level AS selected_level,
                       parent.id AS parent_id, parent.name AS parent_name, parent.slug AS parent_slug,
                       a.id AS asset_id, a.mime_type, a.width_px, a.height_px
                FROM wallpaper w
                JOIN category selected ON selected.id = w.category_id
                LEFT JOIN category parent ON parent.id = selected.parent_id
                JOIN asset a ON a.id = w.cover_asset_id
                WHERE w.id = ?
                """,
                (resultSet, rowNumber) -> new WallpaperRow(
                        resultSet.getLong("id"),
                        resultSet.getString("title"),
                        resultSet.getString("slug"),
                        WallpaperKind.valueOf(resultSet.getString("kind")),
                        resultSet.getObject("featured_rank", Integer.class),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("selected_id"),
                        resultSet.getString("selected_name"),
                        resultSet.getString("selected_slug"),
                        resultSet.getInt("selected_level"),
                        resultSet.getObject("parent_id", Long.class),
                        resultSet.getString("parent_name"),
                        resultSet.getString("parent_slug"),
                        resultSet.getLong("asset_id"),
                        resultSet.getString("mime_type"),
                        resultSet.getObject("width_px", Integer.class),
                        resultSet.getObject("height_px", Integer.class)),
                wallpaperId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
        WallpaperRow row = rows.get(0);
        CategorySummary selected = new CategorySummary(
                Long.toString(row.selectedId()), row.selectedName(), row.selectedSlug());
        CategorySummary root = row.selectedLevel() == 1
                ? selected
                : new CategorySummary(Long.toString(row.parentId()), row.parentName(), row.parentSlug());
        CategorySummary child = row.selectedLevel() == 2 ? selected : null;
        String capabilitySql = """
                SELECT v.platform, v.resource_type, v.minimum_os_version, v.capability_requirements
                FROM wallpaper_variant v
                JOIN resource_version rv ON rv.variant_id = v.id AND rv.status = 'PUBLISHED'
                WHERE v.wallpaper_id = ?
                """ + (preferredPlatform == null
                        ? " ORDER BY v.id"
                        : " ORDER BY CASE WHEN v.platform = ? THEN 0 WHEN v.platform = 'UNIVERSAL' THEN 1 ELSE 2 END, v.id");
        Object[] capabilityParameters = preferredPlatform == null
                ? new Object[] {wallpaperId}
                : new Object[] {wallpaperId, preferredPlatform.name()};
        List<DeliveryCapability> capabilities = jdbc.query(
                capabilitySql,
                (resultSet, rowNumber) -> new DeliveryCapability(
                        DeliveryPlatform.valueOf(resultSet.getString("platform")),
                        ResourceType.valueOf(resultSet.getString("resource_type")),
                        resultSet.getString("minimum_os_version"),
                        stringList(resultSet.getString("capability_requirements"))),
                capabilityParameters);
        return new PublicWallpaperSummary(
                Long.toString(row.id()),
                row.title(),
                row.slug(),
                row.kind(),
                root,
                child,
                new PublicMedia(
                        Long.toString(row.assetId()),
                        "/api/v1/public/assets/" + row.assetId() + "/content",
                        row.mimeType(),
                        row.widthPx(),
                        row.heightPx()),
                row.featuredRank() != null,
                row.sortOrder(),
                capabilities);
    }

    private List<String> stringList(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() { });
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored capability requirements are invalid", exception);
        }
    }

    private record WallpaperRow(
            long id,
            String title,
            String slug,
            WallpaperKind kind,
            Integer featuredRank,
            int sortOrder,
            long selectedId,
            String selectedName,
            String selectedSlug,
            int selectedLevel,
            Long parentId,
            String parentName,
            String parentSlug,
            long assetId,
            String mimeType,
            Integer widthPx,
            Integer heightPx) {
    }
}
