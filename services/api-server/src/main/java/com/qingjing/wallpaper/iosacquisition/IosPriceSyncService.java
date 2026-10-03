package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.IosProductConfiguration;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class IosPriceSyncService {
    private final JdbcTemplate jdbc;
    private final IosPricingProperties pricing;
    private final IosAcquisitionProperties acquisition;
    private final ApplePriceGateway apple;
    private final IosProductService products;
    private final IosCreditProductService credits;
    public IosPriceSyncService(JdbcTemplate jdbc, IosPricingProperties pricing, IosAcquisitionProperties acquisition,
            ApplePriceGateway apple, IosProductService products, IosCreditProductService credits) {
        this.jdbc = jdbc; this.pricing = pricing; this.acquisition = acquisition; this.apple = apple; this.products = products;
        this.credits=credits;
    }

    @Scheduled(initialDelayString="${qingjing.ios-pricing.initial-delay:15000}",
            fixedDelayString="${qingjing.ios-pricing.sync-delay:300000}")
    public void synchronizeDuePrices() {
        if (!pricing.configured() || acquisition.getAppAppleId() == null) return;
        var due = jdbc.queryForList("""
                SELECT m.wallpaper_id FROM ios_product_mapping m WHERE m.bundle_id=? AND m.enabled=TRUE
                    AND NOT EXISTS (SELECT 1 FROM ios_wallpaper_credit_price c WHERE c.wallpaper_id=m.wallpaper_id AND c.bundle_id=m.bundle_id)
                    AND (price_sync_next_at IS NULL OR price_sync_next_at<=UTC_TIMESTAMP(6))
                    AND (price_sync_lease_until IS NULL OR price_sync_lease_until<UTC_TIMESTAMP(6))
                ORDER BY COALESCE(price_sync_next_at,'1970-01-01'),wallpaper_id LIMIT 20
                """, Long.class, acquisition.getBundleId());
        for (long id : due) synchronize(id);
    }

    public IosProductConfiguration synchronize(long wallpaperId) {
        var configuration = products.get(wallpaperId);
        if("CREDITS".equals(configuration.acquisitionMode())) {credits.synchronizePacks();return products.get(wallpaperId);}
        if (configuration.productId().isEmpty()) throw new ApiException(HttpStatus.CONFLICT,
                "IOS_PRODUCT_CONFIGURATION_REQUIRED", "Bind an Apple product before synchronizing its price");
        if (!pricing.configured() || acquisition.getAppAppleId() == null) throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                "IOS_PRICE_SYNC_UNAVAILABLE", "Configure an App Store Connect API team key for price synchronization");
        String lease = UUID.randomUUID().toString();
        Instant now = Instant.now();
        int claimed = jdbc.update("""
                UPDATE ios_product_mapping SET price_sync_lease_id=?,price_sync_lease_until=?
                WHERE wallpaper_id=? AND bundle_id=? AND product_id=?
                    AND (price_sync_lease_until IS NULL OR price_sync_lease_until<?)
                """, lease, Timestamp.from(now.plusSeconds(180)), wallpaperId, acquisition.getBundleId(), configuration.productId(), Timestamp.from(now));
        if (claimed == 0) return products.get(wallpaperId);
        try {
            var price = apple.currentChinaPrice(configuration.productId());
            if (price.customerPrice() == null || price.customerPrice().signum() <= 0
                    || price.customerPrice().scale() != 2 || price.customerPrice().precision() > 10)
                throw new ApplePriceGateway.PriceFailure("INVALID_RESPONSE");
            Instant completed = Instant.now();
            jdbc.update("""
                    UPDATE ios_product_mapping SET china_reference_price=?,apple_in_app_purchase_id=?,apple_price_point_id=?,
                        price_sync_status='READY',price_synced_at=?,price_sync_error=NULL,price_sync_next_at=?,
                        price_sync_lease_id=NULL,price_sync_lease_until=NULL
                    WHERE wallpaper_id=? AND bundle_id=? AND product_id=? AND price_sync_lease_id=?
                    """, price.customerPrice(), price.appleInAppPurchaseId(), price.pricePointId(), Timestamp.from(completed),
                    Timestamp.from(completed.plusSeconds(300)), wallpaperId, acquisition.getBundleId(), configuration.productId(), lease);
        } catch (ApplePriceGateway.PriceFailure failure) {
            failed(wallpaperId, configuration.productId(), lease, failure.code());
        } catch (RuntimeException failure) {
            failed(wallpaperId, configuration.productId(), lease, "SYNC_FAILED");
        }
        return products.get(wallpaperId);
    }
    private void failed(long wallpaperId, String productId, String lease, String code) {
        jdbc.update("""
                UPDATE ios_product_mapping SET price_sync_status='ERROR',price_sync_error=?,price_sync_next_at=?,
                    price_sync_lease_id=NULL,price_sync_lease_until=NULL
                WHERE wallpaper_id=? AND bundle_id=? AND product_id=? AND price_sync_lease_id=?
                """, code, Timestamp.from(Instant.now().plusSeconds(code.equals("APPLE_RATE_LIMITED") ? 900 : 300)),
                wallpaperId, acquisition.getBundleId(), productId, lease);
    }
}
