package com.qingjing.wallpaper.asset.application;

import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.ARCHIVE_LIMIT_EXCEEDED;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.DECLARED_TYPE_MISMATCH;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.EMPTY_FILE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.IMAGE_DIMENSIONS_EXCEEDED;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_ARCHIVE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_IMAGE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_JSON;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_VIDEO;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.TYPE_NOT_ALLOWED_FOR_PURPOSE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.UNSAFE_ARCHIVE_ENTRY;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.UNSUPPORTED_FILE_TYPE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.VIDEO_DURATION_EXCEEDED;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

public final class AssetContentValidator {

    private static final int HEADER_LENGTH = 64;
    private static final int MAX_IMAGE_WIDTH = 32_768;
    private static final int MAX_IMAGE_HEIGHT = 32_768;
    private static final long MAX_IMAGE_PIXELS = 40_000_000;
    private static final int MAX_ARCHIVE_ENTRIES = 1_024;
    private static final long MAX_ARCHIVE_EXPANDED_BYTES = 512L * 1024 * 1024;
    private static final long MAX_TUTORIAL_DURATION_MS = 15L * 60 * 1_000;

    private final FileStorage fileStorage;
    private final ObjectMapper objectMapper;

    public AssetContentValidator(FileStorage fileStorage, ObjectMapper objectMapper) {
        this.fileStorage = fileStorage;
        this.objectMapper = objectMapper;
    }

    public AssetMetadata validate(
            StagedObject stagedObject,
            AssetPurpose purpose,
            String declaredContentType) {
        if (stagedObject.sizeBytes() == 0) {
            throw new AssetValidationException(EMPTY_FILE, "The uploaded file is empty");
        }

        DetectedAssetType detectedType = detectType(stagedObject);
        if (!purpose.allows(detectedType)) {
            throw new AssetValidationException(
                    TYPE_NOT_ALLOWED_FOR_PURPOSE,
                    "The detected file type is not allowed for this upload purpose");
        }
        validateDeclaredContentType(detectedType, declaredContentType);

        return switch (detectedType) {
            case JPEG, PNG -> validateImage(stagedObject, detectedType);
            case WEBP -> validateWebp(stagedObject);
            case JSON -> validateJson(stagedObject);
            case ZIP -> validateArchive(stagedObject);
            case MP4, QUICKTIME -> purpose == AssetPurpose.TUTORIAL_VIDEO
                    ? validateTutorialVideo(stagedObject)
                    : new AssetMetadata(detectedType, null, null, null);
        };
    }

    private DetectedAssetType detectType(StagedObject stagedObject) {
        byte[] header = new byte[HEADER_LENGTH];
        int length;
        try (StoredContent storedContent = fileStorage.openStaged(stagedObject)) {
            length = storedContent.inputStream().readNBytes(header, 0, header.length);
        } catch (IOException exception) {
            throw storageReadFailure(exception);
        }

        if (startsWith(header, length, 0xff, 0xd8, 0xff)) {
            return DetectedAssetType.JPEG;
        }
        if (startsWith(header, length, 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)) {
            return DetectedAssetType.PNG;
        }
        if (length >= 12
                && asciiEquals(header, 0, "RIFF")
                && asciiEquals(header, 8, "WEBP")) {
            return DetectedAssetType.WEBP;
        }
        if (length >= 12 && asciiEquals(header, 4, "ftyp")) {
            return asciiEquals(header, 8, "qt  ") ? DetectedAssetType.QUICKTIME : DetectedAssetType.MP4;
        }
        if (startsWith(header, length, 0x50, 0x4b, 0x03, 0x04)
                || startsWith(header, length, 0x50, 0x4b, 0x05, 0x06)
                || startsWith(header, length, 0x50, 0x4b, 0x07, 0x08)) {
            return DetectedAssetType.ZIP;
        }

        int firstContentByte = 0;
        while (firstContentByte < length && Character.isWhitespace(header[firstContentByte] & 0xff)) {
            firstContentByte++;
        }
        if (firstContentByte < length && header[firstContentByte] == '{') {
            return DetectedAssetType.JSON;
        }
        throw new AssetValidationException(UNSUPPORTED_FILE_TYPE, "The uploaded file type is not supported");
    }

