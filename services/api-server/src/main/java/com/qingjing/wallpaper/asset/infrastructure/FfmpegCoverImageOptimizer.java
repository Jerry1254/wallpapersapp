package com.qingjing.wallpaper.asset.infrastructure;

import static com.qingjing.wallpaper.asset.application.AssetValidationException.Code.INVALID_IMAGE;

import com.qingjing.wallpaper.asset.application.AssetValidationException;
import com.qingjing.wallpaper.asset.application.CoverImageOptimizer;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.application.StagedObject;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

public final class FfmpegCoverImageOptimizer implements CoverImageOptimizer {

    static final int MAX_WIDTH = 720;
    static final int MAX_HEIGHT = 1280;
    static final long TARGET_BYTES = 300L * 1024;
    static final long MAX_BYTES = 512L * 1024;
    private static final List<Integer> QUALITY_STEPS = List.of(78, 70, 62, 54, 46);
    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final FileStorage storage;
    private final String ffmpeg;

    public FfmpegCoverImageOptimizer(FileStorage storage, String ffmpeg) {
        this.storage = storage;
        this.ffmpeg = ffmpeg;
    }

    @Override
    public StagedObject optimize(StagedObject source) {
        Path input = null;
        Path output = null;
        try {
            input = Files.createTempFile("qj-cover-source-", ".image");
            output = Files.createTempFile("qj-cover-optimized-", ".webp");
            try (var content = storage.openStaged(source)) {
                Files.copy(content.inputStream(), input, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }

            for (int quality : QUALITY_STEPS) {
                transcode(input, output, quality);
                if (Files.size(output) <= TARGET_BYTES) break;
            }
            if (!Files.isRegularFile(output) || Files.size(output) < 1 || Files.size(output) > MAX_BYTES) {
                throw invalid();
            }
            try (InputStream optimized = Files.newInputStream(output)) {
                return storage.stage(optimized, MAX_BYTES);
            }
        } catch (AssetValidationException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw invalid();
        } catch (Exception exception) {
            throw invalid();
        } finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (java.io.IOException ignored) { }
            try { if (output != null) Files.deleteIfExists(output); } catch (java.io.IOException ignored) { }
        }
    }

    private void transcode(Path input, Path output, int quality) throws Exception {
        Process process = new ProcessBuilder(List.of(
                ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                "-protocol_whitelist", "file,pipe", "-i", input.toString(),
                "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
                "-vf", "scale=w='min(720,iw)':h='min(1280,ih)':force_original_aspect_ratio=decrease",
                "-map_metadata", "-1", "-c:v", "libwebp", "-preset", "picture",
                "-compression_level", "4", "-quality", Integer.toString(quality), output.toString()))
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                throw invalid();
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static AssetValidationException invalid() {
        return new AssetValidationException(INVALID_IMAGE, "The wallpaper cover cannot be optimized");
    }
}
