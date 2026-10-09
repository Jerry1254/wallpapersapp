package com.qingjing.wallpaper.risk;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.*;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class RiskInterceptor implements HandlerInterceptor {
    private static final Set<String> RECOVERY=Set.of("/api/v1/device/registrations","/api/v1/device/session-challenges","/api/v1/device/sessions");
    private final RiskService risk;
    private final ClientAddress address;
    public RiskInterceptor(RiskService risk,ClientAddress address) { this.risk=risk; this.address=address; }
    static boolean recovery(String path) { return RECOVERY.contains(path) || path.startsWith("/api/v1/device/security/"); }
    @Override public boolean preHandle(HttpServletRequest request,HttpServletResponse response,Object handler) {
        if(request.getMethod().equals("OPTIONS") || recovery(request.getRequestURI())) return true;
        String ip=address.of(request);
        risk.requireAllowed(null,ip);
        Object value=request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        // Authenticated requests are counted before authentication rate limiting, including rejected bursts.
        if(!(value instanceof DevicePrincipal)) risk.count("REQUEST_FLOOD",null,ip,null);
        return true;
    }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        // A 404 for an outbox lookup is a normal retry, not resource scanning.
        if(response.getStatus()!=404 || !request.getRequestURI().matches("/api/v1/public/wallpapers/[0-9]+")) return;
        Object principal=request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL);
        Long device=principal instanceof DevicePrincipal d?d.deviceId():null;
        try { risk.count("RESOURCE_SCAN",device,address.of(request),null); }
        catch(ApiException failure) { if(!failure.code().equals("ACCESS_UNAVAILABLE")) throw failure; }
    }
}
