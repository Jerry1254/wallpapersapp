package com.qingjing.wallpaper.catalog;

public final class OsVersions {
    private OsVersions() {
    }

    public static boolean compatible(String actual, String minimum) {
        if (minimum == null || minimum.isBlank()) return true;
        if (actual == null
                || !actual.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")
                || !minimum.matches("[0-9]{1,6}(\\.[0-9]{1,6}){0,3}")) return false;
        String[] actualParts = actual.split("\\.");
        String[] minimumParts = minimum.split("\\.");
        for (int index = 0; index < Math.max(actualParts.length, minimumParts.length); index++) {
            int actualPart = index < actualParts.length ? Integer.parseInt(actualParts[index]) : 0;
            int minimumPart = index < minimumParts.length ? Integer.parseInt(minimumParts[index]) : 0;
            if (actualPart != minimumPart) return actualPart > minimumPart;
        }
        return true;
    }
}
