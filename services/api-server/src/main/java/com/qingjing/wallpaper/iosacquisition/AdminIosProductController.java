package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.IosProductConfiguration;
import com.qingjing.wallpaper.iosacquisition.IosAcquisitionDtos.UpdateIosProductRequest;
import com.qingjing.wallpaper.shared.web.Ids;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/wallpapers/{wallpaperId}/ios-acquisition")
public class AdminIosProductController {
    private final IosProductService products;
    private final IosPriceSyncService prices;
    public AdminIosProductController(IosProductService products, IosPriceSyncService prices) { this.products = products; this.prices = prices; }

    @PostMapping("/price-sync")
    IosProductConfiguration syncPrice(@PathVariable String wallpaperId) {
        return prices.synchronize(Ids.parse(wallpaperId, "wallpaperId"));
    }

    @GetMapping
    IosProductConfiguration get(@PathVariable String wallpaperId) {
        return products.get(Ids.parse(wallpaperId, "wallpaperId"));
    }

    @PutMapping
    IosProductConfiguration update(
            @PathVariable String wallpaperId,
            @Valid @RequestBody UpdateIosProductRequest request) {
        return products.update(Ids.parse(wallpaperId, "wallpaperId"), request);
    }
}
