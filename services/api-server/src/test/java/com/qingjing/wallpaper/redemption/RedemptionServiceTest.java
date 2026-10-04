package com.qingjing.wallpaper.redemption;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.qingjing.wallpaper.catalog.PublishedResourceCatalog;
import com.qingjing.wallpaper.device.DeviceDtos.DevicePlatform;
import com.qingjing.wallpaper.entitlement.DeviceEntitlementService;
import com.qingjing.wallpaper.entitlement.EntitlementDtos.EntitlementSummary;
import com.qingjing.wallpaper.entitlement.EntitlementGrantService;
import com.qingjing.wallpaper.entitlement.EntitlementGrantService.SourceType;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionAttempt;
import com.qingjing.wallpaper.redemption.RedemptionDtos.RedemptionResultCode;
import com.qingjing.wallpaper.shared.security.RedisRateLimiter;
import com.qingjing.wallpaper.shared.security.SecurityCrypto;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.KeyHolder;

class RedemptionServiceTest {
    private static final long CODE_ID = 75;
    private static final String CODE = "ABCDEFGHIJKLMNOPQRST";
    private static final Instant GRANTED_AT = Instant.parse("2026-10-04T00:00:00Z");

    @Test
    void oneCodeGrantsSeparateEntitlementsToThreeDevices() throws Exception {
        Fixture fixture = new Fixture();
        var first = fixture.redeem(101, 21);
        var second = fixture.redeem(102, 21);
        var third = fixture.redeem(103, 21);

        assertThat(List.of(first.result().entitlement().id(), second.result().entitlement().id(), third.result().entitlement().id()))
                .doesNotHaveDuplicates();
        assertThat(first.result().result()).isEqualTo(RedemptionResultCode.GRANTED);
        assertThat(second.result().result()).isEqualTo(RedemptionResultCode.GRANTED);
        assertThat(third.result().result()).isEqualTo(RedemptionResultCode.GRANTED);
        assertThat(fixture.owned).hasSize(3);
        assertThat(fixture.usedQuota).isEqualTo(3);
        verify(fixture.grants).grant(101, 21, SourceType.REDEMPTION, "code:75:device:101:wallpaper:21", null, CODE_ID);
        verify(fixture.grants).grant(102, 21, SourceType.REDEMPTION, "code:75:device:102:wallpaper:21", null, CODE_ID);
        verify(fixture.grants).grant(103, 21, SourceType.REDEMPTION, "code:75:device:103:wallpaper:21", null, CODE_ID);
    }

    @Test
    void oneDeviceCanRedeemTheSameCodeForDifferentWallpapers() throws Exception {
        Fixture fixture = new Fixture();
        var first = fixture.redeem(101, 21);
        var second = fixture.redeem(101, 22);

        assertThat(first.result().entitlement().id()).isNotEqualTo(second.result().entitlement().id());
        assertThat(fixture.owned).hasSize(2);
        assertThat(fixture.usedQuota).isEqualTo(2);
        verify(fixture.grants).grant(101, 21, SourceType.REDEMPTION, "code:75:device:101:wallpaper:21", null, CODE_ID);
        verify(fixture.grants).grant(101, 22, SourceType.REDEMPTION, "code:75:device:101:wallpaper:22", null, CODE_ID);
    }

    @Test
    void aLegacyActiveEntitlementStillReturnsAlreadyOwnedWithoutConsumingQuotaOrGrantingAgain() throws Exception {
        Fixture fixture = new Fixture();
        fixture.references.put(Long.toString(CODE_ID), 300L);
        fixture.owned.put("101:21", 300L);

        var result = fixture.redeem(101, 21);

        assertThat(result.result().result()).isEqualTo(RedemptionResultCode.ALREADY_OWNED);
        assertThat(result.result().entitlement().id()).isEqualTo("300");
        assertThat(result.result().quotaDelta()).isZero();
        assertThat(fixture.usedQuota).isZero();
        assertThat(fixture.references).containsOnlyKeys(Long.toString(CODE_ID));
        verifyNoInteractions(fixture.grants);
    }

