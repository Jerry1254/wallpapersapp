package com.qingjing.wallpaper.iosacquisition;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qingjing.ios-acquisition")
public class IosAcquisitionProperties {
    private boolean enabled;
    private String teamId = "";
    private String appIdPrefix = "";
    private String bundleId = "com.qingjing.bizhi";
    private Long appAppleId;
    private String appAttestEnvironment = "DEVELOPMENT";
    private String storeEnvironment = "SANDBOX";
    private String deviceCheckKeyId = "";
    private String deviceCheckPrivateKey = "";
    private String storeIssuerId = "";
    private String storeKeyId = "";
    private String storePrivateKey = "";
    private Duration challengeTtl = Duration.ofMinutes(2);
    private Duration resetTtl = Duration.ofMinutes(30);

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getTeamId() { return teamId; }
    public void setTeamId(String teamId) { this.teamId = teamId; }
    public String getAppIdPrefix() { return appIdPrefix; }
    public void setAppIdPrefix(String appIdPrefix) { this.appIdPrefix = appIdPrefix; }
    public String getBundleId() { return bundleId; }
    public void setBundleId(String bundleId) { this.bundleId = bundleId; }
    public Long getAppAppleId() { return appAppleId; }
    public void setAppAppleId(Long appAppleId) { this.appAppleId = appAppleId; }
    public String getAppAttestEnvironment() { return appAttestEnvironment; }
    public void setAppAttestEnvironment(String value) { appAttestEnvironment = value; }
    public String getStoreEnvironment() { return storeEnvironment; }
    public void setStoreEnvironment(String value) { storeEnvironment = value; }
    public String getDeviceCheckKeyId() { return deviceCheckKeyId; }
    public void setDeviceCheckKeyId(String value) { deviceCheckKeyId = value; }
    public String getDeviceCheckPrivateKey() { return deviceCheckPrivateKey; }
    public void setDeviceCheckPrivateKey(String value) { deviceCheckPrivateKey = value; }
    public String getStoreIssuerId() { return storeIssuerId; }
    public void setStoreIssuerId(String value) { storeIssuerId = value; }
    public String getStoreKeyId() { return storeKeyId; }
    public void setStoreKeyId(String value) { storeKeyId = value; }
    public String getStorePrivateKey() { return storePrivateKey; }
    public void setStorePrivateKey(String value) { storePrivateKey = value; }
    public Duration getChallengeTtl() { return challengeTtl; }
    public void setChallengeTtl(Duration value) { challengeTtl = value; }
    public Duration getResetTtl() { return resetTtl; }
    public void setResetTtl(Duration value) { resetTtl = value; }

    public boolean hasAppleCredentials() {
        return enabled && !teamId.isBlank() && !appIdPrefix.isBlank() && !bundleId.isBlank()
                && appAppleId != null && !deviceCheckKeyId.isBlank() && !deviceCheckPrivateKey.isBlank()
                && !storeIssuerId.isBlank() && !storeKeyId.isBlank() && !storePrivateKey.isBlank();
    }
}
