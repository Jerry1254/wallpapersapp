package com.qingjing.wallpaper.support;

import static com.qingjing.wallpaper.support.SupportDtos.*;

import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SupportService {
    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final AssetUploadService uploads;
    private final FileStorage storage;
    private final TransactionTemplate transaction;

    public SupportService(JdbcTemplate jdbc, StringRedisTemplate redis, AssetUploadService uploads,
                          FileStorage storage, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.uploads = uploads;
        this.storage = storage;
        this.transaction = new TransactionTemplate(transactions);
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    // Opening a page establishes identity only; it cannot restore a hidden conversation.
    public long forDevice(DevicePrincipal device) {
        jdbc.update("INSERT INTO support_conversation(device_id,created_at,updated_at) VALUES(?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)) ON DUPLICATE KEY UPDATE id=id", device.deviceId());
        return jdbc.queryForObject("SELECT id FROM support_conversation WHERE device_id=?", Long.class, device.deviceId());
    }

    public Conversation conversation(long id, boolean customer) {
        var rows = jdbc.query(conversationSelect(customer) + " WHERE c.id=?", (rs, n) -> conversationRow(rs), id);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    public ConversationPage conversations(String search, boolean unread, int page, int pageSize) {
        requirePage(page, pageSize);
        String term = clean(search, 100, false);
        String where = " WHERE c.hidden=FALSE AND c.last_message_id IS NOT NULL";
        var arguments = new ArrayList<Object>();
        if (!term.isEmpty()) {
            where += " AND (LOCATE(?,CONCAT('用户 ',IF(c.device_id<10000,LPAD(c.device_id,4,'0'),CAST(c.device_id AS CHAR))))>0 OR LOCATE(?,CAST(c.id AS CHAR))>0)";
            arguments.add(term);
            arguments.add(term);
        }
        if (unread) where += " AND " + unreadSql(false) + ">0";
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_conversation c" + where, Long.class, arguments.toArray());
        arguments.add(pageSize);
        arguments.add((page - 1L) * pageSize);
        var items = jdbc.query(conversationSelect(false) + where + " ORDER BY c.last_message_id DESC,c.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> conversationRow(rs), arguments.toArray());
        return new ConversationPage(items, page, pageSize, total);
    }

    public MessagePage messages(long conversationId, Long after, Long before, int limit) {
        requireConversation(conversationId, false);
        if (limit < 1 || limit > 100 || (after != null && after < 0) || (before != null && before < 1)
                || (after != null && before != null)) throw invalid("聊天分页参数无效");
        boolean forward = after != null;
        String clause = forward ? " AND m.id>?" : before != null ? " AND m.id<?" : "";
        var args = new ArrayList<Object>();
        args.add(conversationId);
        if (forward) args.add(after); else if (before != null) args.add(before);
        args.add(limit + 1);
        var records = jdbc.query(MESSAGE_SELECT + " WHERE m.conversation_id=?" + clause + " ORDER BY m.id "
                + (forward ? "ASC" : "DESC") + " LIMIT ?", (rs, n) -> messageRow(rs), args.toArray());
        boolean hasMore = records.size() > limit;
        var items = new ArrayList<>(records.subList(0, Math.min(records.size(), limit)));
        if (!forward) Collections.reverse(items);
        String cursor = items.isEmpty() ? (forward ? Long.toString(after) : before == null ? "0" : Long.toString(before))
                : items.get(forward ? items.size() - 1 : 0).id();
        return new MessagePage(items, cursor, hasMore);
    }

    public Message byClient(long conversationId, String sender, String clientId) {
        var rows = jdbc.query(MESSAGE_SELECT + " WHERE m.conversation_id=? AND m.sender=? AND m.client_id=?",
                (rs, n) -> messageRow(rs), conversationId, sender, uuid(clientId));
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_MESSAGE_NOT_FOUND", "尚未确认此消息");
        return rows.get(0);
    }

    public Message send(long conversationId, boolean customer, SendRequest request) {
        if (request.kind() == null || (customer && request.kind() == MessageKind.VIDEO)) throw invalid("消息类型不支持");
        String clientId = uuid(request.clientId());
        String text = request.kind() == MessageKind.TEXT ? clean(request.text(), 2000, true) : null;
        if (request.kind() == MessageKind.TEXT && (request.attachmentId() != null || request.libraryItemId() != null)
                || request.kind() != MessageKind.TEXT && request.text() != null && !request.text().isBlank()) throw invalid("消息内容与类型不匹配");
        Long attachmentId = request.attachmentId() == null ? null : id(request.attachmentId());
        if (request.kind() != MessageKind.TEXT && attachmentId == null) throw invalid("请选择图片或视频");
        String sender = customer ? "CUSTOMER" : "ADMIN";
        String hash = hash(request.kind() + "\n" + Objects.toString(text, "") + "\n" + Objects.toString(attachmentId, "")
                + "\n" + Objects.toString(request.libraryItemId(), ""));
        return transaction.execute(status -> {
            // Serialize writes per conversation: a cursor can never skip an earlier uncommitted message.
            requireConversation(conversationId, true);
            var prior = jdbc.queryForList("SELECT id,payload_hash FROM support_message WHERE conversation_id=? AND sender=? AND client_id=?",
                    conversationId, sender, clientId);
            if (!prior.isEmpty()) {
                if (!hash.equals(prior.get(0).get("payload_hash"))) throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT", "此消息标识已用于不同内容");
                return byClient(conversationId, sender, clientId);
            }
            if (attachmentId != null) {
                var attachment = attachmentRecord(attachmentId);
                if (attachment.kind() != request.kind() || (customer
                        ? !Objects.equals(attachment.conversationId(), conversationId)
                        : attachment.conversationId() != null && !Objects.equals(attachment.conversationId(), conversationId))) throw inaccessible();
                if (request.libraryItemId() != null) {
                    if (customer) throw inaccessible();
                    var library = libraryItem(id(request.libraryItemId()), true);
                    if (library.attachment() == null || !library.attachment().id().equals(Long.toString(attachmentId)))
                        throw new ApiException(HttpStatus.CONFLICT, "SUPPORT_LIBRARY_CHANGED", "素材已变化，请重新选择");
                }
            }
            long messageId = insert("INSERT INTO support_message(conversation_id,sender,client_id,kind,text,attachment_id,payload_hash,created_at) VALUES(?,?,?,?,?,?,?,UTC_TIMESTAMP(6))",
                    conversationId, sender, clientId, request.kind().name(), text, attachmentId, hash);
            jdbc.update("UPDATE support_conversation SET last_message_id=?,updated_at=UTC_TIMESTAMP(6)"
                    + (customer ? ",hidden=FALSE" : "") + " WHERE id=?", messageId, conversationId);
            return byClient(conversationId, sender, clientId);
        });
    }

    public void markRead(long conversationId, boolean customer, long messageId) {
        transaction.executeWithoutResult(status -> {
            requireConversation(conversationId, true);
            if (jdbc.queryForObject("SELECT COUNT(*) FROM support_message WHERE conversation_id=? AND id=?", Long.class,
                    conversationId, messageId) != 1) throw invalid("读取位置不属于此会话");
            String column = customer ? "customer_read_id" : "admin_read_id";
            jdbc.update("UPDATE support_conversation SET " + column + "=GREATEST(" + column + ",?) WHERE id=?", messageId, conversationId);
        });
    }

    public void hide(long conversationId) {
        transaction.executeWithoutResult(status -> {
            requireConversation(conversationId, true);
            jdbc.update("UPDATE support_conversation SET hidden=TRUE WHERE id=?", conversationId);
        });
    }

    public void presence(DevicePrincipal device, boolean active) {
        String key = presenceKey(device.deviceId());
        if (active) redis.opsForValue().set(key, "1", Duration.ofSeconds(45));
        else redis.delete(key);
    }

    public Attachment upload(Long conversationId, Long adminId, MessageKind kind, InputStream input, String name, String mime) {
        if (kind == null || kind == MessageKind.TEXT || (conversationId != null && kind != MessageKind.IMAGE)) throw invalid("文件类型不支持");
        if (conversationId != null) requireConversation(conversationId, false);
        var validated = uploads.upload(input, name, mime, kind == MessageKind.IMAGE ? AssetPurpose.SUPPORT_IMAGE : AssetPurpose.SUPPORT_VIDEO);
        try {
            long attachmentId = insert("""
                INSERT INTO support_attachment(conversation_id,created_by_admin_id,kind,storage_key,filename,mime_type,
                size_bytes,sha256,width_px,height_px,duration_ms,created_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,UTC_TIMESTAMP(6))
                """, conversationId, adminId, kind.name(), validated.storageKey().value(), validated.originalFilename(),
                    validated.mimeType(), validated.sizeBytes(), validated.sha256(), validated.widthPixels(), validated.heightPixels(), validated.durationMs());
            return attachmentRecord(attachmentId).view();
        } catch (RuntimeException failure) {
            try { storage.delete(validated.storageKey()); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    public LibraryPage library(LibraryKind kind, String search, int page, int pageSize) {
        requirePage(page, pageSize);
        String term = clean(search, 100, false);
        String where = " WHERE l.deleted_at IS NULL";
        var args = new ArrayList<Object>();
        if (kind != null) { where += " AND l.kind=?"; args.add(kind.name()); }
        if (!term.isEmpty()) {
            where += " AND (LOCATE(?,l.title)>0 OR LOCATE(?,l.note)>0 OR LOCATE(?,COALESCE(l.text,''))>0)";
            args.add(term); args.add(term); args.add(term);
        }
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM support_library_item l" + where, Long.class, args.toArray());
        args.add(pageSize); args.add((page - 1L) * pageSize);
        var rows = jdbc.query(LIBRARY_SELECT + where + " ORDER BY l.updated_at DESC,l.id DESC LIMIT ? OFFSET ?",
                (rs, n) -> libraryRow(rs), args.toArray());
        return new LibraryPage(rows, page, pageSize, total);
    }

    public LibraryItem saveLibrary(Long itemId, LibraryRequest request) {
        String title = clean(request.title(), 60, true), note = clean(request.note(), 200, false);
        if (request.kind() == null) throw invalid("请选择素材分类");
        String text = request.kind() == LibraryKind.PHRASE ? clean(request.text(), 2000, true) : null;
        Long attachmentId = request.attachmentId() == null ? null : id(request.attachmentId());
        if (request.kind() == LibraryKind.PHRASE && attachmentId != null || request.kind() != LibraryKind.PHRASE && attachmentId == null
                || request.kind() != LibraryKind.PHRASE && request.text() != null && !request.text().isBlank()) throw invalid("素材内容与分类不匹配");
        return transaction.execute(status -> {
            if (attachmentId != null) {
                var attachment = attachmentRecord(attachmentId);
                if (attachment.conversationId() != null || !attachment.kind().name().equals(request.kind().name())) throw inaccessible();
            }
            long savedId;
            if (itemId == null) {
                savedId = insert("INSERT INTO support_library_item(kind,title,note,text,attachment_id,created_at,updated_at) VALUES(?,?,?,?,?,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6))",
                        request.kind().name(), title, note, text, attachmentId);
            } else {
                var current = libraryItem(itemId, true);
                if (current.kind() != request.kind()) throw invalid("编辑不能改变素材分类");
                if (request.version() == null || current.version() != request.version()) throw conflict();
                jdbc.update("UPDATE support_library_item SET title=?,note=?,text=?,attachment_id=?,lock_version=lock_version+1,updated_at=UTC_TIMESTAMP(6) WHERE id=?",
                        title, note, text, attachmentId, itemId);
                savedId = itemId;
            }
            return libraryItem(savedId, false);
        });
    }

    public void deleteLibrary(long itemId, long version) {
        transaction.executeWithoutResult(status -> {
            var current = libraryItem(itemId, true);
            if (version < 0 || current.version() != version) throw conflict();
            jdbc.update("UPDATE support_library_item SET deleted_at=UTC_TIMESTAMP(6),lock_version=lock_version+1 WHERE id=?", itemId);
        });
    }

    public AttachmentRecord authorizedAttachment(long attachmentId, Long conversationId) {
        var attachment = attachmentRecord(attachmentId);
        if (conversationId != null && !Objects.equals(attachment.conversationId(), conversationId)
                && jdbc.queryForObject("SELECT COUNT(*) FROM support_message WHERE attachment_id=? AND conversation_id=?",
                Long.class, attachmentId, conversationId) == 0) throw inaccessible();
        return attachment;
    }

    AttachmentRecord attachmentRecord(long attachmentId) {
        var rows = jdbc.query("SELECT a.*,a.id AS file_id,a.kind AS file_kind FROM support_attachment a WHERE a.id=?", (rs, n) -> new AttachmentRecord(
                attachmentRow(rs), rs.getString("storage_key"), nullableLong(rs, "conversation_id")), attachmentId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_ATTACHMENT_NOT_FOUND", "图片或视频不存在");
        return rows.get(0);
    }

    private LibraryItem libraryItem(long itemId, boolean lock) {
        if (lock) {
            if (jdbc.queryForList("SELECT id FROM support_library_item WHERE id=? AND deleted_at IS NULL FOR UPDATE", itemId).isEmpty())
                throw new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_LIBRARY_NOT_FOUND", "素材已不可用，请重新选择");
        }
        var rows = jdbc.query(LIBRARY_SELECT + " WHERE l.id=? AND l.deleted_at IS NULL", (rs, n) -> libraryRow(rs), itemId);
        if (rows.isEmpty()) throw new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_LIBRARY_NOT_FOUND", "素材已不可用，请重新选择");
        return rows.get(0);
    }

    private void requireConversation(long conversationId, boolean lock) {
        if (conversationId < 1 || jdbc.queryForList("SELECT id FROM support_conversation WHERE id=?" + (lock ? " FOR UPDATE" : ""), conversationId).isEmpty()) throw missing();
    }
    private long insert(String sql, Object... args) {
        var holder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < args.length; index++) statement.setObject(index + 1, args[index]);
            return statement;
        }, holder);
        if (holder.getKey() == null) throw new IllegalStateException("Support insert returned no identifier");
        return holder.getKey().longValue();
    }
    public static long id(String value) {
        try { long parsed = Long.parseLong(value); if (parsed < 1) throw new NumberFormatException(); return parsed; }
        catch (NumberFormatException error) { throw invalid("编号无效"); }
    }
    private static String uuid(String value) {
        try { var normalized = UUID.fromString(value).toString(); if (!normalized.equalsIgnoreCase(value)) throw new IllegalArgumentException(); return normalized; }
        catch (IllegalArgumentException | NullPointerException error) { throw invalid("消息标识无效"); }
    }
    private static String clean(String value, int max, boolean required) {
        String result = value == null ? "" : value.strip();
        if (result.length() > max || required && result.isEmpty()) throw invalid("填写内容为空或超过长度限制");
        return result;
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    private static void requirePage(int page, int size) { if (page < 1 || page > 100000 || size < 1 || size > 100) throw invalid("分页参数无效"); }
    private static String presenceKey(long deviceId) { return "support:presence:" + deviceId; }
    private static Long nullableLong(ResultSet row, String field) throws SQLException { return row.getObject(field) == null ? null : row.getLong(field); }
    private static String unreadSql(boolean customer) {
        return "(SELECT COUNT(*) FROM support_message u WHERE u.conversation_id=c.id AND u.sender='" + (customer ? "ADMIN" : "CUSTOMER")
                + "' AND u.id>c." + (customer ? "customer_read_id" : "admin_read_id") + ")";
    }
    private String conversationSelect(boolean customer) {
        return "SELECT c.id AS conversation_ref,c.device_id,c.hidden," + unreadSql(customer) + " AS unread_count," + MESSAGE_COLUMNS
                + " FROM support_conversation c LEFT JOIN support_message m ON m.id=c.last_message_id LEFT JOIN support_attachment a ON a.id=m.attachment_id";
    }
    private Conversation conversationRow(ResultSet row) throws SQLException {
        long deviceId = row.getLong("device_id");
        return new Conversation(Long.toString(row.getLong("conversation_ref")), String.format(Locale.ROOT, "用户 %04d", deviceId),
                Boolean.TRUE.equals(redis.hasKey(presenceKey(deviceId))), row.getBoolean("hidden"), row.getLong("unread_count"), messageRow(row));
    }
    private static Message messageRow(ResultSet row) throws SQLException {
        if (row.getObject("message_id") == null) return null;
        return new Message(Long.toString(row.getLong("message_id")), Long.toString(row.getLong("conversation_id")), row.getString("client_id"),
                row.getString("sender"), MessageKind.valueOf(row.getString("message_kind")), row.getString("message_text"),
                attachmentRow(row), row.getObject("message_created_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    private static Attachment attachmentRow(ResultSet row) throws SQLException {
        if (row.getObject("file_id") == null) return null;
        return new Attachment(Long.toString(row.getLong("file_id")), MessageKind.valueOf(row.getString("file_kind")), row.getString("filename"),
                row.getString("mime_type"), row.getLong("size_bytes"), (Integer) row.getObject("width_px"), (Integer) row.getObject("height_px"), nullableLong(row, "duration_ms"));
    }
    private static LibraryItem libraryRow(ResultSet row) throws SQLException {
        return new LibraryItem(Long.toString(row.getLong("library_id")), LibraryKind.valueOf(row.getString("library_kind")), row.getString("title"),
                row.getString("note"), row.getString("library_text"), attachmentRow(row), row.getLong("lock_version"), row.getObject("updated_at", LocalDateTime.class).toInstant(ZoneOffset.UTC));
    }
    public record AttachmentRecord(Attachment view, String storageKey, Long conversationId) {
        MessageKind kind() { return view.kind(); }
    }
    private static ApiException missing() { return new ApiException(HttpStatus.NOT_FOUND, "SUPPORT_CONVERSATION_NOT_FOUND", "会话不存在"); }
    private static ApiException invalid(String text) { return new ApiException(HttpStatus.BAD_REQUEST, "SUPPORT_INVALID", text); }
    private static ApiException inaccessible() { return new ApiException(HttpStatus.FORBIDDEN, "SUPPORT_ATTACHMENT_FORBIDDEN", "无权访问此图片或视频"); }
    private static ApiException conflict() { return new ApiException(HttpStatus.CONFLICT, "VERSION_CONFLICT", "内容已更新，请刷新后再编辑"); }
    private static final String ATTACHMENT_COLUMNS = "a.id AS file_id,a.kind AS file_kind,a.filename,a.mime_type,a.size_bytes,a.width_px,a.height_px,a.duration_ms";
    private static final String MESSAGE_COLUMNS = "m.id AS message_id,m.conversation_id,m.client_id,m.sender,m.kind AS message_kind,m.text AS message_text,m.created_at AS message_created_at," + ATTACHMENT_COLUMNS;
    private static final String MESSAGE_SELECT = "SELECT " + MESSAGE_COLUMNS + " FROM support_message m LEFT JOIN support_attachment a ON a.id=m.attachment_id";
    private static final String LIBRARY_SELECT = "SELECT l.id AS library_id,l.kind AS library_kind,l.title,l.note,l.text AS library_text,l.lock_version,l.updated_at,"
            + ATTACHMENT_COLUMNS + " FROM support_library_item l LEFT JOIN support_attachment a ON a.id=l.attachment_id";
}
