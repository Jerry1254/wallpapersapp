package com.qingjing.wallpaper.delivery;

import com.qingjing.wallpaper.catalog.AdminWallpaperService;
import com.qingjing.wallpaper.catalog.AdminContentDtos.AdminWallpaperDetail;
import com.qingjing.wallpaper.shared.web.Ids;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/admin")
public class PreviewRebuildController {
    private final PreviewGenerationService previews;
    private final AdminWallpaperService wallpapers;
    public PreviewRebuildController(PreviewGenerationService previews,AdminWallpaperService wallpapers) {
        this.previews=previews; this.wallpapers=wallpapers;
    }
    @GetMapping("/wallpaper-previews/rebuild-plan")
    PreviewGenerationService.RebuildPlan plan() { return previews.plan(); }
    @PostMapping("/wallpaper-previews/rebuild")
    PreviewGenerationService.RebuildPlan rebuild() { return previews.enqueueAll(); }
    @PostMapping("/wallpapers/{wallpaperId}/preview-rebuild")
    AdminWallpaperDetail retry(@PathVariable String wallpaperId) {
        long id=Ids.parse(wallpaperId,"wallpaperId"); previews.retry(id); return wallpapers.get(id);
    }
}
