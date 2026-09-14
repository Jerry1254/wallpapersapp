package com.qingjing.wallpaper.delivery.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import java.io.ByteArrayInputStream;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import static org.assertj.core.api.Assertions.*;

class PreviewExternalMediaReducerTest {
    @Test void webpFallbackAndVideoProduceMarkedReducedDecodableMedia() throws Exception {
        String ffmpeg=System.getenv().getOrDefault("QJ_FFMPEG","ffmpeg"),ffprobe=System.getenv().getOrDefault("QJ_FFPROBE","ffprobe");
        Assumptions.assumeTrue(available(ffmpeg) && available(ffprobe),"Explicit external decoder runtime is required");
        var mapper=new ObjectMapper();var reducer=new PreviewMediaReducer(mapper,ffmpeg);var inspector=new PackageMediaInspector(mapper,ffprobe,ffmpeg);
        Path root=Files.createTempDirectory("qj-preview-test-");
        try {
            Path webp=root.resolve("source.webp"),video=root.resolve("source.mp4");
            run(List.of(ffmpeg,"-v","error","-nostdin","-y","-f","lavfi","-i","color=c=blue:s=1920x1080:d=1","-frames:v","1","-c:v","libwebp","-threads","1",webp.toString()));
            run(List.of(ffmpeg,"-v","error","-nostdin","-y","-f","lavfi","-i","color=c=blue:s=1920x1080:d=1","-r","30","-c:v","libx264","-threads","1","-pix_fmt","yuv420p","-an",video.toString()));
            byte[] source=Files.readAllBytes(webp);
            var image=reducer.reduce("STATIC_IMAGE",List.of(new SecurePackageCodec.Payload("STATIC_IMAGE",0,"image/webp",source)),
                Map.of("STATIC_IMAGE:0",inspector.inspect(source,false))).get(0);
            assertThat(image.mimeType()).isEqualTo("image/jpeg");assertThat(image.content()).isNotEqualTo(source);
            var decoded=ImageIO.read(new ByteArrayInputStream(image.content()));
            try {
                assertThat(decoded.getWidth()).isEqualTo(1280);assertThat(decoded.getHeight()).isEqualTo(720);
                Set<Integer> colors=new HashSet<>();for(int y=0;y<720;y+=5) for(int x=0;x<1280;x+=5) colors.add(decoded.getRGB(x,y));
                assertThat(colors.size()).isGreaterThan(20);
            } finally { decoded.flush(); }
            byte[] originalVideo=Files.readAllBytes(video);
            var result=reducer.reduce("VIDEO",List.of(new SecurePackageCodec.Payload("VIDEO",0,"video/mp4",originalVideo)),
                Map.of("VIDEO:0",inspector.inspect(originalVideo,true))).get(0);
            assertThat(result.content()).isNotEqualTo(originalVideo);assertThat(result.mimeType()).isEqualTo("video/mp4");
            var metadata=inspector.inspect(result.content(),true);assertThat(metadata.width()).isEqualTo(1280);assertThat(metadata.height()).isEqualTo(720);
        } finally {
            try(var files=Files.list(root)) { for(Path path:files.toList()) Files.deleteIfExists(path); } Files.deleteIfExists(root);
        }
    }
    private static boolean available(String command) {
        try { Process p=new ProcessBuilder(command,"-version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            try { return p.waitFor(5,TimeUnit.SECONDS) && p.exitValue()==0; } finally { if(p.isAlive()) p.destroyForcibly(); }
        } catch(Exception ignored) { return false; }
    }
    private static void run(List<String> command) throws Exception {
        Process p=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try { assertThat(p.waitFor(30,TimeUnit.SECONDS)).isTrue();assertThat(p.exitValue()).isZero(); }
        finally { if(p.isAlive()) p.destroyForcibly(); }
    }
}
