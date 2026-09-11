package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.asset.AdminAssetService.AssetContentDescriptor;
import com.qingjing.wallpaper.asset.application.AssetPurpose;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/assets")
public class AdminAssetController {

    private final AdminAssetService assets;

    public AdminAssetController(AdminAssetService assets) {
        this.assets = assets;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<AdminAssetView> upload(
            @RequestParam("purpose") AssetPurpose purpose,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) throws IOException {
        AdminPrincipal admin = (AdminPrincipal) request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        AdminAssetView created = assets.upload(
                file.getInputStream(),
                file.getOriginalFilename(),
                file.getContentType(),
                purpose,
                admin.id());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/{assetId}")
    AdminAssetView get(@PathVariable String assetId) {
        return assets.get(Ids.parse(assetId, "assetId"));
    }

    @GetMapping("/{assetId}/content")
    ResponseEntity<InputStreamResource> content(@PathVariable String assetId) {
        AssetContentDescriptor descriptor = assets.content(Ids.parse(assetId, "assetId"));
        StoredContent content = assets.open(descriptor);
        InputStreamResource body = new InputStreamResource(content.inputStream());
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(descriptor.mimeType()))
                .contentLength(descriptor.sizeBytes())
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.ETAG, '"' + descriptor.sha256() + '"')
                .body(body);
    }
}
