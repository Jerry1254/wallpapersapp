package com.qingjing.wallpaper.tutorial;

import com.qingjing.wallpaper.shared.web.ApiException;
import org.springframework.http.HttpStatus;

enum WallpaperTutorialDefinition {
    ANDROID_PARALLAX_4D("4D动态壁纸教程", "ANDROID", "PARALLAX_4D"),
    ANDROID_DYNAMIC("动态壁纸教程", "ANDROID", "DYNAMIC"),
    STATIC("静态壁纸教程", "UNIVERSAL", "STATIC"),
    HARMONYOS_DYNAMIC("华为动态壁纸教程", "HARMONYOS", "DYNAMIC"),
    IOS_DYNAMIC("苹果动态壁纸教程", "IOS", "DYNAMIC");

    private final String title;
    private final String platform;
    private final String wallpaperKind;

    WallpaperTutorialDefinition(String title, String platform, String wallpaperKind) {
        this.title = title;
        this.platform = platform;
        this.wallpaperKind = wallpaperKind;
    }

    String key() {
        return name();
    }

    String title() {
        return title;
    }

    String platform() {
        return platform;
    }

    String wallpaperKind() {
        return wallpaperKind;
    }

    static WallpaperTutorialDefinition require(String key) {
        try {
            return valueOf(key);
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new ApiException(HttpStatus.NOT_FOUND, "TUTORIAL_NOT_FOUND", "The wallpaper tutorial does not exist");
        }
    }
}
