package com.qingjing.wallpaper.audit;

import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import com.qingjing.wallpaper.shared.web.RequestAttributes;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class AdminAuditFilter extends OncePerRequestFilter {

    private static final List<String> SAFE_METHODS = List.of("GET", "HEAD", "OPTIONS");

    private final AdminAuditService auditService;

    public AdminAuditFilter(AdminAuditService auditService) {
        this.auditService = auditService;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/admin/")
                || SAFE_METHODS.contains(request.getMethod());
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        try {
            filterChain.doFilter(request, response);
        } finally {
            String requestId = String.valueOf(request.getAttribute(RequestAttributes.REQUEST_ID));
            AdminPrincipal principal = (AdminPrincipal) request.getAttribute(RequestAttributes.ADMIN_PRINCIPAL);
            AuditTarget target = target(request);
            @SuppressWarnings("unchecked")
            Map<String, Object> summary = request.getAttribute(RequestAttributes.AUDIT_CHANGE_SUMMARY) instanceof Map<?, ?> value
                    ? (Map<String, Object>) value
                    : Map.of();
            auditService.record(
                    principal,
                    requestId,
                    target.action(),
                    target.aggregateType(),
                    target.aggregateId(),
                    response.getStatus(),
                    summary);
        }
    }

    private AuditTarget target(HttpServletRequest request) {
        String relative = request.getRequestURI().substring("/api/v1/admin/".length());
        String[] segments = relative.split("/");
        String root = segments.length == 0 ? "system" : segments[0];
        String id = segments.length > 1
                && (segments[1].matches("[0-9]+") || root.equals("wallpaper-tutorials"))
                        ? segments[1]
                        : "new";
        String suffix = segments.length > 2 ? "_" + segments[2].replace('-', '_') : "";
        return new AuditTarget(
                (request.getMethod() + "_" + root + suffix).toUpperCase(),
                aggregateType(root),
                id);
    }

    private String aggregateType(String root) {
        return switch (root) {
            case "sessions" -> "ADMIN_ACCOUNT";
            case "assets" -> "ASSET";
            case "categories" -> "CATEGORY";
            case "wallpapers" -> "WALLPAPER";
            case "variants" -> "WALLPAPER_VARIANT";
            case "resource-versions" -> "RESOURCE_VERSION";
            case "wallpaper-tutorials" -> "WALLPAPER_TUTORIAL";
            case "code-batches" -> "CODE_BATCH";
            case "devices" -> "ANONYMOUS_DEVICE";
            case "redemptions" -> "REDEMPTION_CODE";
            default -> "SYSTEM";
        };
    }

    private record AuditTarget(String action, String aggregateType, String aggregateId) {
    }
}
