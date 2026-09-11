package com.qingjing.wallpaper.adminidentity;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration(proxyBeanMethods = false)
public class AdminWebConfiguration implements WebMvcConfigurer {

    private final AdminAuthInterceptor interceptor;

    public AdminWebConfiguration(AdminAuthInterceptor interceptor) {
        this.interceptor = interceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/api/v1/admin/**");
    }
}
