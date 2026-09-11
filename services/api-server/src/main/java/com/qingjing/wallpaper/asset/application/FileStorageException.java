package com.qingjing.wallpaper.asset.application;

public final class FileStorageException extends RuntimeException {

    public enum Code {
        INVALID_STORAGE_KEY,
        INVALID_STAGING_TOKEN,
        INVALID_EXTENSION,
        FILE_TOO_LARGE,
        FILE_NOT_FOUND,
        STORAGE_BOUNDARY_VIOLATION,
        STORAGE_IO_ERROR
    }

    private final Code code;

    public FileStorageException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public FileStorageException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
