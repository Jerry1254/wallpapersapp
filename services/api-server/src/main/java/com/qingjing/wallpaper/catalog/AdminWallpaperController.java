package com.qingjing.wallpaper.catalog;

import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminResourceVersion;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperDetail;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperPage;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperVariant;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.CreateResourceVersionRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.PublishWallpaperRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.StateChangeReasonRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.VariantWriteRequest;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperKind;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperStatus;
import static com.qingjing.wallpaper.catalog.AdminContentDtos.WallpaperWriteRequest;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.EntityTags;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminWallpaperController {

    private final AdminWallpaperService wallpapers;

    public AdminWallpaperController(AdminWallpaperService wallpapers) {
        this.wallpapers = wallpapers;
    }

    @GetMapping("/wallpapers")
    AdminWallpaperPage list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) WallpaperStatus status,
            @RequestParam(required = false) WallpaperKind kind,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String q) {
        return wallpapers.list(page, pageSize, status, kind, categoryId, q);
    }

    @PostMapping("/wallpapers")
    ResponseEntity<AdminWallpaperDetail> create(@Valid @RequestBody WallpaperWriteRequest request) {
        AdminWallpaperDetail created = wallpapers.create(request);
        return withEtag(201, created, created.version());
    }

    @GetMapping("/wallpapers/{wallpaperId}")
    ResponseEntity<AdminWallpaperDetail> get(@PathVariable String wallpaperId) {
        AdminWallpaperDetail wallpaper = wallpapers.get(Ids.parse(wallpaperId, "wallpaperId"));
        return withEtag(200, wallpaper, wallpaper.version());
    }

    @PatchMapping("/wallpapers/{wallpaperId}")
    ResponseEntity<AdminWallpaperDetail> update(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody WallpaperWriteRequest request) {
        AdminWallpaperDetail wallpaper = wallpapers.update(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch),
                request);
        return withEtag(200, wallpaper, wallpaper.version());
    }

    @DeleteMapping("/wallpapers/{wallpaperId}")
    ResponseEntity<Void> deleteDraft(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch) {
        wallpapers.deleteDraft(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/wallpapers/{wallpaperId}/publish")
    ResponseEntity<AdminWallpaperDetail> publish(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody PublishWallpaperRequest request) {
        AdminWallpaperDetail wallpaper = wallpapers.publish(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch),
                request);
        return withEtag(200, wallpaper, wallpaper.version());
    }

    @PostMapping("/wallpapers/{wallpaperId}/offline")
    ResponseEntity<AdminWallpaperDetail> offline(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody(required = false) StateChangeReasonRequest request,
            HttpServletRequest servletRequest) {
        if (request != null) {
            servletRequest.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY, Map.of("reason", request.reason()));
        }
        AdminWallpaperDetail wallpaper = wallpapers.offline(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch));
        return withEtag(200, wallpaper, wallpaper.version());
    }

    @PostMapping("/wallpapers/{wallpaperId}/archive")
    ResponseEntity<AdminWallpaperDetail> archive(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody StateChangeReasonRequest request,
            HttpServletRequest servletRequest) {
        servletRequest.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY, Map.of("reason", request.reason()));
        AdminWallpaperDetail wallpaper = wallpapers.archive(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch));
        return withEtag(200, wallpaper, wallpaper.version());
    }

    @PostMapping("/wallpapers/{wallpaperId}/variants")
    ResponseEntity<AdminWallpaperVariant> createVariant(
            @PathVariable String wallpaperId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody VariantWriteRequest request) {
        AdminWallpaperVariant created = wallpapers.createVariant(
                Ids.parse(wallpaperId, "wallpaperId"),
                EntityTags.parseRequired(ifMatch),
                request);
        return ResponseEntity.status(201).body(created);
    }

    @PatchMapping("/variants/{variantId}")
    ResponseEntity<AdminWallpaperVariant> updateVariant(
            @PathVariable String variantId,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody VariantWriteRequest request) {
        AdminWallpaperVariant variant = wallpapers.updateVariant(
                Ids.parse(variantId, "variantId"),
                EntityTags.parseRequired(ifMatch),
                request);
        return withEtag(200, variant, variant.version());
    }

    @DeleteMapping("/variants/{variantId}")
    ResponseEntity<Void> deleteVariant(
            @PathVariable String variantId,
            @RequestHeader("If-Match") String ifMatch) {
        wallpapers.deleteVariant(
                Ids.parse(variantId, "variantId"),
                EntityTags.parseRequired(ifMatch));
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/variants/{variantId}/resource-versions")
    ResponseEntity<AdminResourceVersion> createResourceVersion(
            @PathVariable String variantId,
            @Valid @RequestBody CreateResourceVersionRequest request,
            HttpServletRequest servletRequest) {
        AdminPrincipal admin = (AdminPrincipal) servletRequest.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        AdminResourceVersion created = wallpapers.createResourceVersion(
                Ids.parse(variantId, "variantId"),
                request,
                admin.id());
        return ResponseEntity.status(201).body(created);
    }

    @GetMapping("/resource-versions/{resourceVersionId}")
    AdminResourceVersion getResourceVersion(@PathVariable String resourceVersionId) {
        return wallpapers.getResourceVersion(Ids.parse(resourceVersionId, "resourceVersionId"));
    }

    private <T> ResponseEntity<T> withEtag(int status, T body, long version) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.ETAG, EntityTags.of(version))
                .body(body);
    }
}
