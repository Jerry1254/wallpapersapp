package com.qingjing.wallpaper.asset.infrastructure;

import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.FILE_TOO_LARGE;
import static com.qingjing.wallpaper.asset.application.FileStorageException.Code.STORAGE_BOUNDARY_VIOLATION;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.qingjing.wallpaper.asset.application.FileStorageException;
import com.qingjing.wallpaper.asset.application.StagedObject;
import com.qingjing.wallpaper.asset.application.StorageKey;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.asset.application.StoredObject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalFileStorageTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void stagesHashesCommitsAndReadsThroughOpaqueKeys() throws Exception {
        LocalFileStorage storage = new LocalFileStorage(temporaryDirectory.resolve("storage"));
        byte[] content = "validated content".getBytes(StandardCharsets.UTF_8);

        StagedObject staged = storage.stage(new ByteArrayInputStream(content), content.length);
        assertThat(staged.sizeBytes()).isEqualTo(content.length);
        assertThat(staged.sha256()).isEqualTo(sha256(content));

        try (StoredContent stagedContent = storage.openStaged(staged)) {
            assertThat(stagedContent.inputStream().readAllBytes()).isEqualTo(content);
        }

        StoredObject stored = storage.commit(staged, "JSON");
        assertThat(stored.storageKey().value())
                .matches("objects/[a-f0-9]{2}/[a-f0-9]{2}/[a-f0-9-]{36}\\.json")
                .doesNotContain(temporaryDirectory.toString());
        try (StoredContent storedContent = storage.open(stored.storageKey())) {
            assertThat(storedContent.sizeBytes()).isEqualTo(content.length);
            assertThat(storedContent.inputStream().readAllBytes()).isEqualTo(content);
        }
    }

    @Test
    void rejectsOversizedStreamsAndRemovesPartialStagingFile() throws IOException {
        Path root = temporaryDirectory.resolve("storage");
        LocalFileStorage storage = new LocalFileStorage(root);

        assertThatThrownBy(() -> storage.stage(new ByteArrayInputStream(new byte[] {1, 2, 3, 4}), 3))
                .isInstanceOfSatisfying(
                        FileStorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(FILE_TOO_LARGE));

        try (var stagedFiles = Files.list(root.resolve(".staging"))) {
            assertThat(stagedFiles).isEmpty();
        }
    }

    @Test
    void storageKeysRejectAbsoluteTraversalAndAlternateSeparators() {
        assertThatThrownBy(() -> new StorageKey("/etc/passwd"))
                .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> new StorageKey("objects/../secret"))
                .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> new StorageKey("objects\\secret"))
                .isInstanceOf(FileStorageException.class);
        assertThatThrownBy(() -> new StorageKey("C:/secret"))
                .isInstanceOf(FileStorageException.class);
    }

    @Test
    void controlledReadRejectsKeysOutsideTheObjectNamespace() {
        LocalFileStorage storage = new LocalFileStorage(temporaryDirectory.resolve("storage"));

        assertThatThrownBy(() -> storage.open(new StorageKey("private/secret.bin")))
                .isInstanceOfSatisfying(
                        FileStorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(STORAGE_BOUNDARY_VIOLATION));
    }

    @Test
    void deletesOnlyTheObjectAddressedByItsOpaqueKey() {
        LocalFileStorage storage = new LocalFileStorage(temporaryDirectory.resolve("storage"));
        StagedObject firstStage = storage.stage(new ByteArrayInputStream(new byte[] {1}), 1);
        StagedObject secondStage = storage.stage(new ByteArrayInputStream(new byte[] {2}), 1);
        StoredObject first = storage.commit(firstStage, "bin");
        StoredObject second = storage.commit(secondStage, "bin");

        storage.delete(first.storageKey());

        assertThatThrownBy(() -> storage.open(first.storageKey()))
                .isInstanceOf(FileStorageException.class);
        try (StoredContent remaining = storage.open(second.storageKey())) {
            assertThat(remaining.sizeBytes()).isEqualTo(1);
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        storage.delete(first.storageKey());
    }

    @Test
    void commitRejectsStagedContentChangedAfterHashing() throws IOException {
        Path root = temporaryDirectory.resolve("storage");
        LocalFileStorage storage = new LocalFileStorage(root);
        StagedObject staged = storage.stage(new ByteArrayInputStream(new byte[] {1, 2, 3}), 3);
        Files.write(root.resolve(".staging").resolve(staged.token()), new byte[] {3, 2, 1});

        assertThatThrownBy(() -> storage.commit(staged, "bin"))
                .isInstanceOfSatisfying(
                        FileStorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(STORAGE_BOUNDARY_VIOLATION));
    }

    @Test
    void controlledReadRejectsSymlinkEscape() throws IOException {
        Path root = temporaryDirectory.resolve("storage");
        LocalFileStorage storage = new LocalFileStorage(root);
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "outside");
        Path link = root.resolve("objects/link.txt");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException exception) {
            Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
        }

        assertThatThrownBy(() -> storage.open(new StorageKey("objects/link.txt")))
                .isInstanceOfSatisfying(
                        FileStorageException.class,
                        exception -> assertThat(exception.code()).isEqualTo(STORAGE_BOUNDARY_VIOLATION));
    }

    private static String sha256(byte[] content) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
    }
}
