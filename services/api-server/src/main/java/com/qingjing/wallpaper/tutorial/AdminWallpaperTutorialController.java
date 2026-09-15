package com.qingjing.wallpaper.tutorial;

import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.AdminTutorialList;
import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.AdminWallpaperTutorial;
import static com.qingjing.wallpaper.tutorial.WallpaperTutorialDtos.TutorialWriteRequest;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.EntityTags;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/wallpaper-tutorials")
public class AdminWallpaperTutorialController {

    private final AdminWallpaperTutorialService tutorials;

    AdminWallpaperTutorialController(AdminWallpaperTutorialService tutorials) {
        this.tutorials = tutorials;
    }

    @GetMapping
    AdminTutorialList list() {
        return new AdminTutorialList(tutorials.list());
    }

    @PutMapping("/{tutorialKey}")
    ResponseEntity<AdminWallpaperTutorial> update(
            @PathVariable String tutorialKey,
            @RequestHeader("If-Match") String ifMatch,
            @Valid @RequestBody TutorialWriteRequest request,
            HttpServletRequest servletRequest) {
        AdminPrincipal admin = (AdminPrincipal) servletRequest.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        AdminWallpaperTutorialService.UpdateResult result = tutorials.update(
                tutorialKey,
                EntityTags.parseRequired(ifMatch),
                request,
                admin.id());
        servletRequest.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY, result.auditSummary());
        return ResponseEntity.ok()
                .header(HttpHeaders.ETAG, EntityTags.of(result.tutorial().version()))
                .body(result.tutorial());
    }
}
