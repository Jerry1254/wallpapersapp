package com.qingjing.wallpaper.shared.web;

import org.springframework.http.HttpStatus;

public final class Ids {

    private Ids() {
    }

    public static long parse(String value, String field) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed < 1) {
                throw new NumberFormatException("non-positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new ApiException(
                    HttpStatus.BAD_REQUEST,
                    "VALIDATION_FAILED",
                    field + " must be a positive decimal ID");
        }
    }
}
