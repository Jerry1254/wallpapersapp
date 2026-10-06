package com.qingjing.wallpaper.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ClassPathResource;

class PreviewWatermarkRendererTest {
    private final String ffmpeg = System.getenv().getOrDefault("QJ_FFMPEG", "ffmpeg");
    private final String ffprobe = System.getenv().getOrDefault("QJ_FFPROBE", "ffprobe");
    private final ObjectMapper mapper = new ObjectMapper();
    @TempDir Path directory;
    private PreviewWatermarkRenderer renderer;

    @BeforeEach
    void setUp() throws Exception {
        Assumptions.assumeTrue(available(ffmpeg) && available(ffprobe), "FFmpeg/FFprobe required for native media tests");
        renderer = new PreviewWatermarkRenderer(mapper, ffmpeg, ffprobe);
    }

    @Test
    void marksAnImageWithoutResizingOrChangingPixelsOutsideTheSingleTextRegion() throws Exception {
        byte[] source = png(241, 419, false);
        byte[] untouched = source.clone();
        BufferedImage before = ImageIO.read(new ByteArrayInputStream(source));
        BufferedImage after = ImageIO.read(new ByteArrayInputStream(renderer.image(source)));

        assertThat(after.getWidth()).isEqualTo(241);
        assertThat(after.getHeight()).isEqualTo(419);
        int changed = 0;
        int maxDifference = 0;
        for (int y = 0; y < after.getHeight(); y++) {
            for (int x = 0; x < after.getWidth(); x++) {
                int previous = before.getRGB(x, y);
                int current = after.getRGB(x, y);
                if (previous != current) {
                    changed++;
                    assertThat(x).isBetween(60, 181);
                    assertThat(y).isBetween(183, 220);
                    Color oldPixel = new Color(previous, true);
                    Color newPixel = new Color(current, true);
                    assertThat(newPixel.getRed()).isGreaterThanOrEqualTo(oldPixel.getRed());
                    assertThat(newPixel.getGreen()).isGreaterThanOrEqualTo(oldPixel.getGreen());
                    assertThat(newPixel.getBlue()).isGreaterThanOrEqualTo(oldPixel.getBlue());
                    maxDifference = Math.max(maxDifference, newPixel.getRed() - oldPixel.getRed());
                }
            }
        }
        assertThat(changed).isGreaterThan(100);
        assertThat(maxDifference).isBetween(25, 40);
        assertThat(source).isEqualTo(untouched);
    }

    @Test
    void preservesForegroundTransparencyAndPlacesOnlyFaintAlphaText() throws Exception {
        byte[] source = png(240, 420, true);
        BufferedImage after = ImageIO.read(new ByteArrayInputStream(renderer.image(source)));
        int nontransparent = 0;
        int maxAlpha = 0;
        for (int y = 0; y < after.getHeight(); y++) {
            for (int x = 0; x < after.getWidth(); x++) {
                int alpha = (after.getRGB(x, y) >>> 24) & 255;
                if (alpha != 0) nontransparent++;
                maxAlpha = Math.max(maxAlpha, alpha);
            }
        }
        assertThat(after.getColorModel().hasAlpha()).isTrue();
        assertThat(nontransparent).isBetween(100, 2000);
        assertThat(maxAlpha).isBetween(37, 39);
        assertThat(after.getRGB(0, 0) >>> 24).isZero();
        assertThat(after.getRGB(239, 419) >>> 24).isZero();
    }

    @Test
    void decodesWebpWithoutLosingDimensions() throws Exception {
        // Lossless 240×420 solid-colour WebP: this tests decoding even on FFmpeg builds without a WebP encoder.
        byte[] source = Base64.getDecoder().decode("UklGRigAAABXRUJQVlA4TBwAAAAv78BoAAdQkGIUpv8BIUHi//U2Ivqf8Z///D8H");
        BufferedImage result = ImageIO.read(new ByteArrayInputStream(renderer.image(source)));
        assertThat(result.getWidth()).isEqualTo(240);
        assertThat(result.getHeight()).isEqualTo(420);
    }

    @Test
    void burnsEveryMp4FrameAndPreservesFractionalRateResolutionAndDuration() throws Exception {
        verifyVideo(false, "30000/1001", 30);
    }

    @Test
    void createsQuickTimePreviewWithoutChangingTiming() throws Exception {
        verifyVideo(true, "25", 50);
    }

