package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Generates separate preview derivatives. Callers must never replace formal source assets. */
@Component
public final class PreviewWatermarkRenderer {
    private static final Logger log = LoggerFactory.getLogger(PreviewWatermarkRenderer.class);
    private static final String TEXT = "预览专用";
    private static final float OPACITY = 0.15f;
    private static final long MAX_BYTES = 256L * 1024 * 1024;
    private static final long MAX_PIXELS = 32L * 1024 * 1024;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration IMAGE_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration VIDEO_TIMEOUT = Duration.ofSeconds(180);

    private final ObjectMapper mapper;
    private final String ffmpeg;
    private final String ffprobe;
    private final Font font;

    public PreviewWatermarkRenderer(
            ObjectMapper mapper,
            @Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg,
            @Value("${qingjing.delivery.ffprobe:ffprobe}") String ffprobe) {
        this.mapper = mapper;
        this.ffmpeg = ffmpeg;
        this.ffprobe = ffprobe;
        try (var input = new ClassPathResource("preview-watermark/qingjing-preview.ttf").getInputStream()) {
            // The bundled OFL subset makes Chinese glyphs identical on Linux and macOS, without
            // installed system fonts or FFmpeg's optional drawtext/libfreetype build feature.
            this.font = Font.createFont(Font.TRUETYPE_FONT, input);
            if (font.canDisplayUpTo(TEXT) != -1) throw new IOException("Missing watermark glyphs");
        } catch (Exception exception) {
            throw new IllegalStateException("The bundled preview watermark font cannot be loaded", exception);
        }
    }

