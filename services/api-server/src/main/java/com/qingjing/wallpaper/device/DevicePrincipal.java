package com.qingjing.wallpaper.device;

public record DevicePrincipal(
        long deviceId,
        String credentialKeyId,
        DeviceDtos.DevicePlatform platform,
        DeviceDtos.CredentialType credentialType) {
}
