package com.qingjing.wallpaper.adminidentity;

import com.qingjing.wallpaper.adminidentity.AdminSessionService.SessionData;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class AdminAuthInterceptor implements HandlerInterceptor {

    private final AdminSessionService sessions;

    public AdminAuthInterceptor(AdminSessionService sessions) {
        this.sessions = sessions;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getMethod().equals("OPTIONS") || isLoginRequest(request)) {
            return true;
        }
        String sessionToken = cookie(request, AdminSessionService.COOKIE_NAME);
        SessionData session = sessions.require(sessionToken);
        if (requiresCsrf(request.getMethod())) {
            sessions.requireCsrf(session, request.getHeader("X-CSRF-Token"));
        }
        request.setAttribute(RequestAttributes.ADMIN_PRINCIPAL, session.principal());
        return true;
    }

    private boolean isLoginRequest(HttpServletRequest request) {
        return request.getMethod().equals("POST") && request.getRequestURI().equals("/api/v1/admin/sessions");
    }

    private static boolean requiresCsrf(String method) {
        return !method.equals("GET") && !method.equals("HEAD") && !method.equals("OPTIONS");
    }

    static String cookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        return Arrays.stream(request.getCookies())
                .filter(cookie -> cookie.getName().equals(name))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
