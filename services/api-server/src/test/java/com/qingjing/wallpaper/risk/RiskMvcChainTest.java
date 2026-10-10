package com.qingjing.wallpaper.risk;

import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.ApiExceptionHandler;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class RiskMvcChainTest {
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({DeviceWebConfiguration.class,RiskWebConfiguration.class})
    static class Config {
        @Bean RiskService risk() { return mock(RiskService.class); }
        @Bean ClientAddress address() { return new ClientAddress(""); }
        @Bean DeviceIdentityService identity() { return mock(DeviceIdentityService.class); }
        @Bean RedisRateLimiter limiter() { return mock(RedisRateLimiter.class); }
        @Bean DeviceAuthInterceptor auth(DeviceIdentityService identity,RedisRateLimiter limiter,RiskService risk,ClientAddress address) {
            return new DeviceAuthInterceptor(identity,limiter,risk,address);
        }
        @Bean RiskInterceptor guard(RiskService risk,ClientAddress address) { return new RiskInterceptor(risk,address); }
        @Bean HarmonyRiskService harmony() { return mock(HarmonyRiskService.class); }
        @Bean DeviceSecurityController security(RiskService risk,ClientAddress address,HarmonyRiskService harmony) { return new DeviceSecurityController(risk,address,harmony); }
        @Bean ProbeController probes() { return new ProbeController(); }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
    }
    @RestController static class ProbeController {
        @GetMapping({"/api/v1/device/me/probe","/api/v1/public/assets/probe","/api/v1/delivery/probe","/api/v1/preview/probe","/api/v1/app-updates/probe","/api/v1/admin/probe"}) String get() { return "ok"; }
        @PostMapping("/api/v1/device/session-challenges") String bootstrap() { return "ok"; }
    }
    @Test void bansBlockEveryBusinessGatewayButRecoveryAndSeparateAdminRemainReachable() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var risk=context.getBean(RiskService.class);var identity=context.getBean(DeviceIdentityService.class);
            var session=new DeviceIdentityService.SessionData(291,"test-install",DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY,Instant.now().plusSeconds(60));
            var device=new DevicePrincipal(291,"test-install",DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
            when(identity.requireSession("test-token")).thenReturn(session);when(identity.principal(session)).thenReturn(device);
            when(risk.state(eq(device),anyString())).thenReturn(new RiskDtos.State(false,List.of()));
            doThrow(RiskService.unavailable()).when(risk).requireAllowed(any(),anyString());
            var mvc=MockMvcBuilders.webAppContextSetup(context).build();
            for(String path:List.of("device/me/probe","public/assets/probe","delivery/probe","preview/probe","app-updates/probe")) {
                mvc.perform(get("/api/v1/"+path).header("Authorization","Bearer test-token"))
                    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error.code").value("ACCESS_UNAVAILABLE"))
                    .andExpect(jsonPath("$.error.message").value("网络异常，请稍后重试"));
            }
            mvc.perform(get("/api/v1/device/security/state").header("Authorization","Bearer test-token"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.allowed").value(false));
            for (String path:List.of("harmony/challenges","harmony/reports")) {
                mvc.perform(post("/api/v1/device/security/"+path).header("Authorization","Bearer test-token")
                    .contentType("application/json").content("{}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.error.code").value("SIGNED_REQUEST_INVALID"));
            }
            verifyNoInteractions(context.getBean(HarmonyRiskService.class));
            mvc.perform(post("/api/v1/device/session-challenges")).andExpect(status().isOk());
            mvc.perform(get("/api/v1/admin/probe")).andExpect(status().isOk());
            clearInvocations(risk);reset(risk);
            doThrow(RiskService.unavailable()).when(risk).requireAllowed(eq(291L),anyString());
            mvc.perform(get("/api/v1/device/me/probe").header("Authorization","Bearer test-token"))
                .andExpect(status().isForbidden());
            verify(risk).requireAllowed(eq(291L),anyString());
        }
    }
}
