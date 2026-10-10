package com.qingjing.wallpaper.operations;

import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.risk.*;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockServletContext;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

class OperationsMvcTest {
    @Configuration(proxyBeanMethods=false) @EnableWebMvc
    @Import({DeviceWebConfiguration.class,OperationsController.class})
    static class Config {
        @Bean OperationsService operations() { return mock(OperationsService.class); }
        @Bean DeviceIdentityService identity() { return mock(DeviceIdentityService.class); }
        @Bean RiskService risk() { return mock(RiskService.class); }
        @Bean DeviceAuthInterceptor auth(DeviceIdentityService identity,RiskService risk) {
            return new DeviceAuthInterceptor(identity,mock(RedisRateLimiter.class),risk,new ClientAddress(""));
        }
        @Bean ApiExceptionHandler errors() { return new ApiExceptionHandler(); }
    }
    @Test void foregroundEndpointRequiresSessionSignatureAndAllowedDeviceAndKeepsExactSignedBody() throws Exception {
        try(var context=new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());context.register(Config.class);context.refresh();
            var identity=context.getBean(DeviceIdentityService.class);var operations=context.getBean(OperationsService.class);var risk=context.getBean(RiskService.class);
            var session=new DeviceIdentityService.SessionData(1,"test",DeviceDtos.DevicePlatform.IOS,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY,Instant.now().plusSeconds(3600));
            when(identity.requireSession("test")).thenReturn(session);
            when(identity.principal(session)).thenReturn(new DevicePrincipal(1,"test",DeviceDtos.DevicePlatform.IOS,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY));
            var resolver=context.getBean("handlerExceptionResolver",org.springframework.web.servlet.HandlerExceptionResolver.class);
            var mvc=MockMvcBuilders.webAppContextSetup(context).addFilters(new SignedBodyFilter(resolver)).build();
            String body="{\"manufacturer\":\"Apple\",\"model\":\"iPhone\",\"osVersion\":\"26\"}";
            mvc.perform(post("/api/v1/device/activity").contentType("application/json").content(body)).andExpect(status().isUnauthorized());
            mvc.perform(post("/api/v1/device/activity").header("Authorization","Bearer test").contentType("application/json").content(body)).andExpect(status().isBadRequest());
            verifyNoInteractions(operations);
            mvc.perform(post("/api/v1/device/activity").header("Authorization","Bearer test")
                    .header("X-Request-Timestamp","time").header("X-Request-Nonce","nonce").header("X-Request-Signature","signature")
                    .header("X-App-Version-Name","1.0.3").contentType("application/json").content(body)).andExpect(status().isNoContent());
            verify(identity).verifySignedRequest(eq(session),eq("POST"),eq("/api/v1/device/activity"),eq("time"),eq("nonce"),aryEq(body.getBytes(StandardCharsets.UTF_8)),eq("signature"));
            verify(operations).recordActivity(1,new OperationsService.Activity("Apple","iPhone","26"),"1.0.3",null);
            clearInvocations(operations);
            doThrow(new ApiException(HttpStatus.FORBIDDEN,"ACCESS_UNAVAILABLE","网络异常，请稍后重试")).when(risk).requireAllowed(eq(1L),anyString());
            mvc.perform(post("/api/v1/device/activity").header("Authorization","Bearer test").contentType("application/json").content(body)).andExpect(status().isForbidden());
            verifyNoInteractions(operations);
        }
    }
}
