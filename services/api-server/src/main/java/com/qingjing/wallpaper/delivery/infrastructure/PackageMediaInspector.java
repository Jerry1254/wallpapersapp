package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** External decoder boundary; temporary filesystem paths never enter business records or responses. */
@Component
public final class PackageMediaInspector {
    private final ObjectMapper mapper;
    private final String ffprobe, ffmpeg;
    public PackageMediaInspector(ObjectMapper mapper,
            @Value("${qingjing.delivery.ffprobe:ffprobe}") String ffprobe,
            @Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg) {
        this.mapper = mapper; this.ffprobe = ffprobe; this.ffmpeg = ffmpeg;
    }
    public record Media(int width, int height, boolean alpha) {}
    public Media inspect(byte[] content, boolean video) {
        Path input = null, output = null;
        try {
            input = Files.createTempFile("qj-media-", ".bin");
            output = Files.createTempFile("qj-probe-", ".json");
            Files.write(input, content);
            run(List.of(ffprobe, "-v", "error", "-protocol_whitelist", "file,pipe", "-show_entries",
                    "stream=codec_name,codec_type,width,height,pix_fmt,avg_frame_rate:format=duration", "-of", "json", input.toString()), output, Duration.ofSeconds(15));
            if (Files.size(output) > 65536) throw invalid();
            JsonNode metadata = mapper.readTree(Files.readAllBytes(output));
            JsonNode streams = metadata.path("streams");
            if (!streams.isArray() || streams.size() != 1) throw invalid();
            JsonNode stream = streams.get(0);
            int width = stream.path("width").asInt(), height = stream.path("height").asInt();
            String codec = stream.path("codec_name").asText(), pixels = stream.path("pix_fmt").asText();
            if (!stream.path("codec_type").asText().equals("video") || width < 1 || height < 1 || width > 4096 || height > 4096) throw invalid();
            if (video) {
                double seconds = metadata.path("format").path("duration").asDouble(Double.NaN);
                String[] rate = stream.path("avg_frame_rate").asText().split("/");
                double fps = rate.length == 2 ? Double.parseDouble(rate[0]) / Double.parseDouble(rate[1]) : Double.NaN;
                if (!codec.equals("h264") || !Double.isFinite(seconds) || seconds <= 0 || seconds > 30 ||
                        !Double.isFinite(fps) || fps <= 0 || fps > 60 || !pixels.equals("yuv420p")) throw invalid();
            } else if (!List.of("png", "mjpeg", "webp").contains(codec)) throw invalid();
            // Decode the whole accepted asset, not just its header. No audio, scripts or network protocols.
            var decode = new ArrayList<>(List.of(ffmpeg, "-v", "error", "-xerror", "-nostdin", "-protocol_whitelist", "file,pipe",
                    "-threads", "1", "-i", input.toString(), "-map", "0:v:0", "-an", "-f", "null", "-"));
            run(decode, output, Duration.ofSeconds(45));
            boolean alpha = pixels.contains("rgba") || pixels.contains("bgra") || pixels.startsWith("yuva") || pixels.equals("argb");
            return new Media(width, height, alpha);
        } catch (ApiException exception) { throw exception; }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw invalid(); }
        catch (Exception exception) { throw invalid(); }
        finally {
            try { if (input != null) Files.deleteIfExists(input); } catch (java.io.IOException ignored) { }
            try { if (output != null) Files.deleteIfExists(output); } catch (java.io.IOException ignored) { }
        }
    }
    private void run(List<String> args, Path output, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(args).redirectOutput(output.toFile()).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS) || process.exitValue() != 0) throw invalid();
        } finally {
            if (process.isAlive()) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); }
        }
    }
    private static ApiException invalid() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "ASSET_VALIDATION_FAILED", "The package media cannot be decoded or exceeds supported limits");
    }
}
