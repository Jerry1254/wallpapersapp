package com.qingjing.wallpaper.iosacquisition;

import com.qingjing.wallpaper.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Safe default: Apple-backed writes stay closed until credentials and trust roots are configured. */
@Component
public class UnavailableIosAppleGateway implements IosAppleGateway {
    private ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IOS_ACQUISITION_UNAVAILABLE",
                "Apple verification credentials are not configured");
    }
    @Override public AttestedKey verifyAttestation(String a,String b,String c,String d){ throw unavailable(); }
    @Override public long verifyAssertion(String a,String b,byte[] c,long d){ throw unavailable(); }
    @Override public DeviceBits queryDeviceBits(String a){ throw unavailable(); }
    @Override public void markFirstFreeUsed(String a,String b){ throw unavailable(); }
    @Override public void resetFirstFreeBit(String a,String b){ throw unavailable(); }
    @Override public VerifiedTransaction verifyTransaction(String a,String b,String c){ throw unavailable(); }
    @Override public VerifiedNotification verifyNotification(String a){ throw unavailable(); }
}
