package com.qingjing.wallpaper.asset.application;

import java.util.Objects;

public record StoredObject(StorageKey storageKey, long sizeBytes, String sha256) {

    public StoredObject {
        Objects.requireNonNull(storageKey, "storageKey");
        Objects.requireNonNull(sha256, "sha256");
        if (sizeBytes < 0 || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid stored object metadata");
        }
    }
}
