package com.qingjing.wallpaper.device;

import com.qingjing.wallpaper.device.DeviceIdentityService.SessionData;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
public class DeviceAuthInterceptor implements HandlerInterceptor {

    private final DeviceIdentityService identity;
    private final RedisRateLimiter rateLimiter;
    private final com.qingjing.wallpaper.risk.RiskService risk;
    private final com.qingjing.wallpaper.risk.ClientAddress address;

    public DeviceAuthInterceptor(DeviceIdentityService identity, RedisRateLimiter rateLimiter,
            com.qingjing.wallpaper.risk.RiskService risk,com.qingjing.wallpaper.risk.ClientAddress address) {
        this.identity = identity;
        this.rateLimiter = rateLimiter;
        this.risk=risk; this.address=address;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (request.getMethod().equals("OPTIONS")) {
            return true;
        }
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "A device session is required");
        }
        SessionData session = identity.requireSession(authorization.substring(7));
        String uri = request.getRequestURI();
        request.setAttribute(RequestAttributes.DEVICE_PRINCIPAL, identity.principal(session));
        String ip=address.of(request);
        if(!uri.startsWith("/api/v1/device/security/")) {
            risk.requireAllowed(session.deviceId(),ip);
            risk.count("REQUEST_FLOOD",session.deviceId(),ip,null);
        }
        boolean sensitiveWrite = SignedDeviceRoutes.requiresSignature(request.getMethod(), uri);
        boolean supportUpload = request.getMethod().equals("POST") && uri.equals("/api/v1/device/support/attachments");
        boolean supportControl = request.getMethod().equals("POST") && (uri.equals("/api/v1/device/support/read") || uri.equals("/api/v1/device/support/presence"));
        rateLimiter.require(
                supportUpload ? "support-image-upload" : supportControl ? "support-control" : sensitiveWrite ? "device-sensitive-write" : "device-read",
                Long.toString(session.deviceId()),
                supportUpload ? 5 : supportControl ? 90 : sensitiveWrite ? 30 : 120,
                Duration.ofMinutes(1));
        if (sensitiveWrite) {
            Object value = request.getAttribute(RequestAttributes.SIGNED_BODY_BYTES);
            byte[] body = value instanceof byte[] bytes ? bytes : new byte[0];
            try { identity.verifySignedRequest(
                    session,
                    request.getMethod(),
                    canonicalPath(request),
                    requiredHeader(request, "X-Request-Timestamp"),
                    requiredHeader(request, "X-Request-Nonce"),
                    body,
                    requiredHeader(request, "X-Request-Signature"));
            } catch(ApiException error) {
                if(error.code().equals("REQUEST_SIGNATURE_INVALID") || error.code().equals("REQUEST_NONCE_REUSED"))
                    risk.count("INVALID_SIGNATURE",session.deviceId(),ip,null);
                throw error;
            }
        }
        request.setAttribute(RequestAttributes.DEVICE_PRINCIPAL, identity.principal(session));
        return true;
    }

    private String requiredHeader(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "SIGNED_REQUEST_INVALID", "A signed request header is missing");
        }
        return value;
    }

    private String canonicalPath(HttpServletRequest request) {
        Map<String, String[]> parameters = request.getParameterMap();
        if (parameters.isEmpty()) {
            return request.getRequestURI();
        }
        List<QueryPart> parts = new ArrayList<>();
        parameters.forEach((name, values) -> {
            if (values.length == 0) {
                parts.add(new QueryPart(name, ""));
            } else {
                for (String value : values) {
                    parts.add(new QueryPart(name, value));
                }
            }
        });
        parts.sort(Comparator.comparing(QueryPart::name).thenComparing(QueryPart::value));
        return request.getRequestURI() + "?" + parts.stream()
                .map(part -> rfc3986(part.name()) + "=" + rfc3986(part.value()))
                .collect(java.util.stream.Collectors.joining("&"));
    }

    private String rfc3986(String value) {
        StringBuilder result = new StringBuilder();
        for (byte valueByte : value.getBytes(StandardCharsets.UTF_8)) {
            int current = valueByte & 0xff;
            if ((current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~') {
                result.append((char) current);
            } else {
                result.append('%').append(String.format("%02X", current));
            }
        }
        return result.toString();
    }

    private record QueryPart(String name, String value) {
    }
}
