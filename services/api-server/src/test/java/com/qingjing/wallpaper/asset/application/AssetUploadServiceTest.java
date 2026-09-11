package com.qingjing.wallpaper.asset.application;

import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.DECLARED_TYPE_MISMATCH;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_JSON;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.TYPE_NOT_ALLOWED_FOR_PURPOSE;
import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.UNSAFE_ARCHIVE_ENTRY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AssetUploadServiceTest {

    @TempDir
    Path temporaryDirectory;

    private Path storageRoot;
    private FileStorage storage;
    private AssetUploadService service;

    @BeforeEach
    void setUp() {
        storageRoot = temporaryDirectory.resolve("storage");
        storage = new LocalFileStorage(storageRoot);
        AssetContentValidator validator = new AssetContentValidator(storage, new ObjectMapper());
        service = new AssetUploadService(storage, validator);
    }

    @Test
    void detectsImageMetadataAndDoesNotOverwriteSameNamedUploads() throws Exception {
        byte[] image = png(3, 2);

        ValidatedAsset first = service.upload(
                new ByteArrayInputStream(image),
                "../../cover.png",
                "image/png",
                AssetPurpose.WALLPAPER_COVER);
        ValidatedAsset second = service.upload(
                new ByteArrayInputStream(image),
                "../../cover.png",
                "image/png; charset=binary",
                AssetPurpose.WALLPAPER_COVER);

        assertThat(first.originalFilename()).isEqualTo("cover.png");
        assertThat(first.mimeType()).isEqualTo("image/png");
        assertThat(first.fileExtension()).isEqualTo("png");
        assertThat(first.widthPixels()).isEqualTo(3);
        assertThat(first.heightPixels()).isEqualTo(2);
        assertThat(first.sha256()).hasSize(64).isEqualTo(second.sha256());
        assertThat(first.storageKey()).isNotEqualTo(second.storageKey());
        assertThat(first.storageKey().value()).doesNotContain("cover.png", temporaryDirectory.toString());

        try (StoredContent storedContent = storage.open(first.storageKey())) {
            assertThat(storedContent.inputStream().readAllBytes()).isEqualTo(image);
        }
        try (StoredContent storedContent = storage.open(second.storageKey())) {
            assertThat(storedContent.inputStream().readAllBytes()).isEqualTo(image);
        }
    }

    @Test
    void rejectsDeclaredTypeMismatchAndCleansStaging() throws Exception {
        assertThatThrownBy(() -> service.upload(
                        new ByteArrayInputStream(png(1, 1)),
                        "icon.jpg",
                        "image/jpeg",
                        AssetPurpose.CATEGORY_ICON))
                .isInstanceOfSatisfying(
                        AssetValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(DECLARED_TYPE_MISMATCH));

        assertStorageEmpty();
    }

    @Test
    void purposeRulesRejectJpegForegroundEvenWhenDeclaredTypeMatches() throws Exception {
        assertThatThrownBy(() -> service.upload(
                        new ByteArrayInputStream(jpeg(2, 2)),
                        "foreground.jpg",
                        "image/jpeg",
                        AssetPurpose.FOREGROUND))
                .isInstanceOfSatisfying(
                        AssetValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(TYPE_NOT_ALLOWED_FOR_PURPOSE));

        assertStorageEmpty();
    }

    @Test
    void validatesJsonObjectsAndRejectsMalformedConfiguration() {
        byte[] valid = "{\"layers\":[]}".getBytes(StandardCharsets.UTF_8);
        ValidatedAsset stored = service.upload(
                new ByteArrayInputStream(valid),
                "config.txt",
                "application/octet-stream",
                AssetPurpose.PARALLAX_CONFIG);

        assertThat(stored.mimeType()).isEqualTo("application/json");
        assertThat(stored.fileExtension()).isEqualTo("json");

        assertThatThrownBy(() -> service.upload(
                        new ByteArrayInputStream("{broken".getBytes(StandardCharsets.UTF_8)),
                        "config.json",
                        "application/json",
                        AssetPurpose.PARALLAX_CONFIG))
                .isInstanceOfSatisfying(
                        AssetValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(INVALID_JSON));

        assertThatThrownBy(() -> service.upload(
                        new ByteArrayInputStream("{} {}".getBytes(StandardCharsets.UTF_8)),
                        "two-roots.json",
                        "application/json",
                        AssetPurpose.PARALLAX_CONFIG))
                .isInstanceOfSatisfying(
                        AssetValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(INVALID_JSON));
    }

    @Test
    void validatesSafeArchiveAndRejectsTraversalEntry() throws Exception {
        ValidatedAsset stored = service.upload(
                new ByteArrayInputStream(zip(Map.of("manifest.json", "{}", "assets/cover.txt", "ok"))),
                "theme.zip",
                "application/zip",
                AssetPurpose.THEME_PACKAGE);
        assertThat(stored.mimeType()).isEqualTo("application/zip");

        assertThatThrownBy(() -> service.upload(
                        new ByteArrayInputStream(zip(Map.of("../escape.txt", "unsafe"))),
                        "unsafe.zip",
                        "application/zip",
                        AssetPurpose.THEME_PACKAGE))
                .isInstanceOfSatisfying(
                        AssetValidationException.class,
                        exception -> assertThat(exception.code()).isEqualTo(UNSAFE_ARCHIVE_ENTRY));
    }

    private void assertStorageEmpty() throws IOException {
        try (var staging = Files.list(storageRoot.resolve(".staging"));
                var objects = Files.walk(storageRoot.resolve("objects"))) {
            assertThat(staging).isEmpty();
            assertThat(objects.filter(Files::isRegularFile)).isEmpty();
        }
    }

    private static byte[] png(int width, int height) throws IOException {
        return image("png", width, height);
    }

    private static byte[] jpeg(int width, int height) throws IOException {
        return image("jpg", width, height);
    }

    private static byte[] image(String format, int width, int height) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) {
            throw new IOException("Test image encoder is unavailable: " + format);
        }
        return output.toByteArray();
    }

    private static byte[] zip(Map<String, String> entries) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return output.toByteArray();
    }
}
