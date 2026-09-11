package com.qingjing.wallpaper.adminidentity;

import com.qingjing.wallpaper.adminidentity.AdminSessionService.CreatedSession;
import com.qingjing.wallpaper.adminidentity.AdminSessionService.SessionData;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Duration;
import java.time.Instant;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/sessions")
public class AdminSessionController {

    private final AdminSessionService sessions;
    private final AdminIdentityProperties properties;

    public AdminSessionController(AdminSessionService sessions, AdminIdentityProperties properties) {
        this.sessions = sessions;
        this.properties = properties;
    }

    @PostMapping
    ResponseEntity<AdminSessionResponse> login(
            @Valid @RequestBody AdminLoginRequest body,
            HttpServletRequest request) {
        CreatedSession created = sessions.login(body.username(), body.password(), request.getRemoteAddr());
        request.setAttribute(RequestAttributes.ADMIN_PRINCIPAL, created.data().principal());
        return ResponseEntity.status(201)
                .header(HttpHeaders.SET_COOKIE, sessionCookie(created.sessionToken(), properties.getSessionTtl()).toString())
                .body(response(created.data()));
    }

    @GetMapping
    AdminSessionResponse current(
            @CookieValue(name = AdminSessionService.COOKIE_NAME) String token) {
        return response(sessions.require(token));
    }

    @DeleteMapping
    ResponseEntity<Void> logout(
            @CookieValue(name = AdminSessionService.COOKIE_NAME) String token,
            HttpServletResponse response) {
        sessions.revoke(token);
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(AdminSessionService.COOKIE_NAME, value)
                .httpOnly(true)
                .secure(properties.isCookieSecure())
                .sameSite("Strict")
                .path("/api/v1/admin")
                .maxAge(maxAge)
                .build();
    }

    private AdminSessionResponse response(SessionData session) {
        return new AdminSessionResponse(
                new AdminReference(Long.toString(session.adminId()), session.username()),
                session.csrfToken(),
                session.expiresAt());
    }

    public record AdminLoginRequest(
            @NotBlank @Size(min = 4, max = 64) String username,
            @NotBlank @Size(min = 12, max = 128) String password) {
    }

    public record AdminSessionResponse(AdminReference admin, String csrfToken, Instant expiresAt) {
    }

    public record AdminReference(String id, String username) {
    }
}
