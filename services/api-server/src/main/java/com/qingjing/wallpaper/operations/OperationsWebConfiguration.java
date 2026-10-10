package com.qingjing.wallpaper.operations;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
public class OperationsWebConfiguration implements WebMvcConfigurer {
    private final DeviceActivityInterceptor activity;
    public OperationsWebConfiguration(DeviceActivityInterceptor activity) { this.activity=activity; }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(activity).addPathPatterns("/api/v1/device/**","/api/v1/public/**").order(40);
    }
}
