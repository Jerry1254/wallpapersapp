package com.qingjing.wallpaper.iosacquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.qingjing.wallpaper.iosacquisition.IosAppleGateway.VerifiedTransaction;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class IosCreditPurchaseServiceTest {
    @Test
    void acceptsObservedSandboxUnitPriceAndDocumentedTotal() {
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("SANDBOX",3,1000L),1,3)).isTrue();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("SANDBOX",3,3000L),1,3)).isTrue();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("SANDBOX",9,2000L),2,18)).isTrue();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("SANDBOX",9,1000L),2,18)).isFalse();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("SANDBOX",3,null),1,3)).isFalse();
    }

    @Test
    void productionAndUnknownEnvironmentsAlwaysRequireTotalPrice() {
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("PRODUCTION",3,1000L),1,3)).isFalse();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("PRODUCTION",3,3000L),1,3)).isTrue();
        assertThat(IosCreditPurchaseService.matchesPrice(transaction("UNKNOWN",3,1000L),1,3)).isFalse();
    }

    private VerifiedTransaction transaction(String environment,int quantity,Long price) {
        var now=Instant.now();
        return new VerifiedTransaction(environment,"com.qingjing.bizhi","com.qingjing.bizhi.credits.1","transaction","transaction",
                "account-token","device",now,null,now,"account","CONSUMABLE",quantity,"CNY",price,"CHN");
    }
}
