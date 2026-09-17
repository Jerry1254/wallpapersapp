package com.qingjing.wallpaper.catalog;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicCategoryList;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperDetail;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperPage;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.catalog.PublicCatalogService.CatalogSort;
import com.qingjing.wallpaper.catalog.PublicCatalogService.CatalogView;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/public")
public class PublicCatalogController {

    private final PublicCatalogService catalog;

    public PublicCatalogController(PublicCatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping("/categories")
    PublicCategoryList categories(HttpServletRequest request) {
        return catalog.categories(principal(request).deviceId());
    }

    @GetMapping("/wallpapers")
    PublicWallpaperPage wallpapers(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String rootCategoryId,
            @RequestParam(required = false) String childCategoryId,
            @RequestParam(required = false) CatalogView view,
            @RequestParam(required = false) WallpaperAccessType accessType,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(defaultValue = "DEFAULT") CatalogSort sort,
            HttpServletRequest request) {
        return catalog.wallpapers(
                principal(request).deviceId(),
                page,
                pageSize,
                optionalId(rootCategoryId, "rootCategoryId"),
                optionalId(childCategoryId, "childCategoryId"),
                view,
                accessType,
                query,
                sort);
    }

    @GetMapping("/wallpapers/{wallpaperId}")
    PublicWallpaperDetail wallpaper(
            @PathVariable String wallpaperId,
            HttpServletRequest request) {
        return catalog.wallpaper(
                principal(request).deviceId(), Ids.parse(wallpaperId, "wallpaperId"));
    }

    private Long optionalId(String value, String field) {
        return value == null ? null : Ids.parse(value, field);
    }

    private DevicePrincipal principal(HttpServletRequest request) {
        return (DevicePrincipal) request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
    }
}
