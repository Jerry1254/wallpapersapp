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
    private final IosPricingProperties pricing;

    public IosProductService(JdbcTemplate jdbc, IosAcquisitionProperties properties, IosPricingProperties pricing) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.pricing = pricing;
    }

    @Transactional(readOnly = true)
    public IosProductConfiguration get(long wallpaperId) {
        ensureWallpaper(wallpaperId);
        List<IosProductConfiguration> rows = jdbc.query(
                """
                SELECT *
                FROM ios_product_mapping WHERE wallpaper_id=? AND bundle_id=?
                """,
                (rs, row) -> {
                    PriceView price = view(rs);
                    return new IosProductConfiguration(rs.getString("product_id"), price.amount(),
                            "APP_STORE_CONNECT", "CNY", price.status(), price.syncedAt(), rs.getString("price_sync_error"),
                            rs.getBoolean("first_free_eligible"), rs.getBoolean("enabled"),
                            rs.getTimestamp("verified_transaction_at") != null, timestamp(rs.getTimestamp("verified_transaction_at")));
                },
                wallpaperId, properties.getBundleId());
        return rows.isEmpty() ? new IosProductConfiguration("", null, "APP_STORE_CONNECT", "CNY", "UNSYNCED", null, null, false, false, false, null) : rows.get(0);
    }

    @Transactional(readOnly = true)
    public List<Product> catalogue() {
        return jdbc.query(
                """
                SELECT m.*
                FROM ios_product_mapping m JOIN wallpaper w ON w.id=m.wallpaper_id
                WHERE m.bundle_id=? AND m.enabled=TRUE AND w.status='PUBLISHED'
                  AND EXISTS (SELECT 1 FROM wallpaper_variant v JOIN resource_version rv ON rv.variant_id=v.id
                    WHERE v.wallpaper_id=w.id AND v.enabled=TRUE AND v.platform IN ('IOS','UNIVERSAL') AND rv.status='PUBLISHED')
                ORDER BY m.wallpaper_id
                """,
                (rs,row) -> {
                    PriceView price = view(rs);
                    return new Product(rs.getString("wallpaper_id"), rs.getString("product_id"), price.amount(),
                            "APP_STORE_CONNECT", "CNY", price.status(), price.syncedAt());
                }, properties.getBundleId());
    }

    @Transactional
    public IosProductConfiguration update(long wallpaperId, UpdateIosProductRequest request) {
        ensureWallpaper(wallpaperId);
        jdbc.queryForObject("SELECT id FROM wallpaper WHERE id=? FOR UPDATE", Long.class, wallpaperId);
        IosProductConfiguration previous = get(wallpaperId);
        // Keep accepting the deprecated field for older admin clients, but never persist it.
        // Only a verified App Store Connect GET response can populate the display-price cache.
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
        jdbc.update("""
                INSERT INTO ios_product_mapping (wallpaper_id,bundle_id,product_id,first_free_eligible,enabled)
                VALUES (?,?,?,?,?)
                ON DUPLICATE KEY UPDATE first_free_eligible=VALUES(first_free_eligible),enabled=VALUES(enabled)
                """, wallpaperId, properties.getBundleId(), request.productId(), request.firstFreeEligible(), request.enabled());
        if (!previous.productId().isEmpty() && !previous.productId().equals(request.productId())) {
            jdbc.update("""
                    UPDATE ios_product_mapping SET product_id=?,china_reference_price=NULL,apple_in_app_purchase_id=NULL,
                        apple_price_point_id=NULL,price_sync_status='UNSYNCED',price_synced_at=NULL,price_sync_error=NULL,
                        price_sync_next_at=NULL,price_sync_lease_id=NULL,price_sync_lease_until=NULL
                    WHERE wallpaper_id=? AND bundle_id=?
                    """, request.productId(), wallpaperId, properties.getBundleId());
        }
        return get(wallpaperId);
    }

    private record PriceView(String amount, String status, Instant syncedAt) {}
    private PriceView view(java.sql.ResultSet rs) throws java.sql.SQLException {
        Instant synced = timestamp(rs.getTimestamp("price_synced_at"));
        String status = rs.getString("price_sync_status");
        if (!pricing.configured()) status = "UNAVAILABLE";
        else if (status.equals("READY") && (synced == null || !synced.plus(pricing.getMaxAge()).isAfter(Instant.now()))) status = "STALE";
        return new PriceView(status.equals("READY") ? price(rs.getBigDecimal("china_reference_price")) : null, status, synced);
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
