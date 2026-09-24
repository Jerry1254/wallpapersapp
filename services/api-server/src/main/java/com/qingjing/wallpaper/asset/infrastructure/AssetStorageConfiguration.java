package com.qingjing.wallpaper.asset.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.AssetContentValidator;
import com.qingjing.wallpaper.asset.application.AssetUploadService;
import com.qingjing.wallpaper.asset.application.CoverImageOptimizer;
import com.qingjing.wallpaper.asset.application.FileStorage;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LocalStorageProperties.class)
public class AssetStorageConfiguration {

    @Bean
    FileStorage fileStorage(LocalStorageProperties properties) {
        return new LocalFileStorage(Path.of(properties.getRoot()));
    }

    @Bean
    AssetContentValidator assetContentValidator(FileStorage fileStorage, ObjectMapper objectMapper) {
        return new AssetContentValidator(fileStorage, objectMapper);
    }

    @Bean
    CoverImageOptimizer coverImageOptimizer(
            FileStorage fileStorage,
            @Value("${qingjing.delivery.ffmpeg:ffmpeg}") String ffmpeg) {
        return new FfmpegCoverImageOptimizer(fileStorage, ffmpeg);
    }

    @Bean
    AssetUploadService assetUploadService(
            FileStorage fileStorage,
            AssetContentValidator contentValidator,
            CoverImageOptimizer coverImageOptimizer) {
        return new AssetUploadService(fileStorage, contentValidator, coverImageOptimizer);
    }
}
