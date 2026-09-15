package com.qingjing.wallpaper.asset.application;

public final class AssetValidationException extends RuntimeException {

    public enum Code {
        EMPTY_FILE,
        UNSUPPORTED_FILE_TYPE,
        TYPE_NOT_ALLOWED_FOR_PURPOSE,
        DECLARED_TYPE_MISMATCH,
        INVALID_IMAGE,
        IMAGE_DIMENSIONS_EXCEEDED,
        INVALID_JSON,
        INVALID_VIDEO,
        VIDEO_DURATION_EXCEEDED,
        INVALID_ARCHIVE,
        UNSAFE_ARCHIVE_ENTRY,
        ARCHIVE_LIMIT_EXCEEDED
    }

    private final Code code;

    public AssetValidationException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public AssetValidationException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
