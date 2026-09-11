package com.qingjing.wallpaper.adminidentity;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdminIdentityProperties.class)
public class AdminIdentityConfiguration {

    @Bean
    PasswordEncoder adminPasswordEncoder(AdminIdentityProperties properties) {
        return new BCryptPasswordEncoder(properties.getPasswordStrength());
    }
}
