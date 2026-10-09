package com.qingjing.wallpaper.risk;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.*;

@Configuration(proxyBeanMethods=false)
public class RiskWebConfiguration implements WebMvcConfigurer {
    private final RiskInterceptor interceptor;
    private final RiskService risk;
    private final ClientAddress address;
    public RiskWebConfiguration(RiskInterceptor interceptor,RiskService risk,ClientAddress address) {
        this.interceptor=interceptor; this.risk=risk; this.address=address;
    }
    @Override public void addInterceptors(InterceptorRegistry registry) {
        String[] paths={"/api/v1/device/**","/api/v1/public/**","/api/v1/delivery/**","/api/v1/preview/**","/api/v1/app-updates/**"};
        registry.addInterceptor(new org.springframework.web.servlet.HandlerInterceptor() {
            @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest request,jakarta.servlet.http.HttpServletResponse response,Object handler) {
                if(!request.getMethod().equals("OPTIONS") && !RiskInterceptor.recovery(request.getRequestURI()))
                    risk.requireAllowed(null,address.of(request));
                return true;
            }
        }).addPathPatterns(paths).order(5);
        registry.addInterceptor(interceptor).addPathPatterns(paths).order(15);
    }
}
