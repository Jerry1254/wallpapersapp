package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform.*;
import static com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType.*;
import static org.assertj.core.api.Assertions.assertThat;

import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import org.junit.jupiter.api.Test;

class PlatformResourceScopeTest {
    @Test
    void exposesOnlyTheAuthenticatedAppPlatformAndUniversalStatic() {
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.ANDROID, ANDROID, LAYER_PARALLAX)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.ANDROID, ANDROID, VIDEO)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.ANDROID, IOS, LIVE_PHOTO)).isFalse();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.IOS, IOS, LIVE_PHOTO)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.HARMONYOS, HARMONYOS, MOVING_PHOTO)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.HARMONYOS, ANDROID, VIDEO)).isFalse();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.ANDROID, UNIVERSAL, STATIC_IMAGE)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.IOS, UNIVERSAL, STATIC_IMAGE)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.HARMONYOS, UNIVERSAL, STATIC_IMAGE)).isTrue();
        assertThat(PlatformResourceScope.visibleTo(DevicePlatform.H5_TEST, UNIVERSAL, STATIC_IMAGE)).isFalse();
    }
}
