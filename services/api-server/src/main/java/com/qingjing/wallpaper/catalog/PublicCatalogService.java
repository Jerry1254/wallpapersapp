package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicCategoryList;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicChildCategory;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicMedia;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicRootCategory;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperDetail;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperPage;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperSummary;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.WallpaperKind;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PublicCatalogService {

    public enum CatalogView { FEATURED, STATIC }

    public enum CatalogSort { DEFAULT, NEWEST }

    private final JdbcTemplate jdbc;
    private final PublicWallpaperViewReader views;

    public PublicCatalogService(JdbcTemplate jdbc, PublicWallpaperViewReader views) {
        this.jdbc = jdbc;
        this.views = views;
    }

    @Transactional(readOnly = true)
    public PublicCategoryList categories() {
        List<RootRow> roots = jdbc.query(
                """
                SELECT root.id, root.name, root.slug, root.sort_order,
                       asset.id AS asset_id, asset.mime_type, asset.width_px, asset.height_px,
                       COUNT(w.id) AS wallpaper_count
                FROM category root
                JOIN asset ON asset.id = root.icon_asset_id
                    AND asset.deleted_at IS NULL AND asset.validation_status = 'READY'
                LEFT JOIN category selected ON (selected.id = root.id OR selected.parent_id = root.id)
                    AND selected.deleted_at IS NULL
                LEFT JOIN wallpaper w ON w.category_id = selected.id AND w.status = 'PUBLISHED'
                WHERE root.level = 1 AND root.deleted_at IS NULL
                GROUP BY root.id, root.name, root.slug, root.sort_order,
                         asset.id, asset.mime_type, asset.width_px, asset.height_px
                HAVING COUNT(w.id) > 0
                ORDER BY root.sort_order, root.id
                """,
                (resultSet, rowNumber) -> new RootRow(
                        resultSet.getLong("id"),
                        resultSet.getString("name"),
                        resultSet.getString("slug"),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("asset_id"),
                        resultSet.getString("mime_type"),
                        resultSet.getObject("width_px", Integer.class),
                        resultSet.getObject("height_px", Integer.class),
                        resultSet.getLong("wallpaper_count")));
        List<PublicRootCategory> items = roots.stream().map(root -> new PublicRootCategory(
                Long.toString(root.id()),
                root.name(),
                root.slug(),
                media(root.assetId(), root.mimeType(), root.widthPx(), root.heightPx()),
                root.sortOrder(),
                root.wallpaperCount(),
                children(root.id()))).toList();
        return new PublicCategoryList(items);
    }

    @Transactional(readOnly = true)
    public PublicWallpaperPage wallpapers(
            int page,
            int pageSize,
            Long rootCategoryId,
            Long childCategoryId,
            CatalogView view,
            WallpaperKind kind,
            DeliveryPlatform platform,
            String query,
            CatalogSort sort) {
        validatePage(page, pageSize);
        validateCategories(rootCategoryId, childCategoryId);
        String normalizedQuery = normalizeQuery(query);

        List<Object> parameters = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE w.status = 'PUBLISHED'");
        if (rootCategoryId != null) {
            where.append(" AND (selected.id = ? OR selected.parent_id = ?)");
            parameters.add(rootCategoryId);
            parameters.add(rootCategoryId);
        }
        if (childCategoryId != null) {
            where.append(" AND selected.id = ?");
            parameters.add(childCategoryId);
        }
        if (view == CatalogView.FEATURED) {
            where.append(" AND w.featured_rank IS NOT NULL");
        } else if (view == CatalogView.STATIC) {
            where.append("""
                     AND EXISTS (
                        SELECT 1 FROM wallpaper_variant static_variant
                        JOIN resource_version static_version
                          ON static_version.variant_id = static_variant.id
                         AND static_version.status = 'PUBLISHED'
                        WHERE static_variant.wallpaper_id = w.id
                          AND static_variant.resource_type = 'STATIC_IMAGE'
                     )
                    """);
        }
        if (kind != null) {
            where.append(" AND w.kind = ?");
            parameters.add(kind.name());
        }
        if (platform != null) {
            where.append("""
                     AND EXISTS (
                        SELECT 1 FROM wallpaper_variant platform_variant
                        JOIN resource_version platform_version
                          ON platform_version.variant_id = platform_variant.id
                         AND platform_version.status = 'PUBLISHED'
                        WHERE platform_variant.wallpaper_id = w.id
                          AND platform_variant.platform IN (?, 'UNIVERSAL')
                     )
                    """);
            parameters.add(platform.name());
        }
        if (normalizedQuery != null) {
            where.append("""
                     AND (w.title LIKE ? ESCAPE '!'
                       OR CONVERT(w.slug USING utf8mb4) COLLATE utf8mb4_0900_ai_ci LIKE ? ESCAPE '!')
                    """);
            String like = "%" + escapeLike(normalizedQuery.toLowerCase(Locale.ROOT)) + "%";
            parameters.add(like);
            parameters.add(like);
        }

        String from = " FROM wallpaper w JOIN category selected ON selected.id = w.category_id";
        long total = jdbc.queryForObject("SELECT COUNT(*)" + from + where, Long.class, parameters.toArray());
        String order = sort == CatalogSort.NEWEST
                ? " ORDER BY w.published_at DESC, w.id DESC"
                : view == CatalogView.FEATURED
                        ? " ORDER BY w.featured_rank, w.id DESC"
                        : " ORDER BY w.sort_order, w.id DESC";
        List<Object> listParameters = new ArrayList<>(parameters);
        listParameters.add(pageSize);
        listParameters.add((long) (page - 1) * pageSize);
        List<Long> ids = jdbc.query(
                "SELECT w.id" + from + where + order + " LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                listParameters.toArray());
        List<PublicWallpaperSummary> items = ids.stream()
                .map(id -> views.summary(id, platform))
                .toList();
        int totalPages = total == 0 ? 0 : Math.toIntExact((total + pageSize - 1) / pageSize);
        return new PublicWallpaperPage(items, new PageMetadata(page, pageSize, total, totalPages));
    }

    @Transactional(readOnly = true)
    public PublicWallpaperDetail wallpaper(long wallpaperId, DeliveryPlatform platform) {
        List<DetailRow> rows = jdbc.query(
                """
                SELECT copyright_note, published_at
                FROM wallpaper
                WHERE id = ? AND status = 'PUBLISHED'
                """,
                (resultSet, rowNumber) -> new DetailRow(
                        resultSet.getString("copyright_note"),
                        resultSet.getTimestamp("published_at")),
                wallpaperId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
        DetailRow row = rows.get(0);
        return PublicWallpaperDetail.from(
                views.summary(wallpaperId, platform),
                row.copyrightNote(),
                row.publishedAt().toInstant());
    }

    private List<PublicChildCategory> children(long rootId) {
        return jdbc.query(
                """
                SELECT child.id, child.name, child.slug, child.sort_order, COUNT(w.id) AS wallpaper_count
                FROM category child
                JOIN wallpaper w ON w.category_id = child.id AND w.status = 'PUBLISHED'
                WHERE child.parent_id = ? AND child.level = 2 AND child.deleted_at IS NULL
                GROUP BY child.id, child.name, child.slug, child.sort_order
                ORDER BY child.sort_order, child.id
                """,
                (resultSet, rowNumber) -> new PublicChildCategory(
                        Long.toString(resultSet.getLong("id")),
                        resultSet.getString("name"),
                        resultSet.getString("slug"),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("wallpaper_count")),
                rootId);
    }

    private void validateCategories(Long rootCategoryId, Long childCategoryId) {
        if (childCategoryId != null && rootCategoryId == null) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "childCategoryId requires rootCategoryId");
        }
        if (rootCategoryId == null) {
            return;
        }
        Integer roots = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE id = ? AND level = 1 AND deleted_at IS NULL",
                Integer.class,
                rootCategoryId);
        if (roots == null || roots == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "rootCategoryId is invalid");
        }
        if (childCategoryId != null) {
            Integer children = jdbc.queryForObject(
                    """
                    SELECT COUNT(*) FROM category
                    WHERE id = ? AND parent_id = ? AND level = 2 AND deleted_at IS NULL
                    """,
                    Integer.class,
                    childCategoryId,
                    rootCategoryId);
            if (children == null || children == 0) {
                throw new ApiException(
                        HttpStatus.BAD_REQUEST,
                        "VALIDATION_FAILED",
                        "childCategoryId does not belong to rootCategoryId");
            }
        }
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "page and pageSize are outside the accepted range");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }
        String normalized = query.trim();
        if (normalized.isEmpty() || normalized.length() > 40) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    "q must contain between 1 and 40 characters after trimming");
        }
        return normalized;
    }

    private String escapeLike(String value) {
        return value.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    private PublicMedia media(long assetId, String mimeType, Integer widthPx, Integer heightPx) {
        return new PublicMedia(
                Long.toString(assetId),
                "/api/v1/public/assets/" + assetId + "/content",
                mimeType,
                widthPx,
                heightPx);
    }

    private record RootRow(
            long id,
            String name,
            String slug,
            int sortOrder,
            long assetId,
            String mimeType,
            Integer widthPx,
            Integer heightPx,
            long wallpaperCount) {
    }

    private record DetailRow(String copyrightNote, Timestamp publishedAt) {
    }
}
