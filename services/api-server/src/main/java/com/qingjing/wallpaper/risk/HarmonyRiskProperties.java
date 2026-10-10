package com.qingjing.wallpaper.risk;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qingjing.risk.harmony")
public class HarmonyRiskProperties {
    private boolean enabled = true;
    private String appId = "6917617603491246393";
    // Public Huawei root from the Profile downloaded directly from AGC on 2026-10-10.
    private String rootCa = "classpath:huawei/Huawei_CBG_Root_CA_G2.pem";
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getRootCa() { return rootCa; }
    public void setRootCa(String rootCa) { this.rootCa = rootCa; }
}
