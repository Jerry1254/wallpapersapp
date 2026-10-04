package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.shared.web.Ids;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicPreviewCoverController {
    private final PublicAssetService assets;
    public PublicPreviewCoverController(PublicAssetService assets) { this.assets=assets; }
    @GetMapping("/api/v1/wallpapers/{wallpaperId}/cover")
    ResponseEntity<InputStreamResource> cover(@PathVariable String wallpaperId) {
        var descriptor=assets.previewCover(Ids.parse(wallpaperId,"wallpaperId"));
        var content=assets.open(descriptor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(descriptor.mimeType()))
            .contentLength(descriptor.sizeBytes()).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.ETAG,'"'+descriptor.sha256()+'"')
            .body(new InputStreamResource(content.inputStream()));
    }
}
