package com.qingjing.wallpaper.risk;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.device.*;
import com.qingjing.wallpaper.shared.security.*;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class HarmonyRiskIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("harmony_risk_test").withUsername("harmony_risk_test").withPassword(UUID.randomUUID().toString());
    @Container static final GenericContainer<?> REDIS=new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static JdbcTemplate jdbc; static DataSourceTransactionManager transactions;
    static LettuceConnectionFactory connections; static StringRedisTemplate redis; static SecurityCrypto crypto;
    final DevicePrincipal user=new DevicePrincipal(291,"harmony-install",DeviceDtos.DevicePlatform.HARMONYOS,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
    final String ip="203.0.113.9";
    HarmonyRiskTestEvidence evidence; RiskService risk; HarmonyRiskService harmony;
    @BeforeAll static void start() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);transactions=new DataSourceTransactionManager(source);
        jdbc.update("INSERT INTO admin_account(id,username,password_hash,password_changed_at) VALUES(1,'harmony-test','test-only-not-a-password',UTC_TIMESTAMP(6))");
        for(long id:List.of(291L,327L)) jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,'HARMONYOS','com.qingjing.bizhi',?,UTC_TIMESTAMP(6))",
            id,UUID.randomUUID().toString(),String.format("%064d",id));
        connections=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));connections.afterPropertiesSet();connections.start();redis=new StringRedisTemplate(connections);
        var properties=new SecurityProperties();properties.setMasterKey("cd".repeat(32));crypto=new SecurityCrypto(properties);
    }
    @AfterAll static void close() { if(connections!=null)connections.destroy(); }
    @BeforeEach void setup() throws Exception {
        jdbc.update("DELETE FROM security_ban");jdbc.update("DELETE FROM security_whitelist");
        jdbc.update("UPDATE security_policy SET enabled=TRUE,lock_version=0");jdbc.update("UPDATE security_rule SET enabled=FALSE,lock_version=0");
        var keys=redis.keys("risk:harmony:*");if(keys!=null&&!keys.isEmpty())redis.delete(keys);
        evidence=new HarmonyRiskTestEvidence();var verifier=evidence.verifier();
        risk=new RiskService(jdbc,mock(RiskCounter.class),new ClientAddress(""),transactions,verifier);
        harmony=new HarmonyRiskService(risk,verifier,redis,crypto,new ObjectMapper());
    }
    @Test void verifiedDeveloperRiskCreatesPermanentDeviceAndWholeIpBansWithTimeAndAdminRelease() throws Exception {
        enable("DEVELOPER_MODE");enable("USB_DEBUGGING");
        assertThat(risk.policy().rules().stream().filter(r->r.key().equals("DEVELOPER_MODE")).findFirst().orElseThrow().platforms()).containsExactly("ANDROID","HARMONYOS");
        // A valid installation signature is not Huawei attestation.
        assertThat(risk.report(user,ip,new RiskDtos.Report(Map.of("DEVELOPER_MODE",RiskDtos.Signal.RISK))).allowed()).isTrue();
        var challenge=harmony.challenge(user,ip);assertThat(challenge.checks()).containsExactly("DEVELOPER_MODE","USB_DEBUGGING");
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(challenge.nonce(),"true","0"))).allowed()).isFalse();
        var bans=risk.bans("","ACTIVE",1,50).items();
        assertThat(bans).hasSize(2).allSatisfy(b->{assertThat(b.ruleKey()).isEqualTo("DEVELOPER_MODE");assertThat(b.bannedAt()).isNotNull();assertThat(b.releasedAt()).isNull();});
        assertThat(risk.blocked(327L,ip)).isTrue();assertThat(risk.blocked(291L,"198.51.100.3")).isTrue();
        risk.updatePolicy(new RiskDtos.PolicyUpdate(false,risk.policy().version()));
        assertThat(harmony.challenge(user,ip).allowed()).isFalse();assertThat(risk.state(user,ip).allowed()).isFalse();
        var ban=bans.get(0);risk.release(Long.parseLong(ban.id()),new RiskDtos.ReleaseRequest(ban.version(),true,"管理员解除"),1);
        assertThat(risk.state(user,ip).allowed()).isTrue();
    }
    @Test void usbAndWifiDebuggingAreIndependentFromDeveloperRule() throws Exception {
        enable("USB_DEBUGGING");var challenge=harmony.challenge(user,ip);
        assertThat(challenge.checks()).containsExactly("USB_DEBUGGING");
        // Developer factor was not requested; neither debugging transport is enabled.
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(challenge.nonce(),"true","0"))).allowed()).isTrue();
        redis.delete("risk:harmony:interval:291");challenge=harmony.challenge(user,ip);
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(challenge.nonce(),"false","2"))).allowed()).isFalse();
        assertThat(risk.bans("","ACTIVE",1,50).items()).allSatisfy(b->assertThat(b.ruleKey()).isEqualTo("USB_DEBUGGING"));
    }
    @Test void disabledAfterChallengeFailedFactorAndUnknownDoNotBan() throws Exception {
        enable("DEVELOPER_MODE");var challenge=harmony.challenge(user,ip);
        var rule=rule("DEVELOPER_MODE");risk.updateRule(rule.key(),new RiskDtos.RuleUpdate(false,1,60,rule.version()));
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(challenge.nonce(),"true","1"))).allowed()).isTrue();
        assertThat(harmony.challenge(user,ip).nonce()).isNull();
        enable("DEVELOPER_MODE");redis.delete("risk:harmony:interval:291");challenge=harmony.challenge(user,ip);
        var payload=evidence.payload(challenge.nonce(),"true","1");payload.withObject("/isDeveloperMode").put("status",-1);
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(evidence.header().toString(),payload.toString()))).allowed()).isTrue();
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
    }
    @Test void malformedEvidenceAndReplayedExpiredOrCrossDeviceNonceCannotCreateBans() throws Exception {
        enable("DEVELOPER_MODE");var challenge=harmony.challenge(user,ip);
        String normal=evidence.jws(challenge.nonce(),"false","0");
        assertThat(harmony.report(user,ip,new RiskDtos.HarmonyProof(challenge.nonce(),normal)).allowed()).isTrue();
        var replay=new RiskDtos.HarmonyProof(challenge.nonce(),evidence.jws(challenge.nonce(),"true","1"));
        assertThatThrownBy(()->harmony.report(user,ip,replay)).isInstanceOf(ApiException.class);
        redis.delete("risk:harmony:interval:291");var fresh=harmony.challenge(user,ip);
        var other=new DevicePrincipal(327,"other-install",DeviceDtos.DevicePlatform.HARMONYOS,DeviceDtos.CredentialType.PLATFORM_PUBLIC_KEY);
        var proof=new RiskDtos.HarmonyProof(fresh.nonce(),evidence.jws(fresh.nonce(),"true","1"));
        assertThatThrownBy(()->harmony.report(other,ip,proof)).isInstanceOf(ApiException.class);
        var callerRoot=new HarmonyRiskTestEvidence();
        var forged=new RiskDtos.HarmonyProof(fresh.nonce(),callerRoot.jws(fresh.nonce(),"true","1"));
        assertThatThrownBy(()->harmony.report(user,ip,forged)).isInstanceOf(ApiException.class);
        redis.delete("risk:harmony:interval:291");var expired=harmony.challenge(user,ip);
        redis.delete("risk:harmony:challenge:291:"+crypto.sha256Hex(expired.nonce()));
        var expiredProof=new RiskDtos.HarmonyProof(expired.nonce(),evidence.jws(expired.nonce(),"true","1"));
        assertThatThrownBy(()->harmony.report(user,ip,expiredProof)).isInstanceOf(ApiException.class);
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
    }
    @Test void concurrentChallengesConsumeOneSlotAndDailyQuotaStopsAtTwenty() throws Exception {
        enable("DEVELOPER_MODE");var pool=Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<Future<RiskDtos.HarmonyChallenge>>();
            for(int i=0;i<8;i++)jobs.add(pool.submit(()->harmony.challenge(user,ip)));
            int issued=0;for(var job:jobs)if(job.get(10,TimeUnit.SECONDS).nonce()!=null)issued++;
            assertThat(issued).isEqualTo(1);
        } finally { pool.shutdownNow(); }
        for(int n=1;n<20;n++) { redis.delete("risk:harmony:interval:291");assertThat(harmony.challenge(user,ip).nonce()).isNotNull(); }
        redis.delete("risk:harmony:interval:291");var exhausted=harmony.challenge(user,ip);
        assertThat(exhausted.allowed()).isTrue();assertThat(exhausted.nonce()).isNull();assertThat(exhausted.retryAfterSeconds()).isGreaterThan(0);
        assertThat(risk.bans("","ALL",1,50).total()).isZero();
    }
    private RiskDtos.Rule rule(String key) { return risk.policy().rules().stream().filter(r->r.key().equals(key)).findFirst().orElseThrow(); }
    private void enable(String key) { var rule=rule(key);risk.updateRule(key,new RiskDtos.RuleUpdate(true,1,60,rule.version())); }
}
