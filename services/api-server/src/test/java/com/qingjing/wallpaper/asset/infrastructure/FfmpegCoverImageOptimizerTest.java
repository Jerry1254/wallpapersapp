package com.qingjing.wallpaper.asset.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.AssetContentValidator;
import com.qingjing.wallpaper.asset.application.AssetPurpose;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FfmpegCoverImageOptimizerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsBoundedWebpWithoutUpscaling() throws Exception {
        String ffmpeg = System.getenv().getOrDefault("QJ_FFMPEG", "ffmpeg");
        Assumptions.assumeTrue(canRun(ffmpeg), "FFmpeg is required for the real cover optimization test");
        var storage = new LocalFileStorage(temporaryDirectory.resolve("storage"));
        byte[] source = png(1080, 2160);
        var staged = storage.stage(new ByteArrayInputStream(source), 20L * 1024 * 1024);

        var optimized = new FfmpegCoverImageOptimizer(storage, ffmpeg).optimize(staged);
        var metadata = new AssetContentValidator(storage, new ObjectMapper())
                .validate(optimized, AssetPurpose.WALLPAPER_COVER, "image/webp");

        assertThat(metadata.widthPixels()).isEqualTo(640);
        assertThat(metadata.heightPixels()).isEqualTo(1280);
        assertThat(optimized.sizeBytes()).isLessThanOrEqualTo(FfmpegCoverImageOptimizer.MAX_BYTES);
        assertThat(optimized.sizeBytes()).isLessThan(source.length);
    }

    private static boolean canRun(String executable) {
        try {
            return new ProcessBuilder(executable, "-version").redirectErrorStream(true).start().waitFor() == 0;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static byte[] png(int width, int height) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                image.setRGB(x, y, new Color(x % 256, y % 256, (x + y) % 256).getRGB());
            }
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
