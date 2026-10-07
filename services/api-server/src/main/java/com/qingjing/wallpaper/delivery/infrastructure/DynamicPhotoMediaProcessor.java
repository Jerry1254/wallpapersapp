package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Bounded native-media worker used by the two platform dynamic-photo publishers.
 * Source files and generated paths are always local temporary files; commands never
 * receive user-controlled options or remote URLs.
 */
@Component
public final class DynamicPhotoMediaProcessor {
    private static final Logger log = LoggerFactory.getLogger(DynamicPhotoMediaProcessor.class);
    private static final long MAX_VIDEO_BYTES = 80L * 1024 * 1024;
    private static final long MAX_PHOTO_BYTES = 20L * 1024 * 1024;
    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration VIDEO_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration PHOTO_TIMEOUT = Duration.ofSeconds(60);
    private static final String TEMPLATE_IDENTIFIER = "3E42CF7C-5CD8-4A94-814C-61448ED6D2D6";

    public enum ProcessingMode { PASSTHROUGH, REMUX, TRANSCODE }

    public record MovingPhoto(
            byte[] video,
            byte[] poster,
            int width,
            int height,
            long durationMs,
            String inputVideoCodec,
            String outputVideoCodec,
            double frameRate,
            ProcessingMode processingMode) { }

    public record LivePhoto(
            byte[] photo,
            byte[] video,
            String assetIdentifier,
            int width,
            int height,
            long durationMs,
            String inputVideoCodec,
            String outputVideoCodec,
            double frameRate,
            ProcessingMode processingMode) { }

    private record VideoInfo(
            String codec,
            String codecTag,
            String pixelFormat,
            int width,
            int height,
            String frameRateExpression,
            double frameRate,
            long decodedFrames,
            double durationSeconds) { }

    private final ObjectMapper mapper;
    private final String ffprobe;
    private final String ffmpeg;
    private final String mp4Box;
    private final String heifEncoder;
    private final String exifTool;

    public DynamicPhotoMediaProcessor(
            ObjectMapper mapper,
            @Value("${qingjing.delivery.ffprobe:ffprobe}") String ffprobe,
            @Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg,
            @Value("${qingjing.delivery.mp4box:MP4Box}") String mp4Box,
            @Value("${qingjing.delivery.heif-encoder:heif-enc}") String heifEncoder,
            @Value("${qingjing.delivery.exiftool:exiftool}") String exifTool) {
        this.mapper = mapper;
        this.ffprobe = ffprobe;
        this.ffmpeg = ffmpeg;
        this.mp4Box = mp4Box;
        this.heifEncoder = heifEncoder;
        this.exifTool = exifTool;
    }

