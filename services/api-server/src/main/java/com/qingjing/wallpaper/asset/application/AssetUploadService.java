package com.qingjing.wallpaper.asset.application;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.Objects;

public final class AssetUploadService {

    private final FileStorage fileStorage;
    private final AssetContentValidator contentValidator;

    public AssetUploadService(FileStorage fileStorage, AssetContentValidator contentValidator) {
        this.fileStorage = fileStorage;
        this.contentValidator = contentValidator;
    }

    public ValidatedAsset upload(
            InputStream source,
            String originalFilename,
            String declaredContentType,
            AssetPurpose purpose) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(purpose, "purpose");

        StagedObject stagedObject = fileStorage.stage(source, purpose.maximumBytes());
        boolean committed = false;
        RuntimeException uploadFailure = null;
        try {
            AssetMetadata metadata = contentValidator.validate(stagedObject, purpose, declaredContentType);
            StoredObject storedObject = fileStorage.commit(stagedObject, metadata.type().extension());
            committed = true;
            return new ValidatedAsset(
                    storedObject.storageKey(),
                    sanitizeFilename(originalFilename, metadata.type().extension()),
                    metadata.type().mimeType(),
                    metadata.type().extension(),
                    storedObject.sizeBytes(),
                    storedObject.sha256(),
                    metadata.widthPixels(),
                    metadata.heightPixels());
        } catch (RuntimeException exception) {
            uploadFailure = exception;
            throw exception;
        } finally {
            if (!committed) {
                try {
                    fileStorage.discard(stagedObject);
                } catch (RuntimeException cleanupFailure) {
                    if (uploadFailure != null) {
                        uploadFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
        }
    }

    private static String sanitizeFilename(String originalFilename, String fallbackExtension) {
        String normalized = originalFilename == null
                ? ""
                : Normalizer.normalize(originalFilename, Normalizer.Form.NFC).replace('\\', '/');
        int lastSlash = normalized.lastIndexOf('/');
        if (lastSlash >= 0) {
            normalized = normalized.substring(lastSlash + 1);
        }
        normalized = normalized.replaceAll("[\\p{Cntrl}]", "").strip();
        if (normalized.isBlank() || normalized.equals(".") || normalized.equals("..")) {
            normalized = "upload." + fallbackExtension;
        }
        if (normalized.length() > 255) {
            normalized = normalized.substring(0, 255);
        }
        return normalized;
    }
}
