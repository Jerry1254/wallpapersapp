package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.asset.PublicAssetService.PublicAssetContent;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.shared.web.Ids;
import java.time.Duration;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public/assets")
public class PublicAssetController {

    private final PublicAssetService assets;

    public PublicAssetController(PublicAssetService assets) {
        this.assets = assets;
    }

    @GetMapping("/{assetId}/content")
    ResponseEntity<InputStreamResource> content(@PathVariable String assetId) {
        PublicAssetContent descriptor = assets.content(Ids.parse(assetId, "assetId"));
        StoredContent content = assets.open(descriptor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(descriptor.mimeType()))
                .contentLength(descriptor.sizeBytes())
                .cacheControl(CacheControl.maxAge(Duration.ofHours(1)).cachePublic())
                .header(HttpHeaders.ETAG, '"' + descriptor.sha256() + '"')
                .body(new InputStreamResource(content.inputStream()));
    }
}
