package com.qingjing.wallpaper.adminidentity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AdminSessionService {

    public static final String COOKIE_NAME = "QJ_ADMIN_SESSION";
    private static final String SESSION_PREFIX = "admin:session:";
    private static final String LOGIN_LIMIT_PREFIX = "admin:login-limit:";
    private static final int MAX_LOGIN_ATTEMPTS = 5;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final PasswordEncoder passwordEncoder;
    private final AdminIdentityProperties properties;

    public AdminSessionService(
            JdbcTemplate jdbc,
            StringRedisTemplate redis,
            ObjectMapper objectMapper,
            PasswordEncoder passwordEncoder,
            AdminIdentityProperties properties) {
        this.jdbc = jdbc;
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    public CreatedSession login(String username, String password, String remoteAddress) {
        String normalizedUsername = username.strip().toLowerCase(Locale.ROOT);
        String limitKey = loginLimitKey(normalizedUsername, remoteAddress);
        Long attempts = redis.opsForValue().increment(limitKey);
        if (attempts != null && attempts == 1) {
            redis.expire(limitKey, java.time.Duration.ofMinutes(5));
        }
        if (attempts != null && attempts > MAX_LOGIN_ATTEMPTS) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many login attempts");
        }

        List<AccountRow> matches = jdbc.query(
                "SELECT id, username, password_hash FROM admin_account WHERE username = ?",
                (resultSet, rowNumber) -> new AccountRow(
                        resultSet.getLong("id"),
                        resultSet.getString("username"),
                        resultSet.getString("password_hash")),
                normalizedUsername);
        AccountRow account = matches.isEmpty() ? null : matches.get(0);
        String safeHash = account == null
                ? "$2a$12$E9Q9kt1cY1E9MZps8kYLXepDFMZW1hq3YS8C4mN0cUGotWblh9B6e"
                : account.passwordHash();
        boolean accepted = passwordEncoder.matches(password, safeHash);
        if (account == null || !accepted) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "The username or password is incorrect");
        }

        redis.delete(limitKey);
        String sessionToken = randomToken();
        String csrfToken = randomToken();
        Instant expiresAt = Instant.now().plus(properties.getSessionTtl());
        SessionData session = new SessionData(account.id(), account.username(), csrfToken, expiresAt);
        redis.opsForValue().set(sessionKey(sessionToken), writeSession(session), properties.getSessionTtl());
        return new CreatedSession(sessionToken, session);
    }

    public SessionData require(String sessionToken) {
        if (sessionToken == null || sessionToken.isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "An admin session is required");
        }
        String stored = redis.opsForValue().get(sessionKey(sessionToken));
        if (stored == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "The admin session has expired");
        }
        SessionData session = readSession(stored);
        if (session.expiresAt().isBefore(Instant.now())) {
            redis.delete(sessionKey(sessionToken));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "SESSION_EXPIRED", "The admin session has expired");
        }
        return session;
    }

    public void requireCsrf(SessionData session, String suppliedCsrf) {
        if (suppliedCsrf == null || !MessageDigest.isEqual(
                session.csrfToken().getBytes(StandardCharsets.UTF_8),
                suppliedCsrf.getBytes(StandardCharsets.UTF_8))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "CSRF_INVALID", "The CSRF token is invalid");
        }
    }

    public void revoke(String sessionToken) {
        if (sessionToken != null && !sessionToken.isBlank()) {
            redis.delete(sessionKey(sessionToken));
        }
    }

    private String sessionKey(String token) {
        return SESSION_PREFIX + sha256(token);
    }

    private String loginLimitKey(String username, String remoteAddress) {
        return LOGIN_LIMIT_PREFIX + sha256(username + "\n" + remoteAddress);
    }

    private String writeSession(SessionData session) {
        try {
            return objectMapper.writeValueAsString(session);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Admin session cannot be serialized", exception);
        }
    }

    private SessionData readSession(String value) {
        try {
            return objectMapper.readValue(value, SessionData.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Admin session cannot be deserialized", exception);
        }
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private record AccountRow(long id, String username, String passwordHash) {
    }

    public record CreatedSession(String sessionToken, SessionData data) {
    }

    public record SessionData(long adminId, String username, String csrfToken, Instant expiresAt) {
        public AdminPrincipal principal() {
            return new AdminPrincipal(adminId, username);
        }
    }
}