    public MovingPhoto movingPhoto(byte[] content) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("qj-moving-photo-");
            Path source = directory.resolve("source.mp4");
            Path video = directory.resolve("video.mp4");
            Path poster = directory.resolve("poster.jpg");
            Files.write(source, content);
            VideoInfo input = probeSource(source, 2d);
            ProcessingMode mode = createBoundedVideo(source, video, input, 2d, false);
            VideoInfo output = probeGeneratedVideo(video, 2d, input.width(), input.height(), false);
            createPoster(video, poster, 0d, false);
            verifyImage(poster, input.width(), input.height(), "image/jpeg");
            byte[] videoBytes = boundedBytes(video, MAX_VIDEO_BYTES);
            byte[] posterBytes = boundedBytes(poster, MAX_PHOTO_BYTES);
            return new MovingPhoto(videoBytes, posterBytes, output.width(), output.height(), 2000,
                    input.codec(), output.codec(), output.frameRate(), mode);
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw processingFailed("MOVING_PHOTO_PROCESSING_FAILED");
        } catch (Exception exception) {
            throw processingFailed("MOVING_PHOTO_PROCESSING_FAILED");
        } finally {
            deleteTree(directory);
        }
    }

    public LivePhoto livePhoto(byte[] content) {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("qj-live-photo-");
            Path source = directory.resolve("source.mp4");
            Path boundedVideo = directory.resolve("bounded.mov");
            Path frame = directory.resolve("frame.png");
            Path photo = directory.resolve("photo.heic");
            Path video = directory.resolve("video.mov");
            Path movieTemplate = directory.resolve("metadata-template.mov");
            Path photoTemplate = directory.resolve("maker-note-template.heic");
            Files.write(source, content);
            copyResource("live-photo/wallpaper-metadata-template.mov", movieTemplate);
            copyResource("live-photo/apple-maker-note-template.heic", photoTemplate);

            VideoInfo input = probeSource(source, 0d);
            createLivePhotoVideo(source, boundedVideo, input.width(), input.height());
            createPosterAtFrame(boundedVideo, frame, 30);
            createHeic(frame, photo);

            String identifier = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);
            writeHeicIdentifier(photo, photoTemplate, identifier);
            createLivePhotoMovie(boundedVideo, movieTemplate, video, identifier);
            VideoInfo output = probeGeneratedLivePhoto(video, input.width(), input.height());
            verifyHeic(photo, input.width(), input.height(), identifier);
            verifyMovieMetadata(video, identifier);

            return new LivePhoto(
                    boundedBytes(photo, MAX_PHOTO_BYTES),
                    boundedBytes(video, MAX_VIDEO_BYTES),
                    identifier,
                    output.width(),
                    output.height(),
                    1000,
                    input.codec(),
                    output.codec(),
                    output.frameRate(),
                    ProcessingMode.TRANSCODE);
        } catch (ApiException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        } catch (Exception exception) {
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        } finally {
            deleteTree(directory);
        }
    }

    private VideoInfo probeSource(Path source, double minimumSeconds) throws Exception {
        VideoInfo info = probeVideo(source, false, false);
        if (!List.of("h264", "hevc").contains(info.codec()) || info.width() < 1 || info.height() < 1
                || info.width() > 4096 || info.height() > 4096
                || !Double.isFinite(info.frameRate()) || info.frameRate() <= 0 || info.frameRate() > 60) {
            throw formatInvalid();
        }
        if (info.pixelFormat().startsWith("yuv420") && ((info.width() & 1) != 0 || (info.height() & 1) != 0)) {
            throw formatInvalid();
        }
        if (!Double.isFinite(info.durationSeconds())
                || (minimumSeconds > 0d && info.durationSeconds() + 0.0005d < minimumSeconds)) {
            throw durationInvalid();
        }
        return info;
    }

    private VideoInfo probeVideo(Path input, boolean requireMetadataTracks, boolean countFrames) throws Exception {
        Path output = Files.createTempFile(input.getParent(), "probe-", ".json");
        try {
            List<String> command = new ArrayList<>(List.of(
                    ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe"));
            if (countFrames) command.add("-count_frames");
            command.addAll(List.of(
                    "-show_streams", "-show_format",
                    "-of", "json", input.toString()));
            run(command, output, PROBE_TIMEOUT);
            if (Files.size(output) > 128 * 1024) throw formatInvalid();
            JsonNode root = mapper.readTree(Files.readAllBytes(output));
            JsonNode streams = root.path("streams");
            if (!streams.isArray() || streams.isEmpty() || streams.size() > 8) throw formatInvalid();
            JsonNode video = null;
            int dataTracks = 0;
            for (JsonNode stream : streams) {
                String type = stream.path("codec_type").asText();
                if (type.equals("video")) {
                    if (video != null) throw formatInvalid();
                    video = stream;
                } else if (type.equals("data")) {
                    dataTracks++;
                    if (requireMetadataTracks && !stream.path("codec_tag_string").asText().equals("mebx")) {
                        throw formatInvalid();
                    }
                } else if (type.equals("audio")) {
                    if (requireMetadataTracks) throw formatInvalid();
                } else {
                    throw formatInvalid();
                }
            }
            if (video == null || (requireMetadataTracks && dataTracks != 2)) throw formatInvalid();
            int rotation = video.path("tags").path("rotate").asInt(0);
            JsonNode sideData = video.path("side_data_list");
            if (sideData.isArray()) {
                for (JsonNode item : sideData) rotation += item.path("rotation").asInt(0);
            }
            if (rotation % 360 != 0) throw formatInvalid();
            return new VideoInfo(
                    video.path("codec_name").asText(),
                    video.path("codec_tag_string").asText(),
                    video.path("pix_fmt").asText(),
                    video.path("width").asInt(),
                    video.path("height").asInt(),
                    video.path("avg_frame_rate").asText(),
                    rate(video.path("avg_frame_rate").asText()),
                    countFrames ? frameCount(video.path("nb_read_frames")) : -1L,
                    root.path("format").path("duration").asDouble(Double.NaN));
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private ProcessingMode createBoundedVideo(
            Path source,
            Path output,
            VideoInfo input,
            double seconds,
            boolean quickTime) throws Exception {
        List<String> copy = baseFfmpeg(source);
        copy.addAll(List.of("-t", duration(seconds), "-map", "0:v:0", "-an", "-sn", "-dn",
                "-c:v", "copy", "-avoid_negative_ts", "make_zero", "-fflags", "+genpts",
                "-map_metadata", "-1"));
        if (input.codec().equals("hevc")) copy.addAll(List.of("-tag:v", "hvc1"));
        copy.addAll(List.of("-movflags", "+faststart", output.toString()));
        if (tryRun(copy, VIDEO_TIMEOUT)) {
            try {
                VideoInfo copied = probeGeneratedVideo(output, seconds, input.width(), input.height(), quickTime);
                if (copied.codec().equals(input.codec())) return ProcessingMode.REMUX;
            } catch (ApiException ignored) {
                // Sample boundaries can extend past the fixed interval. Rebuild the interval in that case.
            }
        }
        Files.deleteIfExists(output);
        List<String> transcode = baseFfmpeg(source);
        transcode.addAll(List.of("-t", duration(seconds), "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", "setpts=PTS-STARTPTS,format=yuv420p",
                "-c:v", input.codec().equals("hevc") ? "libx265" : "libx264",
                "-preset", "medium", "-crf", "12", "-map_metadata", "-1"));
        if (input.codec().equals("hevc")) transcode.addAll(List.of("-tag:v", "hvc1"));
        transcode.addAll(List.of("-movflags", "+faststart", output.toString()));
        runQuietly(transcode, VIDEO_TIMEOUT);
        probeGeneratedVideo(output, seconds, input.width(), input.height(), quickTime);
        return ProcessingMode.TRANSCODE;
    }

    void createLivePhotoVideo(Path source, Path output, int width, int height) throws Exception {
        if (countDisplayFrames(source, 60) < 60) throw frameCountInvalid();
        List<String> command = baseFfmpeg(source);
        command.addAll(List.of(
                "-map", "0:v:0", "-an", "-sn", "-dn",
                "-vf", "select=lt(n\\,60),setpts=N/(60*TB),format=yuv420p",
                "-frames:v", "60", "-r", "60",
                "-c:v", "libx265", "-preset", "medium", "-crf", "12",
                "-x265-params", "pools=1:frame-threads=1:log-level=error",
                "-tag:v", "hvc1", "-video_track_timescale", "600",
                "-map_metadata", "-1", "-movflags", "+faststart", output.toString()));
        runQuietly(command, VIDEO_TIMEOUT);
        VideoInfo normalized = probeVideo(output, false, true);
        if (normalized.decodedFrames() < 60) throw frameCountInvalid();
        validateLivePhotoVideo(normalized, width, height);
    }

    private long countDisplayFrames(Path source, int maximum) throws Exception {
        Path output = Files.createTempFile(source.getParent(), "frame-count-", ".txt");
        try {
            run(List.of(
                    ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                    "-protocol_whitelist", "file,pipe", "-threads", "1", "-i", source.toString(),
                    "-map", "0:v:0", "-an", "-sn", "-dn", "-frames:v", Integer.toString(maximum),
                    "-f", "framemd5", "-"), output, VIDEO_TIMEOUT);
            if (Files.size(output) > 128 * 1024) throw formatInvalid();
            try (var lines = Files.lines(output, StandardCharsets.UTF_8)) {
                return lines.filter(line -> !line.isBlank() && !line.startsWith("#")).count();
            }
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private List<String> baseFfmpeg(Path source) {
        return new ArrayList<>(List.of(
                ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                "-protocol_whitelist", "file,pipe", "-threads", "1", "-i", source.toString()));
    }

    private VideoInfo probeGeneratedVideo(
            Path output,
            double requiredSeconds,
            int width,
            int height,
            boolean quickTime) throws Exception {
        VideoInfo info = probeVideo(output, false, false);
        double frameWindow = Math.max(0.002d, 1d / info.frameRate() + 0.002d);
        double maximumDuration = quickTime ? requiredSeconds + 0.002d : requiredSeconds + frameWindow;
        if (!List.of("h264", "hevc").contains(info.codec()) || info.width() != width || info.height() != height
                || info.frameRate() <= 0 || info.frameRate() > 60
                || info.durationSeconds() + 0.002d < requiredSeconds
                || info.durationSeconds() > maximumDuration
                || (!info.pixelFormat().equals("yuv420p") && !info.pixelFormat().equals("yuvj420p"))) {
            throw processingFailed(quickTime ? "LIVE_PHOTO_PROCESSING_FAILED" : "MOVING_PHOTO_PROCESSING_FAILED");
        }
        return info;
    }

    private void createPoster(Path video, Path output, double second, boolean png) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                "-protocol_whitelist", "file,pipe", "-threads", "1",
                "-ss", duration(second), "-i", video.toString(), "-map", "0:v:0", "-frames:v", "1"));
        if (png) command.addAll(List.of("-compression_level", "1"));
        else command.addAll(List.of("-q:v", "1"));
        command.add(output.toString());
        runQuietly(command, PHOTO_TIMEOUT);
    }

    private void createPosterAtFrame(Path video, Path output, int frameIndex) throws Exception {
        List<String> command = new ArrayList<>(List.of(
                ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                "-protocol_whitelist", "file,pipe", "-threads", "1", "-i", video.toString(),
                "-map", "0:v:0", "-vf", "select=eq(n\\," + frameIndex + ")", "-frames:v", "1",
                "-compression_level", "1", output.toString()));
        runQuietly(command, PHOTO_TIMEOUT);
    }

    private void createHeic(Path frame, Path output) throws Exception {
        if (tryRun(List.of(heifEncoder, "-q", "100", "-o", output.toString(), frame.toString()), PHOTO_TIMEOUT)) {
            return;
        }
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac")
                && tryRun(List.of("/usr/bin/sips", "-s", "format", "heic", "-s", "formatOptions", "best",
                        frame.toString(), "--out", output.toString()), PHOTO_TIMEOUT)) {
            return;
        }
        throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
    }

    private void writeHeicIdentifier(Path photo, Path template, String identifier) throws Exception {
        runQuietly(List.of(exifTool, "-overwrite_original", "-TagsFromFile", template.toString(),
                "-Make", "-Model", "-MakerNotes", photo.toString()), PHOTO_TIMEOUT);
        runQuietly(List.of(exifTool, "-overwrite_original", "-Make=Apple", "-Model=iPad",
                "-Apple:ContentIdentifier=" + identifier, photo.toString()), PHOTO_TIMEOUT);
    }

    private void createLivePhotoMovie(Path source, Path template, Path output, String identifier) throws Exception {
        Files.copy(template, output, StandardCopyOption.REPLACE_EXISTING);
        runQuietly(List.of(mp4Box, "-rem", "1", output.toString()), PHOTO_TIMEOUT, output.getParent());
        runQuietly(List.of(mp4Box, "-add", source + "#video:ID=1:tkidx=1", output.toString()),
                VIDEO_TIMEOUT, output.getParent());
        runQuietly(List.of(exifTool, "-overwrite_original", "-Keys:ContentIdentifier=" + identifier,
                output.toString()), PHOTO_TIMEOUT);
    }

    private VideoInfo probeGeneratedLivePhoto(Path video, int width, int height) throws Exception {
        VideoInfo info = probeVideo(video, true, true);
        validateLivePhotoVideo(info, width, height);
        String boxes = new String(Files.readAllBytes(video), StandardCharsets.ISO_8859_1);
        if (!boxes.contains("com.apple.quicktime.live-photo-info")
                || !boxes.contains("com.apple.quicktime.still-image-time")
                || !boxes.contains("com.apple.quicktime.live-photo-still-image-transform")) {
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        }
        return info;
    }

    private void validateLivePhotoVideo(VideoInfo info, int width, int height) {
        if (!info.codec().equals("hevc") || !info.codecTag().equals("hvc1")
                || !info.pixelFormat().equals("yuv420p")
                || info.width() != width || info.height() != height
                || !info.frameRateExpression().equals("60/1") || info.frameRate() != 60d
                || info.decodedFrames() != 60
                || info.durationSeconds() < 0.998d || info.durationSeconds() > 1.002d) {
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        }
    }

    private void verifyMovieMetadata(Path movie, String identifier) throws Exception {
        JsonNode root = exifJson(movie, "-Keys:ContentIdentifier");
        String actual = firstText(root, "ContentIdentifier");
        if (!identifier.equals(actual) || TEMPLATE_IDENTIFIER.equals(actual)) {
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        }
    }

    private void verifyHeic(Path photo, int width, int height, String identifier) throws Exception {
        JsonNode root = exifJson(photo, "-ImageWidth", "-ImageHeight", "-Apple:ContentIdentifier");
        if (firstInt(root, "ImageWidth") != width || firstInt(root, "ImageHeight") != height
                || !identifier.equals(firstText(root, "ContentIdentifier"))) {
            throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
        }
    }

    private JsonNode exifJson(Path input, String... fields) throws Exception {
        Path output = Files.createTempFile(input.getParent(), "exif-", ".json");
        try {
            List<String> command = new ArrayList<>(List.of(exifTool, "-j", "-n"));
            command.addAll(List.of(fields));
            command.add(input.toString());
            run(command, output, PHOTO_TIMEOUT);
            JsonNode root = mapper.readTree(Files.readAllBytes(output));
            if (!root.isArray() || root.size() != 1) throw processingFailed("LIVE_PHOTO_PROCESSING_FAILED");
            return root.get(0);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    private static String firstText(JsonNode root, String field) {
        JsonNode direct = root.get(field);
        if (direct != null) return direct.asText();
        for (var fields = root.fields(); fields.hasNext();) {
            var item = fields.next();
            if (item.getKey().endsWith(":" + field)) return item.getValue().asText();
        }
        return "";
    }

    private static int firstInt(JsonNode root, String field) {
        String value = firstText(root, field);
        try { return Integer.parseInt(value); }
        catch (NumberFormatException exception) { return -1; }
    }

    private void verifyImage(Path image, int width, int height, String mimeType) throws IOException {
        byte[] bytes = boundedBytes(image, MAX_PHOTO_BYTES);
        java.awt.image.BufferedImage decoded = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
        if (decoded == null || decoded.getWidth() != width || decoded.getHeight() != height
                || !mimeType.equals("image/jpeg")) {
            throw processingFailed("MOVING_PHOTO_PROCESSING_FAILED");
        }
        decoded.flush();
    }

    private static byte[] boundedBytes(Path path, long maximum) throws IOException {
        long size = Files.size(path);
        if (size < 1 || size > maximum) throw new IOException("Generated media exceeds its configured bound");
        return Files.readAllBytes(path);
    }

    private static double rate(String text) {
        String[] parts = text.split("/", -1);
        try {
            double value = parts.length == 2
                    ? Double.parseDouble(parts[0]) / Double.parseDouble(parts[1])
                    : Double.parseDouble(text);
            return Double.isFinite(value) ? value : Double.NaN;
        } catch (RuntimeException exception) {
            return Double.NaN;
        }
    }

    private static long frameCount(JsonNode value) {
        try {
            long count = Long.parseLong(value.asText());
            return count >= 0 ? count : -1L;
        } catch (RuntimeException exception) {
            return -1L;
        }
    }

    private static String duration(double seconds) {
        return String.format(Locale.ROOT, "%.3f", seconds);
    }

    private void copyResource(String name, Path target) throws IOException {
        try (var input = new ClassPathResource(name).getInputStream()) {
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private boolean tryRun(List<String> args, Duration timeout) throws InterruptedException {
        try {
            runQuietly(args, timeout);
            return true;
        } catch (IOException | ApiException exception) {
            return false;
        }
    }

    private void run(List<String> args, Path output, Duration timeout) throws IOException, InterruptedException {
        run(args, output, timeout, null);
    }

    private void run(
            List<String> args,
            Path output,
            Duration timeout,
            Path workingDirectory) throws IOException, InterruptedException {
        Path error = Files.createTempFile(output.getParent(), "native-error-", ".log");
        Process process = null;
        try {
            try {
                ProcessBuilder builder = new ProcessBuilder(args)
                        .redirectOutput(output.toFile())
                        .redirectError(error.toFile());
                if (workingDirectory != null) {
                    builder.directory(workingDirectory.toFile());
                    builder.environment().put("HOME", workingDirectory.toString());
                }
                process = builder.start();
            } catch (IOException exception) {
                log.warn("Native media executable could not be started: executable={}, reason={}",
                        args.get(0), exception.getMessage());
                throw exception;
            }
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished || process.exitValue() != 0) {
                String diagnostic = Files.exists(error)
                        ? Files.readString(error, StandardCharsets.UTF_8).replaceAll("[\\r\\n]+", " ")
                        : "";
                if (diagnostic.length() > 4000) diagnostic = diagnostic.substring(0, 4000);
                log.warn("Native media command failed: executable={}, finished={}, exit={}, diagnostic={}",
                        args.get(0), finished, finished ? process.exitValue() : "timeout", diagnostic);
                throw new IOException("Native media command failed");
            }
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            Files.deleteIfExists(error);
        }
    }

    private void runQuietly(List<String> args, Duration timeout) throws IOException, InterruptedException {
        Path output = Files.createTempFile("qj-native-output-", ".log");
        try { run(args, output, timeout); }
        finally { Files.deleteIfExists(output); }
    }

    private void runQuietly(List<String> args, Duration timeout, Path workingDirectory)
            throws IOException, InterruptedException {
        Path output = Files.createTempFile(workingDirectory, "native-output-", ".log");
        try { run(args, output, timeout, workingDirectory); }
        finally { Files.deleteIfExists(output); }
    }

    private static void deleteTree(Path root) {
        if (root == null) return;
        try (var paths = Files.walk(root)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); } catch (IOException ignored) { }
            });
        } catch (IOException ignored) { }
    }

    private static ApiException formatInvalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DYNAMIC_SOURCE_FORMAT_INVALID",
                "The dynamic-photo source format is not supported");
    }

    private static ApiException durationInvalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "DYNAMIC_SOURCE_DURATION_INVALID",
                "The dynamic-photo source is shorter than the platform interval");
    }

    private static ApiException frameCountInvalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IOS_LIVE_PHOTO_FRAME_COUNT_INVALID",
                "The iOS Live Photo source must contain at least 60 display frames");
    }

    private static ApiException processingFailed(String code) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code,
                "The platform dynamic-photo media could not be generated");
    }
}
