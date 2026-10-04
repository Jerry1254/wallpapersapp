package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.appupdate.AppReleaseDtos.CheckResult;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.Envelope;
import jakarta.servlet.http.HttpServletResponse;
import java.io.EOFException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/app-updates")
public class AppUpdateController {
    private final AppReleaseService releases;
    public AppUpdateController(AppReleaseService releases) { this.releases = releases; }
    @GetMapping("/check")
    public Envelope<CheckResult> check(@RequestParam String platform, @RequestParam String versionName,
            @RequestParam long versionCode, @RequestParam(required = false) String abi,
            @RequestParam(required = false) Integer androidSdk,
            @RequestParam(required = false) String packageName, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new Envelope<>(releases.check(platform, versionName, versionCode, abi, androidSdk, packageName));
    }
    @GetMapping("/packages/{id}")
    public ResponseEntity<StreamingResponseBody> download(@PathVariable String id,
            @RequestHeader(value = "Range", required = false) String range,
            @RequestHeader(value = "If-Range", required = false) String ifRange) {
        var release = releases.downloadable(id);
        String etag = "\"" + release.sha256() + "\"";
        String effectiveRange = ifRange != null && !ifRange.equals(etag) ? null : range;
        ApkByteRange selected;
        try { selected = ApkByteRange.parse(effectiveRange, release.fileSize()); }
        catch (IllegalArgumentException invalid) {
            return ResponseEntity.status(416).header("Content-Range", "bytes */" + release.fileSize())
                    .header("Cache-Control", "no-store").build();
        }
        var builder = ResponseEntity.status(selected.partial() ? 206 : 200)
                .contentType(MediaType.valueOf("application/vnd.android.package-archive"))
                .contentLength(selected.length()).header("Cache-Control", "no-store")
                .header("Accept-Ranges", "bytes").header("ETag", etag)
                .header("Content-Disposition", "attachment; filename=qingjing-" + release.versionCode() + ".apk")
                .header("X-Content-Type-Options", "nosniff");
        if (selected.partial()) builder.header("Content-Range", "bytes " + selected.start() + "-" + selected.end() + "/" + release.fileSize());
        return builder.body(output -> {
            try (var content = releases.openPackage(id)) {
                if (content.sizeBytes() != release.fileSize()) throw new EOFException("Package size changed");
                content.inputStream().skipNBytes(selected.start());
                byte[] buffer = new byte[64 * 1024];
                long remaining = selected.length();
                while (remaining > 0) {
                    int read = content.inputStream().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (read == -1) throw new EOFException("Package ended before its advertised length");
                    output.write(buffer, 0, read);
                    remaining -= read;
                }
            }
        });
    }
}
