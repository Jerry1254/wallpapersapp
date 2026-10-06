package com.qingjing.wallpaper.appupdate;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AppUpdateWebConfiguration implements WebMvcConfigurer {
    private final AppUpdateInterceptor interceptor;
    public AppUpdateWebConfiguration(AppUpdateInterceptor interceptor) { this.interceptor = interceptor; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/device/**").order(20);
    }
}
