package com.qingjing.wallpaper.iosacquisition;

import static org.assertj.core.api.Assertions.assertThat;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;

class IosProductPriceValidationTest {
    @Test
    void pricesUsePositiveDecimalStringsWithoutRoundingOrExponents() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            for (String price : new String[]{null, "0.10", "18.80", "99999999.99"}) {
                assertThat(validator.validate(new UpdateIosProductRequest("com.example.wallpaper", price, false, true)))
                        .as("accepted price %s", price).isEmpty();
            }
            for (String price : new String[]{"0.00", "-1.00", "1.001", "1.0", "01.00", "1e2", "100000000.00"}) {
                assertThat(validator.validate(new UpdateIosProductRequest("com.example.wallpaper", price, false, true)))
                        .as("rejected price %s", price).isNotEmpty();
            }
        }
    }
}
