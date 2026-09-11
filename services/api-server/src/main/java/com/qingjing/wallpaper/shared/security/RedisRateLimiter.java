package com.qingjing.wallpaper.shared.security;

import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.Duration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class RedisRateLimiter {

    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;

    public RedisRateLimiter(StringRedisTemplate redis, SecurityCrypto crypto) {
        this.redis = redis;
        this.crypto = crypto;
    }

    public void require(String scope, String subject, int limit, Duration window) {
        String key = "rate:" + scope + ":" + crypto.sha256Hex(subject);
        Long count = redis.opsForValue().increment(key);
        if (count != null && count == 1) {
            redis.expire(key, window);
        }
        if (count != null && count > limit) {
            throw new ApiException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "RATE_LIMITED",
                    "Too many requests",
                    java.util.List.of(),
                    Math.max(1, window.toSeconds()));
        }
    }
}
