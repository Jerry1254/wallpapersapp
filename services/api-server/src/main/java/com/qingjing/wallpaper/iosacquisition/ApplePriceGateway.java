package com.qingjing.wallpaper.iosacquisition;

import java.math.BigDecimal;

public interface ApplePriceGateway {
    record ChinaPrice(String appleInAppPurchaseId, String pricePointId, BigDecimal customerPrice) {}
    ChinaPrice currentChinaPrice(String productId);
    default ChinaPrice currentChinaPrice(String productId, String productType) { return currentChinaPrice(productId); }

    final class PriceFailure extends RuntimeException {
        private final String code;
        public PriceFailure(String code) { super(code); this.code = code; }
        public String code() { return code; }
    }
}
