package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.*;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/admin/app-releases")
public class AdminAppReleaseController {
    private final AppReleaseService releases;
    public AdminAppReleaseController(AppReleaseService releases) { this.releases = releases; }
    @GetMapping
    public Envelope<ReleaseList> list(@RequestParam String platform, @RequestParam(required = false) String packageName, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return new Envelope<>(new ReleaseList(releases.list(platform, packageName)));
    }
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Envelope<ReleaseView>> create(@Valid @RequestBody StoreReleaseRequest body, HttpServletRequest request) {
        return ResponseEntity.status(201).body(new Envelope<>(audited(releases.createStore(body, admin(request).id()), request)));
    }
    @PostMapping(value = "/android", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Envelope<ReleaseView>> upload(@RequestPart MultipartFile file,
            @RequestParam String releaseNotes, @RequestParam(required = false) String packageName, HttpServletRequest request) {
        return ResponseEntity.status(201).body(new Envelope<>(audited(releases.uploadAndroid(file, releaseNotes, admin(request).id(), packageName), request)));
    }
    @PutMapping("/{id}")
    public Envelope<ReleaseView> update(@PathVariable String id, @Valid @RequestBody UpdateReleaseRequest body, HttpServletRequest request) {
        return new Envelope<>(audited(releases.update(id, body), request));
    }
    @PostMapping("/{id}/publish")
    public Envelope<ReleaseView> publish(@PathVariable String id, HttpServletRequest request) {
        return new Envelope<>(audited(releases.publish(id), request));
    }
    @PostMapping("/{id}/deprecate")
    public Envelope<ReleaseView> deprecate(@PathVariable String id, HttpServletRequest request) {
        return new Envelope<>(audited(releases.deprecate(id), request));
    }
    private AdminPrincipal admin(HttpServletRequest request) { return (AdminPrincipal) request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL); }
    private ReleaseView audited(ReleaseView release, HttpServletRequest request) {
        request.setAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY, Map.of("releaseId",release.id(),"platform",release.platform(),
                "versionName",release.versionName(),"versionCode",release.versionCode(),"forceUpdate",release.forceUpdate(),"status",release.status()));
        return release;
    }
}
