package com.qingjing.wallpaper.operations;

import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** Older clients still contribute through successful authenticated business requests. */
@Component
public class DeviceActivityInterceptor implements HandlerInterceptor {
    private static final Logger LOG=LoggerFactory.getLogger(DeviceActivityInterceptor.class);
    private final OperationsService operations;
    public DeviceActivityInterceptor(OperationsService operations) { this.operations=operations; }
    @Override public void afterCompletion(HttpServletRequest request,HttpServletResponse response,Object handler,Exception error) {
        if(error!=null || response.getStatus()<200 || response.getStatus()>=300 ||
                !(request.getAttribute(RequestAttributes.DEVICE_PRINCIPAL) instanceof DevicePrincipal device) ||
                !isUsage(request.getMethod(),request.getRequestURI())) return;
        try { operations.recordActivity(device.deviceId(),new OperationsService.Activity(null,null,null),
                request.getHeader("X-App-Version-Name"),request.getHeader("X-App-Version-Code"));
            if(request.getAttribute(RequestAttributes.DEVICE_DOWNLOAD_OUTCOME) instanceof com.qingjing.wallpaper.delivery.DeliveryDtos.DownloadDescriptor download) {
                operations.recordDownload(device.deviceId(),Long.parseLong(download.wallpaperId()),download.ticket());
            }
        }
        catch(DataAccessException failure) { LOG.warn("Unable to persist device activity",failure); }
    }
    static boolean isUsage(String method,String path) {
        if(!method.equals("GET") && !method.equals("POST")) return false;
        return path.equals("/api/v1/public/categories") || path.startsWith("/api/v1/public/wallpapers")
            || path.equals("/api/v1/device/me/entitlements") || path.startsWith("/api/v1/device/wallpapers/")
            || path.startsWith("/api/v1/device/redemptions") || path.startsWith("/api/v1/device/ios/");
    }
}
