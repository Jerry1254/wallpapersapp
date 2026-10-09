package com.qingjing.wallpaper.appupdate;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.device.DeviceDtos.*;
import com.qingjing.wallpaper.entitlement.DeviceEntitlementService;
import com.qingjing.wallpaper.redemption.DeviceRedemptionController;
import com.qingjing.wallpaper.redemption.RedemptionService;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class AppUpdateMvcChainTest {
    @Configuration(proxyBeanMethods=false)
    @EnableWebMvc
    @Import({DeviceWebConfiguration.class,AppUpdateWebConfiguration.class})
    static class Config {
        @Bean DeviceIdentityService identity() { return mock(DeviceIdentityService.class); }
        @Bean RedisRateLimiter limiter() { return mock(RedisRateLimiter.class); }
        @Bean DeviceAuthInterceptor authentication(DeviceIdentityService identity,RedisRateLimiter limiter) {
            return new DeviceAuthInterceptor(identity,limiter,mock(com.qingjing.wallpaper.risk.RiskService.class),new com.qingjing.wallpaper.risk.ClientAddress(""));
        }
        @Bean AppReleaseService releases() { return mock(AppReleaseService.class); }
        @Bean AppUpdateInterceptor updates(AppReleaseService releases) { return new AppUpdateInterceptor(releases); }
        @Bean RedemptionService redemptions() { return mock(RedemptionService.class); }
        @Bean DeviceRedemptionController redemption(RedemptionService service) { return new DeviceRedemptionController(service,mock(DeviceEntitlementService.class)); }
        @Bean AppUpdateController updateController(AppReleaseService releases) { return new AppUpdateController(releases); }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
    }
    @Test void actualMvcInterceptorChainAuthenticatesBeforeBlockingAndLeavesPublicUpdateCheckReachable() throws Exception {
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Config.class);
            context.refresh();
            var identity = context.getBean(DeviceIdentityService.class);
            var releases = context.getBean(AppReleaseService.class);
            var session = new DeviceIdentityService.SessionData(1,"test-installation",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY,Instant.now().plusSeconds(600));
            var principal = new DevicePrincipal(1,"test-installation",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY);
            when(identity.requireSession("test-token")).thenReturn(session);
            when(identity.principal(session)).thenReturn(principal);
            doThrow(new ApiException(HttpStatus.UPGRADE_REQUIRED,"APP_UPDATE_REQUIRED","需要更新"))
                    .when(releases).requireForDevice(eq(principal),isNull(),isNull(),isNull(),isNull());
            var mvc = MockMvcBuilders.webAppContextSetup(context).build();
            mvc.perform(post("/api/v1/device/redemptions").header("Authorization","Bearer test-token")
                            .header("X-Request-Timestamp",Instant.now().toString()).header("X-Request-Nonce","test-nonce")
                            .header("X-Request-Signature","test-signature").contentType("application/json").content("{}"))
                    .andExpect(status().is(426)).andExpect(jsonPath("$.error.code").value("APP_UPDATE_REQUIRED"));
            verify(releases).requireForDevice(eq(principal),isNull(),isNull(),isNull(),isNull());
            verifyNoInteractions(context.getBean(RedemptionService.class));
            mvc.perform(post("/api/v1/device/redemptions").contentType("application/json").content("{}"))
                    .andExpect(status().isUnauthorized());
            clearInvocations(identity,releases);
            when(releases.check("ios","1.0.0",10022,null,null,"com.qingjing.bizhi"))
                    .thenReturn(new CheckResult("ios",true,true,null,null,Instant.EPOCH));
            mvc.perform(get("/api/v1/app-updates/check").param("platform","ios").param("versionName","1.0.0")
                            .param("versionCode","10022").param("packageName","com.qingjing.bizhi"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.mandatory").value(true));
            verifyNoInteractions(identity);
        }
    }
}
