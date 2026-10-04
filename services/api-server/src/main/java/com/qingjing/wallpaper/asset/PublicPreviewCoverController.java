package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.shared.web.Ids;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class PublicPreviewCoverController {
    private final PublicAssetService assets;
    private final PublicResourceIdentity identity;
    public PublicPreviewCoverController(PublicAssetService assets) { this(assets,new PublicResourceIdentity(null)); }
    @org.springframework.beans.factory.annotation.Autowired
    public PublicPreviewCoverController(PublicAssetService assets,PublicResourceIdentity identity) { this.assets=assets;this.identity=identity; }
    @GetMapping("/api/v1/wallpapers/{wallpaperId}/cover")
    ResponseEntity<InputStreamResource> cover(@PathVariable String wallpaperId,
            @RequestHeader(value="Authorization",required=false) String authorization) {
        var descriptor=assets.previewCover(Ids.parse(wallpaperId,"wallpaperId"),identity.deviceId(authorization));
        var content=assets.open(descriptor);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(descriptor.mimeType()))
            .contentLength(descriptor.sizeBytes()).cacheControl(CacheControl.noStore())
            .header(HttpHeaders.ETAG,'"'+descriptor.sha256()+'"')
            .body(new InputStreamResource(content.inputStream()));
    }
}
