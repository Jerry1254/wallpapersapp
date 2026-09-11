package com.qingjing.wallpaper.redemption;

import com.qingjing.wallpaper.catalog.PublicCatalogDtos.PageMetadata;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminCodeBatchDetail;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminCodeBatchSummary;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.AdminRedemptionCode;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CodeBatchPage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CreateCodeBatchRequest;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.CreateCodeBatchResponse;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.DeliveryStatus;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.RedemptionCodePage;
import com.qingjing.wallpaper.redemption.AdminRedemptionDtos.RedemptionCodeStatus;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AdminCodeBatchService {

    private static final char[] CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final char[] BASE32 = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final Duration DELIVERY_TTL = Duration.ofMinutes(10);
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;
    private final RedisRateLimiter rateLimiter;

    public AdminCodeBatchService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            SecurityCrypto crypto,
            RedisRateLimiter rateLimiter) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.crypto = crypto;
        this.rateLimiter = rateLimiter;
    }

    @Transactional(readOnly = true)
    public CodeBatchPage list(int page, int pageSize, String query) {
        validatePage(page, pageSize);
        String normalized = normalizeQuery(query);
        String predicate = normalized == null ? "" : " WHERE b.batch_no LIKE ? OR b.name LIKE ?";
        List<Object> arguments = new ArrayList<>();
        if (normalized != null) {
            String like = "%" + escapeLike(normalized) + "%";
            arguments.add(like);
            arguments.add(like);
        }
        Long total = jdbc.queryForObject("SELECT COUNT(*) FROM code_batch b" + predicate, Long.class, arguments.toArray());
        arguments.add(pageSize);
        arguments.add((long) (page - 1) * pageSize);
        List<BatchRow> rows = jdbc.query(
                batchSelect() + predicate + " GROUP BY b.id ORDER BY b.created_at DESC, b.id DESC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> batchRow(resultSet),
                arguments.toArray());
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new CodeBatchPage(
                rows.stream().map(this::summary).toList(),
                new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public AdminCodeBatchDetail get(long batchId) {
        return detail(requireBatch(batchId));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CreatedBatch create(long adminId, String idempotencyKey, CreateCodeBatchRequest request) {
        requireUuid(idempotencyKey);
        String name = request.name().strip();
        if (name.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The batch name must not be blank");
        }
        rateLimiter.require("admin-code-batch", Long.toString(adminId), 5, Duration.ofMinutes(1));
        String batchNo = deterministicBatchNo(adminId, idempotencyKey);
        List<BatchRow> existing = batchByNo(batchNo);
        if (!existing.isEmpty()) {
            return existingBatch(existing.get(0), request, name);
        }

        KeyHolder batchKey = new GeneratedKeyHolder();
        try {
            jdbc.update(connection -> {
                PreparedStatement statement = connection.prepareStatement(
                        """
                        INSERT INTO code_batch
                            (batch_no, name, generated_count, quota_per_code_snapshot, created_by_admin_id)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                        Statement.RETURN_GENERATED_KEYS);
                statement.setString(1, batchNo);
                statement.setString(2, name);
                statement.setInt(3, request.generatedCount());
                statement.setInt(4, request.quotaPerCode());
                statement.setLong(5, adminId);
                return statement;
            }, batchKey);
        } catch (DataIntegrityViolationException exception) {
            List<BatchRow> raced = batchByNo(batchNo);
            if (raced.isEmpty()) {
                throw exception;
            }
            return existingBatch(raced.get(0), request, name);
        }
        Number key = batchKey.getKey();
        if (key == null) {
            throw new IllegalStateException("Code batch insert returned no identifier");
        }
        long batchId = key.longValue();
        List<GeneratedCode> codes = generateCodes(request.generatedCount());
        try {
            jdbc.batchUpdate(
                    """
                    INSERT INTO redemption_code
                        (batch_id, code_hash, code_key_version, code_suffix, total_quota)
                    VALUES (?, ?, 1, ?, ?)
                    """,
                    codes,
                    500,
                    (statement, code) -> {
                        statement.setLong(1, batchId);
                        statement.setString(2, crypto.hmacHex("redemption-code-v1", code.value()));
                        statement.setString(3, code.value().substring(15));
                        statement.setInt(4, request.quotaPerCode());
                    });
        } catch (DataIntegrityViolationException exception) {
            throw new ApiException(HttpStatus.CONFLICT, "CODE_GENERATION_CONFLICT", "The code batch could not be generated safely");
        }
        String csv = csv(batchNo, request.quotaPerCode(), codes);
        redis.opsForValue().set(
                materialKey(batchId),
                crypto.encrypt("code-delivery-v1", csv.getBytes(StandardCharsets.UTF_8)),
                DELIVERY_TTL);
        BatchRow row = requireBatch(batchId);
        return new CreatedBatch(issueDelivery(row, DELIVERY_TTL), true);
    }

    @Transactional(readOnly = true)
    public RedemptionCodePage listCodes(
            long batchId,
            int page,
            int pageSize,
            RedemptionCodeStatus status,
            String suffix) {
        validatePage(page, pageSize);
        requireBatch(batchId);
        String normalizedSuffix = suffix == null ? null : suffix.strip().toUpperCase(Locale.ROOT);
        if (normalizedSuffix != null && !normalizedSuffix.matches("^[A-Z0-9]{4,8}$")) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "The code suffix is invalid");
        }
        StringBuilder predicate = new StringBuilder(" WHERE batch_id = ?");
        List<Object> arguments = new ArrayList<>();
        arguments.add(batchId);
        if (status != null) {
            predicate.append(status == RedemptionCodeStatus.AVAILABLE
                    ? " AND used_quota < total_quota"
                    : " AND used_quota = total_quota");
        }
        if (normalizedSuffix != null) {
            predicate.append(" AND code_suffix LIKE ?");
            arguments.add("%" + normalizedSuffix);
        }
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM redemption_code" + predicate,
                Long.class,
                arguments.toArray());
        arguments.add(pageSize);
        arguments.add((long) (page - 1) * pageSize);
        List<AdminRedemptionCode> items = jdbc.query(
                """
                SELECT id, code_suffix, total_quota, used_quota
                FROM redemption_code
                """ + predicate + " ORDER BY id ASC LIMIT ? OFFSET ?",
                (resultSet, rowNumber) -> {
                    int totalQuota = resultSet.getInt("total_quota");
                    int usedQuota = resultSet.getInt("used_quota");
                    String codeSuffix = resultSet.getString("code_suffix");
                    return new AdminRedemptionCode(
                            Long.toString(resultSet.getLong("id")),
                            "*****-*****-*****-" + codeSuffix,
                            codeSuffix,
                            totalQuota,
                            usedQuota,
                            totalQuota - usedQuota,
                            usedQuota < totalQuota ? RedemptionCodeStatus.AVAILABLE : RedemptionCodeStatus.EXHAUSTED);
                },
                arguments.toArray());
        long totalItems = total == null ? 0 : total;
        int totalPages = totalItems == 0 ? 0 : (int) ((totalItems + pageSize - 1) / pageSize);
        return new RedemptionCodePage(items, new PageMetadata(page, pageSize, totalItems, totalPages));
    }

    @Transactional(readOnly = true)
    public DeliveryFile delivery(long batchId, String ticket) {
        if (ticket == null || ticket.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TICKET_INVALID", "A delivery ticket is required");
        }
        BatchRow batch = requireBatch(batchId);
        if (batch.deliveryConfirmedAt() != null) {
            throw gone();
        }
        String ticketBatch = redis.opsForValue().get(ticketKey(ticket));
        if (!Long.toString(batchId).equals(ticketBatch)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "DELIVERY_TICKET_INVALID", "The delivery ticket is invalid");
        }
        String encrypted = redis.opsForValue().get(materialKey(batchId));
        if (encrypted == null) {
            throw gone();
        }
        byte[] content = crypto.decrypt("code-delivery-v1", encrypted);
        return new DeliveryFile(batch.batchNo() + ".csv", content);
    }

    @Transactional
    public void confirmDelivery(long batchId) {
        BatchRow batch = requireBatch(batchId);
        if (batch.deliveryConfirmedAt() == null) {
            jdbc.update(
                    "UPDATE code_batch SET delivery_confirmed_at = UTC_TIMESTAMP(6) WHERE id = ? AND delivery_confirmed_at IS NULL",
                    batchId);
        }
        redis.delete(materialKey(batchId));
    }

    private CreateCodeBatchResponse responseForExisting(BatchRow row) {
        Long remainingSeconds = redis.getExpire(materialKey(row.id()), TimeUnit.SECONDS);
        if (row.deliveryConfirmedAt() != null || remainingSeconds == null || remainingSeconds <= 0) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "DELIVERY_EXPIRED",
                    "The original code delivery material is no longer available");
        }
        return issueDelivery(row, Duration.ofSeconds(remainingSeconds));
    }

    private CreatedBatch existingBatch(BatchRow row, CreateCodeBatchRequest request, String name) {
        if (!row.name().equals(name)
                || row.generatedCount() != request.generatedCount()
                || row.quotaPerCode() != request.quotaPerCode()) {
            throw new ApiException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "The idempotency key was reused with another request");
        }
        return new CreatedBatch(responseForExisting(row), false);
    }

    private CreateCodeBatchResponse issueDelivery(BatchRow row, Duration ttl) {
        String ticket = crypto.randomToken(32);
        redis.opsForValue().set(ticketKey(ticket), Long.toString(row.id()), ttl);
        return new CreateCodeBatchResponse(
                detail(row),
                ticket,
                "/api/v1/admin/code-batches/" + row.id() + "/delivery",
                Instant.now().plus(ttl));
    }

    private BatchRow requireBatch(long batchId) {
        List<BatchRow> rows = jdbc.query(
                batchSelect() + " WHERE b.id = ? GROUP BY b.id",
                (resultSet, rowNumber) -> batchRow(resultSet),
                batchId);
        if (rows.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "CODE_BATCH_NOT_FOUND", "The code batch was not found");
        }
        return rows.get(0);
    }

    private List<BatchRow> batchByNo(String batchNo) {
        return jdbc.query(
                batchSelect() + " WHERE b.batch_no = ? GROUP BY b.id",
                (resultSet, rowNumber) -> batchRow(resultSet),
                batchNo);
    }

    private String batchSelect() {
        return """
                SELECT b.id, b.batch_no, b.name, b.generated_count, b.quota_per_code_snapshot,
                       b.delivery_confirmed_at, b.created_at,
                       COALESCE(SUM(c.total_quota), 0) AS total_quota,
                       COALESCE(SUM(c.used_quota), 0) AS used_quota,
                       COALESCE(SUM(c.used_quota < c.total_quota), 0) AS available_count,
                       COALESCE(SUM(c.used_quota = c.total_quota), 0) AS exhausted_count
                FROM code_batch b
                LEFT JOIN redemption_code c ON c.batch_id = b.id
                """;
    }

    private BatchRow batchRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        return new BatchRow(
                resultSet.getLong("id"),
                resultSet.getString("batch_no"),
                resultSet.getString("name"),
                resultSet.getInt("generated_count"),
                resultSet.getInt("quota_per_code_snapshot"),
                resultSet.getLong("total_quota"),
                resultSet.getLong("used_quota"),
                resultSet.getInt("available_count"),
                resultSet.getInt("exhausted_count"),
                resultSet.getTimestamp("delivery_confirmed_at") == null
                        ? null : resultSet.getTimestamp("delivery_confirmed_at").toInstant(),
                resultSet.getTimestamp("created_at").toInstant());
    }

    private AdminCodeBatchSummary summary(BatchRow row) {
        return new AdminCodeBatchSummary(
                Long.toString(row.id()), row.batchNo(), row.name(), row.generatedCount(), row.quotaPerCode(),
                row.totalQuota(), row.usedQuota(), usage(row), deliveryStatus(row), row.deliveryConfirmedAt(), row.createdAt());
    }

    private AdminCodeBatchDetail detail(BatchRow row) {
        return new AdminCodeBatchDetail(
                Long.toString(row.id()), row.batchNo(), row.name(), row.generatedCount(), row.quotaPerCode(),
                row.totalQuota(), row.usedQuota(), usage(row), deliveryStatus(row), row.deliveryConfirmedAt(), row.createdAt(),
                row.availableCount(), row.exhaustedCount());
    }

    private double usage(BatchRow row) {
        return row.totalQuota() == 0 ? 0 : Math.round(row.usedQuota() * 10000.0 / row.totalQuota()) / 100.0;
    }

    private DeliveryStatus deliveryStatus(BatchRow row) {
        if (row.deliveryConfirmedAt() != null) {
            return DeliveryStatus.CONFIRMED;
        }
        return Boolean.TRUE.equals(redis.hasKey(materialKey(row.id())))
                ? DeliveryStatus.AVAILABLE
                : DeliveryStatus.EXPIRED;
    }

    private List<GeneratedCode> generateCodes(int count) {
        Set<String> unique = new HashSet<>(count * 2);
        while (unique.size() < count) {
            char[] value = new char[20];
            for (int index = 0; index < value.length; index++) {
                value[index] = CODE_ALPHABET[RANDOM.nextInt(CODE_ALPHABET.length)];
            }
            unique.add(new String(value));
        }
        return unique.stream().sorted().map(GeneratedCode::new).toList();
    }

    private String csv(String batchNo, int quota, List<GeneratedCode> codes) {
        StringBuilder result = new StringBuilder("batchNo,code,totalQuota\r\n");
        for (GeneratedCode code : codes) {
            result.append(batchNo).append(',').append(group(code.value())).append(',').append(quota).append("\r\n");
        }
        return result.toString();
    }

    private String group(String code) {
        return code.substring(0, 5) + "-" + code.substring(5, 10) + "-"
                + code.substring(10, 15) + "-" + code.substring(15, 20);
    }

    private String deterministicBatchNo(long adminId, String idempotencyKey) {
        byte[] input = crypto.hmac("code-batch-idempotency-v1", adminId + "\n" + idempotencyKey);
        StringBuilder result = new StringBuilder(26);
        int buffer = 0;
        int bits = 0;
        for (byte inputByte : input) {
            buffer = (buffer << 8) | (inputByte & 0xff);
            bits += 8;
            while (bits >= 5 && result.length() < 26) {
                result.append(BASE32[(buffer >> (bits - 5)) & 31]);
                bits -= 5;
            }
            if (result.length() == 26) {
                break;
            }
        }
        return result.toString();
    }

    private String ticketKey(String ticket) {
        return "admin:code-delivery-ticket:" + crypto.sha256Hex(ticket);
    }

    private String materialKey(long batchId) {
        return "admin:code-delivery-material:" + batchId;
    }

    private ApiException gone() {
        return new ApiException(HttpStatus.GONE, "DELIVERY_EXPIRED", "The code delivery material has expired or was confirmed");
    }

    private void requireUuid(String value) {
        try {
            UUID.fromString(value);
        } catch (RuntimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Idempotency-Key must be a UUID");
        }
    }

    private void validatePage(int page, int pageSize) {
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "page and pageSize are outside the accepted range");
        }
    }

    private String normalizeQuery(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        String normalized = query.strip();
        if (normalized.length() > 50) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "q must contain no more than 50 characters");
        }
        return normalized;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private record GeneratedCode(String value) {
    }

    private record BatchRow(
            long id,
            String batchNo,
            String name,
            int generatedCount,
            int quotaPerCode,
            long totalQuota,
            long usedQuota,
            int availableCount,
            int exhaustedCount,
            Instant deliveryConfirmedAt,
            Instant createdAt) {
    }

    public record CreatedBatch(CreateCodeBatchResponse response, boolean created) {
    }

    public record DeliveryFile(String filename, byte[] content) {
    }
}
