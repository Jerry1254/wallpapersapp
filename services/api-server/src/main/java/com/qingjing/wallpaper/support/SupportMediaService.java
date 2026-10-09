package com.qingjing.wallpaper.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.asset.application.*;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@Service
public class SupportMediaService {
    private static final Duration TTL = Duration.ofMinutes(15);
    private final SupportService support;
    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final FileStorage storage;
    public SupportMediaService(SupportService support, StringRedisTemplate redis, SecurityCrypto crypto,
                               ObjectMapper mapper, JdbcTemplate jdbc, FileStorage storage) {
        this.support = support; this.redis = redis; this.crypto = crypto;
        this.mapper = mapper; this.jdbc = jdbc; this.storage = storage;
    }
    public SupportDtos.MediaAccess access(long attachmentId, DevicePrincipal device) {
        support.authorizedAttachment(attachmentId, device == null ? null : support.forDevice(device));
        String token = crypto.randomToken(32);
        var ticket = new MediaTicket(attachmentId, device == null ? null : device.deviceId(),
                device == null ? null : device.credentialKeyId(), Instant.now().plus(TTL));
        try { redis.opsForValue().set(key(token), mapper.writeValueAsString(ticket), TTL); }
        catch (com.fasterxml.jackson.core.JsonProcessingException error) { throw new IllegalStateException(error); }
        return new SupportDtos.MediaAccess("/api/v1/support/files/" + attachmentId + "?ticket=" + token, ticket.expiresAt());
    }
    public ResponseEntity<StreamingResponseBody> content(long attachmentId, String token, String range) {
        MediaTicket ticket = ticket(attachmentId, token);
        if (ticket.deviceId() != null && jdbc.queryForObject("""
                SELECT COUNT(*) FROM anonymous_device d JOIN device_credential c ON c.device_id=d.id
                WHERE d.id=? AND d.status='ACTIVE' AND c.credential_key_id=? AND c.status='ACTIVE'
                """, Long.class, ticket.deviceId(), ticket.credentialKeyId()) != 1) throw invalid();
        var file = support.attachmentRecord(attachmentId);
        long size = file.view().sizeBytes();
        long start = 0, end = size - 1;
        boolean partial = range != null;
        if (partial) {
            try {
                var ranges = HttpRange.parseRanges(range);
                if (ranges.size() != 1) throw new IllegalArgumentException();
                start = ranges.get(0).getRangeStart(size);
                end = ranges.get(0).getRangeEnd(size);
                if (start < 0 || start >= size || end < start || end >= size) throw new IllegalArgumentException();
            } catch (IllegalArgumentException error) {
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .header(HttpHeaders.CONTENT_RANGE, "bytes */" + size).build();
            }
        }
        final long offset = start, length = end - start + 1;
        StreamingResponseBody body = output -> {
            // Revalidate a grant when streaming begins; storage paths never reach clients.
            ticket(attachmentId, token);
            try (var content = storage.open(new StorageKey(file.storageKey()))) {
                if (content.sizeBytes() != size) throw new java.io.IOException("Support attachment length changed");
                content.inputStream().skipNBytes(offset);
                long remaining = length;
                byte[] buffer = new byte[32768];
                while (remaining > 0) {
                    int count = content.inputStream().read(buffer, 0, (int) Math.min(buffer.length, remaining));
                    if (count < 0) throw new java.io.EOFException("Incomplete support attachment");
                    output.write(buffer, 0, count); remaining -= count;
                }
            }
        };
        var response = ResponseEntity.status(partial ? 206 : 200).contentType(MediaType.parseMediaType(file.view().mimeType()))
                .contentLength(length).cacheControl(CacheControl.noStore()).header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename("support-" + attachmentId
                        + (file.view().kind() == SupportDtos.MessageKind.VIDEO ? ".mp4" : ".image")).build().toString());
        if (partial) response.header(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + size);
        return response.body(body);
    }
    private MediaTicket ticket(long attachmentId, String token) {
        if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) throw invalid();
        String json = redis.opsForValue().get(key(token));
        if (json == null) throw invalid();
        try {
            var ticket = mapper.readValue(json, MediaTicket.class);
            if (ticket.attachmentId() != attachmentId || !ticket.expiresAt().isAfter(Instant.now())) throw invalid();
            return ticket;
        } catch (java.io.IOException error) { throw invalid(); }
    }
    private String key(String token) { return "support:media:" + crypto.sha256Hex(token); }
    private static ApiException invalid() { return new ApiException(HttpStatus.UNAUTHORIZED, "SUPPORT_MEDIA_ACCESS_EXPIRED", "图片或视频访问已过期，请重新加载"); }
    public record MediaTicket(long attachmentId, Long deviceId, String credentialKeyId, Instant expiresAt) {}
}
