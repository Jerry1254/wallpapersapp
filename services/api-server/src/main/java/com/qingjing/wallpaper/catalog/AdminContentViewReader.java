package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminResourceBinding;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminResourceVersion;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperDetail;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperSummary;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperVariant;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AssetRole;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CategorySummary;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.DeliveryCapability;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.DeliveryPlatform;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.ResourceType;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.ResourceVersionStatus;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperKind;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperStatus;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.AdminAssetService;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AdminContentViewReader {

    private final JdbcTemplate jdbc;
    private final AdminAssetService assets;
    private final ObjectMapper objectMapper;
    private final com.qingjing.wallpaper.parallax.ParallaxPackageReader sourcePackages;

    public AdminContentViewReader(JdbcTemplate jdbc, AdminAssetService assets, ObjectMapper objectMapper,
            com.qingjing.wallpaper.parallax.ParallaxPackageReader sourcePackages) {
        this.jdbc = jdbc;
        this.assets = assets;
        this.objectMapper = objectMapper;
        this.sourcePackages = sourcePackages;
    }

    public AdminWallpaperDetail wallpaper(long wallpaperId) {
        WallpaperRow row = wallpaperRow(wallpaperId);
        AdminWallpaperSummary summary = summary(row);
        return new AdminWallpaperDetail(
                summary.id(),
                summary.title(),
                summary.slug(),
                summary.kind(),
                summary.accessType(),
                summary.rootCategory(),
                summary.childCategory(),
                summary.cover(),
                summary.featured(),
                summary.featuredRank(),
                summary.sortOrder(),
                summary.capabilities(),
                summary.status(),
                summary.publishedAt(),
                summary.createdAt(),
                summary.updatedAt(),
                summary.version(),
                row.copyrightNote(),
                variants(wallpaperId));
    }

    public AdminWallpaperSummary summary(WallpaperRow row) {
        CategorySummary root = new CategorySummary(
                Long.toString(row.rootCategoryId()), row.rootCategoryName(), row.rootCategorySlug());
        CategorySummary child = row.childCategoryId() == null
                ? null
                : new CategorySummary(
                        Long.toString(row.childCategoryId()), row.childCategoryName(), row.childCategorySlug());
        return new AdminWallpaperSummary(
                Long.toString(row.id()),
                row.title(),
                row.slug(),
                WallpaperKind.valueOf(row.kind()),
                WallpaperAccessType.valueOf(row.accessType()),
                root,
                child,
                assets.get(row.coverAssetId()),
                row.featuredRank() != null,
                row.featuredRank(),
                row.sortOrder(),
                capabilities(row.id()),
                WallpaperStatus.valueOf(row.status()),
                instant(row.publishedAt()),
                row.createdAt().toInstant(),
                row.updatedAt().toInstant(),
                row.lockVersion());
    }

    public WallpaperRow wallpaperRow(long wallpaperId) {
        List<WallpaperRow> rows = jdbc.query(
                """
                SELECT w.id, w.title, w.slug, w.kind, w.access_type, w.category_id, w.cover_asset_id,
                       w.featured_rank, w.sort_order, w.copyright_note, w.status,
                       w.published_at, w.archived_at, w.created_at, w.updated_at, w.lock_version,
                       CASE WHEN selected.level = 1 THEN selected.id ELSE root.id END AS root_id,
                       CASE WHEN selected.level = 1 THEN selected.name ELSE root.name END AS root_name,
                       CASE WHEN selected.level = 1 THEN selected.slug ELSE root.slug END AS root_slug,
                       CASE WHEN selected.level = 2 THEN selected.id ELSE NULL END AS child_id,
                       CASE WHEN selected.level = 2 THEN selected.name ELSE NULL END AS child_name,
                       CASE WHEN selected.level = 2 THEN selected.slug ELSE NULL END AS child_slug
                FROM wallpaper w
                JOIN category selected ON selected.id = w.category_id
                LEFT JOIN category root ON root.id = selected.parent_id
                WHERE w.id = ?
                """,
                this::mapWallpaper,
                wallpaperId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper does not exist");
        }
        return rows.get(0);
    }

    public AdminWallpaperVariant variant(long variantId) {
        List<VariantRow> rows = jdbc.query(
                """
                SELECT id, wallpaper_id, platform, resource_type, minimum_os_version,
                       capability_requirements, lock_version
                FROM wallpaper_variant WHERE id = ?
                """,
                this::mapVariant,
                variantId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The wallpaper variant does not exist");
        }
        return variant(rows.get(0));
    }

    public VariantRow variantRow(long variantId) {
        List<VariantRow> rows = jdbc.query(
                """
                SELECT id, wallpaper_id, platform, resource_type, minimum_os_version,
                       capability_requirements, lock_version
                FROM wallpaper_variant WHERE id = ?
                """,
                this::mapVariant,
                variantId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The wallpaper variant does not exist");
        }
        return rows.get(0);
    }

    public AdminResourceVersion resourceVersion(long versionId) {
        List<ResourceVersionRow> rows = jdbc.query(
                """
                SELECT id, variant_id, version_no, status, manifest_sha256,
                       published_at, retired_at, created_at, lock_version
                FROM resource_version WHERE id = ?
                """,
                this::mapResourceVersion,
                versionId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The resource version does not exist");
        }
        return resourceVersion(rows.get(0));
    }

    public ResourceVersionRow resourceVersionRow(long versionId) {
        List<ResourceVersionRow> rows = jdbc.query(
                """
                SELECT id, variant_id, version_no, status, manifest_sha256,
                       published_at, retired_at, created_at, lock_version
                FROM resource_version WHERE id = ?
                """,
                this::mapResourceVersion,
                versionId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "The resource version does not exist");
        }
        return rows.get(0);
    }

    private List<AdminWallpaperVariant> variants(long wallpaperId) {
        return jdbc.query(
                        """
                        SELECT id, wallpaper_id, platform, resource_type, minimum_os_version,
                               capability_requirements, lock_version
                        FROM wallpaper_variant
                        WHERE wallpaper_id = ?
                        ORDER BY id
                        """,
                        this::mapVariant,
                        wallpaperId)
                .stream().map(this::variant).toList();
    }

    private AdminWallpaperVariant variant(VariantRow row) {
        List<AdminResourceVersion> versions = jdbc.query(
                        """
                        SELECT id, variant_id, version_no, status, manifest_sha256,
                               published_at, retired_at, created_at, lock_version
                        FROM resource_version
                        WHERE variant_id = ?
                        ORDER BY version_no DESC
                        """,
                        this::mapResourceVersion,
                        row.id())
                .stream().map(this::resourceVersion).toList();
        return new AdminWallpaperVariant(
                Long.toString(row.id()),
                DeliveryPlatform.valueOf(row.platform()),
                ResourceType.valueOf(row.resourceType()),
                row.minimumOsVersion(),
                readCapabilities(row.capabilityRequirements()),
                versions,
                row.lockVersion());
    }

    private AdminResourceVersion resourceVersion(ResourceVersionRow row) {
        List<AdminResourceBinding> bindings = jdbc.query(
                """
                SELECT id, asset_id, role, ordinal
                FROM resource_binding
                WHERE resource_version_id = ?
                ORDER BY role, ordinal, id
                """,
                (resultSet, rowNumber) -> new AdminResourceBinding(
                        Long.toString(resultSet.getLong("id")),
                        AssetRole.valueOf(resultSet.getString("role")),
                        resultSet.getInt("ordinal"),
                        assets.get(resultSet.getLong("asset_id"))),
                row.id());
        return new AdminResourceVersion(
                Long.toString(row.id()),
                row.versionNo(),
                ResourceVersionStatus.valueOf(row.status()),
                row.manifestSha256(),
                List.of(),
                bindings,
                sourcePackages.forVersion(row.id()),
                instant(row.publishedAt()),
                instant(row.retiredAt()),
                row.createdAt().toInstant(),
                row.lockVersion());
    }

    private List<DeliveryCapability> capabilities(long wallpaperId) {
        return jdbc.query(
                """
                SELECT v.platform, v.resource_type, v.minimum_os_version, v.capability_requirements
                FROM wallpaper_variant v
                JOIN resource_version rv ON rv.variant_id = v.id AND rv.status = 'PUBLISHED'
                WHERE v.wallpaper_id = ?
                ORDER BY v.id
                """,
                (resultSet, rowNumber) -> new DeliveryCapability(
                        DeliveryPlatform.valueOf(resultSet.getString("platform")),
                        ResourceType.valueOf(resultSet.getString("resource_type")),
                        resultSet.getString("minimum_os_version"),
                        readCapabilities(resultSet.getString("capability_requirements"))),
                wallpaperId);
    }

    private WallpaperRow mapWallpaper(ResultSet resultSet, int rowNumber) throws SQLException {
        return new WallpaperRow(
                resultSet.getLong("id"),
                resultSet.getString("title"),
                resultSet.getString("slug"),
                resultSet.getString("kind"),
                resultSet.getString("access_type"),
                resultSet.getLong("category_id"),
                resultSet.getLong("cover_asset_id"),
                (Integer) resultSet.getObject("featured_rank"),
                resultSet.getInt("sort_order"),
                resultSet.getString("copyright_note"),
                resultSet.getString("status"),
                resultSet.getTimestamp("published_at"),
                resultSet.getTimestamp("archived_at"),
                resultSet.getTimestamp("created_at"),
                resultSet.getTimestamp("updated_at"),
                resultSet.getLong("lock_version"),
                resultSet.getLong("root_id"),
                resultSet.getString("root_name"),
                resultSet.getString("root_slug"),
                (Long) resultSet.getObject("child_id"),
                resultSet.getString("child_name"),
                resultSet.getString("child_slug"));
    }

    private VariantRow mapVariant(ResultSet resultSet, int rowNumber) throws SQLException {
        return new VariantRow(
                resultSet.getLong("id"),
                resultSet.getLong("wallpaper_id"),
                resultSet.getString("platform"),
                resultSet.getString("resource_type"),
                resultSet.getString("minimum_os_version"),
                resultSet.getString("capability_requirements"),
                resultSet.getLong("lock_version"));
    }

    private ResourceVersionRow mapResourceVersion(ResultSet resultSet, int rowNumber) throws SQLException {
        return new ResourceVersionRow(
                resultSet.getLong("id"),
                resultSet.getLong("variant_id"),
                resultSet.getInt("version_no"),
                resultSet.getString("status"),
                resultSet.getString("manifest_sha256"),
                resultSet.getTimestamp("published_at"),
                resultSet.getTimestamp("retired_at"),
                resultSet.getTimestamp("created_at"),
                resultSet.getLong("lock_version"));
    }

    private List<String> readCapabilities(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("Stored capability requirements are invalid", exception);
        }
    }

    private static java.time.Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    public record WallpaperRow(
            long id,
            String title,
            String slug,
            String kind,
            String accessType,
            long categoryId,
            long coverAssetId,
            Integer featuredRank,
            int sortOrder,
            String copyrightNote,
            String status,
            Timestamp publishedAt,
            Timestamp archivedAt,
            Timestamp createdAt,
            Timestamp updatedAt,
            long lockVersion,
            long rootCategoryId,
            String rootCategoryName,
            String rootCategorySlug,
            Long childCategoryId,
            String childCategoryName,
            String childCategorySlug) {
    }

    public record VariantRow(
            long id,
            long wallpaperId,
            String platform,
            String resourceType,
            String minimumOsVersion,
            String capabilityRequirements,
            long lockVersion) {
    }

    public record ResourceVersionRow(
            long id,
            long variantId,
            int versionNo,
            String status,
            String manifestSha256,
            Timestamp publishedAt,
            Timestamp retiredAt,
            Timestamp createdAt,
            long lockVersion) {
    }
}
