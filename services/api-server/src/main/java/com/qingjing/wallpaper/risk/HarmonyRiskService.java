package com.qingjing.wallpaper.risk;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

/** Short-lived challenges and quota coordination are disposable Redis state, never ban facts. */
@Service
public class HarmonyRiskService {
    private static final int TTL_SECONDS = 120;
    private static final DefaultRedisScript<Long> BUDGET = new DefaultRedisScript<>("""
        local count=tonumber(redis.call('GET',KEYS[1]) or '0')
        if count>=20 then return tonumber(ARGV[1]) end
        local ttl=redis.call('TTL',KEYS[2])
        if ttl>0 then return ttl end
        redis.call('SET',KEYS[2],'1','EX',60)
        redis.call('INCR',KEYS[1]); redis.call('EXPIRE',KEYS[1],172800)
        return 0
        """, Long.class);
    private final RiskService risk;
    private final HarmonyAttestationVerifier verifier;
    private final StringRedisTemplate redis;
    private final SecurityCrypto crypto;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();
    private record Ticket(long issuedAt, List<String> checks) {}
    public HarmonyRiskService(RiskService risk, HarmonyAttestationVerifier verifier, StringRedisTemplate redis,
        SecurityCrypto crypto, ObjectMapper json) {
        this.risk=risk; this.verifier=verifier; this.redis=redis; this.crypto=crypto; this.json=json;
    }
    public RiskDtos.HarmonyChallenge challenge(DevicePrincipal device, String ip) {
        requireHarmony(device);
        var state = risk.state(device, ip);
        if (!state.allowed() || state.checks().isEmpty() || !verifier.available())
            return new RiskDtos.HarmonyChallenge(state.allowed(), List.of(), null, 0);
        Instant now = Instant.now();
        // Huawei's quota is per calendar day. Use UTC+8 and an extra minute at the boundary.
        ZonedDateTime local = now.atZone(ZoneId.of("Asia/Shanghai"));
        long tomorrow = Duration.between(now, local.toLocalDate().plusDays(1).atStartOfDay(local.getZone()).toInstant()).getSeconds()+60;
        Long retry = redis.execute(BUDGET, List.of("risk:harmony:daily:"+device.deviceId()+":"+local.toLocalDate(),
            "risk:harmony:interval:"+device.deviceId()), Long.toString(tomorrow));
        if (retry == null) throw new IllegalStateException("SafetyDetect quota unavailable");
        if (retry > 0) return new RiskDtos.HarmonyChallenge(true, List.of(), null, retry.intValue());
        byte[] bytes = new byte[32]; random.nextBytes(bytes);
        String nonce = Base64.getEncoder().encodeToString(bytes);
        var checks = state.checks().stream().filter(k -> Set.of("DEVELOPER_MODE","USB_DEBUGGING").contains(k)).toList();
        try { redis.opsForValue().set(key(device,nonce), json.writeValueAsString(new Ticket(now.toEpochMilli(),checks)), Duration.ofSeconds(TTL_SECONDS)); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
        return new RiskDtos.HarmonyChallenge(true, checks, nonce, 5400);
    }
    public RiskDtos.State report(DevicePrincipal device, String ip, RiskDtos.HarmonyProof body) {
        requireHarmony(device); risk.requireAllowed(device.deviceId(),ip);
        var state = risk.state(device,ip);
        if (state.checks().isEmpty() || !verifier.available()) return state;
        // GETDEL is atomic: an expired, cross-device, or replayed proof can never create a ban.
        String stored = redis.opsForValue().getAndDelete(key(device,body.nonce()));
        if (stored == null) throw HarmonyAttestationVerifier.invalid();
        try {
            Ticket ticket = json.readValue(stored,Ticket.class);
            var verified = new HashMap<>(verifier.verify(body.jws(),body.nonce(),Instant.ofEpochMilli(ticket.issuedAt())));
            verified.keySet().retainAll(ticket.checks());
            return risk.reportHarmonyVerified(device,ip,verified);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw HarmonyAttestationVerifier.invalid(); }
    }
    private String key(DevicePrincipal device, String nonce) { return "risk:harmony:challenge:"+device.deviceId()+":"+crypto.sha256Hex(nonce); }
    private static void requireHarmony(DevicePrincipal device) {
        if (device == null || device.platform()!=DevicePlatform.HARMONYOS) throw HarmonyAttestationVerifier.invalid();
    }
}
