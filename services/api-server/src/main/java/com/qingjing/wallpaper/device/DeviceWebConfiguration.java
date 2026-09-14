package com.qingjing.wallpaper.device;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class DeviceWebConfiguration implements WebMvcConfigurer {

    private final DeviceAuthInterceptor interceptor;

    public DeviceWebConfiguration(DeviceAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns(
                "/api/v1/device/me/**",
                "/api/v1/device/encryption-key",
                "/api/v1/device/redemptions",
                "/api/v1/device/redemptions/**",
                "/api/v1/device/wallpapers/**");
    }
}
