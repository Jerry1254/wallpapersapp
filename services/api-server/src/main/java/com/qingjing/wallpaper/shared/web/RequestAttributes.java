package com.qingjing.wallpaper.shared.web;

public final class RequestAttributes {

    public static final String REQUEST_ID = RequestAttributes.class.getName() + ".requestId";
    public static final String ADMIN_PRINCIPAL = "com.qingjing.wallpaper.shared.web.RequestAttributes.adminPrincipal";
    public static final String DEVICE_PRINCIPAL = "com.qingjing.wallpaper.shared.web.RequestAttributes.devicePrincipal";
    public static final String DEVICE_DOWNLOAD_OUTCOME = RequestAttributes.class.getName() + ".deviceDownloadOutcome";
    public static final String SIGNED_BODY_BYTES = RequestAttributes.class.getName() + ".signedBodyBytes";
    public static final String AUDIT_CHANGE_SUMMARY = RequestAttributes.class.getName() + ".auditChangeSummary";

    private RequestAttributes() {
    }
}
