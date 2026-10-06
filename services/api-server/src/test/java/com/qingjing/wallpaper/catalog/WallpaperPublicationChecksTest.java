package com.qingjing.wallpaper.catalog;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.ByteArrayInputStream;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class WallpaperPublicationChecksTest {
    JdbcTemplate jdbc;FileStorage storage;WallpaperPublicationChecks checks;
    Map<String,Object> resource,asset,wallpaper;
    byte[] bytes={1,2,3,4};
    @BeforeEach void fixture() {
        jdbc=mock(JdbcTemplate.class);storage=mock(FileStorage.class);checks=new WallpaperPublicationChecks(jdbc,storage);
        wallpaper=new HashMap<>(Map.of("id",1L,"lock_version",3L,"status","DRAFT","category_id",5L,"title","真实壁纸","copyright_note","原创","cover_asset_id",2L));
        resource=new HashMap<>(Map.of("id",10L,"variant_id",11L,"wallpaper_id",1L,"enabled",true,"status","READY","platform","UNIVERSAL","resource_type","STATIC_IMAGE"));
        asset=new HashMap<>(Map.of("storage_key","fixtures/source.png","size_bytes",4L,"sha256",SecurePackageCodec.sha256(bytes),"validation_status","READY","mime_type","image/png","purpose","STATIC_IMAGE"));
        when(jdbc.queryForList(anyString(),any(Object[].class))).thenAnswer(call->{
            String sql=call.getArgument(0);Object id=call.getArgument(1);
            if(sql.contains("FROM wallpaper WHERE"))return List.of(wallpaper);
            if(sql.contains("FROM category c"))return List.of(Map.of("level",1L));
            if(sql.contains("FROM resource_version rv JOIN"))return List.of(resource);
            if(sql.contains("FROM resource_binding"))return List.of(Map.of("role",asset.get("purpose"),"ordinal",0L,"asset_id",3L));
            if(sql.contains("FROM asset")){var value=new HashMap<>(asset);if(id.equals(2L))value.put("purpose","WALLPAPER_COVER");return List.of(value);}
            if(sql.contains("FROM secure_resource_package"))return List.of(Map.of("storage_key","fixtures/delivery.4dwp","size_bytes",4L,"encrypted_sha256",SecurePackageCodec.sha256(bytes)));
            return List.of();
        });
        when(jdbc.queryForObject(anyString(),eq(Integer.class),any(Object[].class))).thenReturn(0);
        when(storage.open(any())).thenAnswer(call->new StoredContent(new ByteArrayInputStream(bytes),bytes.length));
    }
    @Test void permitsReadyFormalDeliveryAndDescribesTheExistingAsyncPreviewQueue() {
        var result=checks.check(1,List.of("10"));assertThat(result.canPublish()).isTrue();assertThat(result.canPrepare()).isTrue();
        assertThat(result.checks()).extracting(WallpaperPublicationChecks.Check::code).contains("PREVIEW_PACKAGE_PENDING");
        assertThat(result.checks()).allMatch(c->c.level().equals("WARNING"));
    }
    @Test void preventsWrongOwnerDisabledVariantAndDuplicateVersionSelection() {
        resource.put("wallpaper_id",2L);resource.put("enabled",false);
        var result=checks.check(1,List.of("10","10"));assertThat(result.canPublish()).isFalse();assertThat(result.canPrepare()).isFalse();
        assertThat(result.checks()).extracting(WallpaperPublicationChecks.Check::code).contains("RESOURCE_OWNER_MISMATCH","VARIANT_DISABLED","RESOURCE_SELECTION_DUPLICATE");
    }
    @Test void actualCorruptedBytesBlockBothPreflightAndFinalPublication() {
        when(storage.open(any())).thenAnswer(call->new StoredContent(new ByteArrayInputStream(new byte[]{9,9,9,9}),4));
        assertThat(checks.check(1,List.of("10")).canPrepare()).isFalse();
        assertThatThrownBy(()->checks.requirePublishable(1,List.of("10"))).isInstanceOfSatisfying(ApiException.class,e->{assertThat(e.code()).isEqualTo("PUBLICATION_CHECK_FAILED");assertThat(e.details()).anyMatch(d->d.reason().contains("ASSET_CONTENT_INVALID"));});
    }
    @Test void rejectsMovAsTheFormalIosSourceEvenWhenTheUploadPurposeAllowsMov() {
        resource.put("platform","IOS");resource.put("resource_type","LIVE_PHOTO");asset.put("purpose","LIVE_PHOTO_SOURCE");asset.put("mime_type","video/quicktime");
        var result=checks.check(1,List.of("10"));assertThat(result.canPublish()).isFalse();
        assertThat(result.checks()).extracting(WallpaperPublicationChecks.Check::code).contains("IOS_SOURCE_MP4_REQUIRED","LIVE_PHOTO_PENDING");
    }
    @Test void rejectsArchivedProductsAndRemovedCategoriesWithoutWriting() {
        wallpaper.put("status","ARCHIVED");when(jdbc.queryForList(contains("FROM category c"),any(Object[].class))).thenReturn(List.of());
        var result=checks.check(1,List.of("10"));assertThat(result.checks()).extracting(WallpaperPublicationChecks.Check::code).contains("WALLPAPER_ARCHIVED","CATEGORY_INVALID");
        verify(jdbc,never()).update(anyString(),any(Object[].class));
    }
    @Test void existingLabConfigVersionsDoNotNeedToInventAnOriginalZip() {
        resource.put("platform","ANDROID");resource.put("resource_type","LAYER_PARALLAX");
        when(jdbc.queryForList(contains("FROM resource_binding"),any(Object[].class))).thenReturn(List.of(
                Map.of("role","BACKGROUND","ordinal",0L,"asset_id",3L),
                Map.of("role","FOREGROUND","ordinal",0L,"asset_id",4L),
                Map.of("role","PARALLAX_CONFIG","ordinal",0L,"asset_id",5L)));
        when(jdbc.queryForList(contains("FROM asset"),any(Object[].class))).thenAnswer(call->{
            var value=new HashMap<>(asset);long id=call.getArgument(1);
            if(id==2)value.put("purpose","WALLPAPER_COVER");
            if(id==5)value.put("mime_type","application/json");
            return List.of(value);
        });
        assertThat(checks.check(1,List.of("10")).canPublish()).isTrue();
    }
}
