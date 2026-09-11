package com.qingjing.wallpaper.shared.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qingjing.security")
public class SecurityProperties {

    private String masterKey = "";

    public String getMasterKey() {
        return masterKey;
    }

    public void setMasterKey(String masterKey) {
        this.masterKey = masterKey;
    }
}
