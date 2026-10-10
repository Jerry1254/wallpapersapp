package com.qingjing.wallpaper.risk;

import static com.qingjing.wallpaper.risk.RiskDtos.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class RiskIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("risk_test").withUsername("risk_test").withPassword(UUID.randomUUID().toString());
    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactions;
    RiskService risk; RiskCounter counter;
    static final DevicePrincipal USER=new DevicePrincipal(291,UUID.randomUUID().toString(),DeviceDtos.DevicePlatform.ANDROID,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
    @BeforeAll static void database() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source); transactions=new DataSourceTransactionManager(source);
        jdbc.update("INSERT INTO admin_account(id,username,password_hash,password_changed_at) VALUES(1,'risk-test','test-only-not-a-password',UTC_TIMESTAMP(6))");
        for(long id:List.of(291L,327L)) jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,'ANDROID','com.qingjing.bizhi',?,UTC_TIMESTAMP(6))",
            id,UUID.randomUUID().toString(),String.format("%064d",id));
    }
    @BeforeEach void service() {
        jdbc.update("DELETE FROM security_ban");jdbc.update("DELETE FROM security_whitelist");
        jdbc.update("UPDATE security_policy SET enabled=TRUE,lock_version=0");
        jdbc.update("UPDATE security_rule SET enabled=FALSE,lock_version=0");
        counter=mock(RiskCounter.class);risk=new RiskService(jdbc,counter,new ClientAddress(""),transactions);
    }
    @Test void bansBothIdentityAndWholeIpAndOnlyAdminReleaseRestoresAccess() {
        enable("ROOT_JAILBREAK",1);
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of("ROOT_JAILBREAK",Signal.RISK))).allowed()).isFalse();
        assertThat(risk.blocked(291L,"198.51.100.1")).isTrue();
        assertThat(risk.blocked(327L,"203.0.113.9")).isTrue();
        assertThat(risk.blocked(327L,"203.0.113.10")).isFalse();
        var p=risk.policy();risk.updatePolicy(new PolicyUpdate(false,p.version()));
        var rule=rule("ROOT_JAILBREAK");risk.updateRule(rule.key(),new RuleUpdate(false,1,60,rule.version()));
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isFalse();
        var rows=risk.bans("","ACTIVE",1,50).items();assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(b->{assertThat(b.bannedAt()).isNotNull();assertThat(b.releasedAt()).isNull();});
        var device=rows.stream().filter(b->b.subjectType().equals("DEVICE")).findFirst().orElseThrow();
        risk.release(Long.parseLong(device.id()),new ReleaseRequest(device.version(),false,"仅解除设备"),1);
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isFalse();
        var ip=risk.bans("","ACTIVE",1,50).items().get(0);
        risk.release(Long.parseLong(ip.id()),new ReleaseRequest(ip.version(),false,"解除 IP"),1);
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isTrue();
        assertThat(risk.bans("","RELEASED",1,50).items()).allSatisfy(b->{assertThat(b.releasedBy()).isEqualTo("risk-test");assertThat(b.releasedAt()).isNotNull();assertThat(b.releaseReason()).isNotBlank();});
    }
    @Test void disabledRulesSkipCountingAndIgnoreReportedSignalsAndUnknownNeverBans() {
        risk.count("BULK_DOWNLOAD",291L,"203.0.113.9","1");verifyNoInteractions(counter);
        risk.report(USER,"203.0.113.9",new Report(Map.of("ROOT_JAILBREAK",Signal.RISK)));
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
        enable("ROOT_JAILBREAK",1);
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of("ROOT_JAILBREAK",Signal.UNKNOWN))).allowed()).isTrue();
        risk.report(USER,"203.0.113.9",new Report(Map.of("DEVELOPER_MODE",Signal.RISK,"USB_DEBUGGING",Signal.RISK)));
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
        enable("DEVELOPER_MODE",1);enable("USB_DEBUGGING",1);
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of("DEVELOPER_MODE",Signal.UNKNOWN,"USB_DEBUGGING",Signal.UNKNOWN))).allowed()).isTrue();
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
        var p=risk.policy();risk.updatePolicy(new PolicyUpdate(false,p.version()));
        assertThat(risk.state(USER,"203.0.113.9").checks()).isEmpty();
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of("DEVELOPER_MODE",Signal.RISK,"USB_DEBUGGING",Signal.RISK))).allowed()).isTrue();
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
    }
    @Test void developerAndUsbSwitchesAreAvailableIndependentlyAndOnlySentToAndroid() {
        for(String key:List.of("DEVELOPER_MODE","USB_DEBUGGING")) {
            assertThat(rule(key).available()).isTrue();
            assertThat(rule(key).enabled()).isFalse();
            assertThat(rule(key).platforms()).containsExactly("ANDROID");
        }
        enable("DEVELOPER_MODE",1);
        assertThat(risk.state(USER,"203.0.113.9").checks()).containsExactly("DEVELOPER_MODE");
        enable("USB_DEBUGGING",1);
        assertThat(risk.state(USER,"203.0.113.9").checks()).containsExactly("DEVELOPER_MODE","USB_DEBUGGING");
        for(var platform:List.of(DeviceDtos.DevicePlatform.IOS,DeviceDtos.DevicePlatform.HARMONYOS)) {
            var device=new DevicePrincipal(291,UUID.randomUUID().toString(),platform,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
            assertThat(risk.state(device,"203.0.113.9").checks()).isEmpty();
            assertThat(risk.report(device,"203.0.113.9",new Report(Map.of("DEVELOPER_MODE",Signal.RISK,"USB_DEBUGGING",Signal.RISK))).allowed()).isTrue();
        }
        var developer=rule("DEVELOPER_MODE");risk.updateRule(developer.key(),new RuleUpdate(false,1,60,developer.version()));
        assertThat(risk.state(USER,"203.0.113.9").checks()).containsExactly("USB_DEBUGGING");
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of("DEVELOPER_MODE",Signal.RISK,"USB_DEBUGGING",Signal.UNKNOWN))).allowed()).isTrue();
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"DEVELOPER_MODE","USB_DEBUGGING"})
    void developerOrUsbPositiveSignalPermanentlyBansDeviceAndWholeIp(String key) {
        enable(key,1);
        assertThat(risk.report(USER,"203.0.113.9",new Report(Map.of(key,Signal.RISK))).allowed()).isFalse();
        var rows=risk.bans("","ACTIVE",1,50).items();
        assertThat(rows).hasSize(2).allSatisfy(b->{
            assertThat(b.ruleKey()).isEqualTo(key);assertThat(b.bannedAt()).isNotNull();assertThat(b.releasedAt()).isNull();
        });
        assertThat(risk.blocked(291L,"198.51.100.1")).isTrue();
        assertThat(risk.blocked(327L,"203.0.113.9")).isTrue();
        var rule=rule(key);risk.updateRule(key,new RuleUpdate(false,1,60,rule.version()));
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isFalse();
        var policy=risk.policy();risk.updatePolicy(new PolicyUpdate(false,policy.version()));
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isFalse();
        var ban=rows.get(0);risk.release(Long.parseLong(ban.id()),new ReleaseRequest(ban.version(),true,"管理员解除调试检测封禁"),1);
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isTrue();
        assertThat(risk.bans("","RELEASED",1,50).items()).hasSize(2).allSatisfy(b->{
            assertThat(b.releasedAt()).isNotNull();assertThat(b.releasedBy()).isEqualTo("risk-test");
        });
    }
    @Test void thresholdBanCommitsBeforeRequestFailureAndDeviceWhitelistDoesNotUndoExistingBan() {
        enable("BULK_DOWNLOAD",2);when(counter.count(any(),anyString(),anyString())).thenReturn(3L);
        assertThatThrownBy(()->risk.count("BULK_DOWNLOAD",291L,"203.0.113.9","2")).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("ACCESS_UNAVAILABLE"));
        assertThat(risk.bans("","ACTIVE",1,50).total()).isEqualTo(2);
        risk.addWhitelist(new WhitelistRequest("DEVICE","291","测试设备"),1);
        assertThat(risk.state(USER,"203.0.113.9").allowed()).isFalse();
        var device=risk.bans("291","ACTIVE",1,50).items().get(0);
        risk.release(Long.parseLong(device.id()),new ReleaseRequest(device.version(),true,"解除关联封禁"),1);
        reset(counter);risk.count("BULK_DOWNLOAD",291L,"203.0.113.9","3");verifyNoInteractions(counter);
    }
    @Test void concurrentDetectionKeepsOneActiveBanPerSubjectAndCannotRewriteBanTime() throws Exception {
        enable("ROOT_JAILBREAK",1);var pool=Executors.newFixedThreadPool(6);
        try {
            var tasks=new ArrayList<Future<?>>();
            for(int n=0;n<8;n++) tasks.add(pool.submit(()->{
                try { risk.report(USER,"203.0.113.9",new Report(Map.of("ROOT_JAILBREAK",Signal.RISK))); }
                catch(ApiException e) { assertThat(e.code()).isEqualTo("ACCESS_UNAVAILABLE"); }
            }));
            for(var task:tasks) task.get(15,TimeUnit.SECONDS);
            assertThat(risk.bans("","ACTIVE",1,50).total()).isEqualTo(2);
            var first=risk.bans("","ACTIVE",1,50).items();risk.manualBan(new BanRequest("291","203.0.113.9","重复封禁"),1);
            assertThat(risk.bans("","ACTIVE",1,50).items()).isEqualTo(first);
        } finally { pool.shutdownNow(); }
    }
    @Test void optimisticVersionsPreventSilentRuleOverwriteAndOldReleaseCannotReleaseANewBan() {
        var rule=rule("EMULATOR");risk.updateRule(rule.key(),new RuleUpdate(true,1,60,rule.version()));
        assertThatThrownBy(()->risk.updateRule(rule.key(),new RuleUpdate(false,1,60,rule.version()))).isInstanceOf(ApiException.class);
        risk.manualBan(new BanRequest("291",null,"手动封禁"),1);var b=risk.bans("","ACTIVE",1,50).items().get(0);
        risk.release(Long.parseLong(b.id()),new ReleaseRequest(b.version(),true,"恢复访问"),1);
        risk.manualBan(new BanRequest("291",null,"再次封禁"),1);
        assertThatThrownBy(()->risk.release(Long.parseLong(b.id()),new ReleaseRequest(b.version(),true,"过期操作"),1)).isInstanceOf(ApiException.class);
        assertThat(risk.blocked(291L,null)).isTrue();
    }
    @Test void securityOperationsRemainInThePersistentAuditHistory() {
        jdbc.update("DELETE FROM audit_event WHERE aggregate_type='SECURITY_POLICY'");
        var audit=new com.qingjing.wallpaper.audit.AdminAuditService(jdbc,new com.fasterxml.jackson.databind.ObjectMapper());
        audit.record(new com.qingjing.wallpaper.adminidentity.AdminPrincipal(1,"risk-test"),UUID.randomUUID().toString(),
            "PUT /api/v1/admin/security/policy","SECURITY_POLICY","1",200,Map.of("enabled",false));
        assertThat(risk.events()).hasSize(1);
        assertThat(risk.events().get(0).actor()).isEqualTo("risk-test");
        assertThat(risk.events().get(0).changes()).contains("false");
    }
    private Rule rule(String key) { return risk.policy().rules().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow(); }
    private void enable(String key,int threshold) { var rule=rule(key);risk.updateRule(key,new RuleUpdate(true,threshold,60,rule.version())); }
}
