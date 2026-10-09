package com.qingjing.wallpaper.risk;

import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/** Atomic expiry prevents interrupted requests from leaving immortal counters. */
@Component
public class RiskCounter {
    private static final DefaultRedisScript<Long> COUNT = new DefaultRedisScript<>(
        "local n=redis.call('INCR',KEYS[1]); if n==1 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return n", Long.class);
    private static final DefaultRedisScript<Long> DISTINCT = new DefaultRedisScript<>(
        "redis.call('SADD',KEYS[1],ARGV[2]); if redis.call('TTL',KEYS[1])<0 then redis.call('EXPIRE',KEYS[1],ARGV[1]); end; return redis.call('SCARD',KEYS[1])", Long.class);
    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;
    public RiskCounter(StringRedisTemplate redis, SecurityCrypto crypto) { this.redis=redis; this.crypto=crypto; }
    public long count(RiskDtos.Rule rule, String subject, String distinct) {
        String key = "risk:counter:"+rule.key()+":"+rule.version()+":"+crypto.sha256Hex(subject);
        Long value = distinct == null ? redis.execute(COUNT,List.of(key),String.valueOf(rule.windowSeconds()))
                : redis.execute(DISTINCT,List.of(key),String.valueOf(rule.windowSeconds()),distinct);
        if (value == null) throw new IllegalStateException("Risk counter unavailable");
        return value;
    }
    public void reset(RiskDtos.Rule rule,String subject) {
        redis.delete("risk:counter:"+rule.key()+":"+rule.version()+":"+crypto.sha256Hex(subject));
    }
}
