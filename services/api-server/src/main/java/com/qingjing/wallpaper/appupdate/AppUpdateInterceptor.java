package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Runs only after device authentication. Header platform claims never override the authenticated platform. */
@Component
public class AppUpdateInterceptor implements HandlerInterceptor {
    private final AppReleaseService releases;
    public AppUpdateInterceptor(AppReleaseService releases) { this.releases = releases; }
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!blocksNewOperation(request.getMethod(), request.getRequestURI())) return true;
        Object value = request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        if (!(value instanceof DevicePrincipal principal)) return true;
        releases.requireForDevice(principal, request.getHeader("X-App-Version-Name"), request.getHeader("X-App-Version-Code"),
                request.getHeader("X-App-ABI"), request.getHeader("X-App-Android-Sdk"));
        return true;
    }
    static boolean blocksNewOperation(String method, String path) {
        if (!method.equals("POST")) return false;
        return path.equals("/api/v1/device/redemptions")
                || path.equals("/api/v1/device/ios/acquisition/free-claims")
                || path.equals("/api/v1/device/ios/acquisition/credit-orders")
                || (path.startsWith("/api/v1/device/wallpapers/") && path.endsWith("/download-tickets"));
    }
}
