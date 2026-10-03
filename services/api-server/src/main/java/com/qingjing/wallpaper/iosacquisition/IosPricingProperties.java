package com.qingjing.wallpaper.iosacquisition;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qingjing.ios-pricing")
public class IosPricingProperties {
    private boolean enabled;
    private String issuerId = "", keyId = "", privateKey = "", privateKeyFile = "";
    private Duration maxAge = Duration.ofMinutes(15);
    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean value) { enabled = value; }
    public String getIssuerId() { return issuerId; }
    public void setIssuerId(String value) { issuerId = value; }
    public String getKeyId() { return keyId; }
    public void setKeyId(String value) { keyId = value; }
    public String getPrivateKey() { return privateKey; }
    public void setPrivateKey(String value) { privateKey = value; }
    public String getPrivateKeyFile() { return privateKeyFile; }
    public void setPrivateKeyFile(String value) { privateKeyFile = value; }
    public Duration getMaxAge() { return maxAge; }
    public void setMaxAge(Duration value) { maxAge = value; }
    public boolean configured() {
        return enabled && !issuerId.isBlank() && !keyId.isBlank()
                && (!privateKey.isBlank() ^ !privateKeyFile.isBlank())
                && !maxAge.isZero() && !maxAge.isNegative();
    }
}
