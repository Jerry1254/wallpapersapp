package com.qingjing.wallpaper.iosacquisition;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
class IosCreditPackTest {
  @Test void supportedAmountsAreExactAndSinglePaymentQuantityNeverExceedsTen() {
    var supported=java.util.Set.of(1,2,3,4,5,6,7,8,9,10,12,14,15,16,18,20,21,24,27,30);
    for(int amount=1;amount<=32;amount++) {
      final int price=amount;
      if(supported.contains(price)) {
        int unit=IosCreditProductService.packFor(price);
        assertThat(unit).isBetween(1,3);assertThat(price/unit).isBetween(1,10);assertThat((price/unit)*unit).isEqualTo(price);
      } else assertThatThrownBy(()->IosCreditProductService.packFor(price)).isInstanceOf(com.qingjing.wallpaper.shared.web.ApiException.class);
    }
  }
}