    /** Lossless PNG encoding, preserving source dimensions and transparent pixels outside the mark. */
    public byte[] image(byte[] source) {
        validateBytes(source);
        Path directory = null;
        try {
            directory = Files.createTempDirectory("qj-preview-image-");
            Path input = directory.resolve("source.image");
            Files.write(input, source);
            VideoInfo info = probe(input, directory);
            BufferedImage original = ImageIO.read(new ByteArrayInputStream(source));
            if (original == null) {
                Path decoded = directory.resolve("decoded.png");
                run(List.of(ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                        "-protocol_whitelist", "file,pipe", "-i", input.toString(),
                        "-map", "0:v:0", "-frames:v", "1", "-an", "-sn", "-dn",
                        "-c:v", "png", "-pix_fmt", "rgba", decoded.toString()), directory, IMAGE_TIMEOUT, null);
                original = ImageIO.read(decoded.toFile());
            }
            if (original == null || original.getWidth() != info.width() || original.getHeight() != info.height()) {
                throw new IOException("Unexpected decoded image dimensions");
            }
            BufferedImage result = new BufferedImage(info.width(), info.height(), BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = result.createGraphics();
            try {
                graphics.setComposite(AlphaComposite.Src);
                graphics.drawImage(original, 0, 0, null);
                graphics.setComposite(AlphaComposite.SrcOver);
                graphics.drawImage(watermark(info.width(), info.height()), 0, 0, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            if (!ImageIO.write(result, "png", output)) throw new IOException("PNG encoder unavailable");
            byte[] bytes = output.toByteArray();
            validateBytes(bytes);
            return bytes;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(exception);
        } catch (Exception exception) {
            throw failure(exception);
        } finally {
            deleteTree(directory);
        }
    }

    /** Burns the same mark into every frame without resizing, trimming, or forcing a new frame rate. */
    public byte[] video(byte[] source, boolean quickTime) {
        validateBytes(source);
        Path directory = null;
        try {
            directory = Files.createTempDirectory("qj-preview-video-");
            Path input = directory.resolve("source.video");
            Path overlay = directory.resolve("watermark.png");
            Path output = directory.resolve(quickTime ? "preview.mov" : "preview.mp4");
            Files.write(input, source);
            VideoInfo before = probe(input, directory);
            if (!Double.isFinite(before.durationSeconds()) || before.durationSeconds() <= 0
                    || !Double.isFinite(before.frameRate()) || before.frameRate() <= 0) {
                throw new IOException("Invalid source video timing");
            }
            ImageIO.write(watermark(before.width(), before.height()), "png", overlay.toFile());
            boolean tenBit = before.pixelFormat().contains("10") || before.pixelFormat().contains("12");
            String pixelFormat = tenBit ? "yuv420p10le"
                    : ((before.width() & 1) != 0 || (before.height() & 1) != 0) ? "yuv444p" : "yuv420p";
            String overlayFormat = tenBit ? "yuv420p10" : pixelFormat.equals("yuv444p") ? "yuv444" : "yuv420";
            boolean fullRange = before.colorRange().equals("pc") || before.pixelFormat().startsWith("yuvj");
            String filter = "[0:v:0][1:v:0]overlay=x=0:y=0:format=" + overlayFormat
                    + ":eof_action=repeat:repeatlast=1[v]";
            if (fullRange) {
                // Overlay works in limited-range YUV. Explicitly convert both ways rather than
                // merely tagging its limited-range samples as full-range (which changes contrast).
                filter = "[0:v:0]scale=in_range=pc:out_range=tv,format=" + pixelFormat + "[base];"
                        + "[base][1:v:0]overlay=x=0:y=0:format=" + overlayFormat
                        + ":eof_action=repeat:repeatlast=1,scale=in_range=tv:out_range=pc[v]";
                if (!tenBit) pixelFormat = pixelFormat.equals("yuv444p") ? "yuvj444p" : "yuvj420p";
            }
            List<String> command = new ArrayList<>(List.of(
                    ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin", "-noautorotate",
                    "-filter_complex_threads", "1",
                    "-protocol_whitelist", "file,pipe", "-i", input.toString(),
                    "-protocol_whitelist", "file,pipe", "-i", overlay.toString(),
                    "-filter_complex", filter,
                    "-map", "[v]", "-map", "0:a?", "-map_metadata", "0", "-sn", "-dn",
                    "-c:v", tenBit ? "libx265" : "libx264", "-threads", "2", "-preset", "medium", "-crf", "12",
                    "-pix_fmt", pixelFormat, "-fps_mode", "passthrough", "-c:a", "copy",
                    "-movflags", "+faststart"));
            if (fullRange) command.addAll(List.of("-color_range", "pc"));
            if (tenBit) command.addAll(List.of("-tag:v", "hvc1", "-x265-params", "pools=2:frame-threads=2:log-level=error"));
            command.addAll(List.of("-f", quickTime ? "mov" : "mp4", output.toString()));
            try {
                run(command, directory, VIDEO_TIMEOUT, null);
            } catch (IOException unsupported) {
                // Jammy's FFmpeg 4.4 predates fps_mode; recent FFmpeg removed vsync.
                // Only retry this specific option error, using the equivalent timestamp mode.
                if (!unsupported.getMessage().contains("Unrecognized option 'fps_mode'")) throw unsupported;
                int mode = command.indexOf("-fps_mode");
                command.set(mode, "-vsync");
                command.set(mode + 1, "0");
                run(command, directory, VIDEO_TIMEOUT, null);
            }
            VideoInfo after = probe(output, directory);
            if (before.width() != after.width() || before.height() != after.height()
                    || Math.abs(before.durationSeconds() - after.durationSeconds()) > 0.05
                    || Math.abs(before.frameRate() - after.frameRate()) > 0.01
                    || (before.frames() > 0 && after.frames() > 0 && before.frames() != after.frames())) {
                throw new IOException("Preview video dimensions or timing changed");
            }
            if (!Files.isRegularFile(output) || Files.size(output) > MAX_BYTES) {
                throw new IOException("Preview derivative exceeds its size limit");
            }
            byte[] bytes = Files.readAllBytes(output);
            validateBytes(bytes);
            return bytes;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(exception);
        } catch (Exception exception) {
            throw failure(exception);
        } finally {
            deleteTree(directory);
        }
    }

    private BufferedImage watermark(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, OPACITY));
            graphics.setColor(Color.WHITE);
            Font scaledFont = font.deriveFont(Math.max(1f, Math.min(width, height) * 0.12f));
            TextLayout text = new TextLayout(TEXT, scaledFont, graphics.getFontRenderContext());
            Rectangle2D bounds = text.getBounds();
            float x = (float) ((width - bounds.getWidth()) / 2d - bounds.getX());
            float baseline = (float) (height * 0.48d - bounds.getCenterY());
            // One flat, translucent mark; no stroke, shadow, glow, or badge.
            text.draw(graphics, x, baseline);
        } finally {
            graphics.dispose();
        }
        return image;
    }

