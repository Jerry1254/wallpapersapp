package com.qingjing.wallpaper.risk;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class RiskDtos {
    private RiskDtos() {}
    public enum Signal { NORMAL, RISK, UNKNOWN }
    public record Rule(String key, String title, String description, boolean enabled, boolean available,
                       List<String> platforms, int threshold, int windowSeconds, long version) {}
    public record Policy(boolean enabled, long version, List<Rule> rules) {}
    public record State(boolean allowed, List<String> checks) {}
    public record Report(@NotNull @Size(max=4) Map<String, Signal> signals) {}
    public record PolicyUpdate(@NotNull Boolean enabled, @NotNull @Min(0) Long version) {}
    public record RuleUpdate(@NotNull Boolean enabled, @Min(1) @Max(100000) int threshold,
                             @Min(10) @Max(86400) int windowSeconds, @NotNull @Min(0) Long version) {}
    public record BanRequest(String deviceId, @Size(max=45) String ip,
                             @NotBlank @Size(max=300) String reason) {}
    public record ReleaseRequest(@NotNull @Min(0) Long version, boolean includeRelated,
                                 @NotBlank @Size(max=300) String reason) {}
    public record Ban(String id, String groupId, String subjectType, String subjectValue,
                      String deviceId, String platform, String ip, String ruleKey, String reason,
                      Instant bannedAt, String bannedBy, Instant releasedAt, String releasedBy,
                      String releaseReason, long version) {}
    public record BanPage(List<Ban> items, long total, int page, int pageSize) {}
    public record WhitelistRequest(@NotBlank String subjectType, @NotBlank @Size(max=64) String subjectValue,
                                   @NotBlank @Size(max=300) String note) {}
    public record Whitelist(String id, String subjectType, String subjectValue, String note, Instant createdAt) {}
    public record Event(String id,String action,String actor,String result,String changes,Instant createdAt) {}
}
