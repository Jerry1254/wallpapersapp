package com.qingjing.wallpaper.iosacquisition;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IosAcquisitionProperties.class, IosPricingProperties.class})
public class IosAcquisitionConfiguration {
}
