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
    private final PublicResourceIdentity identity;

    public PublicAssetController(PublicAssetService assets) {
        this(assets,new PublicResourceIdentity(null));
    }

    @org.springframework.beans.factory.annotation.Autowired
    public PublicAssetController(PublicAssetService assets,PublicResourceIdentity identity) {
        this.assets = assets;
        this.identity=identity;
    }

    @GetMapping("/{assetId}/content")
    ResponseEntity<InputStreamResource> content(@PathVariable String assetId,
            @org.springframework.web.bind.annotation.RequestHeader(value="Authorization",required=false) String authorization) {
        PublicAssetContent descriptor = assets.content(Ids.parse(assetId, "assetId"),identity.deviceId(authorization));
        StoredContent content = assets.open(descriptor);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(descriptor.mimeType()))
                .contentLength(descriptor.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, '"' + descriptor.sha256() + '"')
                .body(new InputStreamResource(content.inputStream()));
    }
}
