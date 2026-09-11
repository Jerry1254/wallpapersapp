package com.qingjing.wallpaper.asset.application;

import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.INVALID_STAGING_TOKEN;

import java.util.Objects;
import java.util.UUID;

public record StagedObject(String token, long sizeBytes, String sha256) {

    public StagedObject {
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(sha256, "sha256");
        try {
            if (!UUID.fromString(token).toString().equals(token)) {
                throw new IllegalArgumentException("Non-canonical UUID");
            }
        } catch (IllegalArgumentException exception) {
            throw new FileStorageException(INVALID_STAGING_TOKEN, "The staging token is invalid", exception);
        }
        if (sizeBytes < 0 || !sha256.matches("[a-f0-9]{64}")) {
            throw new IllegalArgumentException("Invalid staged object metadata");
        }
    }
}