    private static final class Fixture {
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final EntitlementGrantService grants = mock(EntitlementGrantService.class);
        final Map<String, Long> references = new HashMap<>();
        final Map<String, Long> owned = new HashMap<>();
        final Map<Long, Object[]> events = new HashMap<>();
        final RedemptionService service;
        int usedQuota;
        long requestId;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Fixture() throws Exception {
            SecurityCrypto crypto = mock(SecurityCrypto.class);
            DeviceEntitlementService entitlements = mock(DeviceEntitlementService.class);
            PublishedResourceCatalog catalog = mock(PublishedResourceCatalog.class);
            when(crypto.hmacHex(eq("redemption-code-v1"), anyString())).thenReturn("code-hash");
            when(crypto.sha256Hex(anyString())).thenAnswer(call -> call.getArgument(0));
            when(catalog.resolve(DevicePlatform.ANDROID)).thenReturn(new PublishedResourceCatalog.PublishedCatalog(
                    Map.of(21L, List.of(), 22L, List.of())));
            when(entitlements.summary(anyLong(), anyLong(), anyLong(), any(Instant.class))).thenAnswer(call ->
                    new EntitlementSummary(Long.toString(call.getArgument(1)), null, GRANTED_AT));
            when(grants.grant(anyLong(), anyLong(), eq(SourceType.REDEMPTION), anyString(), isNull(), eq(CODE_ID))).thenAnswer(call -> {
                String reference = call.getArgument(3);
                Long existing = references.get(reference);
                if (existing != null) return existing;
                long id = 400 + references.size();
                references.put(reference, id);
                owned.put(call.getArgument(0) + ":" + call.getArgument(1), id);
                return id;
            });
            doAnswer(call -> {
                KeyHolder holder = call.getArgument(1);
                holder.getKeyList().add(Map.of("id", ++requestId));
                return 1;
            }).when(jdbc).update(any(PreparedStatementCreator.class), any(KeyHolder.class));
            doAnswer(call -> {
                String sql = call.getArgument(0);
                Object[] args = (Object[]) call.getRawArguments()[1];
                if (sql.contains("UPDATE redemption_code")) ++usedQuota;
                if (sql.contains("INSERT INTO redemption_event")) events.put((Long) args[0], args);
                return 1;
            }).when(jdbc).update(anyString(), any(Object[].class));
            doAnswer(call -> {
                ResultSet row = mock(ResultSet.class);
                when(row.getString("status")).thenReturn("ACTIVE");
                when(row.getString("platform")).thenReturn("ANDROID");
                return ((RowMapper) call.getArgument(1)).mapRow(row, 0);
            }).when(jdbc).queryForObject(anyString(), any(RowMapper.class), any(Object[].class));
            doAnswer(this::query).when(jdbc).query(anyString(), any(RowMapper.class), any(Object[].class));
            service = new RedemptionService(jdbc, crypto, mock(RedisRateLimiter.class), entitlements, catalog, grants);
        }

        RedemptionAttempt redeem(long device, long wallpaper) {
            return service.redeem(device, UUID.randomUUID().toString(), wallpaper, CODE);
        }

        @SuppressWarnings("unchecked")
        private Object query(InvocationOnMock call) throws Exception {
            String sql = call.getArgument(0);
            Object[] args = (Object[]) call.getRawArguments()[2];
            RowMapper<Object> mapper = call.getArgument(1);
            ResultSet row = mock(ResultSet.class);
            when(row.getTimestamp("granted_at")).thenReturn(Timestamp.from(GRANTED_AT));
            if (sql.contains("FROM redemption_request")) return List.of();
            if (sql.contains("FROM wallpaper")) {
                when(row.getString("status")).thenReturn("PUBLISHED");
                when(row.getString("access_type")).thenReturn("REDEEM");
            } else if (sql.contains("FROM device_entitlement")) {
                Long id = owned.get(args[0] + ":" + args[1]);
                if (id == null) return List.of();
                when(row.getLong("id")).thenReturn(id);
            } else if (sql.contains("FROM redemption_code")) {
                when(row.getLong("id")).thenReturn(CODE_ID);
                when(row.getString("code_suffix")).thenReturn("PQRST");
                when(row.getInt("total_quota")).thenReturn(10);
                when(row.getInt("used_quota")).thenReturn(usedQuota);
            } else if (sql.contains("FROM redemption_event")) {
                Object[] event = events.get((Long) args[0]);
                when(row.getLong("device_id")).thenReturn((Long) event[1]);
                when(row.getString("result")).thenReturn((String) event[6]);
                when(row.getInt("quota_delta")).thenReturn((Integer) event[7]);
                when(row.getObject("entitlement_id", Long.class)).thenReturn((Long) event[5]);
                when(row.getObject("wallpaper_id", Long.class)).thenReturn((Long) event[2]);
                when(row.getTimestamp("created_at")).thenReturn(Timestamp.from(GRANTED_AT));
            } else throw new AssertionError("Unexpected redemption query: " + sql);
            return List.of(mapper.mapRow(row, 0));
        }
    }
}
