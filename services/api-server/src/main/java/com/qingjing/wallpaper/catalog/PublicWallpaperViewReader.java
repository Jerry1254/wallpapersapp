package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.CategorySummary;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryCapability;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperSummary;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class PublicWallpaperViewReader {

    private final JdbcTemplate jdbc;

    public PublicWallpaperViewReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public PublicWallpaperSummary summary(long wallpaperId) {
        return summary(wallpaperId, List.of());
    }

    public PublicWallpaperSummary summary(long wallpaperId, List<DeliveryCapability> availableCapabilities) {
        List<WallpaperRow> rows = jdbc.query("""
                SELECT w.id, w.title, w.slug, w.access_type, w.featured_rank, w.sort_order,
                       selected.id AS selected_id, selected.name AS selected_name,
                       selected.slug AS selected_slug, selected.level AS selected_level,
                       parent.id AS parent_id, parent.name AS parent_name, parent.slug AS parent_slug,
                       a.id AS asset_id, a.mime_type, a.width_px, a.height_px
                FROM wallpaper w
                JOIN category selected ON selected.id = w.category_id
                LEFT JOIN category parent ON parent.id = selected.parent_id
                JOIN asset a ON a.id = w.cover_asset_id
                WHERE w.id = ?
                """, (resultSet, rowNumber) -> new WallpaperRow(
                resultSet.getLong("id"), resultSet.getString("title"), resultSet.getString("slug"),
                WallpaperAccessType.valueOf(resultSet.getString("access_type")),
                resultSet.getObject("featured_rank", Integer.class), resultSet.getInt("sort_order"),
                resultSet.getLong("selected_id"), resultSet.getString("selected_name"),
                resultSet.getString("selected_slug"), resultSet.getInt("selected_level"),
                resultSet.getObject("parent_id", Long.class), resultSet.getString("parent_name"),
                resultSet.getString("parent_slug"), resultSet.getLong("asset_id"),
                resultSet.getString("mime_type"), resultSet.getObject("width_px", Integer.class),
                resultSet.getObject("height_px", Integer.class)), wallpaperId);
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
        return new PublicWallpaperSummary(
                Long.toString(row.id()), row.title(), row.slug(), row.accessType(), root, child,
                new PublicMedia(Long.toString(row.assetId()),
                        "/api/v1/public/assets/" + row.assetId() + "/content", row.mimeType(),
                        row.widthPx(), row.heightPx()),
                row.featuredRank() != null, row.sortOrder(), List.copyOf(availableCapabilities));
    }

    private record WallpaperRow(
            long id, String title, String slug, WallpaperAccessType accessType, Integer featuredRank,
            int sortOrder, long selectedId, String selectedName, String selectedSlug, int selectedLevel,
            Long parentId, String parentName, String parentSlug, long assetId, String mimeType,
            Integer widthPx, Integer heightPx) {
    }
}
