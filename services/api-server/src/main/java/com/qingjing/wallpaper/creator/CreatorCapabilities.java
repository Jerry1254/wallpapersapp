package com.qingjing.wallpaper.creator;

import com.qingjing.wallpaper.asset.application.AssetPurpose;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class CreatorCapabilities {
    public static final String RULES_VERSION="creator-wallpaper-rules-1";
    public record Environment(String id,String label,String mode,String apiVersion) { }
    public record VariantRule(String platform,String resourceType,String uploadPurpose,String requiredRole,
                              List<String> acceptedMimeTypes,long maximumBytes,Map<String,Object> source,
                              Map<String,Object> preview) { }
    public record Rules(Environment environment,String rulesVersion,List<VariantRule> wallpaperVariants,
                        long coverMaximumBytes,List<String> coverMimeTypes,List<String> supportedMetadataFields,
                        List<String> supportedIosAcquisitionFields,Map<String,Boolean> supportedOperations) { }
    private final Environment environment;
    public CreatorCapabilities(@Value("${info.app.environment-id:UNCONFIGURED}") String id,
                               @Value("${QJ_ENVIRONMENT_LABEL:倾境壁纸}") String label,
                               @Value("${QJ_DEPLOYMENT_STAGE:LOCAL}") String mode,
                               @Value("${info.app.contract-version}") String apiVersion) {
        environment=new Environment(id,label,mode,apiVersion);
    }
    public String environmentId(){return environment.id();}
    public Rules get() {
        return new Rules(environment,RULES_VERSION,List.of(
                rule("UNIVERSAL","STATIC_IMAGE","STATIC_IMAGE",AssetPurpose.STATIC_IMAGE.maximumBytes(),List.of("image/jpeg","image/png","image/webp"),Map.of(),Map.of()),
                rule("ANDROID","VIDEO","VIDEO",AssetPurpose.VIDEO.maximumBytes(),List.of("video/mp4","video/quicktime"),Map.of("maximumDurationMs",30000,"maximumInputFps",240,"maximumWidthPx",4096,"maximumHeightPx",4096),Map.of("codec","h264","format","mp4")),
                rule("IOS","LIVE_PHOTO","LIVE_PHOTO_SOURCE",AssetPurpose.LIVE_PHOTO_SOURCE.maximumBytes(),List.of("video/mp4"),Map.of("minDisplayFrames",60,"maximumFps",60,"maximumWidthPx",4096,"maximumHeightPx",4096,"codecs",List.of("h264","hevc")),Map.of("outputFrames",60,"fps",60,"durationMs",1000)),
                rule("HARMONYOS","MOVING_PHOTO","MOVING_PHOTO_SOURCE",AssetPurpose.MOVING_PHOTO_SOURCE.maximumBytes(),List.of("video/mp4"),Map.of("minimumDurationMs",2000,"maximumFps",60,"maximumWidthPx",4096,"maximumHeightPx",4096,"codecs",List.of("h264","hevc")),Map.of("maximumDurationMs",2000,"videoMimeType","video/mp4","posterMimeType","image/jpeg")),
                rule("ANDROID","LAYER_PARALLAX","PARALLAX_ZIP",100L*1024*1024,List.of("application/zip"),Map.of("minimumLayers",2,"maximumLayers",12,"minimumDimensionPx",512,"maximumDimensionPx",4096,"minimumFormatVersion",2,"maximumConfigBytes",65536,"maximumExpandedBytes",85L*1024*1024,"maximumPayloadBytes",64L*1024*1024,"coverRequired",false),Map.of())),
                AssetPurpose.WALLPAPER_COVER.maximumBytes(),List.of("image/jpeg","image/png","image/webp"),
                List.of("title","slug","accessType","rootCategoryId","childCategoryId","coverAssetId","featuredRank","sortOrder","copyrightNote","previewWatermarkEnabled","offlinePromotionOnly"),
                List.of("productId","firstFreeEligible","enabled","acquisitionMode","credits"),
                Map.of("wallpaperUpload",true,"wallpaperPublication",true,"publicationCheck",true,"projectPersistence",false,"mediaLibrary",false,"renderTasks",false,"templates",false,"favorites",false,"works",false));
    }
    private VariantRule rule(String platform,String type,String purpose,long max,List<String> mimes,Map<String,Object> source,Map<String,Object> preview) {
        return new VariantRule(platform,type,purpose,purpose.equals("PARALLAX_ZIP")?"SOURCE_PACKAGE":purpose,mimes,max,source,preview);
    }
}
