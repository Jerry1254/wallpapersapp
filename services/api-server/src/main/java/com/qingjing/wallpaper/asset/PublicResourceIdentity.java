package com.qingjing.wallpaper.asset;

import com.qingjing.wallpaper.device.DeviceIdentityService;
import com.qingjing.wallpaper.shared.web.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Cover URLs stay public for ONLINE resources; optional sessions enable the registered offline App. */
@Component
public class PublicResourceIdentity {
    private final DeviceIdentityService identity;
    public PublicResourceIdentity(DeviceIdentityService identity) { this.identity=identity; }

    public long deviceId(String authorization) {
        if(authorization==null) return 0;
        if(identity==null || !authorization.startsWith("Bearer ") || authorization.length()<=7)
            throw new ApiException(HttpStatus.UNAUTHORIZED,"UNAUTHORIZED","A valid device session is required");
        return identity.requireSession(authorization.substring(7)).deviceId();
    }
}