    @Test
    void convertsTheActualOneSecondLivePhotoMovieWithoutCopyingItsMetadataTracks() throws Exception {
        byte[] source;
        try (var input = new ClassPathResource("live-photo/wallpaper-metadata-template.mov").getInputStream()) {
            source = input.readAllBytes();
        }
        byte[] unchanged = source.clone();
        Path output = directory.resolve("ios-preview.mov");
        Files.write(output, renderer.video(source, true));
        Path metadata = directory.resolve("ios-streams.json");
        run(List.of(ffprobe, "-v", "error", "-show_entries",
                "stream=codec_type,codec_name,width,height,pix_fmt,color_range,duration,avg_frame_rate,nb_frames:format=duration",
                "-of", "json", output.toString()), metadata);
        JsonNode result = mapper.readTree(Files.readAllBytes(metadata));
        assertThat(result.path("streams").size()).isEqualTo(1);
        JsonNode video = result.path("streams").get(0);
        assertThat(video.path("codec_type").asText()).isEqualTo("video");
        assertThat(video.path("codec_name").asText()).isEqualTo("h264");
        // This real template uses full-range 8-bit 4:2:0; retain its range rather than relabeling it as limited.
        assertThat(video.path("pix_fmt").asText()).isEqualTo("yuvj420p");
        assertThat(video.path("color_range").asText()).isEqualTo("pc");
        assertThat(video.path("width").asInt()).isEqualTo(1080);
        assertThat(video.path("height").asInt()).isEqualTo(1546);
        assertThat(video.path("avg_frame_rate").asText()).isEqualTo("60/1");
        assertThat(video.path("nb_frames").asInt()).isEqualTo(60);
        assertThat(video.path("duration").asDouble()).isEqualTo(1d);
        assertThat(result.path("format").path("duration").asDouble()).isEqualTo(1d);
        assertThat(source).isEqualTo(unchanged);
    }

    @Test
    void refusesMalformedMediaInsteadOfSilentlyReturningAnUnwatermarkedSource() {
        assertThatThrownBy(() -> renderer.image(new byte[] {1, 2, 3})).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> renderer.video(new byte[] {1, 2, 3}, false)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> renderer.image(new byte[0])).isInstanceOf(ApiException.class);
    }

    private void verifyVideo(boolean quickTime, String rate, int frames) throws Exception {
        Path sourceFile = directory.resolve("source.mp4");
        run(List.of(ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                "color=c=0x202020:s=240x420:r=" + rate, "-frames:v", Integer.toString(frames),
                "-c:v", "libx264", "-crf", "0", "-pix_fmt", "yuv420p", sourceFile.toString()), null);
        byte[] source = Files.readAllBytes(sourceFile);
        byte[] unchanged = source.clone();
        byte[] derivative = renderer.video(source, quickTime);
        Path outputFile = directory.resolve(quickTime ? "preview.mov" : "preview.mp4");
        Files.write(outputFile, derivative);
        var inspected = new PackageMediaInspector(mapper, ffprobe, ffmpeg).inspect(derivative, true);
        assertThat(inspected.width()).isEqualTo(240);
        assertThat(inspected.height()).isEqualTo(420);
        JsonNode before = probe(sourceFile);
        JsonNode after = probe(outputFile);
        assertThat(after.path("width").asInt()).isEqualTo(before.path("width").asInt());
        assertThat(after.path("height").asInt()).isEqualTo(before.path("height").asInt());
        assertThat(after.path("avg_frame_rate").asText()).isEqualTo(before.path("avg_frame_rate").asText());
        assertThat(after.path("nb_frames").asInt()).isEqualTo(frames);
        assertThat(after.path("duration").asDouble()).isCloseTo(before.path("duration").asDouble(),
                org.assertj.core.data.Offset.offset(0.01));
        Path raw = directory.resolve("decoded.gray");
        run(List.of(ffmpeg, "-v", "error", "-y", "-nostdin", "-i", outputFile.toString(),
                "-pix_fmt", "gray", "-f", "rawvideo", raw.toString()), null);
        byte[] pixels = Files.readAllBytes(raw);
        int framePixels = 240 * 420;
        assertThat(pixels.length).isEqualTo(framePixels * frames);
        for (int frame = 0; frame < frames; frame++) {
            int max = 0;
            int corner = pixels[frame * framePixels] & 255;
            for (int i = 0; i < framePixels; i++) max = Math.max(max, pixels[frame * framePixels + i] & 255);
            assertThat(max - corner).isBetween(20, 45);
        }
        assertThat(Arrays.equals(source, derivative)).isFalse();
        assertThat(source).isEqualTo(unchanged);
        assertThat(Files.readAllBytes(sourceFile)).isEqualTo(unchanged);
    }

    private JsonNode probe(Path input) throws Exception {
        Path output = directory.resolve("test-probe.json");
        run(List.of(ffprobe, "-v", "error", "-select_streams", "v:0", "-show_entries",
                "stream=width,height,duration,avg_frame_rate,nb_frames", "-of", "json", input.toString()), output);
        return mapper.readTree(Files.readAllBytes(output)).path("streams").get(0);
    }

    private static byte[] png(int width, int height, boolean transparent) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        if (!transparent) {
            var graphics = image.createGraphics();
            graphics.setColor(new Color(24, 32, 48));
            graphics.fillRect(0, 0, width, height);
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static boolean available(String executable) {
        try {
            Process process = new ProcessBuilder(executable, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception exception) { return false; }
    }

    private void run(List<String> command, Path output) throws Exception {
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.INHERIT);
        if (output == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            assertThat(process.waitFor(Duration.ofSeconds(30).toMillis(), TimeUnit.MILLISECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
