package com.qingjing.wallpaper.asset.application;

import java.io.InputStream;
import java.text.Normalizer;
import java.util.Objects;

public final class AssetUploadService {

    private final FileStorage fileStorage;
    private final AssetContentValidator contentValidator;
    private final CoverImageOptimizer coverImageOptimizer;

    public AssetUploadService(
            FileStorage fileStorage,
            AssetContentValidator contentValidator,
            CoverImageOptimizer coverImageOptimizer) {
        this.fileStorage = fileStorage;
        this.contentValidator = contentValidator;
        this.coverImageOptimizer = coverImageOptimizer;
    }

    public ValidatedAsset upload(
            InputStream source,
            String originalFilename,
            String declaredContentType,
            AssetPurpose purpose) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(purpose, "purpose");

        StagedObject uploaded = fileStorage.stage(source, purpose.maximumBytes());
        StagedObject readyToStore = uploaded;
        boolean uploadedDiscarded = false;
        boolean committed = false;
        RuntimeException uploadFailure = null;
        try {
            AssetMetadata metadata = contentValidator.validate(uploaded, purpose, declaredContentType);
            if (purpose == AssetPurpose.WALLPAPER_COVER) {
                readyToStore = coverImageOptimizer.optimize(uploaded);
                metadata = contentValidator.validate(readyToStore, purpose, "image/webp");
                if (metadata.type() != DetectedAssetType.WEBP) {
                    throw new AssetValidationException(
                            AssetValidationException.Code.INVALID_IMAGE,
                            "The optimized wallpaper cover is not WebP");
                }
                fileStorage.discard(uploaded);
                uploadedDiscarded = true;
            }
            StoredObject storedObject = fileStorage.commit(readyToStore, metadata.type().extension());
            committed = true;
            return new ValidatedAsset(
                    storedObject.storageKey(),
                    storedFilename(originalFilename, metadata.type().extension(), purpose),
                    metadata.type().mimeType(),
                    metadata.type().extension(),
                    storedObject.sizeBytes(),
                    storedObject.sha256(),
                    metadata.widthPixels(),
                    metadata.heightPixels(),
                    metadata.durationMs());
        } catch (RuntimeException exception) {
            uploadFailure = exception;
            throw exception;
        } finally {
            if (!committed) {
                try {
                    fileStorage.discard(readyToStore);
                } catch (RuntimeException cleanupFailure) {
                    if (uploadFailure != null) {
                        uploadFailure.addSuppressed(cleanupFailure);
                    } else {
                        throw cleanupFailure;
                    }
                }
            }
            if (!readyToStore.equals(uploaded) && !uploadedDiscarded) {
                try {
                    fileStorage.discard(uploaded);
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

    private static String storedFilename(
            String originalFilename,
            String extension,
            AssetPurpose purpose) {
        String sanitized = sanitizeFilename(originalFilename, extension);
        if (purpose != AssetPurpose.WALLPAPER_COVER) return sanitized;
        int dot = sanitized.lastIndexOf('.');
        String base = dot > 0 ? sanitized.substring(0, dot) : sanitized;
        int maximumBaseLength = 255 - extension.length() - 1;
        if (base.length() > maximumBaseLength) base = base.substring(0, maximumBaseLength);
        return base + "." + extension;
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
