package com.qingjing.wallpaper.delivery.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
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
}
