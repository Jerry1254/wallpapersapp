package com.qingjing.wallpaper.asset.application;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

public record StoredContent(InputStream inputStream, long sizeBytes) implements AutoCloseable {

    public StoredContent {
        Objects.requireNonNull(inputStream, "inputStream");
        if (sizeBytes < 0) {
            throw new IllegalArgumentException("sizeBytes must not be negative");
        }
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }
}
