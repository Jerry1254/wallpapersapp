package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.asset.application.AssetPurpose;
import com.qingjing.wallpaper.asset.application.AssetUploadService;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.asset.application.ValidatedAsset;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.InputStream;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

@Service
public class AdminAssetService {

    private final JdbcTemplate jdbc;
    private final AssetUploadService uploads;
    private final FileStorage fileStorage;

    public AdminAssetService(JdbcTemplate jdbc, AssetUploadService uploads, FileStorage fileStorage) {
        this.jdbc = jdbc;
        this.uploads = uploads;
        this.fileStorage = fileStorage;
    }

    public AdminAssetView upload(
            InputStream source,
            String originalFilename,
            String declaredContentType,
            AssetPurpose purpose,
            long adminId) {
        ValidatedAsset asset = uploads.upload(source, originalFilename, declaredContentType, purpose);
        long assetId;
        try {
            KeyHolder keyHolder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO asset
                            (storage_key, original_filename, mime_type, file_extension, size_bytes,
                             sha256, width_px, height_px, validation_status, created_by_admin_id)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'READY', ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, asset.storageKey().value());
                statement.setString(2, asset.originalFilename());
                statement.setString(3, asset.mimeType());
                statement.setString(4, asset.fileExtension());
                statement.setLong(5, asset.sizeBytes());
                statement.setString(6, asset.sha256());
                if (asset.widthPixels() == null) {
                    statement.setNull(7, java.sql.Types.INTEGER);
                    statement.setNull(8, java.sql.Types.INTEGER);
                } else {
                    statement.setInt(7, asset.widthPixels());
                    statement.setInt(8, asset.heightPixels());
                }
                statement.setLong(9, adminId);
                return statement;
            }, keyHolder);
            Number key = keyHolder.getKey();
            if (key == null) {
                throw new IllegalStateException("Asset insert returned no identifier");
            }
            assetId = key.longValue();
        } catch (RuntimeException databaseFailure) {
            try {
                fileStorage.delete(asset.storageKey());
            } catch (RuntimeException cleanupFailure) {
                databaseFailure.addSuppressed(cleanupFailure);
            }
            throw databaseFailure;
        }
        return get(assetId);
    }

    public AdminAssetView get(long assetId) {
        List<AssetRow> rows = jdbc.query(
                """
                SELECT id, storage_key, original_filename, mime_type, file_extension, size_bytes,
                       sha256, width_px, height_px, duration_ms, validation_status,
                       validation_error_code, created_at, lock_version
                FROM asset
                WHERE id = ? AND deleted_at IS NULL
                """,
                (resultSet, rowNumber) -> new AssetRow(
                        resultSet.getLong("id"),
                        resultSet.getString("storage_key"),
                        resultSet.getString("original_filename"),
                        resultSet.getString("mime_type"),
                        resultSet.getString("file_extension"),
                        resultSet.getLong("size_bytes"),
                        resultSet.getString("sha256"),
                        (Integer) resultSet.getObject("width_px"),
                        (Integer) resultSet.getObject("height_px"),
                        (Long) resultSet.getObject("duration_ms"),
                        resultSet.getString("validation_status"),
                        resultSet.getString("validation_error_code"),
                        resultSet.getTimestamp("created_at"),
                        resultSet.getLong("lock_version")),
                assetId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "The asset does not exist");
        }
        return view(rows.get(0));
    }

    public AssetContentDescriptor content(long assetId) {
        List<AssetContentDescriptor> rows = jdbc.query(
                """
                SELECT storage_key, mime_type, size_bytes, sha256
                FROM asset
                WHERE id = ? AND deleted_at IS NULL AND validation_status = 'READY'
                """,
                (resultSet, rowNumber) -> new AssetContentDescriptor(
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

    public StoredContent open(AssetContentDescriptor descriptor) {
        return fileStorage.open(descriptor.storageKey());
    }

    private AdminAssetView view(AssetRow row) {
        return new AdminAssetView(
                Long.toString(row.id()),
                row.originalFilename(),
                row.mimeType(),
                row.fileExtension(),
                row.sizeBytes(),
                row.sha256(),
                row.widthPx(),
                row.heightPx(),
                row.durationMs(),
                row.validationStatus(),
                row.validationErrorCode(),
                "/api/v1/admin/assets/" + row.id() + "/content",
                row.createdAt().toInstant(),
                row.lockVersion());
    }

    private record AssetRow(
            long id,
            String storageKey,
            String originalFilename,
            String mimeType,
            String fileExtension,
            long sizeBytes,
            String sha256,
            Integer widthPx,
            Integer heightPx,
            Long durationMs,
            String validationStatus,
            String validationErrorCode,
            Timestamp createdAt,
            long lockVersion) {
    }

    public record AssetContentDescriptor(StorageKey storageKey, String mimeType, long sizeBytes, String sha256) {
    }
}
