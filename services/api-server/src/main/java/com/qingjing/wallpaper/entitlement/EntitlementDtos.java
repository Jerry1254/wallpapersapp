package com.qingjing.wallpaper.entitlement;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PublicWallpaperSummary;
import java.time.Instant;
import java.util.List;

public final class EntitlementDtos {

    private EntitlementDtos() {
    }

    public record EntitlementSummary(String id, PublicWallpaperSummary wallpaper, Instant grantedAt) {
    }

    public record EntitlementPage(List<EntitlementSummary> items, PageMetadata page) {
    }
}
