package com.qingjing.wallpaper.device;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("qingjing.device")
public class DeviceProperties {

    private boolean androidEnabled;
    private List<String> allowedAndroidScopes = new ArrayList<>();
    private List<String> onlineTestAndroidScopes = new ArrayList<>();
    public boolean isAndroidEnabled() { return androidEnabled; }
    public void setAndroidEnabled(boolean value) { androidEnabled = value; }
    public List<String> getAllowedAndroidScopes() { return List.copyOf(allowedAndroidScopes); }
    public void setAllowedAndroidScopes(List<String> value) { allowedAndroidScopes = new ArrayList<>(value); }
    public List<String> getOnlineTestAndroidScopes() { return List.copyOf(onlineTestAndroidScopes); }
    public void setOnlineTestAndroidScopes(List<String> value) { onlineTestAndroidScopes = new ArrayList<>(value); }
    public boolean isAndroidScopeAllowed(String scope) {
        return allowedAndroidScopes.contains(scope) || onlineTestAndroidScopes.contains(scope);
    }

    private boolean h5TestEnabled;
    private List<String> allowedH5Scopes = new ArrayList<>();
    private Duration challengeTtl = Duration.ofMinutes(2);
    private Duration sessionTtl = Duration.ofMinutes(30);
    private Duration clockSkew = Duration.ofMinutes(5);
    private Duration nonceTtl = Duration.ofMinutes(5);

    public boolean isH5TestEnabled() {
        return h5TestEnabled;
    }

    public void setH5TestEnabled(boolean h5TestEnabled) {
        this.h5TestEnabled = h5TestEnabled;
    }

    public List<String> getAllowedH5Scopes() {
        return List.copyOf(allowedH5Scopes);
    }

    public void setAllowedH5Scopes(List<String> allowedH5Scopes) {
        this.allowedH5Scopes = new ArrayList<>(allowedH5Scopes);
    }

    public Duration getChallengeTtl() {
        return challengeTtl;
    }

    public void setChallengeTtl(Duration challengeTtl) {
        this.challengeTtl = challengeTtl;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public Duration getClockSkew() {
        return clockSkew;
    }

    public void setClockSkew(Duration clockSkew) {
        this.clockSkew = clockSkew;
    }

    public Duration getNonceTtl() {
        return nonceTtl;
    }

    public void setNonceTtl(Duration nonceTtl) {
        this.nonceTtl = nonceTtl;
    }
}
