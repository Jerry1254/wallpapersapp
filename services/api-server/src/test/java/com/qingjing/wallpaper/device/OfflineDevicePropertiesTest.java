package com.qingjing.wallpaper.device;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;
import org.junit.jupiter.api.Test;

class OfflineDevicePropertiesTest {
    @Test void promotionScopeRequiresItsOwnExplicitRegistrationSwitch() {
        var properties = new DeviceProperties();
        properties.setAllowedAndroidScopes(List.of("com.qingjing.bizhi", "com.jiyi.wallpaper"));
        assertThat(properties.isAndroidScopeAllowed("com.qingjing.bizhi")).isTrue();
        assertThat(properties.isAndroidScopeAllowed("com.jiyi.wallpaper")).isFalse();
        properties.setOfflineAndroidEnabled(true);
        assertThat(properties.isAndroidScopeAllowed("com.jiyi.wallpaper")).isTrue();
        assertThat(properties.isAndroidScopeAllowed("com.jiyi.wallpaper.fake")).isFalse();
        assertThat(properties.isIosScopeAllowed("com.jiyi.wallpaper")).isFalse();
        assertThat(properties.isHarmonyScopeAllowed("com.jiyi.wallpaper")).isFalse();
    }
}
