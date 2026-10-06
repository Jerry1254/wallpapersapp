package com.qingjing.wallpaper.creator;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.qingjing.wallpaper.adminidentity.*;
import com.qingjing.wallpaper.catalog.*;
import com.qingjing.wallpaper.delivery.*;
import com.qingjing.wallpaper.iosacquisition.IosProductService;
import com.qingjing.wallpaper.parallax.ParallaxPackageService;
import com.qingjing.wallpaper.shared.web.*;
import jakarta.validation.Validation;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.PlatformTransactionManager;

class CreatorPublicationTest {
    final ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    final CreatorCapabilities capabilities=new CreatorCapabilities("creator-test","隔离测试","LOCAL","2.21.0");
    CreatorPublicationService service;
    @BeforeEach void setUp() {
        var jdbc=mock(JdbcTemplate.class);when(jdbc.getDataSource()).thenReturn(mock(DataSource.class));
        service=new CreatorPublicationService(jdbc,mock(PlatformTransactionManager.class),mapper,
                Validation.buildDefaultValidatorFactory().getValidator(),capabilities,mock(AdminWallpaperService.class),
                mock(ParallaxPackageService.class),mock(IosProductService.class),mock(SecurePackagePublisher.class),
                mock(MovingPhotoPublisher.class),mock(LivePhotoPublisher.class),mock(WallpaperPublicationChecks.class));
    }
    static String valid() {return """
            {"environmentId":"creator-test","clientProjectKey":"local-project-1","action":"PUBLISH",
             "rulesVersion":"creator-wallpaper-rules-1","metadata":{"title":"创作台测试","slug":"creator-test",
             "accessType":"FREE","rootCategoryId":"1","coverAssetId":"2","sortOrder":0,"copyrightNote":"原创"},
             "resources":[{"platform":"UNIVERSAL","resourceType":"STATIC_IMAGE","assetId":"3"}],
             "retainResourceVersionIds":[]}
            """;}
    ObjectNode body()throws Exception{return (ObjectNode)mapper.readTree(valid());}
    void reject(ObjectNode body,String code) {
        assertThatThrownBy(()->service.decode(body)).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo(code));
    }
    @Test void separatesSourceAndPreviewRulesAndDeclaresTheNarrowScope() {
        var rules=capabilities.get();
        var ios=rules.wallpaperVariants().stream().filter(r->r.platform().equals("IOS")).findFirst().orElseThrow();
        assertThat(ios.acceptedMimeTypes()).containsExactly("video/mp4");
        assertThat(ios.source()).containsEntry("minDisplayFrames",60);
        assertThat(ios.preview()).containsEntry("durationMs",1000).containsEntry("fps",60);
        assertThat(rules.supportedOperations()).containsEntry("projectPersistence",false).containsEntry("wallpaperPublication",true);
        assertThat(rules.supportedMetadataFields()).contains("previewWatermarkEnabled","offlinePromotionOnly");
    }
    @Test void acceptsARealStaticUploadAndKeepsStableStringIds()throws Exception {
        var request=service.decode(body());assertThat(request.resources().get(0).assetId()).isEqualTo("3");
        assertThat(request.metadata().coverAssetId()).isEqualTo("2");
    }
    @Test void rejectsOldMediaAndArtifactContractsInsteadOfPretendingToSupportProjects()throws Exception {
        var input=body();input.put("coverMediaId","2");reject(input,"VALIDATION_FAILED");
        input=body();((ObjectNode)input.path("resources").get(0)).put("artifactId","20");reject(input,"VALIDATION_FAILED");
        input=body();((ObjectNode)input.path("metadata")).put("presentationPolicy","hidden");reject(input,"VALIDATION_FAILED");
    }
    @Test void preventsCrossEnvironmentAndStaleRuleSubmissions()throws Exception {
        var input=body();input.put("environmentId","production");reject(input,"CREATOR_ENVIRONMENT_MISMATCH");
        input=body();input.put("rulesVersion","old");reject(input,"CREATOR_RULES_CHANGED");
    }
    @Test void updateRequiresIdAndVersionTogetherAndDraftDoesNotNeedResources()throws Exception {
        var input=body();input.put("wallpaperId","10");reject(input,"VALIDATION_FAILED");
        input=body();input.put("expectedWallpaperVersion",7);reject(input,"VALIDATION_FAILED");
        input=body();input.putArray("resources");reject(input,"VALIDATION_FAILED");
        input.put("action","SAVE_DRAFT");assertThat(service.decode(input).resources()).isEmpty();
    }
    @Test void rejectsInvalidPairsDuplicateCapabilitiesAndUnparsed4DAssets()throws Exception {
        var input=body();var resource=(ObjectNode)input.path("resources").get(0);
        resource.put("platform","HARMONYOS");reject(input,"VALIDATION_FAILED");
        input=body();((com.fasterxml.jackson.databind.node.ArrayNode)input.path("resources")).add(input.path("resources").get(0).deepCopy());reject(input,"VALIDATION_FAILED");
        input=body();resource=(ObjectNode)input.path("resources").get(0);resource.put("platform","ANDROID");resource.put("resourceType","LAYER_PARALLAX");reject(input,"VALIDATION_FAILED");
        resource.remove("assetId");resource.put("sourcePackageId","5");assertThat(service.decode(input).resources().get(0).sourcePackageId()).isEqualTo("5");
    }
    @Test void hashIsIndependentOfJsonFieldOrderButChangesWhenThePublicationInputChanges()throws Exception {
        var original=body();var reordered=mapper.createObjectNode();var names=new java.util.ArrayList<String>();original.fieldNames().forEachRemaining(names::add);java.util.Collections.reverse(names);names.forEach(n->reordered.set(n,original.get(n)));
        assertThat(service.hash(original)).isEqualTo(service.hash(reordered));
        reordered.put("action","SAVE_DRAFT");assertThat(service.hash(original)).isNotEqualTo(service.hash(reordered));
    }
    @Test void aQueuedCreationHasNullWallpaperIdentityAndSupportsMysqlDatetimeValues()throws Exception {
        var row=new java.util.HashMap<String,Object>();
        row.put("request_json",valid());row.put("id",1L);row.put("state","QUEUED");row.put("stage","QUEUED");row.put("environment_id","creator-test");row.put("steps","{}");row.put("attempt",0);row.put("retryable",false);
        var now=java.time.LocalDateTime.of(2026,10,6,12,0);row.put("created_at",now);row.put("updated_at",java.sql.Timestamp.valueOf(now));
        var result=service.view(row);assertThat(result.wallpaperId()).isNull();assertThat(result.wallpaperVersion()).isNull();assertThat(result.clientProjectKey()).isEqualTo("local-project-1");
        assertThat(mapper.readTree(mapper.writeValueAsString(result)).has("wallpaperVersion")).isTrue();
    }
    @Test void newRoutesRetainAdminAuthenticationAndCsrfIncludingTheReadOnlyPost()throws Exception {
        var sessions=mock(AdminSessionService.class);
        when(sessions.require(null)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","login required"));
        MockMvc mvc=MockMvcBuilders.standaloneSetup(new CreatorPublicationController(capabilities,service))
                .addInterceptors(new AdminAuthInterceptor(sessions)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/admin/creator/capabilities")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/admin/creator/wallpaper-publications").contentType("application/json").content(valid())
                .header("Idempotency-Key",java.util.UUID.randomUUID())).andExpect(status().isUnauthorized());
        verify(sessions,times(2)).require(null);
        var session=new AdminSessionService.SessionData(1,"operator","csrf",java.time.Instant.now().plusSeconds(3600));
        when(sessions.require("valid-session")).thenReturn(session);
        doThrow(new ApiException(HttpStatus.FORBIDDEN,"CSRF_INVALID","csrf required")).when(sessions).requireCsrf(session,null);
        mvc.perform(get("/api/v1/admin/creator/capabilities").cookie(new jakarta.servlet.http.Cookie(AdminSessionService.COOKIE_NAME,"valid-session"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/admin/creator/wallpaper-publications").contentType("application/json").content(valid())
                .cookie(new jakarta.servlet.http.Cookie(AdminSessionService.COOKIE_NAME,"valid-session"))
                .header("Idempotency-Key",java.util.UUID.randomUUID())).andExpect(status().isForbidden());
        verify(sessions).requireCsrf(session,null);
    }
}
