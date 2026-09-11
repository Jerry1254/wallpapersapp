package com.qingjing.wallpaper.shared.web;

import org.springframework.http.HttpStatus;

public final class EntityTags {

    private EntityTags() {
    }

    public static long parseRequired(String value) {
        if (value == null || !value.matches("\"[0-9]+\"")) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MALFORMED_REQUEST",
                    "If-Match must be a quoted non-negative version");
        }
        try {
            return Long.parseLong(value.substring(1, value.length() - 1));
        } catch (NumberFormatException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "MALFORMED_REQUEST",
                    "If-Match is outside the supported range");
        }
    }

    public static String of(long version) {
        return "\"" + version + "\"";
    }
}
