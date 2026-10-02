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
    private String deviceCheckPrivateKeyFile = "";
    private String storePrivateKeyFile = "";
    private String deviceCheckEnvironment = "DEVELOPMENT";
    private String acceptedStoreEnvironments = "";
    private String acceptedBundleVersions = "";
    private String appAttestRoot = "classpath:apple/Apple_App_Attestation_Root_CA.pem";
    private String storeRoot = "classpath:apple/AppleRootCA-G3.cer";
    private boolean onlineCertificateChecks = true;
    private Duration connectTimeout = Duration.ofSeconds(5);
    private Duration requestTimeout = Duration.ofSeconds(15);
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

    public String getDeviceCheckPrivateKeyFile() { return deviceCheckPrivateKeyFile; }
    public void setDeviceCheckPrivateKeyFile(String value) { deviceCheckPrivateKeyFile = value; }
    public String getStorePrivateKeyFile() { return storePrivateKeyFile; }
    public void setStorePrivateKeyFile(String value) { storePrivateKeyFile = value; }
    public String getDeviceCheckEnvironment() { return deviceCheckEnvironment; }
    public void setDeviceCheckEnvironment(String value) { deviceCheckEnvironment = value; }
    public String getAcceptedStoreEnvironments() { return acceptedStoreEnvironments; }
    public void setAcceptedStoreEnvironments(String value) { acceptedStoreEnvironments = value; }
    public String getAcceptedBundleVersions() { return acceptedBundleVersions; }
    public void setAcceptedBundleVersions(String value) { acceptedBundleVersions = value; }
    public String getAppAttestRoot() { return appAttestRoot; }
    public void setAppAttestRoot(String value) { appAttestRoot = value; }
    public String getStoreRoot() { return storeRoot; }
    public void setStoreRoot(String value) { storeRoot = value; }
    public boolean getOnlineCertificateChecks() { return onlineCertificateChecks; }
    public void setOnlineCertificateChecks(boolean value) { onlineCertificateChecks = value; }
    public Duration getConnectTimeout() { return connectTimeout; }
    public void setConnectTimeout(Duration value) { connectTimeout = value; }
    public Duration getRequestTimeout() { return requestTimeout; }
    public void setRequestTimeout(Duration value) { requestTimeout = value; }

    public java.util.Set<String> storeEnvironments() {
        String value = acceptedStoreEnvironments.isBlank() ? storeEnvironment : acceptedStoreEnvironments;
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String item : value.split(",")) {
            String environment = item.strip();
            if (!java.util.Set.of("SANDBOX", "PRODUCTION").contains(environment)) throw new IllegalArgumentException("Invalid StoreKit environment");
            result.add(environment);
        }
        return result;
    }

    public java.util.Set<String> bundleVersions() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        for (String item : acceptedBundleVersions.split(",")) {
            String version = item.strip();
            if (!version.isEmpty()) result.add(version);
        }
        return result;
    }

    public boolean hasAppleCredentials() {
        return enabled && !teamId.isBlank() && !appIdPrefix.isBlank() && !bundleId.isBlank()
                && appAppleId != null && !deviceCheckKeyId.isBlank() && (!deviceCheckPrivateKey.isBlank() || !deviceCheckPrivateKeyFile.isBlank())
                && !storeIssuerId.isBlank() && !storeKeyId.isBlank() && (!storePrivateKey.isBlank() || !storePrivateKeyFile.isBlank());
    }
}
