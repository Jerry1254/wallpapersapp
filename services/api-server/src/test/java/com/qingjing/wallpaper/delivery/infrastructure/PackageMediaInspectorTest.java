package com.qingjing.wallpaper.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class PackageMediaInspectorTest {
    private final String ffmpeg = System.getenv().getOrDefault("QJ_FFMPEG", "ffmpeg");
    private final String ffprobe = System.getenv().getOrDefault("QJ_FFPROBE", "ffprobe");

    @Test
    void normalizesHevcVideoWithAudioForAndroidPlayback() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version"),
                "FFmpeg and FFprobe are required");
        Assumptions.assumeTrue(supportsEncoder("libx265"), "libx265 is required for the HEVC fixture");
        Path source = Files.createTempFile("qj-hevc-audio-", ".mp4");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin",
                    "-f", "lavfi", "-i", "testsrc2=size=64x96:rate=12",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100",
                    "-t", "1", "-map", "0:v:0", "-map", "1:a:0",
                    "-c:v", "libx265", "-pix_fmt", "yuv420p", "-threads", "1",
                    "-x265-params", "pools=1:frame-threads=1:log-level=error", "-c:a", "aac", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            byte[] original = Files.readAllBytes(source);
            var inspector = new PackageMediaInspector(new ObjectMapper(), ffprobe, ffmpeg);
            byte[] normalized = inspector.androidVideo(original, true);

            assertThat(normalized).isNotEqualTo(original);
            assertThat(inspector.inspect(normalized, true)).isEqualTo(
                    new PackageMediaInspector.Media(64, 96, false));
            assertThat(streamTypes(normalized)).containsExactly("h264,video");
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void leavesCompatibleAndroidVideoByteIdentical() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version"),
                "FFmpeg and FFprobe are required");
        Path source = Files.createTempFile("qj-h264-", ".mp4");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                    "testsrc2=size=64x96:rate=12", "-t", "1", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-threads", "1", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            byte[] original = Files.readAllBytes(source);
            var inspector = new PackageMediaInspector(new ObjectMapper(), ffprobe, ffmpeg);
            assertThat(inspector.androidVideo(original, true)).isEqualTo(original);
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void createsBoundedHarmonyMovingPhotoPair() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version"),
                "FFmpeg and FFprobe are required");
        Path source = Files.createTempFile("qj-moving-photo-source-", ".mov");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                    "testsrc2=size=64x96:rate=12", "-t", "3", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-threads", "1", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            var result = new DynamicPhotoMediaProcessor(new ObjectMapper(), ffprobe, ffmpeg,
                    "MP4Box", "heif-enc", "exiftool")
                    .movingPhoto(Files.readAllBytes(source));

            assertThat(result.durationMs()).isEqualTo(2000L);
            assertThat(result.width()).isEqualTo(64);
            assertThat(result.height()).isEqualTo(96);
            assertThat(result.inputVideoCodec()).isEqualTo("h264");
            assertThat(result.outputVideoCodec()).isEqualTo("h264");
            assertThat(result.frameRate()).isEqualTo(12d);
            assertThat(result.processingMode()).isIn(
                    DynamicPhotoMediaProcessor.ProcessingMode.REMUX,
                    DynamicPhotoMediaProcessor.ProcessingMode.TRANSCODE);
            assertThat(streamTypes(result.video())).containsExactly("h264,video");
            assertThat(result.poster()).startsWith((byte) 0xff, (byte) 0xd8, (byte) 0xff);
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void shipsTheApprovedLivePhotoMetadataFixture() throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/live-photo/wallpaper-metadata-template.mov")) {
            assertThat(input).isNotNull();
            fixture = input.readAllBytes();
        }
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(fixture)))
                .isEqualTo("39b7239d8cb0a442b659948ac2e47eaba05e5922771f12614355042ae073a1f0");
    }

    @Test
    void createsOneSecondLivePhotoWithMatchingAppleMetadata() throws Exception {
        String mp4Box = System.getenv().getOrDefault("QJ_MP4BOX", "MP4Box");
        String heifEncoder = System.getenv().getOrDefault("QJ_HEIF_ENCODER", "heif-enc");
        String exifTool = System.getenv().getOrDefault("QJ_EXIFTOOL", "exiftool");
        boolean canCreateHeic = commandOutputContains(
                List.of(heifEncoder, "--list-encoders"), "x265")
                || (System.getProperty("os.name", "").toLowerCase().contains("mac")
                    && Files.isExecutable(Path.of("/usr/bin/sips")));
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version")
                        && canRun(mp4Box, "-version") && canRun(exifTool, "-ver") && canCreateHeic,
                "Live Photo native media tools are required");
        Path source = Files.createTempFile("qj-live-photo-source-", ".mp4");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                    "testsrc2=size=64x96:rate=30", "-frames:v", "61", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-threads", "1", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            var result = new DynamicPhotoMediaProcessor(new ObjectMapper(), ffprobe, ffmpeg,
                    mp4Box, heifEncoder, exifTool).livePhoto(Files.readAllBytes(source));

            assertThat(result.durationMs()).isEqualTo(1000L);
            assertThat(result.width()).isEqualTo(64);
            assertThat(result.height()).isEqualTo(96);
            assertThat(result.assetIdentifier()).matches("[0-9A-F-]{36}");
            assertThat(result.photo()).isNotEmpty();
            assertThat(result.video()).isNotEmpty();
            assertThat(result.outputVideoCodec()).isEqualTo("hevc");
            assertThat(result.frameRate()).isEqualTo(60d);
            assertThat(result.processingMode()).isEqualTo(DynamicPhotoMediaProcessor.ProcessingMode.TRANSCODE);
            assertThat(probeDuration(result.video())).isBetween(0.998d, 1.002d);
            var facts = probeVideoFacts(result.video());
            assertThat(facts.path("codec_name").asText()).isEqualTo("hevc");
            assertThat(facts.path("codec_tag_string").asText()).isEqualTo("hvc1");
            assertThat(facts.path("avg_frame_rate").asText()).isEqualTo("60/1");
            assertThat(facts.path("nb_read_frames").asLong()).isEqualTo(60L);
            for (int frameIndex : List.of(0, 15, 30, 45, 59)) {
                assertThat(meanAbsoluteDifference(
                        decodeRgbFrame(Files.readAllBytes(source), frameIndex),
                        decodeRgbFrame(result.video(), frameIndex)))
                        .as("output frame %s must preserve input frame %s", frameIndex + 1, frameIndex + 1)
                        .isLessThan(12d);
            }
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void standardizesFirstSixtyIosFramesWithoutChangingTheirOrder() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version")
                        && supportsEncoder("libx265"),
                "FFmpeg, FFprobe and libx265 are required");
        Path source = Files.createTempFile("qj-live-photo-frames-", ".mp4");
        Path output = Files.createTempFile("qj-live-photo-normalized-", ".mov");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                    "testsrc2=size=64x96:rate=30", "-frames:v", "61", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-threads", "1", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            var processor = new DynamicPhotoMediaProcessor(new ObjectMapper(), ffprobe, ffmpeg,
                    "missing-MP4Box", "missing-heif-enc", "missing-exiftool");
            processor.createLivePhotoVideo(source, output, 64, 96);

            byte[] normalized = Files.readAllBytes(output);
            var facts = probeVideoFacts(normalized);
            assertThat(facts.path("codec_name").asText()).isEqualTo("hevc");
            assertThat(facts.path("codec_tag_string").asText()).isEqualTo("hvc1");
            assertThat(facts.path("avg_frame_rate").asText()).isEqualTo("60/1");
            assertThat(facts.path("nb_read_frames").asLong()).isEqualTo(60L);
            assertThat(probeDuration(normalized)).isBetween(0.998d, 1.002d);
            assertThat(streamTypes(normalized)).containsExactly("hevc,video");
            byte[] original = Files.readAllBytes(source);
            for (int frameIndex : List.of(0, 15, 30, 45, 59)) {
                assertThat(meanAbsoluteDifference(
                        decodeRgbFrame(original, frameIndex), decodeRgbFrame(normalized, frameIndex)))
                        .as("output frame %s must preserve input frame %s", frameIndex + 1, frameIndex + 1)
                        .isLessThan(12d);
            }
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(output); }
    }

    @Test
    void rejectsIosSourceWithFewerThanSixtyDisplayFrames() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version")
                        && supportsEncoder("libx265"),
                "FFmpeg, FFprobe and libx265 are required");
        Path source = Files.createTempFile("qj-live-photo-short-", ".mp4");
        try {
            Process process = new ProcessBuilder(List.of(
                    ffmpeg, "-v", "error", "-y", "-nostdin", "-f", "lavfi", "-i",
                    "testsrc2=size=64x96:rate=30", "-frames:v", "59", "-c:v", "libx264",
                    "-pix_fmt", "yuv420p", "-threads", "1", source.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();

            var processor = new DynamicPhotoMediaProcessor(new ObjectMapper(), ffprobe, ffmpeg,
                    "missing-MP4Box", "missing-heif-enc", "missing-exiftool");
            Path output = Files.createTempFile("qj-live-photo-short-output-", ".mov");
            assertThatThrownBy(() -> processor.createLivePhotoVideo(source, output, 64, 96))
                    .isInstanceOfSatisfying(ApiException.class,
                            failure -> assertThat(failure.code())
                                    .isEqualTo("IOS_LIVE_PHOTO_FRAME_COUNT_INVALID"));
            Files.deleteIfExists(output);
        } finally { Files.deleteIfExists(source); }
    }

    @Test
    void rejectsDynamicPhotoSourceWithDisplayMatrixRotation() throws Exception {
        Assumptions.assumeTrue(canRun(ffmpeg, "-version") && canRun(ffprobe, "-version"),
                "FFmpeg and FFprobe are required");
        Path source = Files.createTempFile("qj-rotation-source-", ".mp4");
        Path rotated = Files.createTempFile("qj-rotation-metadata-", ".mp4");
        try {
            var create = new ProcessBuilder(ffmpeg, "-v", "error", "-y", "-nostdin",
                    "-f", "lavfi", "-i", "testsrc2=size=64x96:rate=30", "-t", "2",
                    "-c:v", "libx264", "-pix_fmt", "yuv420p", "-threads", "1", source.toString())
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(create.waitFor(30, TimeUnit.SECONDS)).isTrue();
            assertThat(create.exitValue()).isZero();
            // Set the track display matrix directly: FFmpeg versions differ in rotate-tag remux handling.
            byte[] content = Files.readAllBytes(source);
            int trackHeader = -1;
            for (int index = 4; index < content.length - 100; index++) {
                if (content[index] == 't' && content[index + 1] == 'k'
                        && content[index + 2] == 'h' && content[index + 3] == 'd') {
                    trackHeader = index + 4;
                    break;
                }
            }
            assertThat(trackHeader).isPositive();
            var matrix = java.nio.ByteBuffer.wrap(content);
            int matrixOffset = trackHeader + (content[trackHeader] == 1 ? 52 : 40);
            int[] quarterTurn = {0, 65536, 0, -65536, 0, 0, 0, 0, 1073741824};
            for (int index = 0; index < quarterTurn.length; index++) {
                matrix.putInt(matrixOffset + index * 4, quarterTurn[index]);
            }
            Files.write(rotated, content);
            var processor = new DynamicPhotoMediaProcessor(new ObjectMapper(), ffprobe, ffmpeg,
                    "missing-MP4Box", "missing-heif-enc", "missing-exiftool");
            assertThatThrownBy(() -> processor.movingPhoto(Files.readAllBytes(rotated)))
                    .isInstanceOfSatisfying(ApiException.class,
                            failure -> assertThat(failure.code()).isEqualTo("DYNAMIC_SOURCE_FORMAT_INVALID"));
        } finally { Files.deleteIfExists(source); Files.deleteIfExists(rotated); }
    }

    private double probeDuration(byte[] content) throws Exception {
        Path input = Files.createTempFile("qj-live-photo-duration-", ".mov");
        Path output = Files.createTempFile("qj-live-photo-duration-", ".txt");
        try {
            Files.write(input, content);
            Process process = new ProcessBuilder(List.of(ffprobe, "-v", "error", "-show_entries",
                    "format=duration", "-of", "default=nw=1:nk=1", input.toString()))
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Double.parseDouble(Files.readString(output).strip());
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }

    private com.fasterxml.jackson.databind.JsonNode probeVideoFacts(byte[] content) throws Exception {
        Path input = Files.createTempFile("qj-live-photo-facts-", ".mov");
        Path output = Files.createTempFile("qj-live-photo-facts-", ".json");
        try {
            Files.write(input, content);
            Process process = new ProcessBuilder(List.of(ffprobe, "-v", "error", "-count_frames",
                    "-select_streams", "v:0", "-show_entries",
                    "stream=codec_name,codec_tag_string,avg_frame_rate,nb_read_frames", "-of", "json",
                    input.toString()))
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return new ObjectMapper().readTree(Files.readAllBytes(output)).path("streams").get(0);
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }

    private byte[] decodeRgbFrame(byte[] content, int frameIndex) throws Exception {
        Path input = Files.createTempFile("qj-live-photo-frame-", ".mov");
        Path output = Files.createTempFile("qj-live-photo-frame-", ".rgb");
        try {
            Files.write(input, content);
            Process process = new ProcessBuilder(List.of(ffmpeg, "-v", "error", "-xerror", "-y", "-nostdin",
                    "-i", input.toString(), "-map", "0:v:0", "-vf",
                    "select=eq(n\\," + frameIndex + "),format=rgb24", "-frames:v", "1", "-f", "rawvideo",
                    output.toString()))
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readAllBytes(output);
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }

    private static double meanAbsoluteDifference(byte[] first, byte[] second) {
        assertThat(second).hasSameSizeAs(first);
        long sum = 0;
        for (int index = 0; index < first.length; index++) {
            sum += Math.abs(Byte.toUnsignedInt(first[index]) - Byte.toUnsignedInt(second[index]));
        }
        return (double) sum / first.length;
    }

    private List<String> streamTypes(byte[] content) throws Exception {
        Path input = Files.createTempFile("qj-normalized-", ".mp4");
        Path output = Files.createTempFile("qj-normalized-", ".csv");
        try {
            Files.write(input, content);
            Process process = new ProcessBuilder(List.of(ffprobe, "-v", "error", "-show_entries",
                    "stream=codec_name,codec_type", "-of", "csv=p=0", input.toString()))
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            assertThat(process.waitFor(15, TimeUnit.SECONDS)).isTrue();
            assertThat(process.exitValue()).isZero();
            return Files.readAllLines(output).stream().filter(value -> !value.isBlank()).toList();
        } finally { Files.deleteIfExists(input); Files.deleteIfExists(output); }
    }

    private boolean supportsEncoder(String encoder) throws Exception {
        Path output = Files.createTempFile("qj-encoders-", ".txt");
        try {
            Process process = new ProcessBuilder(ffmpeg, "-hide_banner", "-encoders")
                    .redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if (!process.waitFor(15, TimeUnit.SECONDS) || process.exitValue() != 0) return false;
            return Files.readString(output).contains(encoder);
        } finally { Files.deleteIfExists(output); }
    }

    private boolean canRun(String executable, String argument) {
        try {
            Process process = new ProcessBuilder(executable, argument)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS) && process.exitValue() == 0;
        } catch (Exception ignored) { return false; }
    }

    private boolean commandOutputContains(List<String> command, String expected) {
        Path output = null;
        try {
            output = Files.createTempFile("qj-command-output-", ".txt");
            Process process = new ProcessBuilder(command)
                    .redirectOutput(output.toFile())
                    .redirectErrorStream(true)
                    .start();
            return process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS)
                    && process.exitValue() == 0
                    && Files.readString(output).contains(expected);
        } catch (Exception ignored) {
            return false;
        } finally {
            if (output != null) {
                try { Files.deleteIfExists(output); } catch (Exception ignored) { }
            }
        }
    }
}
