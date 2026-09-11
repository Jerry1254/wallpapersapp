package com.qingjing.wallpaper.shared.web;

import java.util.List;
import org.springframework.http.HttpStatus;

public final class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<ErrorDetail> details;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    public ApiException(HttpStatus status, String code, String message, List<ErrorDetail> details) {
        this(status, code, message, details, null);
    }

    public ApiException(
            HttpStatus status,
            String code,
            String message,
            List<ErrorDetail> details,
            Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = List.copyOf(details);
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public List<ErrorDetail> details() {
        return details;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public record ErrorDetail(String field, String reason) {
    }
}
