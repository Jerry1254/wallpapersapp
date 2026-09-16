package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.Ids;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin/lab")
public class AdminParallaxLabController {
    private final ParallaxLabService service;
    public AdminParallaxLabController(ParallaxLabService service){this.service=service;}

    @PostMapping("/wallpapers/{wallpaperId}/parallax-config")
    ResponseEntity<ParallaxLabDtos.SaveResult> save(@PathVariable String wallpaperId,
            @Valid @RequestBody ParallaxLabDtos.SaveRequest body,HttpServletRequest request) {
        var admin=(AdminPrincipal)request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
        return ResponseEntity.status(201).body(service.save(Ids.parse(wallpaperId,"wallpaperId"),
                Ids.parse(body.baseResourceVersionId(),"baseResourceVersionId"),body.config(),admin.id()));
    }
}
