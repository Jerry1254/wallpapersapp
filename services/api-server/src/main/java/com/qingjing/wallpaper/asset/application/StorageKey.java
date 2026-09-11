package com.qingjing.wallpaper.asset.application;

import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.INVALID_STORAGE_KEY;

import java.util.Objects;

public record StorageKey(String value) {

    public StorageKey {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()
                || value.length() > 512
                || value.startsWith("/")
                || value.endsWith("/")
                || value.contains("\\")
                || value.contains(":")
                || !value.matches("[A-Za-z0-9._/-]+")) {
            throw invalid();
        }
        for (String segment : value.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw invalid();
            }
        }
    }

    private static FileStorageException invalid() {
        return new FileStorageException(INVALID_STORAGE_KEY, "The storage key is invalid");
    }

    @Override
    public String toString() {
        return value;
    }
}
