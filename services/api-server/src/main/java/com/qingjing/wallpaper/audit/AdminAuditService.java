package com.qingjing.wallpaper.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.adminidentity.AdminPrincipal;
import java.util.Map;
import java.util.LinkedHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AdminAuditService {

    private static final Logger LOGGER = LoggerFactory.getLogger(AdminAuditService.class);

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public AdminAuditService(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    public void record(
            AdminPrincipal principal,
            String requestId,
            String action,
            String aggregateType,
            String aggregateId,
            int statusCode,
            Map<String, Object> safeChangeSummary) {
        try {
            Map<String, Object> summaryValues = new LinkedHashMap<>();
            summaryValues.put("httpStatus", statusCode);
            summaryValues.putAll(safeChangeSummary);
            String summary = objectMapper.writeValueAsString(summaryValues);
            jdbc.update(
                    """
                    INSERT INTO audit_event
                        (actor_admin_id, request_id, action, aggregate_type, aggregate_id, result, change_summary)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS JSON))
                    """,
                    principal == null ? null : principal.id(),
                    requestId,
                    action,
                    aggregateType,
                    aggregateId,
                    statusCode >= 200 && statusCode < 400 ? "SUCCEEDED" : "FAILED",
                    summary);
        } catch (RuntimeException | JsonProcessingException exception) {
            LOGGER.error("Audit event could not be persisted requestId={} action={}", requestId, action, exception);
        }
    }
}
