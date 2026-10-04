package com.qingjing.wallpaper.appupdate;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.device.DeviceDtos.*;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
class AppUpdateInterceptorTest {
    @Test void newDeliveriesAreBlockedButPaymentConfirmationAndRecoveryStayAccessible() {
        for (String path : new String[]{"/api/v1/device/redemptions", "/api/v1/device/wallpapers/123/download-tickets",
                "/api/v1/device/ios/acquisition/free-claims", "/api/v1/device/ios/acquisition/credit-orders"}) {
            assertThat(AppUpdateInterceptor.blocksNewOperation("POST",path)).isTrue();
        }
        for (String path : new String[]{"/api/v1/device/ios/acquisition/purchases", "/api/v1/device/ios/acquisition/credit-restores",
                "/api/v1/device/ios/acquisition/credit-orders/123/cancel", "/api/v1/device/registrations", "/api/v1/device/sessions",
                "/api/v1/device/ios/acquisition/status", "/api/v1/device/wallpapers/123/preview-tickets"}) {
            assertThat(AppUpdateInterceptor.blocksNewOperation("POST",path)).isFalse();
        }
    }
    @Test void platformIsDerivedFromSessionAndVersionHeadersForwarded() {
        var service = mock(AppReleaseService.class);
        var request = new MockHttpServletRequest("POST","/api/v1/device/redemptions");
        request.setAttribute(RequestAttributes.DEVICE_PRINCIPAL,new DevicePrincipal(1,"test",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY));
        request.addHeader("X-App-Platform","ANDROID");
        request.addHeader("X-App-Version-Name","1.0.0");
        request.addHeader("X-App-Version-Code","10022");
        new AppUpdateInterceptor(service).preHandle(request,new MockHttpServletResponse(),new Object());
        verify(service).requireForDevice(new DevicePrincipal(1,"test",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY),"1.0.0","10022",null,null);
    }
}
