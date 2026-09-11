package com.qingjing.wallpaper.shared.security;

import com.qingjing.wallpaper.device.DeviceProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SecurityProperties.class, DeviceProperties.class})
public class SecurityConfiguration {
}