    private AssetMetadata validateImage(StagedObject stagedObject, DetectedAssetType detectedType) {
        try (StoredContent storedContent = fileStorage.openStaged(stagedObject);
                ImageInputStream imageInput = ImageIO.createImageInputStream(storedContent.inputStream())) {
            if (imageInput == null) {
                throw new AssetValidationException(INVALID_IMAGE, "The image cannot be decoded");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
            if (!readers.hasNext()) {
                throw new AssetValidationException(INVALID_IMAGE, "The image cannot be decoded");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(imageInput, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                validateImageDimensions(width, height);
                reader.read(0);
                return new AssetMetadata(detectedType, width, height, null);
            } finally {
                reader.dispose();
            }
        } catch (AssetValidationException exception) {
            throw exception;
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AssetValidationException(INVALID_IMAGE, "The image cannot be decoded", exception);
        }
    }

    private AssetMetadata validateJson(StagedObject stagedObject) {
        try (StoredContent storedContent = fileStorage.openStaged(stagedObject)) {
            JsonNode root = objectMapper.reader()
                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                    .readTree(storedContent.inputStream());
            if (root == null || !root.isObject()) {
                throw new AssetValidationException(INVALID_JSON, "The configuration must be a JSON object");
            }
            return new AssetMetadata(DetectedAssetType.JSON, null, null, null);
        } catch (AssetValidationException exception) {
            throw exception;
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AssetValidationException(INVALID_JSON, "The configuration is not valid JSON", exception);
        }
    }

    private AssetMetadata validateWebp(StagedObject stagedObject) {
        byte[] header = new byte[30];
        int length;
        try (StoredContent storedContent = fileStorage.openStaged(stagedObject)) {
            length = storedContent.inputStream().readNBytes(header, 0, header.length);
        } catch (IOException exception) {
            throw storageReadFailure(exception);
        }
        if (length < 30
                || !asciiEquals(header, 0, "RIFF")
                || !asciiEquals(header, 8, "WEBP")
                || unsignedLittleEndianInt(header, 4) + 8 != stagedObject.sizeBytes()) {
            throw new AssetValidationException(INVALID_IMAGE, "The WebP structure is invalid");
        }

        long chunkSize = unsignedLittleEndianInt(header, 16);
        if (chunkSize + 20 > stagedObject.sizeBytes()) {
            throw new AssetValidationException(INVALID_IMAGE, "The WebP chunk exceeds the file boundary");
        }

        int width;
        int height;
        if (asciiEquals(header, 12, "VP8X")) {
            if (chunkSize < 10) {
                throw new AssetValidationException(INVALID_IMAGE, "The WebP extended header is incomplete");
            }
            width = 1 + unsignedLittleEndian24(header, 24);
            height = 1 + unsignedLittleEndian24(header, 27);
        } else if (asciiEquals(header, 12, "VP8L")) {
            if (chunkSize < 5 || (header[20] & 0xff) != 0x2f) {
                throw new AssetValidationException(INVALID_IMAGE, "The WebP lossless header is incomplete");
            }
            int b1 = header[21] & 0xff;
            int b2 = header[22] & 0xff;
            int b3 = header[23] & 0xff;
            int b4 = header[24] & 0xff;
            width = 1 + b1 + ((b2 & 0x3f) << 8);
            height = 1 + ((b2 & 0xc0) >> 6) + (b3 << 2) + ((b4 & 0x0f) << 10);
        } else if (asciiEquals(header, 12, "VP8 ")) {
            if (chunkSize < 10
                    || (header[23] & 0xff) != 0x9d
                    || (header[24] & 0xff) != 0x01
                    || (header[25] & 0xff) != 0x2a) {
                throw new AssetValidationException(INVALID_IMAGE, "The WebP lossy header is incomplete");
            }
            width = (header[26] & 0xff) | ((header[27] & 0x3f) << 8);
            height = (header[28] & 0xff) | ((header[29] & 0x3f) << 8);
        } else {
            throw new AssetValidationException(INVALID_IMAGE, "The WebP image chunk is unsupported");
        }
        validateImageDimensions(width, height);
        return new AssetMetadata(DetectedAssetType.WEBP, width, height, null);
    }

    private AssetMetadata validateTutorialVideo(StagedObject stagedObject) {
        boolean hasFtyp = false;
        boolean hasMdat = false;
        boolean hasTrack = false;
        Long durationMs = null;

        try (StoredContent storedContent = fileStorage.openStaged(stagedObject);
                DataInputStream input = new DataInputStream(new BufferedInputStream(storedContent.inputStream()))) {
            long remaining = stagedObject.sizeBytes();
            while (remaining > 0) {
                BoxHeader box = readBoxHeader(input, remaining);
                if ("ftyp".equals(box.type())) {
                    hasFtyp = true;
                    skipExactly(input, box.payloadSize());
                } else if ("moov".equals(box.type())) {
                    MovieMetadata movie = readMovieMetadata(input, box.payloadSize());
                    hasTrack = movie.hasTrack();
                    durationMs = movie.durationMs();
                } else {
                    if ("mdat".equals(box.type())) {
                        hasMdat = true;
                    }
                    skipExactly(input, box.payloadSize());
                }
                remaining -= box.size();
            }
        } catch (AssetValidationException exception) {
            throw exception;
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 structure is invalid", exception);
        }

        if (!hasFtyp || !hasMdat || !hasTrack || durationMs == null || durationMs <= 0) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 must contain a movie track and duration metadata");
        }
        if (durationMs > MAX_TUTORIAL_DURATION_MS) {
            throw new AssetValidationException(VIDEO_DURATION_EXCEEDED, "The tutorial video duration exceeds 15 minutes");
        }
        return new AssetMetadata(DetectedAssetType.MP4, null, null, durationMs);
    }

    private MovieMetadata readMovieMetadata(DataInputStream input, long payloadSize) throws IOException {
        long remaining = payloadSize;
        Long durationMs = null;
        boolean hasTrack = false;
        while (remaining > 0) {
            BoxHeader child = readBoxHeader(input, remaining);
            if ("mvhd".equals(child.type())) {
                durationMs = readMovieHeader(input, child.payloadSize());
            } else {
                if ("trak".equals(child.type())) {
                    hasTrack = true;
                }
                skipExactly(input, child.payloadSize());
            }
            remaining -= child.size();
        }
        return new MovieMetadata(durationMs, hasTrack);
    }

    private long readMovieHeader(DataInputStream input, long payloadSize) throws IOException {
        if (payloadSize < 20) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 movie header is incomplete");
        }
        int version = input.readUnsignedByte();
        input.skipNBytes(3);
        long consumed = 4;
        long timescale;
        long duration;
        if (version == 0) {
            if (payloadSize < 20) {
                throw new AssetValidationException(INVALID_VIDEO, "The MP4 movie header is incomplete");
            }
            input.skipNBytes(8);
            timescale = Integer.toUnsignedLong(input.readInt());
            duration = Integer.toUnsignedLong(input.readInt());
            consumed += 16;
        } else if (version == 1) {
            if (payloadSize < 32) {
                throw new AssetValidationException(INVALID_VIDEO, "The MP4 movie header is incomplete");
            }
            input.skipNBytes(16);
            timescale = Integer.toUnsignedLong(input.readInt());
            duration = input.readLong();
            consumed += 28;
            if (duration < 0) {
                throw new AssetValidationException(INVALID_VIDEO, "The MP4 duration is unsupported");
            }
        } else {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 movie header version is unsupported");
        }
        skipExactly(input, payloadSize - consumed);
        if (timescale == 0) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 timescale is invalid");
        }
        return BigInteger.valueOf(duration)
                .multiply(BigInteger.valueOf(1_000))
                .divide(BigInteger.valueOf(timescale))
                .longValueExact();
    }

    private BoxHeader readBoxHeader(DataInputStream input, long remaining) throws IOException {
        if (remaining < 8) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 box header is incomplete");
        }
        long size = Integer.toUnsignedLong(input.readInt());
        byte[] typeBytes = input.readNBytes(4);
        if (typeBytes.length != 4) {
            throw new EOFException("MP4 box type is incomplete");
        }
        long headerSize = 8;
        if (size == 1) {
            if (remaining < 16) {
                throw new AssetValidationException(INVALID_VIDEO, "The extended MP4 box header is incomplete");
            }
            size = input.readLong();
            headerSize = 16;
        } else if (size == 0) {
            size = remaining;
        }
        if (size < headerSize || size > remaining) {
            throw new AssetValidationException(INVALID_VIDEO, "An MP4 box exceeds the file boundary");
        }
        return new BoxHeader(
                size,
                new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII),
                size - headerSize);
    }

    private void skipExactly(InputStream input, long count) throws IOException {
        if (count < 0) {
            throw new AssetValidationException(INVALID_VIDEO, "The MP4 box size is invalid");
        }
        input.skipNBytes(count);
    }

    private record BoxHeader(long size, String type, long payloadSize) {
    }

    private record MovieMetadata(Long durationMs, boolean hasTrack) {
    }

    private AssetMetadata validateArchive(StagedObject stagedObject) {
        int entryCount = 0;
        long expandedBytes = 0;
        Set<String> names = new HashSet<>();
        byte[] buffer = new byte[16 * 1024];

        try (StoredContent storedContent = fileStorage.openStaged(stagedObject);
                ZipInputStream zipInput = new ZipInputStream(new BufferedInputStream(storedContent.inputStream()))) {
            ZipEntry entry;
            while ((entry = zipInput.getNextEntry()) != null) {
                entryCount++;
                if (entryCount > MAX_ARCHIVE_ENTRIES) {
                    throw new AssetValidationException(ARCHIVE_LIMIT_EXCEEDED, "The archive has too many entries");
                }
                validateArchiveEntryName(entry.getName(), names);
                int read;
                while ((read = zipInput.read(buffer)) != -1) {
                    expandedBytes += read;
                    if (expandedBytes > MAX_ARCHIVE_EXPANDED_BYTES) {
                        throw new AssetValidationException(
                                ARCHIVE_LIMIT_EXCEEDED,
                                "The expanded archive exceeds the accepted size");
                    }
                }
                zipInput.closeEntry();
            }
            if (entryCount == 0) {
                throw new AssetValidationException(INVALID_ARCHIVE, "The archive contains no entries");
            }
            return new AssetMetadata(DetectedAssetType.ZIP, null, null, null);
        } catch (AssetValidationException exception) {
            throw exception;
        } catch (FileStorageException exception) {
            throw exception;
        } catch (ZipException exception) {
            throw new AssetValidationException(INVALID_ARCHIVE, "The archive is invalid", exception);
        } catch (IOException | RuntimeException exception) {
            throw new AssetValidationException(INVALID_ARCHIVE, "The archive cannot be read", exception);
        }
    }

    private void validateArchiveEntryName(String name, Set<String> names) {
        if (name == null
                || name.isBlank()
                || name.startsWith("/")
                || name.startsWith("\\")
                || name.contains("\\")
                || name.matches("^[A-Za-z]:.*")) {
            throw new AssetValidationException(UNSAFE_ARCHIVE_ENTRY, "The archive contains an unsafe entry name");
        }
        String withoutTrailingSlash = name.endsWith("/") ? name.substring(0, name.length() - 1) : name;
        if (withoutTrailingSlash.isBlank()) {
            throw new AssetValidationException(UNSAFE_ARCHIVE_ENTRY, "The archive contains an unsafe entry name");
        }
        for (String segment : withoutTrailingSlash.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new AssetValidationException(UNSAFE_ARCHIVE_ENTRY, "The archive contains an unsafe entry name");
            }
        }
        if (!names.add(withoutTrailingSlash)) {
            throw new AssetValidationException(UNSAFE_ARCHIVE_ENTRY, "The archive contains duplicate entry names");
        }
    }

    private void validateDeclaredContentType(DetectedAssetType detectedType, String declaredContentType) {
        if (declaredContentType == null || declaredContentType.isBlank()) {
            return;
        }
        String normalized = declaredContentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "image/jpg" -> "image/jpeg";
            case "application/x-zip-compressed" -> "application/zip";
            default -> normalized;
        };
        if (normalized.equals("application/octet-stream")) {
            return;
        }
        if (!normalized.equals(detectedType.mimeType())) {
            throw new AssetValidationException(
                    DECLARED_TYPE_MISMATCH,
                    "The declared content type does not match the detected file type");
        }
    }

    private static boolean startsWith(byte[] bytes, int length, int... expected) {
        if (length < expected.length) {
            return false;
        }
        for (int index = 0; index < expected.length; index++) {
            if ((bytes[index] & 0xff) != expected[index]) {
                return false;
            }
        }
        return true;
    }

    private static boolean asciiEquals(byte[] bytes, int offset, String expected) {
        byte[] expectedBytes = expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        return offset + expectedBytes.length <= bytes.length
                && Arrays.equals(bytes, offset, offset + expectedBytes.length, expectedBytes, 0, expectedBytes.length);
    }

    private static long unsignedLittleEndianInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xffL)
                | ((bytes[offset + 1] & 0xffL) << 8)
                | ((bytes[offset + 2] & 0xffL) << 16)
                | ((bytes[offset + 3] & 0xffL) << 24);
    }

    private static int unsignedLittleEndian24(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
                | ((bytes[offset + 1] & 0xff) << 8)
                | ((bytes[offset + 2] & 0xff) << 16);
    }

    private static void validateImageDimensions(int width, int height) {
        if (width <= 0
                || height <= 0
                || width > MAX_IMAGE_WIDTH
                || height > MAX_IMAGE_HEIGHT
                || (long) width * height > MAX_IMAGE_PIXELS) {
            throw new AssetValidationException(
                    IMAGE_DIMENSIONS_EXCEEDED,
                    "The image dimensions exceed the accepted limits");
        }
    }

    private static FileStorageException storageReadFailure(IOException exception) {
        return new FileStorageException(
                FileStorageException.Code.STORAGE_IO_ERROR,
                "The staged object cannot be read",
                exception);
    }
}
