package com.qingjing.wallpaper.tutorial;

import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.PublicTutorialVideo;
import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.PublicWallpaperTutorial;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class PublicWallpaperTutorialService {

    private final JdbcTemplate jdbc;
    private final FileStorage fileStorage;

    PublicWallpaperTutorialService(JdbcTemplate jdbc, FileStorage fileStorage) {
        this.jdbc = jdbc;
        this.fileStorage = fileStorage;
    }

    @Transactional(readOnly = true)
    List<PublicWallpaperTutorial> list() {
        return jdbc.query(
                """
                SELECT t.tutorial_key, t.sort_order, t.lock_version, t.updated_at,
                       a.mime_type, a.size_bytes, a.duration_ms
                FROM wallpaper_setting_tutorial t
                JOIN asset a ON a.id = t.video_asset_id
                WHERE t.enabled = TRUE
                  AND a.deleted_at IS NULL
                  AND a.validation_status = 'READY'
                  AND a.purpose = 'TUTORIAL_VIDEO'
                  AND a.mime_type = 'video/mp4'
                ORDER BY t.sort_order, t.tutorial_key
                """,
                (resultSet, rowNumber) -> {
                    String key = resultSet.getString("tutorial_key");
                    long version = resultSet.getLong("lock_version");
                    WallpaperTutorialDefinition definition = WallpaperTutorialDefinition.require(key);
                    return new PublicWallpaperTutorial(
                            key,
                            definition.title(),
                            definition.platform(),
                            definition.wallpaperKind(),
                            resultSet.getInt("sort_order"),
                            new PublicTutorialVideo(
                                    "/api/v1/public/wallpaper-tutorials/" + key + "/video?v=" + version,
                                    resultSet.getString("mime_type"),
                                    resultSet.getLong("size_bytes"),
                                    (Long) resultSet.getObject("duration_ms")),
                            resultSet.getTimestamp("updated_at").toInstant(),
                            version);
                });
    }

    @Transactional(readOnly = true)
    TutorialContent content(String tutorialKey) {
        WallpaperTutorialDefinition definition = WallpaperTutorialDefinition.require(tutorialKey);
        List<TutorialContent> rows = jdbc.query(
                """
                SELECT a.storage_key, a.size_bytes, a.sha256, t.lock_version
                FROM wallpaper_setting_tutorial t
                JOIN asset a ON a.id = t.video_asset_id
                WHERE t.tutorial_key = ?
                  AND t.enabled = TRUE
                  AND a.deleted_at IS NULL
                  AND a.validation_status = 'READY'
                  AND a.purpose = 'TUTORIAL_VIDEO'
                  AND a.mime_type = 'video/mp4'
                """,
                (resultSet, rowNumber) -> new TutorialContent(
                        new StorageKey(resultSet.getString("storage_key")),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("sha256"),
                        resultSet.getLong("lock_version")),
                definition.key());
        if (rows.isEmpty()) {
            throw notFound();
        }
        return rows.get(0);
    }

    StoredContent open(TutorialContent content) {
        return fileStorage.open(content.storageKey());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "TUTORIAL_NOT_FOUND", "The wallpaper tutorial is unavailable");
    }

    record TutorialContent(StorageKey storageKey, long sizeBytes, String sha256, long version) {
    }
}