    private VideoInfo probe(Path input, Path directory) throws Exception {
        Path output = Files.createTempFile(directory, "probe-", ".json");
        run(List.of(ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,pix_fmt,color_range,avg_frame_rate,nb_frames,duration:format=duration",
                "-of", "json", input.toString()), directory, PROBE_TIMEOUT, output);
        if (Files.size(output) > 128 * 1024) throw new IOException("Unexpected probe output size");
        JsonNode root = mapper.readTree(Files.readAllBytes(output));
        JsonNode streams = root.path("streams");
        if (!streams.isArray() || streams.size() != 1) throw new IOException("No source video/image stream");
        JsonNode stream = streams.get(0);
        int width = stream.path("width").asInt();
        int height = stream.path("height").asInt();
        if (width < 1 || height < 1 || width > 16384 || height > 16384 || (long) width * height > MAX_PIXELS) {
            throw new IOException("Invalid source dimensions");
        }
        String expression = stream.path("avg_frame_rate").asText("0/1");
        double duration = number(stream.path("duration").asText());
        if (!Double.isFinite(duration)) duration = number(root.path("format").path("duration").asText());
        return new VideoInfo(width, height, stream.path("pix_fmt").asText(), stream.path("color_range").asText(), rate(expression),
                duration, stream.path("nb_frames").asLong(-1));
    }

    private static double rate(String expression) {
        String[] parts = expression.split("/", 2);
        double denominator = parts.length == 2 ? number(parts[1]) : 1d;
        return denominator > 0 ? number(parts[0]) / denominator : Double.NaN;
    }

    private static double number(String value) {
        try { return Double.parseDouble(value); }
        catch (NumberFormatException exception) { return Double.NaN; }
    }

    private static void validateBytes(byte[] source) {
        if (source == null || source.length == 0 || source.length > MAX_BYTES) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PREVIEW_WATERMARK_FAILED",
                    "The preview source size is not supported");
        }
    }

    private static void run(List<String> command, Path directory, Duration timeout, Path output)
            throws IOException, InterruptedException {
        Path error = Files.createTempFile(directory, "native-error-", ".log");
        ProcessBuilder builder = new ProcessBuilder(command).redirectError(error.toFile());
        if (output == null) builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        else builder.redirectOutput(output.toFile());
        Process process = builder.start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) {
                String diagnostic = Files.readString(error, StandardCharsets.UTF_8);
                if (diagnostic.length() > 1000) diagnostic = diagnostic.substring(0, 1000);
                throw new IOException("Native preview processing failed: " + diagnostic);
            }
        } finally {
            if (process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    private static ApiException failure(Exception exception) {
        log.warn("Preview watermark derivative could not be generated: {}", exception.toString());
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PREVIEW_WATERMARK_FAILED",
                "The preview watermark derivative could not be generated");
    }

    private static void deleteTree(Path directory) {
        if (directory == null) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private record VideoInfo(int width, int height, String pixelFormat, String colorRange, double frameRate,
            double durationSeconds, long frames) { }
}
