package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminCategory;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CategoryWriteRequest;

import com.qingjing.wallpaper.asset.AdminAssetService;
import com.qingjing.wallpaper.asset.AdminAssetView;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCategoryService {

    private final JdbcTemplate jdbc;
    private final AdminAssetService assets;

    public AdminCategoryService(JdbcTemplate jdbc, AdminAssetService assets) {
        this.jdbc = jdbc;
        this.assets = assets;
    }

    @Transactional(readOnly = true)
    public List<AdminCategory> list(boolean includeDeleted) {
        List<CategoryRow> rows = jdbc.query(
                """
                SELECT c.id, c.parent_id, c.level, c.name, c.slug, c.icon_asset_id, c.sort_order,
                       c.deleted_at, c.created_at, c.updated_at, c.lock_version,
                       CASE WHEN c.level = 1 THEN (
                           SELECT COUNT(*) FROM wallpaper w
                           LEFT JOIN category selected ON selected.id = w.category_id
                           WHERE selected.id = c.id OR selected.parent_id = c.id
                       ) ELSE (SELECT COUNT(*) FROM wallpaper w WHERE w.category_id = c.id) END AS wallpaper_count
                FROM category c
                WHERE (? OR c.deleted_at IS NULL)
                ORDER BY c.level, c.sort_order, c.id
                """,
                this::mapRow,
                includeDeleted);

        Map<Long, AdminCategory> roots = new LinkedHashMap<>();
        Map<Long, List<AdminCategory>> children = new LinkedHashMap<>();
        for (CategoryRow row : rows) {
            if (row.level() == 1) {
                roots.put(row.id(), view(row, new ArrayList<>()));
            } else {
                children.computeIfAbsent(row.parentId(), ignored -> new ArrayList<>()).add(view(row, List.of()));
            }
        }
        List<AdminCategory> result = new ArrayList<>();
        for (AdminCategory root : roots.values()) {
            result.add(copyWithChildren(root, children.getOrDefault(Long.parseLong(root.id()), List.of())));
        }
        return result;
    }

    @Transactional(readOnly = true)
    public AdminCategory get(long categoryId) {
        CategoryRow row = require(categoryId, false);
        List<AdminCategory> children = row.level() == 1
                ? list(false).stream().filter(root -> root.id().equals(Long.toString(categoryId)))
                        .findFirst().map(AdminCategory::children).orElse(List.of())
                : List.of();
        return view(row, children);
    }

    @Transactional
    public AdminCategory create(CategoryWriteRequest request) {
        CategoryShape shape = validateShape(request, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO category (parent_id, level, name, slug, icon_asset_id, sort_order)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                nullableLong(statement, 1, shape.parentId());
                statement.setInt(2, shape.level());
                statement.setString(3, request.name().strip());
                statement.setString(4, request.slug());
                nullableLong(statement, 5, shape.iconAssetId());
                statement.setInt(6, request.sortOrder());
                return statement;
            }, keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw categoryConflict(exception);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Category insert returned no identifier");
        }
        return get(key.longValue());
    }

    @Transactional
    public AdminCategory update(long categoryId, long expectedVersion, CategoryWriteRequest request) {
        CategoryRow existing = require(categoryId, false);
        CategoryShape shape = validateShape(request, existing);
        try {
            int updated = jdbc.update(
                    """
                    UPDATE category
                    SET parent_id = ?, level = ?, name = ?, slug = ?, icon_asset_id = ?,
                        sort_order = ?, lock_version = lock_version + 1
                    WHERE id = ? AND deleted_at IS NULL AND lock_version = ?
                    """,
                    shape.parentId(),
                    shape.level(),
                    request.name().strip(),
                    request.slug(),
                    shape.iconAssetId(),
                    request.sortOrder(),
                    categoryId,
                    expectedVersion);
            if (updated == 0) {
                throw versionConflict();
            }
        } catch (DataIntegrityViolationException exception) {
            throw categoryConflict(exception);
        }
        return get(categoryId);
    }

    @Transactional
    public void delete(long categoryId, long expectedVersion) {
        CategoryRow existing = require(categoryId, false);
        Long currentVersion = jdbc.queryForObject(
                "SELECT lock_version FROM category WHERE id = ? AND deleted_at IS NULL FOR UPDATE",
                Long.class,
                categoryId);
        if (currentVersion == null || currentVersion != expectedVersion) {
            throw versionConflict();
        }
        Long childCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM category WHERE parent_id = ? AND deleted_at IS NULL",
                Long.class,
                categoryId);
        Long wallpaperCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM wallpaper w
                JOIN category selected ON selected.id = w.category_id
                WHERE selected.id = ? OR selected.parent_id = ?
                """,
                Long.class,
                categoryId,
                categoryId);
        if ((childCount != null && childCount > 0) || (wallpaperCount != null && wallpaperCount > 0)) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RESOURCE_IN_USE",
                    "The category is still referenced by children or wallpapers");
        }
        int updated = jdbc.update(
                """
                UPDATE category
                SET deleted_at = UTC_TIMESTAMP(6), lock_version = lock_version + 1
                WHERE id = ? AND deleted_at IS NULL AND lock_version = ?
                """,
                existing.id(),
                expectedVersion);
        if (updated == 0) {
            throw versionConflict();
        }
    }

    CategoryRow require(long categoryId, boolean includeDeleted) {
        List<CategoryRow> rows = jdbc.query(
                """
                SELECT c.id, c.parent_id, c.level, c.name, c.slug, c.icon_asset_id, c.sort_order,
                       c.deleted_at, c.created_at, c.updated_at, c.lock_version,
                       CASE WHEN c.level = 1 THEN (
                           SELECT COUNT(*) FROM wallpaper w
                           LEFT JOIN category selected ON selected.id = w.category_id
                           WHERE selected.id = c.id OR selected.parent_id = c.id
                       ) ELSE (SELECT COUNT(*) FROM wallpaper w WHERE w.category_id = c.id) END AS wallpaper_count
                FROM category c
                WHERE c.id = ? AND (? OR c.deleted_at IS NULL)
                """,
                this::mapRow,
                categoryId,
                includeDeleted);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "The category does not exist");
        }
        return rows.get(0);
    }

    CategoryRow requireForUse(long categoryId) {
        List<Long> locked = jdbc.queryForList(
                "SELECT id FROM category WHERE id = ? AND deleted_at IS NULL FOR SHARE",
                Long.class,
                categoryId);
        if (locked.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CATEGORY_NOT_FOUND", "The category does not exist");
        }
        return require(categoryId, false);
    }

    private CategoryShape validateShape(CategoryWriteRequest request, CategoryRow existing) {
        Long parentId = request.parentId() == null ? null : Ids.parse(request.parentId(), "parentId");
        Long iconAssetId = request.iconAssetId() == null ? null : Ids.parse(request.iconAssetId(), "iconAssetId");
        if (parentId == null) {
            if (iconAssetId == null) {
                throw domainViolation("A root category requires an icon asset");
            }
            AdminAssetView icon = assets.get(iconAssetId);
            if (!icon.validationStatus().equals("READY") || !icon.mimeType().startsWith("image/")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_NOT_READY", "The icon asset is not a ready image");
            }
            if (existing != null && existing.level() == 2) {
                throw domainViolation("A child category cannot be converted into a root category");
            }
            return new CategoryShape(null, 1, iconAssetId);
        }
        if (iconAssetId != null) {
            throw domainViolation("A child category cannot have its own icon asset");
        }
        CategoryRow parent = requireForUse(parentId);
        if (parent.level() != 1) {
            throw domainViolation("A child category must reference a root category");
        }
        if (existing != null && existing.level() == 1) {
            throw domainViolation("A root category cannot be converted into a child category");
        }
        return new CategoryShape(parentId, 2, null);
    }

    private CategoryRow mapRow(java.sql.ResultSet resultSet, int rowNumber) throws java.sql.SQLException {
        return new CategoryRow(
                resultSet.getLong("id"),
                (Long) resultSet.getObject("parent_id"),
                resultSet.getInt("level"),
                resultSet.getString("name"),
                resultSet.getString("slug"),
                (Long) resultSet.getObject("icon_asset_id"),
                resultSet.getInt("sort_order"),
                resultSet.getTimestamp("deleted_at"),
                resultSet.getTimestamp("created_at"),
                resultSet.getTimestamp("updated_at"),
                resultSet.getLong("lock_version"),
                resultSet.getLong("wallpaper_count"));
    }

    private AdminCategory view(CategoryRow row, List<AdminCategory> children) {
        return new AdminCategory(
                Long.toString(row.id()),
                row.parentId() == null ? null : Long.toString(row.parentId()),
                row.level(),
                row.name(),
                row.slug(),
                row.iconAssetId() == null ? null : assets.get(row.iconAssetId()),
                row.sortOrder(),
                row.wallpaperCount(),
                row.deletedAt() == null ? null : row.deletedAt().toInstant(),
                List.copyOf(children),
                row.createdAt().toInstant(),
                row.updatedAt().toInstant(),
                row.lockVersion());
    }

    private AdminCategory copyWithChildren(AdminCategory root, List<AdminCategory> children) {
        return new AdminCategory(
                root.id(), root.parentId(), root.level(), root.name(), root.slug(), root.icon(),
                root.sortOrder(), root.wallpaperCount(), root.deletedAt(), List.copyOf(children),
                root.createdAt(), root.updatedAt(), root.version());
    }

    private static void nullableLong(PreparedStatement statement, int parameter, Long value)
            throws java.sql.SQLException {
        if (value == null) {
            statement.setNull(parameter, java.sql.Types.BIGINT);
        } else {
            statement.setLong(parameter, value);
        }
    }

    private ApiException categoryConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        String code = message.contains("uk_category_slug") ? "DUPLICATE_SLUG" : "DUPLICATE_CATEGORY_NAME";
        return new ApiException(HttpStatus.CONFLICT, code, "A category with the same slug or sibling name already exists");
    }

    private ApiException versionConflict() {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT", "The category version has changed");
    }

    private ApiException domainViolation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", message);
    }

    record CategoryRow(
            long id,
            Long parentId,
            int level,
            String name,
            String slug,
            Long iconAssetId,
            int sortOrder,
            Timestamp deletedAt,
            Timestamp createdAt,
            Timestamp updatedAt,
            long lockVersion,
            long wallpaperCount) {
    }

    private record CategoryShape(Long parentId, int level, Long iconAssetId) {
    }
}
