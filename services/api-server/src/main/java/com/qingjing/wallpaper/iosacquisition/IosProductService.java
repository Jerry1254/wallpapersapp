package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.IosProductConfiguration;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.Product;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.math.BigDecimal;
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
                SELECT product_id,china_reference_price,first_free_eligible,enabled,verified_transaction_at
                FROM ios_product_mapping WHERE wallpaper_id=? AND bundle_id=?
                """,
                (rs, row) -> configuration(
                        rs.getString("product_id"), price(rs.getBigDecimal("china_reference_price")), rs.getBoolean("first_free_eligible"),
                        rs.getBoolean("enabled"), timestamp(rs.getTimestamp("verified_transaction_at"))),
                wallpaperId, properties.getBundleId());
        return rows.isEmpty() ? new IosProductConfiguration("", null, false, false, false, null) : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<Product> catalogue() {
        return jdbc.query(
                """
                SELECT CAST(m.wallpaper_id AS CHAR) wallpaper_id,m.product_id,m.china_reference_price
                FROM ios_product_mapping m JOIN wallpaper w ON w.id=m.wallpaper_id
                WHERE m.bundle_id=? AND m.enabled=TRUE AND w.status='PUBLISHED'
                  AND EXISTS (SELECT 1 FROM wallpaper_variant v JOIN resource_version rv ON rv.variant_id=v.id
                    WHERE v.wallpaper_id=w.id AND v.enabled=TRUE AND v.platform IN ('IOS','UNIVERSAL') AND rv.status='PUBLISHED')
                ORDER BY m.wallpaper_id
                """,
                (rs,row) -> new Product(rs.getString("wallpaper_id"), rs.getString("product_id"),
                        price(rs.getBigDecimal("china_reference_price"))), properties.getBundleId());
    }

    @Transactional
    public IosProductConfiguration update(long wallpaperId, UpdateIosProductRequest request) {
        ensureWallpaper(wallpaperId);
        jdbc.queryForObject("SELECT id FROM wallpaper WHERE id=? FOR UPDATE", Long.class, wallpaperId);
        IosProductConfiguration previous = get(wallpaperId);
        // Older admin clients omit this field. Preserve it only for the same
        // product, so re-binding cannot inherit another Apple's product price.
        String referencePrice = request.chinaReferencePrice() != null
                ? request.chinaReferencePrice()
                : (previous.productId().equals(request.productId()) ? previous.chinaReferencePrice() : null);
        List<Long> owner = jdbc.query("SELECT wallpaper_id FROM ios_product_mapping WHERE bundle_id=? AND product_id=? FOR UPDATE", (rs,n)->rs.getLong(1), properties.getBundleId(),request.productId());
        if (!owner.isEmpty() && owner.get(0) != wallpaperId) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "The product id is already mapped to another wallpaper");
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
                    (wallpaper_id,bundle_id,product_id,china_reference_price,first_free_eligible,enabled)
                VALUES (?,?,?,?,?,?)
                ON DUPLICATE KEY UPDATE product_id=VALUES(product_id),
                    china_reference_price=VALUES(china_reference_price),
                    first_free_eligible=VALUES(first_free_eligible),enabled=VALUES(enabled)
                """,
                wallpaperId, properties.getBundleId(), request.productId().strip(),
                referencePrice == null ? null : new BigDecimal(referencePrice),
                request.firstFreeEligible(), request.enabled());
        return get(wallpaperId);
    }

    private IosProductConfiguration configuration(
            String productId, String referencePrice, boolean firstFreeEligible, boolean enabled, Instant verifiedAt) {
        return new IosProductConfiguration(productId, referencePrice, firstFreeEligible, enabled, verifiedAt != null, verifiedAt);
    }

    private void ensureWallpaper(long wallpaperId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM wallpaper WHERE id=?", Long.class, wallpaperId);
        if (count == null || count == 0) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLPAPER_NOT_FOUND", "The wallpaper was not found");
        }
    }

    private Instant timestamp(Timestamp value) { return value == null ? null : value.toInstant(); }
    private String price(BigDecimal value) { return value == null ? null : value.setScale(2).toPlainString(); }
}
