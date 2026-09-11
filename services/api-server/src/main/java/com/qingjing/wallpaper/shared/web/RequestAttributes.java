package com.qingjing.wallpaper.shared.web;

public final class RequestAttributes {

    public static final String REQUEST_ID = RequestAttributes.class.getName() + ".requestId";
    public static final String ADMIN_PRINCIPAL = RequestAttributes.class.getName() + ".adminPrincipal";
    public static final String AUDIT_CHANGE_SUMMARY = RequestAttributes.class.getName() + ".auditChangeSummary";

    private RequestAttributes() {
    }
}
