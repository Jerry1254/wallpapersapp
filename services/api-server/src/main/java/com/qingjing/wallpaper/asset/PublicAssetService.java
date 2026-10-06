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
    public PublicAssetContent content(long assetId) { return content(assetId,0); }

    @Transactional(readOnly = true)
    public PublicAssetContent content(long assetId,long deviceId) {
        boolean offline=new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).isOfflineDevice(deviceId);
        if(!offline && Long.valueOf(1).equals(jdbc.queryForObject("""
            SELECT COUNT(*)>0 FROM wallpaper w WHERE w.status='PUBLISHED' AND w.offline_promotion_only=TRUE
              AND (w.cover_asset_id=? OR EXISTS(SELECT 1 FROM wallpaper_variant v JOIN resource_version rv ON rv.variant_id=v.id
                    JOIN resource_binding rb ON rb.resource_version_id=rv.id WHERE v.wallpaper_id=w.id AND rb.asset_id=?))
            """,Long.class,assetId,assetId)))
            throw new ApiException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND","The asset content is unavailable");
        // Legacy asset URLs must not bypass paid-wallpaper preview policy.
        var protectedWallpapers=jdbc.query("""
            SELECT marked.id,
                EXISTS (
                    SELECT 1 FROM wallpaper clean
                    WHERE clean.cover_asset_id=marked.cover_asset_id AND clean.status='PUBLISHED'
                      AND (clean.access_type='FREE' OR clean.preview_watermark_enabled=FALSE)
                ) AS clean_alias,
                EXISTS (
                    SELECT 1 FROM category root
                    JOIN category selected ON (selected.id=root.id OR selected.parent_id=root.id)
                      AND selected.deleted_at IS NULL
                    JOIN wallpaper published ON published.category_id=selected.id AND published.status='PUBLISHED'
                    WHERE root.icon_asset_id=marked.cover_asset_id AND root.level=1 AND root.deleted_at IS NULL
                ) AS icon_alias
            FROM wallpaper marked
            WHERE marked.cover_asset_id=? AND marked.status='PUBLISHED'
              AND marked.access_type='REDEEM' AND marked.preview_watermark_enabled=TRUE
            ORDER BY marked.id
            """,(rs,n)->new ProtectedAlias(rs.getLong("id"),rs.getBoolean("clean_alias"),rs.getBoolean("icon_alias")),assetId);
        if(!protectedWallpapers.isEmpty()) {
            if(protectedWallpapers.stream().anyMatch(alias->alias.clean() || alias.icon()))
                throw new ApiException(HttpStatus.NOT_FOUND,"ASSET_NOT_FOUND","The asset content is unavailable");
            return previewCover(protectedWallpapers.get(0).wallpaper(),deviceId);
        }
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
                        AND wallpaper.status = 'PUBLISHED' AND (wallpaper.offline_promotion_only=FALSE OR ?=TRUE)
                    )
                    OR EXISTS (
                      SELECT 1
                      FROM category root
                      JOIN category selected
                        ON (selected.id = root.id OR selected.parent_id = root.id)
                       AND selected.deleted_at IS NULL
                      JOIN wallpaper
                        ON wallpaper.category_id = selected.id
                       AND wallpaper.status = 'PUBLISHED' AND (wallpaper.offline_promotion_only=FALSE OR ?=TRUE)
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
                assetId,offline,offline);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ASSET_NOT_FOUND", "The asset content is unavailable");
        }
        return rows.get(0);
    }

    @Transactional(readOnly=true)
    public PublicAssetContent previewCover(long wallpaperId) { return previewCover(wallpaperId,0); }

    @Transactional(readOnly=true)
    public PublicAssetContent previewCover(long wallpaperId,long deviceId) {
        new com.qingjing.wallpaper.catalog.WallpaperChannelAccess(jdbc).requireVisible(wallpaperId,deviceId);
        var rows=jdbc.query("""
            SELECT ps.status,ps.requested_revision,ps.generated_revision,ps.cover_storage_key,
                   ps.cover_size_bytes,ps.cover_sha256,ps.cover_mime_type
            FROM wallpaper w LEFT JOIN wallpaper_preview_state ps ON ps.wallpaper_id=w.id
            WHERE w.id=? AND w.status='PUBLISHED'
            """,(rs,n)-> {
                if(!"READY".equals(rs.getString("status")) || rs.getLong("requested_revision")!=rs.getLong("generated_revision")) {
                    boolean failed="FAILED".equals(rs.getString("status"));
                    throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,failed?"PREVIEW_GENERATION_FAILED":"PREVIEW_PROCESSING","Preview cover is not ready");
                }
                return new PublicAssetContent(new StorageKey(rs.getString("cover_storage_key")),rs.getString("cover_mime_type"),
                    rs.getLong("cover_size_bytes"),rs.getString("cover_sha256"));
            },wallpaperId);
        if(rows.size()!=1) throw new ApiException(HttpStatus.NOT_FOUND,"WALLPAPER_NOT_FOUND","Wallpaper is unavailable");
        return rows.get(0);
    }

    public StoredContent open(PublicAssetContent descriptor) {
        return fileStorage.open(descriptor.storageKey());
    }

    public record PublicAssetContent(StorageKey storageKey, String mimeType, long sizeBytes, String sha256) {
    }
    private record ProtectedAlias(long wallpaper,boolean clean,boolean icon) { }
}
