package com.qingjing.wallpaper.asset;

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
public class PublicAssetService {

    private final JdbcTemplate jdbc;
    private final FileStorage fileStorage;

    public PublicAssetService(JdbcTemplate jdbc, FileStorage fileStorage) {
        this.jdbc = jdbc;
        this.fileStorage = fileStorage;
    }

    @Transactional(readOnly = true)
    public PublicAssetContent content(long assetId) {
        List<PublicAssetContent> rows = jdbc.query(
                """
                SELECT asset.storage_key, asset.mime_type, asset.size_bytes, asset.sha256
                FROM asset
                WHERE asset.id = ?
                  AND asset.deleted_at IS NULL
                  AND asset.validation_status = 'READY'
                  AND (
                    EXISTS (
                      SELECT 1
                      FROM wallpaper
                      WHERE wallpaper.cover_asset_id = asset.id
                        AND wallpaper.status = 'PUBLISHED'
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM category root
                      JOIN category selected
                        ON (selected.id = root.id OR selected.parent_id = root.id)
                       AND selected.deleted_at IS NULL
                      JOIN wallpaper
                        ON wallpaper.category_id = selected.id
                       AND wallpaper.status = 'PUBLISHED'
                      WHERE root.icon_asset_id = asset.id
                        AND root.level = 1
                        AND root.deleted_at IS NULL
                    )
                  )
                """,
                (resultSet, rowNumber) -> new PublicAssetContent(
                        new StorageKey(resultSet.getString("storage_key")),
                        resultSet.getString("mime_type"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("sha256")),
                assetId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "The asset content is unavailable");
        }
        return rows.get(0);
    }

    public StoredContent open(PublicAssetContent descriptor) {
        return fileStorage.open(descriptor.storageKey());
    }

    public record PublicAssetContent(StorageKey storageKey, String mimeType, long sizeBytes, String sha256) {
    }
}
