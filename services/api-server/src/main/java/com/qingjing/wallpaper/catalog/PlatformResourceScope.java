package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.DeliveryPlatform;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.ResourceType;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;

/** Single platform visibility rule shared by catalog, entitlements and delivery. */
public final class PlatformResourceScope {
    private PlatformResourceScope() { }

    public static boolean visibleTo(
            DevicePlatform appPlatform,
            DeliveryPlatform deliveryPlatform,
            ResourceType resourceType) {
        if (deliveryPlatform == DeliveryPlatform.UNIVERSAL && resourceType == ResourceType.STATIC_IMAGE) {
            return appPlatform == DevicePlatform.ANDROID
                    || appPlatform == DevicePlatform.IOS
                    || appPlatform == DevicePlatform.HARMONYOS;
        }
        return switch (appPlatform) {
            case ANDROID -> deliveryPlatform == DeliveryPlatform.ANDROID
                    && (resourceType == ResourceType.LAYER_PARALLAX || resourceType == ResourceType.VIDEO);
            case IOS -> deliveryPlatform == DeliveryPlatform.IOS && resourceType == ResourceType.LIVE_PHOTO;
            case HARMONYOS -> deliveryPlatform == DeliveryPlatform.HARMONYOS
                    && resourceType == ResourceType.MOVING_PHOTO;
            case H5_TEST -> false;
        };
    }
}
