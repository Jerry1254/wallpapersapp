package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.IosProductConfiguration;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IosProductService {
    private final JdbcTemplate jdbc;
    private final IosAcquisitionProperties properties;

    public IosProductService(JdbcTemplate jdbc, IosAcquisitionProperties properties) {
        this.jdbc = jdbc;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public IosProductConfiguration get(long wallpaperId) {
        ensureWallpaper(wallpaperId);
        List<IosProductConfiguration> rows = jdbc.query(
                """
                SELECT product_id,first_free_eligible,enabled,verified_transaction_at
                FROM ios_product_mapping WHERE wallpaper_id=? AND bundle_id=?
                """,
                (rs, row) -> configuration(
                        rs.getString("product_id"), rs.getBoolean("first_free_eligible"),
                        rs.getBoolean("enabled"), timestamp(rs.getTimestamp("verified_transaction_at"))),
                wallpaperId, properties.getBundleId());
        return rows.isEmpty() ? new IosProductConfiguration("", false, false, false, null) : rows.get(0);
    }

    @Transactional
    public IosProductConfiguration update(long wallpaperId, UpdateIosProductRequest request) {
        ensureWallpaper(wallpaperId);
        List<String> locked = jdbc.query(
                """
                SELECT product_id FROM ios_product_mapping
                WHERE wallpaper_id=? AND bundle_id=? AND verified_transaction_at IS NOT NULL FOR UPDATE
                """,
                (rs, row) -> rs.getString(1), wallpaperId, properties.getBundleId());
        if (!locked.isEmpty() && !locked.get(0).equals(request.productId())) {
            throw new ApiException(HttpStatus.CONFLICT, "IOS_PRODUCT_ID_LOCKED",
                    "The product id cannot change after a verified transaction");
        }
        jdbc.update(
                """
                INSERT INTO ios_product_mapping
                    (wallpaper_id,bundle_id,product_id,first_free_eligible,enabled)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE product_id=VALUES(product_id),
                    first_free_eligible=VALUES(first_free_eligible),enabled=VALUES(enabled)
                """,
                wallpaperId, properties.getBundleId(), request.productId().strip(),
                request.firstFreeEligible(), request.enabled());
        return get(wallpaperId);
    }

    private IosProductConfiguration configuration(
            String productId, boolean firstFreeEligible, boolean enabled, Instant verifiedAt) {
        return new IosProductConfiguration(productId, firstFreeEligible, enabled, verifiedAt != null, verifiedAt);
    }

    private void ensureWallpaper(long wallpaperId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM wallpaper WHERE id=?", Long.class, wallpaperId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
    }

    private Instant timestamp(Timestamp value) { return value == null ? null : value.toInstant(); }
}
