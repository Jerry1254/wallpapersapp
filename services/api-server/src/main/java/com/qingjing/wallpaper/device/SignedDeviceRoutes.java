package com.qingjing.wallpaper.device;

/** Shared by raw-body capture and authentication: neither may omit a sensitive route. */
final class SignedDeviceRoutes {
    private SignedDeviceRoutes() {}
    static boolean requiresSignature(String method, String uri) {
        return (method.equals("PUT") && (uri.equals("/api/v1/device/encryption-key")
                || uri.equals("/api/v1/device/me/capabilities")))
                || (method.equals("POST") && (uri.equals("/api/v1/device/redemptions")
                || (uri.startsWith("/api/v1/device/wallpapers/") && (uri.endsWith("/download-tickets") || uri.endsWith("/preview-tickets")))));
    }
}
