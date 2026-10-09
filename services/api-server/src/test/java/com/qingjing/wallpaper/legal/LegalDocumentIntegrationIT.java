package com.qingjing.wallpaper.legal;

import static com.qingjing.wallpaper.legal.LegalDocumentDtos.*;
import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker = true)
class LegalDocumentIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("legal_test").withUsername("legal_test").withPassword(UUID.randomUUID().toString());
    static AnnotationConfigApplicationContext context;
    static LegalDocumentService service;
    static JdbcTemplate jdbc;
    @BeforeAll static void setup() {
        var ds = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(ds).locations("classpath:db/migration").load().migrate();
        context=new AnnotationConfigApplicationContext(); context.registerBean(DataSource.class,()->ds);
        context.register(Config.class);context.refresh();service=context.getBean(LegalDocumentService.class);jdbc=context.getBean(JdbcTemplate.class);
    }
    @AfterAll static void close() { if(context!=null) context.close(); }
    @Configuration(proxyBeanMethods=false) @EnableTransactionManagement static class Config {
        @Bean JdbcTemplate jdbc(DataSource ds) { return new JdbcTemplate(ds); }
        @Bean PlatformTransactionManager tx(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean LegalDocumentService legal(JdbcTemplate jdbc) { return new LegalDocumentService(jdbc,new ObjectMapper()); }
    }
    @Test void draftsPublicationConsentAndOptimisticLocksRemainConsistent() {
        var initial=service.published();
        assertThat(initial.consentVersion()).isEqualTo("privacy:1|terms:1");
        assertThat(initial.items()).hasSize(2);
        assertThat(initial.items().get(0).content().sections()).anyMatch(s->s.body().contains("匿名请求公开协议文本"));
        var admin=service.list().items().get(0);
        var ordinary=new Content(admin.draft().title(),"2026年10月9日","修订文字",List.of(new Section("说明","不含 HTML 执行")));
        var saved=service.save("privacy",admin.version(),new WriteRequest(ordinary,false));
        assertThat(service.published()).isEqualTo(initial);
        assertThatThrownBy(()->service.publish("privacy",admin.version())).isInstanceOf(ApiException.class)
            .satisfies(e->assertThat(((ApiException)e).code()).isEqualTo("VERSION_CONFLICT"));
        var published=service.publish("privacy",saved.version());
        assertThat(published.published().content()).isEqualTo(ordinary);
        assertThat(published.published().revision()).isEqualTo(2);
        assertThat(service.published().consentVersion()).isEqualTo(initial.consentVersion());
        var important=new Content(ordinary.title(),ordinary.effectiveDate(),"重大隐私变更",ordinary.sections());
        saved=service.save("privacy",published.version(),new WriteRequest(important,true));
        published=service.publish("privacy",saved.version());
        assertThat(service.published().consentVersion()).isEqualTo("privacy:2|terms:1");
        assertThat(published.requiresReconsent()).isFalse();
        assertThat(service.publish("privacy",published.version())).isEqualTo(published);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM legal_document_publication WHERE document_key='privacy'",Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT content_json FROM legal_document_publication WHERE document_key='privacy' AND revision=1",String.class)).contains("重要提示");
        assertThatThrownBy(()->service.save("other",0,new WriteRequest(ordinary,false))).isInstanceOf(ApiException.class);
    }
}
