package com.qingjing.wallpaper.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
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
                    "testsrc2=size=64x96:rate=12", "-t", "2", "-c:v", "libx264",
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
            assertThat(probeDuration(result.video())).isBetween(0.998d, 1.002d);
        } finally { Files.deleteIfExists(source); }
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
