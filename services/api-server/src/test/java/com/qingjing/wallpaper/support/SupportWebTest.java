package com.qingjing.wallpaper.support;

import static org.mockito.Mockito.*;
import static org.mockito.AdditionalMatchers.aryEq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.qingjing.wallpaper.adminidentity.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.*;
import jakarta.servlet.http.Cookie;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.http.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerExceptionResolver;

class SupportWebTest {
    SupportService support; SupportMediaService media; DeviceIdentityService identity; MockMvc mvc;
    DevicePrincipal principal;
    @BeforeEach void setup() {
        support = mock(SupportService.class); media = mock(SupportMediaService.class); identity = mock(DeviceIdentityService.class);
        var adminSessions = mock(AdminSessionService.class);
        var admin = new AdminSessionService.SessionData(1, "fixture", "fixture-csrf", Instant.now().plusSeconds(60));
        when(adminSessions.require(null)).thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "需要登录"));
        when(adminSessions.require("fixture-session")).thenReturn(admin);
        doAnswer(call -> { if (!Objects.equals(call.getArgument(1), admin.csrfToken())) throw new ApiException(HttpStatus.FORBIDDEN,"CSRF_INVALID","会话校验失败"); return null; })
                .when(adminSessions).requireCsrf(eq(admin), nullable(String.class));
        var session = new DeviceIdentityService.SessionData(291, UUID.randomUUID().toString(), DeviceDtos.DevicePlatform.ANDROID,
                DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY, Instant.now().plusSeconds(60));
        principal = new DevicePrincipal(session.deviceId(), session.credentialKeyId(), session.platform(), session.credentialType());
        when(identity.requireSession("fixture-device")).thenReturn(session); when(identity.principal(session)).thenReturn(principal);
        when(support.forDevice(principal)).thenReturn(10L);
        mvc = MockMvcBuilders.standaloneSetup(new DeviceSupportController(support,media), new AdminSupportController(support,media))
                .setControllerAdvice(new ApiExceptionHandler())
                .addFilters(new SignedBodyFilter(mock(HandlerExceptionResolver.class)))
                .addMappedInterceptors(new String[]{"/api/v1/device/support/**"}, new DeviceAuthInterceptor(identity,mock(RedisRateLimiter.class)))
                .addMappedInterceptors(new String[]{"/api/v1/admin/support/**"}, new AdminAuthInterceptor(adminSessions)).build();
    }
    @Test void unauthenticatedReadsCannotReachSupportData() throws Exception {
        for (String path : List.of("/api/v1/device/support/messages", "/api/v1/device/support/attachments/1/access", "/api/v1/admin/support/conversations", "/api/v1/admin/support/library"))
            mvc.perform(get(path)).andExpect(status().isUnauthorized());
        verifyNoInteractions(support,media);
    }
    @Test void administratorWritesRequireTheSessionCsrfProof() throws Exception {
        mvc.perform(delete("/api/v1/admin/support/conversations/10").cookie(new Cookie(AdminSessionService.COOKIE_NAME,"fixture-session")))
                .andExpect(status().isForbidden()); verifyNoInteractions(support);
        mvc.perform(delete("/api/v1/admin/support/conversations/10").cookie(new Cookie(AdminSessionService.COOKIE_NAME,"fixture-session"))
                .header("X-CSRF-Token","fixture-csrf")).andExpect(status().isNoContent()); verify(support).hide(10L);
    }
    @Test void deviceReadsAlwaysUseTheAuthenticatedConversation() throws Exception {
        when(support.messages(10L,null,null,50)).thenReturn(new SupportDtos.MessagePage(List.of(),"0",false));
        mvc.perform(get("/api/v1/device/support/messages").param("conversationId","999").header("Authorization","Bearer fixture-device"))
                .andExpect(status().isOk()).andExpect(jsonPath("items").isEmpty());
        verify(support).messages(10L,null,null,50);
    }
    @Test void signedMessageWritesVerifyTheOriginalRequestBytes() throws Exception {
        String clientId = UUID.randomUUID().toString(); String nonce = UUID.randomUUID().toString(); String timestamp = Instant.now().toString();
        String body = "{\"clientId\":\""+clientId+"\",\"kind\":\"TEXT\",\"text\":\"消息\"}";
        when(support.send(eq(10L),eq(true),any())).thenReturn(new SupportDtos.Message("1","10",clientId,"CUSTOMER",SupportDtos.MessageKind.TEXT,"消息",null,Instant.now()));
        mvc.perform(post("/api/v1/device/support/messages").header("Authorization","Bearer fixture-device").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest()); verify(support,never()).send(anyLong(),anyBoolean(),any());
        mvc.perform(post("/api/v1/device/support/messages").header("Authorization","Bearer fixture-device")
                .header("X-Request-Timestamp",timestamp).header("X-Request-Nonce",nonce).header("X-Request-Signature","fixture-proof")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isCreated()).andExpect(jsonPath("clientId").value(clientId));
        verify(identity).verifySignedRequest(any(),eq("POST"),eq("/api/v1/device/support/messages"),eq(timestamp),eq(nonce),aryEq(body.getBytes(StandardCharsets.UTF_8)),eq("fixture-proof"));
    }
}
