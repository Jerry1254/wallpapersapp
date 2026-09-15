package com.qingjing.wallpaper.tutorial;

import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.AdminTutorialVideo;
import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.AdminWallpaperTutorial;
import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.TutorialWriteRequest;

import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.Ids;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class AdminWallpaperTutorialService {

    private final JdbcTemplate jdbc;

    AdminWallpaperTutorialService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    List<AdminWallpaperTutorial> list() {
        return jdbc.query(
                """
                SELECT t.tutorial_key, t.video_asset_id, t.enabled, t.sort_order, t.lock_version, t.updated_at,
                       a.original_filename, a.mime_type, a.size_bytes, a.duration_ms, a.validation_status
                FROM wallpaper_setting_tutorial t
                LEFT JOIN asset a ON a.id = t.video_asset_id AND a.deleted_at IS NULL
                ORDER BY t.sort_order, t.tutorial_key
                """,
                (resultSet, rowNumber) -> view(new TutorialRow(
                        resultSet.getString("tutorial_key"),
                        (Long) resultSet.getObject("video_asset_id"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("lock_version"),
                        resultSet.getTimestamp("updated_at"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("mime_type"),
                        (Long) resultSet.getObject("size_bytes"),
                        (Long) resultSet.getObject("duration_ms"),
                        resultSet.getString("validation_status"))));
    }

    @Transactional
    UpdateResult update(String tutorialKey, long expectedVersion, TutorialWriteRequest request, long adminId) {
        WallpaperTutorialDefinition definition = WallpaperTutorialDefinition.require(tutorialKey);
        TutorialRow existing = requireForUpdate(definition.key());
        if (existing.version() != expectedVersion) {
            throw versionConflict();
        }

        long assetId = Ids.parse(request.videoAssetId(), "videoAssetId");
        requireTutorialVideo(assetId);
        int updated = jdbc.update(
                """
                UPDATE wallpaper_setting_tutorial
                SET video_asset_id = ?, enabled = ?, sort_order = ?, lock_version = lock_version + 1
                WHERE tutorial_key = ? AND lock_version = ?
                """,
                assetId,
                request.enabled(),
                request.sortOrder(),
                definition.key(),
                expectedVersion);
        if (updated == 0) {
            throw versionConflict();
        }

        AdminWallpaperTutorial current = get(definition.key());
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("tutorialKey", definition.key());
        summary.put("oldVideoAssetId", existing.videoAssetId());
        summary.put("newVideoAssetId", assetId);
        summary.put("oldEnabled", existing.enabled());
        summary.put("newEnabled", request.enabled());
        summary.put("adminId", adminId);
        return new UpdateResult(current, summary);
    }

    @Transactional(readOnly = true)
    AdminWallpaperTutorial get(String tutorialKey) {
        return list().stream()
                .filter(item -> item.key().equals(tutorialKey))
                .findFirst()
                .orElseThrow(() -> new ApiException(
                        HttpStatus.NOT_FOUND,
                        "TUTORIAL_NOT_FOUND",
                        "The wallpaper tutorial does not exist"));
    }

    private TutorialRow requireForUpdate(String tutorialKey) {
        List<TutorialRow> rows = jdbc.query(
                """
                SELECT tutorial_key, video_asset_id, enabled, sort_order, lock_version, updated_at,
                       NULL AS original_filename, NULL AS mime_type, NULL AS size_bytes,
                       NULL AS duration_ms, NULL AS validation_status
                FROM wallpaper_setting_tutorial
                WHERE tutorial_key = ?
                FOR UPDATE
                """,
                (resultSet, rowNumber) -> new TutorialRow(
                        resultSet.getString("tutorial_key"),
                        (Long) resultSet.getObject("video_asset_id"),
                        resultSet.getBoolean("enabled"),
                        resultSet.getInt("sort_order"),
                        resultSet.getLong("lock_version"),
                        resultSet.getTimestamp("updated_at"),
                        null, null, null, null, null),
                tutorialKey);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TUTORIAL_NOT_FOUND", "The wallpaper tutorial does not exist");
        }
        return rows.get(0);
    }

    private void requireTutorialVideo(long assetId) {
        List<AssetBinding> rows = jdbc.query(
                """
                SELECT purpose, mime_type, validation_status
                FROM asset
                WHERE id = ? AND deleted_at IS NULL
                """,
                (resultSet, rowNumber) -> new AssetBinding(
                        resultSet.getString("purpose"),
                        resultSet.getString("mime_type"),
                        resultSet.getString("validation_status")),
                assetId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "The tutorial video asset does not exist");
        }
        AssetBinding asset = rows.get(0);
        if (!"READY".equals(asset.validationStatus())) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_NOT_READY", "The tutorial video asset is not ready");
        }
        if (!"TUTORIAL_VIDEO".equals(asset.purpose()) || !"video/mp4".equals(asset.mimeType())) {
            throw new ApiException(
                    HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "UNSUPPORTED_MEDIA_TYPE",
                    "The selected asset is not a tutorial MP4 video");
        }
    }

    private AdminWallpaperTutorial view(TutorialRow row) {
        WallpaperTutorialDefinition definition = WallpaperTutorialDefinition.require(row.key());
        AdminTutorialVideo video = row.videoAssetId() == null || row.originalFilename() == null
                ? null
                : new AdminTutorialVideo(
                        Long.toString(row.videoAssetId()),
                        row.originalFilename(),
                        row.mimeType(),
                        row.sizeBytes(),
                        row.durationMs(),
                        row.validationStatus(),
                        "/api/v1/admin/assets/" + row.videoAssetId() + "/content");
        return new AdminWallpaperTutorial(
                definition.key(),
                definition.title(),
                definition.platform(),
                definition.wallpaperKind(),
                row.enabled(),
                row.sortOrder(),
                video,
                row.videoAssetId() == null && row.version() == 0 ? null : row.updatedAt().toInstant(),
                row.version());
    }

    private ApiException versionConflict() {
        return new ApiException(
                HttpStatus.PRECONDITION_FAILED,
                "VERSION_CONFLICT",
                "The wallpaper tutorial was changed by another request");
    }

    record UpdateResult(AdminWallpaperTutorial tutorial, Map<String, Object> auditSummary) {
    }

    private record AssetBinding(String purpose, String mimeType, String validationStatus) {
    }

    private record TutorialRow(
            String key,
            Long videoAssetId,
            boolean enabled,
            int sortOrder,
            long version,
            Timestamp updatedAt,
            String originalFilename,
            String mimeType,
            Long sizeBytes,
            Long durationMs,
            String validationStatus) {
    }
}
