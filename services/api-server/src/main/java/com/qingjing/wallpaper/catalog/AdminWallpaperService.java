package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminResourceVersion;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperDetail;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperPage;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperSummary;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperVariant;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AssetRole;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CreateResourceBindingRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CreateResourceVersionRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.DeliveryPlatform;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.PageMetadata;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.PublishWallpaperRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.ResourceType;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.VariantWriteRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperStatus;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperWriteRequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.AdminAssetService;
import com.qingjing.wallpaper.asset.AdminAssetView;
import com.qingjing.wallpaper.catalog.AdminCategoryService.CategoryRow;
import com.qingjing.wallpaper.catalog.AdminContentViewReader.ResourceVersionRow;
import com.qingjing.wallpaper.catalog.AdminContentViewReader.VariantRow;
import com.qingjing.wallpaper.catalog.AdminContentViewReader.WallpaperRow;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminWallpaperService {

    private static final Set<AssetRole> OPTIONAL_RESOURCE_ROLES = Set.of(AssetRole.COVER);

    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final AdminContentViewReader views;
    private final AdminCategoryService categories;
    private final AdminAssetService assets;
    private final ObjectMapper objectMapper;

    public AdminWallpaperService(
            JdbcTemplate jdbc,
            AdminContentViewReader views,
            AdminCategoryService categories,
            AdminAssetService assets,
            ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.namedJdbc = new NamedParameterJdbcTemplate(jdbc);
        this.views = views;
        this.categories = categories;
        this.assets = assets;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public AdminWallpaperPage list(
            int page,
            int pageSize,
            WallpaperStatus status,
            WallpaperAccessType accessType,
            String categoryId,
            String query) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw validation("page and pageSize are outside the accepted range");
        }
        String normalizedQuery = query == null ? null : query.strip();
        if (normalizedQuery != null && normalizedQuery.length() > 40) {
            throw validation("q must contain no more than 40 characters");
        }
        if (normalizedQuery != null && normalizedQuery.isEmpty()) {
            normalizedQuery = null;
        }

        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        MapSqlParameterSource parameters = new MapSqlParameterSource();
        if (status != null) {
            where.append(" AND w.status = :status");
            parameters.addValue("status", status.name());
        }
        if (accessType != null) {
            where.append(" AND w.access_type = :accessType");
            parameters.addValue("accessType", accessType.name());
        }
        if (categoryId != null) {
            long parsedCategory = Ids.parse(categoryId, "categoryId");
            where.append(" AND (selected.id = :categoryId OR selected.parent_id = :categoryId)");
            parameters.addValue("categoryId", parsedCategory);
        }
        if (normalizedQuery != null) {
            where.append(" AND w.title LIKE :query ESCAPE '\\\\'");
            parameters.addValue("query", "%" + escapeLike(normalizedQuery) + "%");
        }

        String from = " FROM wallpaper w JOIN category selected ON selected.id = w.category_id";
        Long total = namedJdbc.queryForObject("SELECT COUNT(*)" + from + where, parameters, Long.class);
        parameters.addValue("limit", pageSize).addValue("offset", (long) (page - 1) * pageSize);
        List<Long> ids = namedJdbc.queryForList(
                "SELECT w.id" + from + where + " ORDER BY w.updated_at DESC, w.id DESC LIMIT :limit OFFSET :offset",
                parameters,
                Long.class);
        List<AdminWallpaperSummary> items = ids.stream()
                .map(views::wallpaperRow)
                .map(views::summary)
                .toList();
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new AdminWallpaperPage(items, new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public AdminWallpaperDetail get(long wallpaperId) {
        return views.wallpaper(wallpaperId);
    }

    @Transactional
    public AdminWallpaperDetail create(WallpaperWriteRequest request) {
        WriteShape shape = validateWrite(request, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> wallpaperInsert(connection, request, shape), keyHolder);
        } catch (DataIntegrityViolationException exception) {
            throw wallpaperConflict(exception);
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Wallpaper insert returned no identifier");
        }
        return views.wallpaper(key.longValue());
    }

    @Transactional
    public AdminWallpaperDetail update(long wallpaperId, long expectedVersion, WallpaperWriteRequest request) {
        WallpaperRow existing = views.wallpaperRow(wallpaperId);
        if (existing.status().equals("ARCHIVED")) {
            throw stateConflict("An archived wallpaper cannot be edited");
        }
        WriteShape shape = validateWrite(request, existing);
        try {
            int updated = jdbc.update(
                    """
                    UPDATE wallpaper
                    SET title = ?, slug = ?, access_type = ?, category_id = ?, cover_asset_id = ?,
                        featured_rank = ?, sort_order = ?, copyright_note = ?, lock_version = lock_version + 1
                    WHERE id = ? AND lock_version = ?
                    """,
                    request.title().strip(),
                    request.slug(),
                    accessType(request).name(),
                    shape.selectedCategoryId(),
                    shape.coverAssetId(),
                    request.featuredRank(),
                    request.sortOrder(),
                    request.copyrightNote().strip(),
                    wallpaperId,
                    expectedVersion);
            if (updated == 0) {
                throw versionConflict("The wallpaper version has changed");
            }
        } catch (DataIntegrityViolationException exception) {
            throw wallpaperConflict(exception);
        }
        return views.wallpaper(wallpaperId);
    }

    @Transactional
    public void deleteDraft(long wallpaperId, long expectedVersion) {
        WallpaperRow wallpaper = lockWallpaper(wallpaperId, expectedVersion);
        if (!wallpaper.status().equals("DRAFT")) {
            throw stateConflict("Only a draft wallpaper can be deleted");
        }
        Long versionCount = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM resource_version rv
                JOIN wallpaper_variant v ON v.id = rv.variant_id
                WHERE v.wallpaper_id = ?
                """,
                Long.class,
                wallpaperId);
        if (versionCount != null && versionCount > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RESOURCE_IN_USE",
                    "A draft with resource version history cannot be deleted");
        }
        jdbc.update("DELETE FROM wallpaper WHERE id = ? AND lock_version = ?", wallpaperId, expectedVersion);
    }

    @Transactional
    public AdminWallpaperVariant createVariant(
            long wallpaperId,
            long expectedWallpaperVersion,
            VariantWriteRequest request) {
        WallpaperRow wallpaper = lockWallpaper(wallpaperId, expectedWallpaperVersion);
        if (wallpaper.status().equals("ARCHIVED")) {
            throw stateConflict("An archived wallpaper cannot receive variants");
        }
        validateVariantPair(request.platform(), request.resourceType());
        List<String> requestedCapabilities = capabilities(request);
        ensureUniqueStrings(requestedCapabilities, "capabilityRequirements");
        String capabilities = writeJson(requestedCapabilities);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO wallpaper_variant
                            (wallpaper_id, platform, resource_type, minimum_os_version, capability_requirements, enabled)
                        VALUES (?, ?, ?, ?, CAST(? AS JSON), ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, wallpaperId);
                statement.setString(2, request.platform().name());
                statement.setString(3, request.resourceType().name());
                statement.setString(4, normalizedNullable(request.minimumOsVersion()));
                statement.setString(5, capabilities);
                statement.setBoolean(6, request.enabled());
                return statement;
            }, keyHolder);
            bumpWallpaper(wallpaperId, expectedWallpaperVersion);
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_VARIANT", "The wallpaper variant already exists");
        }
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("Variant insert returned no identifier");
        }
        return views.variant(key.longValue());
    }

    @Transactional
    public AdminWallpaperVariant updateVariant(
            long variantId,
            long expectedVersion,
            VariantWriteRequest request) {
        VariantRow variant = lockVariant(variantId, expectedVersion);
        WallpaperRow wallpaper = views.wallpaperRow(variant.wallpaperId());
        if (wallpaper.status().equals("ARCHIVED")) {
            throw stateConflict("An archived wallpaper variant cannot be edited");
        }
        Long versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_version WHERE variant_id = ?",
                Long.class,
                variantId);
        validateVariantPair(request.platform(), request.resourceType());
        if (versionCount != null && versionCount > 0
                && (!variant.platform().equals(request.platform().name())
                    || !variant.resourceType().equals(request.resourceType().name()))) {
            throw new ApiException(HttpStatus.CONFLICT, "RESOURCE_IN_USE",
                    "A variant with resource history cannot change its platform or resource type");
        }
        List<String> requestedCapabilities = capabilities(request);
        ensureUniqueStrings(requestedCapabilities, "capabilityRequirements");
        try {
            int updated = jdbc.update(
                    """
                    UPDATE wallpaper_variant
                    SET platform = ?, resource_type = ?, minimum_os_version = ?,
                        capability_requirements = CAST(? AS JSON), enabled = ?, lock_version = lock_version + 1
                    WHERE id = ? AND lock_version = ?
                    """,
                    request.platform().name(),
                    request.resourceType().name(),
                    normalizedNullable(request.minimumOsVersion()),
                    writeJson(requestedCapabilities),
                    request.enabled(),
                    variantId,
                    expectedVersion);
            if (updated == 0) {
                throw versionConflict("The variant version has changed");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_VARIANT", "The wallpaper variant already exists");
        }
        return views.variant(variantId);
    }

    @Transactional
    public void deleteVariant(long variantId, long expectedVersion) {
        VariantRow variant = lockVariant(variantId, expectedVersion);
        Long versionCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM resource_version WHERE variant_id = ?",
                Long.class,
                variantId);
        if (versionCount != null && versionCount > 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "RESOURCE_IN_USE",
                    "A variant with resource history cannot be deleted");
        }
        int deleted = jdbc.update(
                "DELETE FROM wallpaper_variant WHERE id = ? AND lock_version = ?",
                variantId,
                expectedVersion);
        if (deleted == 0) {
            throw versionConflict("The variant version has changed");
        }
        jdbc.update("UPDATE wallpaper SET lock_version = lock_version + 1 WHERE id = ?", variant.wallpaperId());
    }

    @Transactional
    public AdminResourceVersion createResourceVersion(
            long variantId,
            CreateResourceVersionRequest request,
            long adminId) {
        VariantRow variant = views.variantRow(variantId);
        jdbc.queryForObject("SELECT id FROM wallpaper_variant WHERE id = ? FOR UPDATE", Long.class, variantId);
        WallpaperRow wallpaper = views.wallpaperRow(variant.wallpaperId());
        if (wallpaper.status().equals("ARCHIVED")) {
            throw stateConflict("An archived wallpaper cannot receive resource versions");
        }
        List<ResolvedBinding> bindings = validateBindings(ResourceType.valueOf(variant.resourceType()), request.bindings());
        String manifest = request.manifestSha256() == null
                ? computeManifest(bindings)
                : request.manifestSha256();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO resource_version
                            (variant_id, version_no, status, manifest_sha256, created_by_admin_id)
                        VALUES (?, ?, 'READY', ?, ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setLong(1, variantId);
                statement.setInt(2, request.versionNo());
                statement.setString(3, manifest);
                statement.setLong(4, adminId);
                return statement;
            }, keyHolder);
            Number versionKey = keyHolder.getKey();
            if (versionKey == null) {
                throw new IllegalStateException("Resource version insert returned no identifier");
            }
            for (ResolvedBinding binding : bindings) {
                jdbc.update(
                        """
                        INSERT INTO resource_binding (resource_version_id, asset_id, role, ordinal)
                        VALUES (?, ?, ?, ?)
                        """,
                        versionKey.longValue(),
                        binding.assetId(),
                        binding.role().name(),
                        binding.ordinal());
            }
            return views.resourceVersion(versionKey.longValue());
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", "The resource version or binding already exists");
        }
    }

    @Transactional(readOnly = true)
    public AdminResourceVersion getResourceVersion(long resourceVersionId) {
        return views.resourceVersion(resourceVersionId);
    }

    @Transactional
    public AdminWallpaperDetail publish(
            long wallpaperId,
            long expectedVersion,
            PublishWallpaperRequest request) {
        ensureUniqueStrings(request.resourceVersionIds(), "resourceVersionIds");
        WallpaperRow wallpaper = lockWallpaper(wallpaperId, expectedVersion);
        if (wallpaper.status().equals("ARCHIVED")) {
            throw stateConflict("An archived wallpaper cannot be published");
        }
        assets.get(wallpaper.coverAssetId());
        categories.require(wallpaper.categoryId(), false);

        Map<Long, ResourceVersionRow> versionsByVariant = new LinkedHashMap<>();
        for (String id : request.resourceVersionIds()) {
            ResourceVersionRow version = views.resourceVersionRow(Ids.parse(id, "resourceVersionIds"));
            VariantRow variant = views.variantRow(version.variantId());
            if (variant.wallpaperId() != wallpaperId) {
                throw domainViolation("Every selected resource version must belong to this wallpaper");
            }
            if (!variant.enabled()) {
                throw domainViolation("Disabled variants cannot be published");
            }
            if (!version.status().equals("READY") && !version.status().equals("PUBLISHED")) {
                throw new ApiException(
                        HttpStatus.UNPROCESSABLE_ENTITY,
                        "RESOURCE_VERSION_NOT_READY",
                        "Every selected resource version must be ready");
            }
            if (versionsByVariant.putIfAbsent(variant.id(), version) != null) {
                throw domainViolation("Only one resource version may be selected for each variant");
            }
            validateVariantPair(
                    DeliveryPlatform.valueOf(variant.platform()),
                    ResourceType.valueOf(variant.resourceType()));
        }
        if (versionsByVariant.isEmpty()) {
            throw domainViolation("At least one resource version must be selected");
        }

        jdbc.update(
                """
                UPDATE resource_version rv
                JOIN wallpaper_variant v ON v.id = rv.variant_id
                SET rv.status = 'RETIRED', rv.retired_at = UTC_TIMESTAMP(6), rv.lock_version = rv.lock_version + 1
                WHERE v.wallpaper_id = ? AND rv.status = 'PUBLISHED'
                """,
                wallpaperId);
        for (ResourceVersionRow version : versionsByVariant.values()) {
            int published = jdbc.update(
                    """
                    UPDATE resource_version
                    SET status = 'PUBLISHED', published_at = COALESCE(published_at, UTC_TIMESTAMP(6)),
                        retired_at = NULL, lock_version = lock_version + 1
                    WHERE id = ? AND status IN ('READY', 'RETIRED')
                    """,
                    version.id());
            if (published != 1) {
                throw new ApiException(
                        HttpStatus.CONFLICT,
                        "RESOURCE_VERSION_NOT_READY",
                        "A selected resource version changed during publication");
            }
        }
        int updated = jdbc.update(
                """
                UPDATE wallpaper
                SET status = 'PUBLISHED', published_at = COALESCE(published_at, UTC_TIMESTAMP(6)),
                    archived_at = NULL, lock_version = lock_version + 1
                WHERE id = ? AND lock_version = ?
                """,
                wallpaperId,
                expectedVersion);
        if (updated != 1) {
            throw versionConflict("The wallpaper version changed during publication");
        }
        return views.wallpaper(wallpaperId);
    }

    @Transactional
    public AdminWallpaperDetail offline(long wallpaperId, long expectedVersion) {
        WallpaperRow wallpaper = lockWallpaper(wallpaperId, expectedVersion);
        if (!wallpaper.status().equals("PUBLISHED")) {
            throw stateConflict("Only a published wallpaper can be taken offline");
        }
        int updated = jdbc.update(
                """
                UPDATE wallpaper
                SET status = 'OFFLINE', lock_version = lock_version + 1
                WHERE id = ? AND lock_version = ?
                """,
                wallpaperId,
                expectedVersion);
        if (updated != 1) {
            throw versionConflict("The wallpaper version changed while taking it offline");
        }
        return views.wallpaper(wallpaperId);
    }

    @Transactional
    public AdminWallpaperDetail archive(long wallpaperId, long expectedVersion) {
        WallpaperRow wallpaper = lockWallpaper(wallpaperId, expectedVersion);
        if (!wallpaper.status().equals("DRAFT") && !wallpaper.status().equals("OFFLINE")) {
            throw stateConflict("Only a draft or offline wallpaper can be archived");
        }
        jdbc.update(
                """
                UPDATE resource_version rv
                JOIN wallpaper_variant v ON v.id = rv.variant_id
                SET rv.status = 'RETIRED', rv.retired_at = UTC_TIMESTAMP(6), rv.lock_version = rv.lock_version + 1
                WHERE v.wallpaper_id = ? AND rv.status = 'PUBLISHED'
                """,
                wallpaperId);
        int updated = jdbc.update(
                """
                UPDATE wallpaper
                SET status = 'ARCHIVED', archived_at = UTC_TIMESTAMP(6), lock_version = lock_version + 1
                WHERE id = ? AND lock_version = ?
                """,
                wallpaperId,
                expectedVersion);
        if (updated != 1) {
            throw versionConflict("The wallpaper version changed during archival");
        }
        return views.wallpaper(wallpaperId);
    }

    private WriteShape validateWrite(WallpaperWriteRequest request, WallpaperRow existing) {
        long rootId = Ids.parse(request.rootCategoryId(), "rootCategoryId");
        CategoryRow root = categories.requireForUse(rootId);
        if (root.level() != 1) {
            throw domainViolation("rootCategoryId must reference a root category");
        }
        long selectedCategory = rootId;
        if (request.childCategoryId() != null) {
            long childId = Ids.parse(request.childCategoryId(), "childCategoryId");
            CategoryRow child = categories.requireForUse(childId);
            if (child.level() != 2 || child.parentId() == null || child.parentId() != rootId) {
                throw domainViolation("childCategoryId must be a direct child of rootCategoryId");
            }
            selectedCategory = childId;
        }
        long coverId = Ids.parse(request.coverAssetId(), "coverAssetId");
        AdminAssetView cover = assets.get(coverId);
        if (!cover.validationStatus().equals("READY") || !cover.mimeType().startsWith("image/")) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_NOT_READY", "The cover asset is not a ready image");
        }
        return new WriteShape(selectedCategory, coverId);
    }

    private List<ResolvedBinding> validateBindings(
            ResourceType resourceType,
            List<CreateResourceBindingRequest> requests) {
        Set<String> bindingKeys = new HashSet<>();
        List<ResolvedBinding> resolved = new ArrayList<>();
        for (CreateResourceBindingRequest request : requests) {
            String bindingKey = request.role().name() + ":" + request.ordinal();
            if (!bindingKeys.add(bindingKey)) {
                throw domainViolation("Resource binding role and ordinal pairs must be unique");
            }
            long assetId = Ids.parse(request.assetId(), "bindings.assetId");
            AdminAssetView asset = assets.get(assetId);
            if (!asset.validationStatus().equals("READY")) {
                throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_NOT_READY", "Every bound asset must be ready");
            }
            validateAssetRoleType(request.role(), asset.mimeType());
            resolved.add(new ResolvedBinding(assetId, request.role(), request.ordinal(), asset.sha256()));
        }

        Set<AssetRole> required = requiredRoles(resourceType);
        Map<AssetRole, Long> counts = resolved.stream()
                .collect(java.util.stream.Collectors.groupingBy(ResolvedBinding::role, java.util.stream.Collectors.counting()));
        for (AssetRole role : required) {
            if (resourceType == ResourceType.LAYER_PARALLAX && role == AssetRole.FOREGROUND) {
                long count = counts.getOrDefault(role, 0L);
                if (count < 1 || count > 11) {
                    throw domainViolation("Parallax requires between one and eleven foreground bindings");
                }
                for (int ordinal = 0; ordinal < count; ordinal++) {
                    int expectedOrdinal = ordinal;
                    if (resolved.stream().noneMatch(binding -> binding.role() == role && binding.ordinal() == expectedOrdinal)) {
                        throw domainViolation("Parallax foreground ordinals must be continuous starting at zero");
                    }
                }
                continue;
            }
            if (counts.getOrDefault(role, 0L) != 1
                    || resolved.stream().noneMatch(binding -> binding.role() == role && binding.ordinal() == 0)) {
                throw domainViolation("The resource version does not contain exactly one ordinal-zero " + role + " binding");
            }
        }
        Set<AssetRole> permitted = new HashSet<>(required);
        permitted.addAll(OPTIONAL_RESOURCE_ROLES);
        if (resolved.stream().anyMatch(binding -> !permitted.contains(binding.role()))) {
            throw domainViolation("The resource version contains a role that is incompatible with its resource type");
        }
        return resolved;
    }

    private void validateAssetRoleType(AssetRole role, String mimeType) {
        boolean accepted = switch (role) {
            case COVER, BACKGROUND, STATIC_IMAGE -> mimeType.startsWith("image/");
            case FOREGROUND -> mimeType.equals("image/png") || mimeType.equals("image/webp");
            case LIVE_PHOTO_IMAGE -> mimeType.equals("image/jpeg");
            case PARALLAX_CONFIG -> mimeType.equals("application/json");
            case VIDEO, LIVE_PHOTO_VIDEO -> mimeType.startsWith("video/");
            case THEME_PACKAGE -> mimeType.equals("application/zip");
        };
        if (!accepted) {
            throw domainViolation("The bound asset type is incompatible with role " + role);
        }
    }

    private Set<AssetRole> requiredRoles(ResourceType resourceType) {
        return switch (resourceType) {
            case LAYER_PARALLAX -> Set.of(AssetRole.BACKGROUND, AssetRole.FOREGROUND, AssetRole.PARALLAX_CONFIG);
            case VIDEO -> Set.of(AssetRole.VIDEO);
            case LIVE_PHOTO -> Set.of(AssetRole.LIVE_PHOTO_IMAGE, AssetRole.LIVE_PHOTO_VIDEO);
            case STATIC_IMAGE -> Set.of(AssetRole.STATIC_IMAGE);
            case THEME_PACKAGE -> Set.of(AssetRole.THEME_PACKAGE);
        };
    }

    private void validateVariantPair(DeliveryPlatform platform, ResourceType resourceType) {
        boolean accepted = (platform == DeliveryPlatform.ANDROID
                && (resourceType == ResourceType.LAYER_PARALLAX || resourceType == ResourceType.VIDEO))
                || (platform == DeliveryPlatform.IOS && resourceType == ResourceType.LIVE_PHOTO)
                || (platform == DeliveryPlatform.HARMONYOS && resourceType == ResourceType.THEME_PACKAGE)
                || (platform == DeliveryPlatform.UNIVERSAL && resourceType == ResourceType.STATIC_IMAGE);
        if (!accepted) {
            throw domainViolation("The delivery platform and resource type pair is not supported");
        }
    }

    private WallpaperRow lockWallpaper(long wallpaperId, long expectedVersion) {
        WallpaperRow wallpaper = views.wallpaperRow(wallpaperId);
        Long currentVersion = jdbc.queryForObject(
                "SELECT lock_version FROM wallpaper WHERE id = ? FOR UPDATE",
                Long.class,
                wallpaperId);
        if (currentVersion == null || currentVersion != expectedVersion) {
            throw versionConflict("The wallpaper version has changed");
        }
        return wallpaper;
    }

    private VariantRow lockVariant(long variantId, long expectedVersion) {
        VariantRow variant = views.variantRow(variantId);
        Long currentVersion = jdbc.queryForObject(
                "SELECT lock_version FROM wallpaper_variant WHERE id = ? FOR UPDATE",
                Long.class,
                variantId);
        if (currentVersion == null || currentVersion != expectedVersion) {
            throw versionConflict("The variant version has changed");
        }
        return variant;
    }

    private void bumpWallpaper(long wallpaperId, long expectedVersion) {
        int updated = jdbc.update(
                "UPDATE wallpaper SET lock_version = lock_version + 1 WHERE id = ? AND lock_version = ?",
                wallpaperId,
                expectedVersion);
        if (updated != 1) {
            throw versionConflict("The wallpaper version has changed");
        }
    }

    private PreparedStatement wallpaperInsert(
            java.sql.Connection connection,
            WallpaperWriteRequest request,
            WriteShape shape) throws java.sql.SQLException {
        PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO wallpaper
                    (title, slug, access_type, category_id, cover_asset_id, featured_rank,
                     sort_order, copyright_note, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT')
                """,
                Statement.RETURN_GENERATED_KEYS);
        statement.setString(1, request.title().strip());
        statement.setString(2, request.slug());
        statement.setString(3, accessType(request).name());
        statement.setLong(4, shape.selectedCategoryId());
        statement.setLong(5, shape.coverAssetId());
        if (request.featuredRank() == null) {
            statement.setNull(6, java.sql.Types.INTEGER);
        } else {
            statement.setInt(6, request.featuredRank());
        }
        statement.setInt(7, request.sortOrder());
        statement.setString(8, request.copyrightNote().strip());
        return statement;
    }

    private WallpaperAccessType accessType(WallpaperWriteRequest request) {
        return request.accessType() == null ? WallpaperAccessType.REDEEM : request.accessType();
    }

    private String computeManifest(List<ResolvedBinding> bindings) {
        String canonical = bindings.stream()
                .sorted(Comparator.comparing((ResolvedBinding binding) -> binding.role().name())
                        .thenComparingInt(ResolvedBinding::ordinal)
                        .thenComparingLong(ResolvedBinding::assetId))
                .map(binding -> binding.role() + ":" + binding.ordinal() + ":" + binding.assetId() + ":" + binding.sha256())
                .collect(java.util.stream.Collectors.joining("\n"));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Capability requirements cannot be serialized", exception);
        }
    }

    private void ensureUniqueStrings(List<String> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw validation(field + " must not contain duplicate values");
        }
    }

    private String normalizedNullable(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    private List<String> capabilities(VariantWriteRequest request) {
        return request.capabilityRequirements() == null ? List.of() : request.capabilityRequirements();
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private ApiException wallpaperConflict(DataIntegrityViolationException exception) {
        String message = exception.getMostSpecificCause().getMessage();
        String code = message.contains("uk_wallpaper_slug") ? "DUPLICATE_SLUG" : "STATE_CONFLICT";
        return new ApiException(HttpStatus.CONFLICT, code, "The wallpaper conflicts with existing data");
    }

    private ApiException validation(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", message);
    }

    private ApiException domainViolation(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DOMAIN_RULE_VIOLATION", message);
    }

    private ApiException stateConflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "STATE_CONFLICT", message);
    }

    private ApiException versionConflict(String message) {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "VERSION_CONFLICT", message);
    }

    private record WriteShape(long selectedCategoryId, long coverAssetId) {
    }

    private record ResolvedBinding(long assetId, AssetRole role, int ordinal, String sha256) {
    }
}
