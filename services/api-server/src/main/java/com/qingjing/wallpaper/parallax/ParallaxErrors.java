package com.qingjing.wallpaper.parallax;

import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;

public final class ParallaxErrors {
    private ParallaxErrors() {}
    public static ApiException invalid(String file, String reason, String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PARALLAX_PACKAGE_INVALID", message,
                List.of(new ApiException.ErrorDetail(file, reason)));
    }
    public static ApiException size(String file) {
        return new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", file + " 超过资源包大小限制");
    }
    public static ApiException media(String file) {
        return new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE", file + " 的实际文件类型不支持");
    }
}
