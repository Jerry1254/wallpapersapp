package com.qingjing.wallpaper.operations;

import static org.mockito.Mockito.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.*;

class DeviceActivityInterceptorTest {
    @Test void backgroundPollingFailedRequestsAndUnauthenticatedVisitsDoNotCreateUsage() {
        var service=mock(OperationsService.class);var interceptor=new DeviceActivityInterceptor(service);
        for(String path:new String[]{"/api/v1/device/security/state","/api/v1/device/support/unread","/api/v1/device/sessions","/api/v1/device/activity"}) {
            var request=request(path);interceptor.afterCompletion(request,new MockHttpServletResponse(),new Object(),null);
        }
        var failed=new MockHttpServletResponse();failed.setStatus(403);
        interceptor.afterCompletion(request("/api/v1/public/wallpapers"),failed,new Object(),null);
        interceptor.afterCompletion(new MockHttpServletRequest("GET","/api/v1/public/wallpapers"),new MockHttpServletResponse(),new Object(),null);
        verifyNoInteractions(service);
        interceptor.afterCompletion(request("/api/v1/public/wallpapers"),new MockHttpServletResponse(),new Object(),null);
        verify(service).recordActivity(eq(1L),any(),isNull(),isNull());
    }
    private MockHttpServletRequest request(String path) {
        var r=new MockHttpServletRequest("GET",path);r.setAttribute(RequestAttributes.DEVICE_PRINCIPAL,new DevicePrincipal(1,"test",DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY));return r;
    }
    @Test void onlySuccessfullyIssuedDownloadDescriptorsCreateDownloadFacts() {
        var service=mock(OperationsService.class);var interceptor=new DeviceActivityInterceptor(service);
        var descriptor=mock(com.qingjing.wallpaper.delivery.DeliveryDtos.DownloadDescriptor.class);
        when(descriptor.wallpaperId()).thenReturn("42");when(descriptor.ticket()).thenReturn("temporary-ticket");
        var request=request("/api/v1/device/wallpapers/42/download-tickets");request.setMethod("POST");
        request.setAttribute(RequestAttributes.DEVICE_DOWNLOAD_OUTCOME,descriptor);
        var failed=new MockHttpServletResponse();failed.setStatus(500);
        interceptor.afterCompletion(request,failed,new Object(),null);verifyNoInteractions(service);
        var issued=new MockHttpServletResponse();issued.setStatus(201);
        interceptor.afterCompletion(request,issued,new Object(),null);
        verify(service).recordDownload(1,42,"temporary-ticket");
    }
}
