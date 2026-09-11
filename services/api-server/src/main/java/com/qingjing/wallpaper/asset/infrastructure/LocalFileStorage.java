package com.qingjing.wallpaper.asset.infrastructure;

import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.FILE_NOT_FOUND;
import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.FILE_TOO_LARGE;
import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.INVALID_EXTENSION;
import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.STORAGE_BOUNDARY_VIOLATION;
import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.STORAGE_IO_ERROR;

import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.FileStorageException;
import com.qingjing.wallpaper.asset.application.StagedObject;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.asset.application.StoredObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

public final class LocalFileStorage implements FileStorage {

    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private final Path root;
    private final Path stagingRoot;
    private final Path objectsRoot;

    public LocalFileStorage(Path configuredRoot) {
        Objects.requireNonNull(configuredRoot, "configuredRoot");
        this.root = configuredRoot.toAbsolutePath().normalize();
        this.stagingRoot = root.resolve(".staging");
        this.objectsRoot = root.resolve("objects");
        initialize();
    }

    @Override
    public StagedObject stage(InputStream source, long maximumBytes) {
        Objects.requireNonNull(source, "source");
        if (maximumBytes < 1) {
            throw new IllegalArgumentException("maximumBytes must be positive");
        }

        String token = UUID.randomUUID().toString();
        Path stagingFile = stagingRoot.resolve(token);
        MessageDigest digest = sha256Digest();
        long size = 0;
        byte[] buffer = new byte[COPY_BUFFER_SIZE];

        try (OutputStream output = Files.newOutputStream(
                stagingFile,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            int read;
            while ((read = source.read(buffer)) != -1) {
                if (size > maximumBytes - read) {
                    throw new FileStorageException(FILE_TOO_LARGE, "The uploaded file exceeds its size limit");
                }
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                size += read;
            }
            output.flush();
            return new StagedObject(token, size, HexFormat.of().formatHex(digest.digest()));
        } catch (FileStorageException exception) {
            deleteAfterFailure(stagingFile, exception);
            throw exception;
        } catch (IOException exception) {
            deleteAfterFailure(stagingFile, exception);
            throw new FileStorageException(STORAGE_IO_ERROR, "The uploaded file cannot be staged", exception);
        } catch (RuntimeException exception) {
            deleteAfterFailure(stagingFile, exception);
            throw exception;
        }
    }

    @Override
    public StoredContent openStaged(StagedObject stagedObject) {
        Objects.requireNonNull(stagedObject, "stagedObject");
        return openWithin(stagingRoot, stagingRoot.resolve(stagedObject.token()));
    }

    @Override
    public StoredObject commit(StagedObject stagedObject, String fileExtension) {
        Objects.requireNonNull(stagedObject, "stagedObject");
        String extension = normalizeExtension(fileExtension);
        Path source = stagingRoot.resolve(stagedObject.token());

        for (int attempt = 0; attempt < 10; attempt++) {
            String objectId = UUID.randomUUID().toString();
            String firstShard = objectId.substring(0, 2);
            String secondShard = objectId.substring(2, 4);
            StorageKey key = new StorageKey(
                    "objects/" + firstShard + "/" + secondShard + "/" + objectId + "." + extension);
            Path target = resolveCommitTarget(key);
            try {
                validateStagedSource(source, stagedObject);
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
                return new StoredObject(key, stagedObject.sizeBytes(), stagedObject.sha256());
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // A random collision must never overwrite an existing object.
            } catch (AtomicMoveNotSupportedException exception) {
                throw new FileStorageException(
                        STORAGE_IO_ERROR,
                        "The storage filesystem does not support atomic moves",
                        exception);
            } catch (NoSuchFileException exception) {
                throw new FileStorageException(FILE_NOT_FOUND, "The staged object no longer exists", exception);
            } catch (IOException exception) {
                throw new FileStorageException(STORAGE_IO_ERROR, "The staged object cannot be committed", exception);
            }
        }
        throw new FileStorageException(STORAGE_IO_ERROR, "A unique storage key could not be allocated");
    }

    @Override
    public StoredContent open(StorageKey storageKey) {
        Objects.requireNonNull(storageKey, "storageKey");
        if (!storageKey.value().startsWith("objects/")) {
            throw new FileStorageException(STORAGE_BOUNDARY_VIOLATION, "The storage key is outside object storage");
        }
        Path candidate = root.resolve(storageKey.value()).normalize();
        if (!candidate.startsWith(objectsRoot)) {
            throw new FileStorageException(STORAGE_BOUNDARY_VIOLATION, "The storage key escapes object storage");
        }
        return openWithin(objectsRoot, candidate);
    }

    @Override
    public void discard(StagedObject stagedObject) {
        Objects.requireNonNull(stagedObject, "stagedObject");
        Path candidate = stagingRoot.resolve(stagedObject.token()).normalize();
        if (!candidate.startsWith(stagingRoot)) {
            throw new FileStorageException(STORAGE_BOUNDARY_VIOLATION, "The staging token escapes staging storage");
        }
        try {
            Files.deleteIfExists(candidate);
        } catch (IOException exception) {
            throw new FileStorageException(STORAGE_IO_ERROR, "The staged object cannot be discarded", exception);
        }
    }

    private StoredContent openWithin(Path boundary, Path candidate) {
        try {
            Path realBoundary = boundary.toRealPath();
            Path realFile = candidate.toRealPath();
            if (!realFile.startsWith(realBoundary)
                    || !Files.isRegularFile(realFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new FileStorageException(
                        STORAGE_BOUNDARY_VIOLATION,
                        "The stored object resolves outside its storage boundary");
            }
            return new StoredContent(Files.newInputStream(realFile, StandardOpenOption.READ), Files.size(realFile));
        } catch (FileStorageException exception) {
            throw exception;
        } catch (NoSuchFileException exception) {
            throw new FileStorageException(FILE_NOT_FOUND, "The stored object does not exist", exception);
        } catch (IOException exception) {
            throw new FileStorageException(STORAGE_IO_ERROR, "The stored object cannot be opened", exception);
        }
    }

    private Path resolveCommitTarget(StorageKey key) {
        Path candidate = root.resolve(key.value()).normalize();
        if (!candidate.startsWith(objectsRoot)) {
            throw new FileStorageException(STORAGE_BOUNDARY_VIOLATION, "The target escapes object storage");
        }
        Path parent = candidate.getParent();
        try {
            Files.createDirectories(parent);
            Path realObjectsRoot = objectsRoot.toRealPath();
            Path realParent = parent.toRealPath();
            if (!realParent.startsWith(realObjectsRoot)) {
                throw new FileStorageException(
                        STORAGE_BOUNDARY_VIOLATION,
                        "The target directory resolves outside object storage");
            }
            return realParent.resolve(candidate.getFileName());
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileStorageException(STORAGE_IO_ERROR, "The object directory cannot be prepared", exception);
        }
    }

    private void validateStagedSource(Path source, StagedObject stagedObject) throws IOException {
        Path realStagingRoot = stagingRoot.toRealPath();
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(realStagingRoot)
                || !Files.isRegularFile(realSource, LinkOption.NOFOLLOW_LINKS)
                || Files.size(realSource) != stagedObject.sizeBytes()
                || !sha256(realSource).equals(stagedObject.sha256())) {
            throw new FileStorageException(
                    STORAGE_BOUNDARY_VIOLATION,
                    "The staged object changed before it was committed");
        }
    }

    private String normalizeExtension(String fileExtension) {
        if (fileExtension == null) {
            throw new FileStorageException(INVALID_EXTENSION, "The file extension is invalid");
        }
        String normalized = fileExtension.toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9]{1,16}")) {
            throw new FileStorageException(INVALID_EXTENSION, "The file extension is invalid");
        }
        return normalized;
    }

    private void initialize() {
        try {
            Files.createDirectories(stagingRoot);
            Files.createDirectories(objectsRoot);
            Path realRoot = root.toRealPath();
            if (!stagingRoot.toRealPath().startsWith(realRoot) || !objectsRoot.toRealPath().startsWith(realRoot)) {
                throw new FileStorageException(
                        STORAGE_BOUNDARY_VIOLATION,
                        "The storage directories resolve outside the configured root");
            }
        } catch (FileStorageException exception) {
            throw exception;
        } catch (IOException exception) {
            throw new FileStorageException(STORAGE_IO_ERROR, "The local storage cannot be initialized", exception);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String sha256(Path source) throws IOException {
        MessageDigest digest = sha256Digest();
        byte[] buffer = new byte[COPY_BUFFER_SIZE];
        try (InputStream input = Files.newInputStream(source, StandardOpenOption.READ)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void deleteAfterFailure(Path path, Exception failure) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }
}
