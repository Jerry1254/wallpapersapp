package com.qingjing.wallpaper.appupdate;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.*;
import com.qingjing.wallpaper.asset.application.StoredContent;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AppUpdateControllerTest {
    @Test void checkUsesPublicDataEnvelopeWithExplicitNullableVersionFields() throws Exception {
        var service = mock(AppReleaseService.class);
        when(service.check("ios","1.0.0",10022,null,null,null)).thenReturn(new CheckResult("ios",false,false,null,null,Instant.EPOCH));
        var mvc = MockMvcBuilders.standaloneSetup(new AppUpdateController(service)).setControllerAdvice(new ApiExceptionHandler()).build();
        mvc.perform(get("/api/v1/app-updates/check").param("platform","ios").param("versionName","1.0.0").param("versionCode","10022"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.data.mandatory").value(false)).andExpect(jsonPath("$.data.latestVersion").value(org.hamcrest.Matchers.nullValue()));
    }
    @Test void streamedRangesDeliverExactlyTheSelectedBytesAndInvalidRangesDoNotOpenStorage() throws Exception {
        var service = mock(AppReleaseService.class);
        byte[] bytes = new byte[]{0,1,2,3,4,5};
        var view = new ReleaseView("123","android","1.0.0",100,"更新",false,"PUBLISHED","apk",null,"com.qingjing.bizhi",
                "a".repeat(64),6L,"universal","b".repeat(64),24,"/api/v1/app-updates/packages/123",Instant.EPOCH,Instant.EPOCH);
        when(service.downloadable("123")).thenReturn(view);
        when(service.openPackage("123")).thenAnswer(invocation -> new StoredContent(new ByteArrayInputStream(bytes),6));
        var mvc = MockMvcBuilders.standaloneSetup(new AppUpdateController(service)).build();
        var pending = mvc.perform(get("/api/v1/app-updates/packages/123").header("Range","bytes=2-4"))
                .andExpect(request().asyncStarted()).andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isPartialContent())
                .andExpect(header().string("Content-Range","bytes 2-4/6")).andExpect(content().bytes(new byte[]{2,3,4}));
        verify(service).openPackage("123");
        clearInvocations(service);
        mvc.perform(get("/api/v1/app-updates/packages/123").header("Range","bytes=9-"))
                .andExpect(status().is(416)).andExpect(header().string("Content-Range","bytes */6"));
        verify(service,never()).openPackage(anyString());
    }
}
