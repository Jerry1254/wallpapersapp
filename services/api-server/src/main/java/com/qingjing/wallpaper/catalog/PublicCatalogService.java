package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.DeviceCatalogVisibility.VisibleCatalog;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicCategoryList;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicChildCategory;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicRootCategory;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperDetail;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperPage;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperSummary;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicCatalogService {

    public enum CatalogView { FEATURED, STATIC }

    public enum CatalogSort { DEFAULT, NEWEST }

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final PublicWallpaperViewReader views;
    private final DeviceCatalogVisibility visibility;

    public PublicCatalogService(
            JdbcTemplate jdbc, PublicWallpaperViewReader views, DeviceCatalogVisibility visibility) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.views = views;
        this.visibility = visibility;
    }

    @Transactional(readOnly = true)
    public PublicCategoryList categories(long deviceId) {
        VisibleCatalog visible = visibility.resolve(deviceId);
        if (visible.wallpaperIds().isEmpty()) return new PublicCategoryList(List.of());
        Map<Long, Long> rootCounts = new HashMap<>();
        Map<Long, Long> childCounts = new HashMap<>();
        namedJdbc.query("""
                SELECT selected.id, selected.level, selected.parent_id, COUNT(w.id) AS wallpaper_count
                FROM wallpaper w
                JOIN category selected ON selected.id = w.category_id
                WHERE w.status = 'PUBLISHED' AND w.id IN (:wallpaperIds)
                GROUP BY selected.id, selected.level, selected.parent_id
                """, Map.of("wallpaperIds", visible.wallpaperIds()), resultSet -> {
            long count = resultSet.getLong("wallpaper_count");
            if (resultSet.getInt("level") == 1) {
                rootCounts.merge(resultSet.getLong("id"), count, Long::sum);
            } else {
                long childId = resultSet.getLong("id");
                long rootId = resultSet.getLong("parent_id");
                childCounts.put(childId, count);
                rootCounts.merge(rootId, count, Long::sum);
            }
        });
        List<RootRow> roots = jdbc.query("""
                SELECT root.id, root.name, root.slug, root.sort_order,
                       asset.id AS asset_id, asset.mime_type, asset.width_px, asset.height_px
                FROM category root
                JOIN asset ON asset.id = root.icon_asset_id
                    AND asset.deleted_at IS NULL AND asset.validation_status = 'READY'
                WHERE root.level = 1 AND root.deleted_at IS NULL
                ORDER BY root.sort_order, root.id
                """, (rs, rowNumber) -> new RootRow(
                rs.getLong("id"), rs.getString("name"), rs.getString("slug"), rs.getInt("sort_order"),
                rs.getLong("asset_id"), rs.getString("mime_type"),
                rs.getObject("width_px", Integer.class), rs.getObject("height_px", Integer.class)));
        List<PublicRootCategory> items = roots.stream()
                .filter(root -> rootCounts.getOrDefault(root.id(), 0L) > 0)
                .map(root -> new PublicRootCategory(
                        Long.toString(root.id()), root.name(), root.slug(),
                        media(root.assetId(), root.mimeType(), root.widthPx(), root.heightPx()), root.sortOrder(),
                        rootCounts.get(root.id()), children(root.id(), childCounts)))
                .toList();
        return new PublicCategoryList(items);
    }

    @Transactional(readOnly = true)
    public PublicWallpaperPage wallpapers(
            long deviceId,
            int page,
            int pageSize,
            Long rootCategoryId,
            Long childCategoryId,
            CatalogView view,
            WallpaperAccessType accessType,
            String query,
            CatalogSort sort) {
        validatePage(page, pageSize);
        validateCategories(rootCategoryId, childCategoryId);
        String normalizedQuery = normalizeQuery(query);
        VisibleCatalog visible = visibility.resolve(deviceId);
        if (visible.wallpaperIds().isEmpty()) {
            return new PublicWallpaperPage(List.of(), new PageMetadata(page, pageSize, 0, 0));
        }

        StringBuilder where = new StringBuilder(" WHERE w.status = 'PUBLISHED' AND w.id IN (:wallpaperIds)");
        MapSqlParameterSource parameters = new MapSqlParameterSource("wallpaperIds", visible.wallpaperIds());
        if (rootCategoryId != null) {
            where.append(" AND (selected.id = :rootCategoryId OR selected.parent_id = :rootCategoryId)");
            parameters.addValue("rootCategoryId", rootCategoryId);
        }
        if (childCategoryId != null) {
            where.append(" AND selected.id = :childCategoryId");
            parameters.addValue("childCategoryId", childCategoryId);
        }
        if (view == CatalogView.FEATURED) {
            where.append(" AND w.featured_rank IS NOT NULL");
        } else if (view == CatalogView.STATIC) {
            List<Long> staticIds = visible.capabilitiesByWallpaper().entrySet().stream()
                    .filter(entry -> entry.getValue().stream()
                            .anyMatch(capability -> capability.resourceType() == ResourceType.STATIC_IMAGE))
                    .map(Map.Entry::getKey).toList();
            if (staticIds.isEmpty()) {
                return new PublicWallpaperPage(List.of(), new PageMetadata(page, pageSize, 0, 0));
            }
            where.append(" AND w.id IN (:staticIds)");
            parameters.addValue("staticIds", staticIds);
        }
        if (accessType != null) {
            where.append(" AND w.access_type = :accessType");
            parameters.addValue("accessType", accessType.name());
        }
        if (normalizedQuery != null) {
            where.append("""
                     AND (w.title LIKE :query ESCAPE '!'
                       OR CONVERT(w.slug USING utf8mb4) COLLATE utf8mb4_0900_ai_ci LIKE :query ESCAPE '!')
                    """);
            parameters.addValue("query", "%" + escapeLike(normalizedQuery.toLowerCase(Locale.ROOT)) + "%");
        }

        String from = " FROM wallpaper w JOIN category selected ON selected.id = w.category_id";
        Long totalValue = namedJdbc.queryForObject("SELECT COUNT(*)" + from + where, parameters, Long.class);
        long total = totalValue == null ? 0 : totalValue;
        String order = sort == CatalogSort.NEWEST
                ? " ORDER BY w.published_at DESC, w.id DESC"
                : view == CatalogView.FEATURED
                        ? " ORDER BY w.featured_rank, w.id DESC"
                        : " ORDER BY w.sort_order, w.id DESC";
        parameters.addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize);
        List<Long> ids = namedJdbc.queryForList(
                "SELECT w.id" + from + where + order + " LIMIT :limit OFFSET :offset",
                parameters, Long.class);
        List<PublicWallpaperSummary> items = ids.stream()
                .map(id -> views.summary(id, visible.capabilities(id)))
                .toList();
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + pageSize - 1) / pageSize);
        return new PublicWallpaperPage(items, new PageMetadata(page, pageSize, total, totalPages));
    }

    @Transactional(readOnly = true)
    public PublicWallpaperDetail wallpaper(long deviceId, long wallpaperId) {
        VisibleCatalog visible = visibility.resolve(deviceId);
        if (!visible.contains(wallpaperId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_AVAILABLE_FOR_DEVICE",
                    "The wallpaper is not available for this device");
        }
        List<DetailRow> rows = jdbc.query("""
                SELECT copyright_note, published_at FROM wallpaper
                WHERE id = ? AND status = 'PUBLISHED'
                """, (rs, rowNumber) -> new DetailRow(
                rs.getString("copyright_note"), rs.getTimestamp("published_at")), wallpaperId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
        DetailRow row = rows.get(0);
        return PublicWallpaperDetail.from(
                views.summary(wallpaperId, visible.capabilities(wallpaperId)),
                row.copyrightNote(), row.publishedAt().toInstant());
    }

    private List<PublicChildCategory> children(long rootId, Map<Long, Long> counts) {
        return jdbc.query("""
                SELECT id, name, slug, sort_order FROM category
                WHERE parent_id = ? AND level = 2 AND deleted_at IS NULL
                ORDER BY sort_order, id
                """, (rs, rowNumber) -> new PublicChildCategory(
                Long.toString(rs.getLong("id")), rs.getString("name"), rs.getString("slug"),
                rs.getInt("sort_order"), counts.getOrDefault(rs.getLong("id"), 0L)), rootId)
                .stream().filter(child -> child.wallpaperCount() > 0).toList();
    }

    private void validateCategories(Long rootCategoryId, Long childCategoryId) {
        if (childCategoryId != null && rootCategoryId == null) {
            throw validation("childCategoryId requires rootCategoryId");
        }
        if (rootCategoryId == null) return;
        Integer roots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ? AND level = 1 AND deleted_at IS NULL",
                Integer.class, rootCategoryId);
        if (roots == null || roots == 0) throw validation("rootCategoryId is invalid");
        if (childCategoryId != null) {
            Integer child = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM category
                    WHERE id = ? AND parent_id = ? AND level = 2 AND deleted_at IS NULL
                    """, Integer.class, childCategoryId, rootCategoryId);
            if (child == null || child == 0) throw validation("childCategoryId does not belong to rootCategoryId");
        }
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw validation("page and pageSize are outside the accepted range");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) return null;
        String normalized = query.trim();
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw validation("q must contain between 1 and 40 characters after trimming");
        }
        return normalized;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private PublicMedia media(long assetId, String mimeType, Integer widthPx, Integer heightPx) {
        return new PublicMedia(Long.toString(assetId), "/api/v1/public/assets/" + assetId + "/content",
                mimeType, widthPx, heightPx);
    }

    private record RootRow(
            long id, String name, String slug, int sortOrder, long assetId, String mimeType,
            Integer widthPx, Integer heightPx) {
    }

    private record DetailRow(String copyrightNote, Timestamp publishedAt) {
    }
}
