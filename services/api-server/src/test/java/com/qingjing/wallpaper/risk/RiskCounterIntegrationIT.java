package com.qingjing.wallpaper.risk;

import static org.assertj.core.api.Assertions.*;
import com.qingjing.wallpaper.shared.security.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class RiskCounterIntegrationIT {
    @Container static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    static LettuceConnectionFactory connections;
    static StringRedisTemplate redis;
    RiskCounter counter;
    RiskDtos.Rule rule;
    @BeforeAll static void connect() {
        connections=new LettuceConnectionFactory(REDIS.getHost(),REDIS.getMappedPort(6379));
        connections.afterPropertiesSet(); connections.start(); redis=new StringRedisTemplate(connections);
    }
    @AfterAll static void close() { connections.destroy(); }
    @BeforeEach void counter() {
        var properties=new SecurityProperties();properties.setMasterKey("ab".repeat(32));
        counter=new RiskCounter(redis,new SecurityCrypto(properties));
        rule=new RiskDtos.Rule(UUID.randomUUID().toString(),"测试","测试",true,true,List.of("ALL"),10,60,0);
    }
    @Test void concurrentIncrementsAreAtomicWithExpiryAndAdminReleaseStartsFreshObservation() throws Exception {
        var pool=Executors.newFixedThreadPool(8);
        try {
            var jobs=new ArrayList<Future<Long>>();
            for(int n=0;n<48;n++) jobs.add(pool.submit(()->counter.count(rule,"DEVICE:291",null)));
            var values=new HashSet<Long>();for(var job:jobs) values.add(job.get(10,TimeUnit.SECONDS));
            assertThat(values).hasSize(48).contains(1L,48L);
            var keys=redis.keys("risk:counter:"+rule.key()+":*");assertThat(keys).hasSize(1);
            assertThat(redis.getExpire(keys.iterator().next())).isBetween(1L,60L);
            counter.reset(rule,"DEVICE:291");assertThat(counter.count(rule,"DEVICE:291",null)).isEqualTo(1);
        } finally { pool.shutdownNow(); }
    }
    @Test void repeatedWallpaperRetriesDoNotInflateDistinctCountsAndVersionChangesStartFreshWindows() {
        assertThat(counter.count(rule,"IP:203.0.113.9","wallpaper-1")).isEqualTo(1);
        assertThat(counter.count(rule,"IP:203.0.113.9","wallpaper-1")).isEqualTo(1);
        assertThat(counter.count(rule,"IP:203.0.113.9","wallpaper-2")).isEqualTo(2);
        var changed=new RiskDtos.Rule(rule.key(),"测试","测试",true,true,List.of("ALL"),10,60,1);
        assertThat(counter.count(changed,"IP:203.0.113.9","wallpaper-2")).isEqualTo(1);
        counter.reset(rule,"IP:203.0.113.9");assertThat(counter.count(rule,"IP:203.0.113.9","wallpaper-2")).isEqualTo(1);
    }
}
