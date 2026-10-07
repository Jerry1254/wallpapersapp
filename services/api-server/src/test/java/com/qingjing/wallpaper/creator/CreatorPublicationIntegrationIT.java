package com.qingjing.wallpaper.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.asset.*;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.catalog.*;
import com.qingjing.wallpaper.delivery.*;
import com.qingjing.wallpaper.delivery.packageformat.SecurePackageCodec;
import com.qingjing.wallpaper.iosacquisition.IosProductService;
import com.qingjing.wallpaper.parallax.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import jakarta.validation.Validation;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.Path;
import java.util.*;
import javax.imageio.ImageIO;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

/** Real MySQL persistence and recovery; package encoding is a separately tested dependency. */
@Testcontainers(disabledWithoutDocker=true)
class CreatorPublicationIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("creator_publication_test").withUsername("creator_test").withPassword(UUID.randomUUID().toString());
    @TempDir Path temporary;
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    static final ObjectMapper MAPPER=new ObjectMapper().findAndRegisterModules();
    CreatorPublicationService service;
    CreatorAssetUploads uploads;
    AdminWallpaperService wallpapers;
    SecurePackagePublisher packages;
    FileStorage storage;
    String cover,source;
    @BeforeAll static void database() {
        var datasource=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(datasource);transactions=new DataSourceTransactionManager(datasource);
        jdbc.update("INSERT INTO admin_account(id,singleton_key,username,password_hash,password_changed_at) VALUES(1,1,'creator-fixture','not-a-login-password',UTC_TIMESTAMP(6))");
    }
    @BeforeEach void services()throws Exception {
        storage=new LocalFileStorage(temporary);
        var output=new ByteArrayOutputStream();ImageIO.write(new BufferedImage(8,8,BufferedImage.TYPE_INT_RGB),"png",output);
        byte[] image=output.toByteArray();
        var assetUploads=mock(AssetUploadService.class);
        when(assetUploads.upload(any(),anyString(),anyString(),any())).thenAnswer(call->{
            AssetPurpose purpose=call.getArgument(3);var staged=storage.stage(call.getArgument(0),purpose.maximumBytes());
            var object=storage.commit(staged,"png");
            return new ValidatedAsset(object.storageKey(),call.getArgument(1),"image/png","png",object.sizeBytes(),object.sha256(),8,8,null);
        });
        var assets=new AdminAssetService(jdbc,assetUploads,storage);
        String icon=assets.upload(new ByteArrayInputStream(image),"category.png","image/png",AssetPurpose.CATEGORY_ICON,1).id();
        jdbc.update("INSERT INTO category(id,level,name,slug,sort_order,icon_asset_id) VALUES(1,1,'创作台测试','creator-test',0,?) ON DUPLICATE KEY UPDATE icon_asset_id=?",Long.parseLong(icon),Long.parseLong(icon));
        cover=assets.upload(new ByteArrayInputStream(image),"cover.png","image/png",AssetPurpose.WALLPAPER_COVER,1).id();
        source=assets.upload(new ByteArrayInputStream(image),"source.png","image/png",AssetPurpose.STATIC_IMAGE,1).id();
        var ios=mock(IosProductService.class);
        var views=new AdminContentViewReader(jdbc,assets,MAPPER,new ParallaxPackageReader(jdbc),ios);
        var checks=new WallpaperPublicationChecks(jdbc,storage);
        wallpapers=new AdminWallpaperService(jdbc,views,new AdminCategoryService(jdbc,assets),assets,MAPPER,mock(PreviewGenerationService.class),checks);
        packages=mock(SecurePackagePublisher.class);
        doAnswer(call->{
            long id=call.getArgument(0);
            if(jdbc.queryForObject("SELECT COUNT(*) FROM secure_resource_package WHERE resource_version_id=?",Integer.class,id)==1)return null;
            var staged=storage.stage(new ByteArrayInputStream(image),1024);var object=storage.commit(staged,"4dwp");
            jdbc.update("""
                    INSERT INTO secure_resource_package(resource_version_id,storage_key,size_bytes,plaintext_size_bytes,
                    encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext)
                    VALUES(?,?,?,?,?,?,?,?,?)
                    """,id,object.storageKey().value(),object.sizeBytes(),object.sizeBytes()-36,object.sha256(),object.sha256(),object.sha256(),"fixture-key","fixture-ciphertext");
            return null;
        }).when(packages).prepareForPublication(anyLong());
        var capabilities=new CreatorCapabilities("creator-test","测试","LOCAL","2.21.0");
        uploads=new CreatorAssetUploads(jdbc,storage,assets,capabilities,transactions);
        service=new CreatorPublicationService(jdbc,transactions,MAPPER,Validation.buildDefaultValidatorFactory().getValidator(),capabilities,
                wallpapers,mock(ParallaxPackageService.class),ios,packages,mock(MovingPhotoPublisher.class),mock(LivePhotoPublisher.class),checks);
    }
    ObjectNode input(String action)throws Exception {
        var request=(ObjectNode)MAPPER.readTree(CreatorPublicationTest.valid());
        request.put("action",action);request.put("clientProjectKey",UUID.randomUUID().toString());
        var metadata=(ObjectNode)request.path("metadata");metadata.put("slug","creator-"+UUID.randomUUID());metadata.put("coverAssetId",cover);
        ((ObjectNode)request.path("resources").get(0)).put("assetId",source);return request;
    }
    @Test void duplicateSubmissionCreatesOneDraftAndOneVersionAndCanBeRecoveredFromProjectKey()throws Exception {
        var request=input("SAVE_DRAFT");String key=UUID.randomUUID().toString();
        var queued=service.submit(request,key,1);assertThat(queued.wallpaperId()).isNull();
        assertThat(service.submit(request,key,1).taskId()).isEqualTo(queued.taskId());
        service.runNext();var result=service.get(Long.parseLong(queued.taskId()));
        assertThat(result.state()).isEqualTo("SUCCEEDED");assertThat(result.result().status().name()).isEqualTo("DRAFT");
        assertThat(service.submit(request,key,1).result()).isEqualTo(result.result());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE v.wallpaper_id=?",Integer.class,Long.parseLong(result.wallpaperId()))).isEqualTo(1);
        assertThat(service.list(request.path("clientProjectKey").asText(),1,20).get("items")).asList().hasSize(1);
        ((ObjectNode)request.path("metadata")).put("title","不同输入");
        assertThatThrownBy(()->service.submit(request,key,1)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void publishesActualResourceAndCommitsItsTaskResultWithTheWallpaper()throws Exception {
        var task=service.submit(input("PUBLISH"),UUID.randomUUID().toString(),1);service.runNext();
        var result=service.get(Long.parseLong(task.taskId()));assertThat(result.state()).isEqualTo("SUCCEEDED");
        assertThat(result.result().status().name()).isEqualTo("PUBLISHED");
        assertThat(wallpapers.get(Long.parseLong(result.wallpaperId())).status().name()).isEqualTo("PUBLISHED");
        assertThat(wallpapers.getResourceVersion(Long.parseLong(result.result().resourceVersionIds().get(0))).status().name()).isEqualTo("PUBLISHED");
    }
    @Test void processingFailureRetainsIdsAndRetryReusesCompletedBusinessSteps()throws Exception {
        var request=input("PUBLISH");String key=UUID.randomUUID().toString();
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","fixture busy")).when(packages).prepareForPublication(anyLong());
        var queued=service.submit(request,key,1);service.runNext();
        var failed=service.get(Long.parseLong(queued.taskId()));assertThat(failed.state()).isEqualTo("FAILED");assertThat(failed.retryable()).isTrue();assertThat(failed.wallpaperId()).isNotNull();
        String version=failed.completedSteps().stream().filter(s->s.key().equals("RESOURCE_0")).findFirst().orElseThrow().id();
        // SAVE_DRAFT recovery does not need a package. Using a new input must use a new request key.
        assertThatThrownBy(()->{request.put("action","SAVE_DRAFT");service.submit(request,key,1);}).isInstanceOf(ApiException.class);
        doNothing().when(packages).prepareForPublication(anyLong());
        // Simulate a process restart after durable steps. Complete the package externally before resuming.
        var object=storage.commit(storage.stage(new ByteArrayInputStream(new byte[64]),64),"4dwp");
        jdbc.update("INSERT INTO secure_resource_package(resource_version_id,storage_key,size_bytes,plaintext_size_bytes,encrypted_sha256,plaintext_sha256,manifest_sha256,signing_key_id,content_key_ciphertext) VALUES(?,?,?,?,?,?,?,?,?)",
                Long.parseLong(version),object.storageKey().value(),64,28,object.sha256(),object.sha256(),object.sha256(),"fixture-key","fixture-ciphertext");
        service.retry(Long.parseLong(queued.taskId()));service.runNext();
        var recovered=service.get(Long.parseLong(queued.taskId()));assertThat(recovered.state()).isEqualTo("SUCCEEDED");assertThat(recovered.wallpaperId()).isEqualTo(failed.wallpaperId());
        assertThat(recovered.result().resourceVersionIds()).containsExactly(version);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_version r JOIN wallpaper_variant v ON v.id=r.variant_id WHERE v.wallpaper_id=?",Integer.class,Long.parseLong(recovered.wallpaperId()))).isEqualTo(1);
    }
    @Test void anExternalEditStopsResumptionWithoutOverwritingTheNewerVersion()throws Exception {
        doThrow(new ApiException(HttpStatus.TOO_MANY_REQUESTS,"RATE_LIMITED","fixture busy")).when(packages).prepareForPublication(anyLong());
        var queued=service.submit(input("PUBLISH"),UUID.randomUUID().toString(),1);service.runNext();var failed=service.get(Long.parseLong(queued.taskId()));
        jdbc.update("UPDATE wallpaper SET title='外部修改',lock_version=lock_version+1 WHERE id=?",Long.parseLong(failed.wallpaperId()));
        clearInvocations(packages);
        doNothing().when(packages).prepareForPublication(anyLong());service.retry(Long.parseLong(queued.taskId()));service.runNext();
        assertThat(service.get(Long.parseLong(queued.taskId())).state()).isEqualTo("NEEDS_INPUT");
        assertThat(wallpapers.get(Long.parseLong(failed.wallpaperId())).title()).isEqualTo("外部修改");
        verifyNoInteractions(packages);
    }
    ObjectNode updateInput(ObjectNode request,CreatorPublicationDtos.Task completed) {
        var update=request.deepCopy();update.put("wallpaperId",completed.wallpaperId());
        update.put("expectedWallpaperVersion",completed.result().version());
        ((ObjectNode)update.path("metadata")).put("title","仅修改商品资料");
        return update;
    }
    @Test void metadataOnlyUpdateReusesThePublishedVersionAndPackage()throws Exception {
        var request=input("PUBLISH");var first=service.submit(request,UUID.randomUUID().toString(),1);service.runNext();
        var published=service.get(Long.parseLong(first.taskId()));assertThat(published.state()).isEqualTo("SUCCEEDED");
        long version=Long.parseLong(published.result().resourceVersionIds().get(0));
        String packageKey=jdbc.queryForObject("SELECT storage_key FROM secure_resource_package WHERE resource_version_id=?",String.class,version);
        var second=service.submit(updateInput(request,published),UUID.randomUUID().toString(),1);service.runNext();
        var updated=service.get(Long.parseLong(second.taskId()));assertThat(updated.state()).isEqualTo("SUCCEEDED");
        assertThat(updated.wallpaperId()).isEqualTo(published.wallpaperId());
        assertThat(updated.result().resourceVersionIds()).isEqualTo(published.result().resourceVersionIds());
        assertThat(wallpapers.get(Long.parseLong(updated.wallpaperId())).title()).isEqualTo("仅修改商品资料");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_version WHERE variant_id=(SELECT variant_id FROM resource_version WHERE id=?)",Integer.class,version)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT storage_key FROM secure_resource_package WHERE resource_version_id=?",String.class,version)).isEqualTo(packageKey);
    }
    @Test void changedAssetCreatesANewVersionAndRetiresThePreviousPublishedVersion()throws Exception {
        var request=input("PUBLISH");var first=service.submit(request,UUID.randomUUID().toString(),1);service.runNext();
        var published=service.get(Long.parseLong(first.taskId()));assertThat(published.state()).isEqualTo("SUCCEEDED");
        var replacement=uploads.upload(new ByteArrayInputStream(new byte[]{2,3}),"replacement.png","image/png",AssetPurpose.STATIC_IMAGE,1,UUID.randomUUID().toString());
        var update=updateInput(request,published);((ObjectNode)update.path("resources").get(0)).put("assetId",replacement.asset().id());
        var second=service.submit(update,UUID.randomUUID().toString(),1);service.runNext();
        var updated=service.get(Long.parseLong(second.taskId()));assertThat(updated.state()).isEqualTo("SUCCEEDED");
        assertThat(updated.result().resourceVersionIds()).doesNotContainAnyElementsOf(published.result().resourceVersionIds());
        long old=Long.parseLong(published.result().resourceVersionIds().get(0));
        assertThat(wallpapers.getResourceVersion(old).status().name()).isEqualTo("RETIRED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM resource_version WHERE variant_id=(SELECT variant_id FROM resource_version WHERE id=?)",Integer.class,old)).isEqualTo(2);
    }
    @Test void idempotentUploadsReturnTheOriginalAssetAndRejectDifferentFileContent() {
        String key=UUID.randomUUID().toString();byte[] content={1,2,3,4,5};
        var first=uploads.upload(new ByteArrayInputStream(content),"same.png","image/png",AssetPurpose.STATIC_IMAGE,1,key);
        var second=uploads.upload(new ByteArrayInputStream(content),"same.png","image/png",AssetPurpose.STATIC_IMAGE,1,key);
        assertThat(first.created()).isTrue();assertThat(second.created()).isFalse();assertThat(second.asset().id()).isEqualTo(first.asset().id());
        assertThatThrownBy(()->uploads.upload(new ByteArrayInputStream(new byte[]{9}),"same.png","image/png",AssetPurpose.STATIC_IMAGE,1,key))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("IDEMPOTENCY_CONFLICT"));
    }
    @Test void missingCoverAndUnavailableAssetsRejectWithoutLeavingQueuedTasks()throws Exception {
        int before=jdbc.queryForObject("SELECT COUNT(*) FROM creator_wallpaper_publication",Integer.class);
        var absent=input("PUBLISH");((ObjectNode)absent.path("metadata")).remove("coverAssetId");
        assertThatThrownBy(()->service.submit(absent,UUID.randomUUID().toString(),1))
                .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
        for(String id:List.of(cover,source)) {
            for(String status:List.of("UPLOADING","VALIDATING","REJECTED")) {
                jdbc.update("UPDATE asset SET validation_status=? WHERE id=?",status,Long.parseLong(id));
                assertThatThrownBy(()->service.submit(input("PUBLISH"),UUID.randomUUID().toString(),1))
                        .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ASSET_NOT_READY"));
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_wallpaper_publication",Integer.class)).isEqualTo(before);
            }
            jdbc.update("UPDATE asset SET validation_status='READY',deleted_at=UTC_TIMESTAMP(6) WHERE id=?",Long.parseLong(id));
            assertThatThrownBy(()->service.submit(input("PUBLISH"),UUID.randomUUID().toString(),1))
                    .isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ASSET_NOT_READY"));
            jdbc.update("UPDATE asset SET deleted_at=NULL WHERE id=?",Long.parseLong(id));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM creator_wallpaper_publication",Integer.class)).isEqualTo(before);
    }
    @Test void assetRevokedAfterQueuingStopsPublicationBeforeCreatingAWallpaper()throws Exception {
        var queued=service.submit(input("PUBLISH"),UUID.randomUUID().toString(),1);
        jdbc.update("UPDATE asset SET validation_status='REJECTED' WHERE id=?",Long.parseLong(source));
        service.runNext();var result=service.get(Long.parseLong(queued.taskId()));
        assertThat(result.state()).isEqualTo("NEEDS_INPUT");assertThat(result.wallpaperId()).isNull();
        assertThat(result.errorCode()).isEqualTo("ASSET_NOT_READY");
        verifyNoInteractions(packages);
    }
}
