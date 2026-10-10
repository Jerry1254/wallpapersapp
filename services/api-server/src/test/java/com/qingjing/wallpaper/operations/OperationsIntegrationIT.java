package com.qingjing.wallpaper.operations;

import static org.assertj.core.api.Assertions.*;
import com.qingjing.wallpaper.redemption.AdminRedemptionViewService;
import com.qingjing.wallpaper.shared.web.ApiException;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.*;

@Testcontainers(disabledWithoutDocker=true)
class OperationsIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL=new MySQLContainer<>("mysql:8.4")
        .withDatabaseName("operations_test").withUsername("operations_test").withPassword(UUID.randomUUID().toString());
    static JdbcTemplate jdbc;
    static final Instant NOW=Instant.parse("2026-10-09T16:00:30Z");
    OperationsService service;
    static OperationsService at(Instant instant) { return new OperationsService(jdbc,Clock.fixed(instant,ZoneOffset.UTC)); }
    @BeforeAll static void database() {
        var source=new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate(); jdbc=new JdbcTemplate(source);
        jdbc.update("INSERT INTO asset(id,storage_key,original_filename,mime_type,file_extension,size_bytes,sha256,validation_status) VALUES(1,'test/operations.png','test.png','image/png','png',1,?,'READY')","a".repeat(64));
        jdbc.update("INSERT INTO category(id,level,name,slug,icon_asset_id) VALUES(1,1,'测试','operations',1)");
        jdbc.update("INSERT INTO category(id,parent_id,level,name,slug) VALUES(2,1,2,'子类','operations-child')");
        jdbc.update("INSERT INTO wallpaper(id,title,slug,category_id,cover_asset_id,copyright_note) VALUES(1,'测试壁纸','operations-test',2,1,'test')");
        device(1,"ANDROID","com.qingjing.bizhi",false);device(2,"ANDROID","com.jiyi.wallpaper",true);
        device(3,"IOS","com.qingjing.bizhi",false);device(4,"HARMONYOS","com.qingjing.bizhi",true);
        device(5,"H5_TEST","test",true);device(6,"IOS","com.qingjing.bizhi",true);
        device(7,"ANDROID","com.qingjing.bizhi.internal",true);device(8,"IOS","com.qingjing.livephotolab",true);
        jdbc.update("INSERT INTO ios_installation_acquisition(device_id,account_token,is_test_device) VALUES(6,?,TRUE)",UUID.randomUUID().toString());
    }
    static void device(long id,String platform,String scope,boolean today) {
        jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at,created_at) VALUES(?,?,?,?,?,?,?)",id,UUID.randomUUID().toString(),platform,scope,String.format("%064d",id),Timestamp.from(NOW),Timestamp.from(today?NOW:NOW.minus(Duration.ofDays(20))));
    }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM device_activity_daily");jdbc.update("DELETE FROM security_ban");
        jdbc.update("DELETE FROM device_download_request_event");
        jdbc.update("UPDATE anonymous_device SET last_active_at=NULL,operator_note='',operator_note_version=0,app_version_name=NULL,app_version_code=NULL");
        jdbc.update("UPDATE operations_tracking SET started_at=?",Timestamp.from(NOW.minus(Duration.ofDays(2)))); service=at(NOW);
    }
    static OperationsService.Activity info() { return new OperationsService.Activity("HUAWEI","test-model","6.0"); }
    @Test void countsFourEditionsInBeijingTimeAndExcludesTestingIdentities() {
        for(long id:List.of(1L,2L,3L,5L,6L,7L,8L)) service.recordActivity(id,info(),"1.0.3","10");
        at(NOW.minus(Duration.ofDays(12))).recordActivity(4,info(),null,null);
        var stats=service.overview(7,null);
        assertThat(stats.today()).isEqualTo(LocalDate.of(2026,10,10));
        assertThat(stats.totalUsers()).isEqualTo(4);assertThat(stats.newUsersToday()).isEqualTo(2);
        assertThat(stats.activeUsersToday()).isEqualTo(3);assertThat(stats.returningUsersToday()).isEqualTo(2);
        assertThat(stats.activeUsers7Days()).isEqualTo(3);assertThat(stats.activeUsers30Days()).isEqualTo(4);
        assertThat(stats.channels()).extracting(OperationsService.Channel::channel).containsExactlyInAnyOrder("ANDROID_ONLINE","ANDROID_OFFLINE","IOS","HARMONYOS");
        assertThat(stats.trend().get(0).activeUsers()).isNull();
        assertThat(stats.trend().get(6).newUsers()).isEqualTo(2);
        assertThat(stats.trend().get(6).activeUsers()).isEqualTo(3);
        var offline=service.overview(30,"ANDROID_OFFLINE");
        assertThat(offline.totalUsers()).isEqualTo(1);assertThat(offline.newUsersToday()).isEqualTo(1);assertThat(offline.activeUsersToday()).isEqualTo(1);
        assertThatThrownBy(()->service.overview(8,null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->service.overview(7,"IOS' OR 1=1")).isInstanceOf(ApiException.class);
    }
    @Test void concurrentReportsDeduplicateAndNeverMoveRecentActivityBackwards() throws Exception {
        var pool=Executors.newFixedThreadPool(5);
        try {
            List<Future<?>> tasks=new ArrayList<>();
            for(int i=0;i<15;i++) tasks.add(pool.submit(()->service.recordActivity(1,info(),"1.0.3","10")));
            for(var task:tasks) task.get(30,TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        at(NOW.minusSeconds(60)).recordActivity(1,info(),"1.0.2","9");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_activity_daily WHERE device_id=1",Long.class)).isEqualTo(2);
        assertThat(service.overview(7,null).activeUsersToday()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_active_at FROM anonymous_device WHERE id=1",Timestamp.class).toInstant()).isEqualTo(NOW);
        assertThat(jdbc.queryForObject("SELECT app_version_name FROM anonymous_device WHERE id=1",String.class)).isEqualTo("1.0.3");
        assertThat(jdbc.queryForObject("SELECT first_active_at FROM device_activity_daily WHERE device_id=1 AND activity_date='2026-10-10'",Timestamp.class).toInstant()).isEqualTo(NOW);
    }
    @Test void userFilteringMetadataAndNotesPreserveOptimisticVersions() {
        service.recordActivity(2,info(),"1.0.3","10");
        var note=service.updateNote(2,new OperationsService.NoteUpdate("  客服跟进  ",0));assertThat(note.note()).isEqualTo("客服跟进");
        assertThatThrownBy(()->service.updateNote(2,new OperationsService.NoteUpdate("旧备注",0))).isInstanceOfSatisfying(ApiException.class,e->assertThat(e.code()).isEqualTo("VERSION_CONFLICT"));
        jdbc.update("INSERT INTO security_ban(group_id,subject_type,subject_value,origin_device_id,rule_key,reason,banned_at) VALUES(?,'DEVICE','2',2,'MANUAL','test',?)",UUID.randomUUID().toString(),Timestamp.from(NOW));
        var views=new AdminRedemptionViewService(jdbc);
        var rows=views.devices(1,20,null,null,null,null,"ANDROID_OFFLINE","客服",true,true);
        assertThat(rows.items()).hasSize(1);var row=rows.items().get(0);
        assertThat(row.channel()).isEqualTo("ANDROID_OFFLINE");assertThat(row.model()).isEqualTo("test-model");assertThat(row.appVersionName()).isEqualTo("1.0.3");
        assertThat(row.bannedAt()).isEqualTo(NOW);assertThat(row.noteVersion()).isEqualTo(1);
        assertThat(views.device(2).note()).isEqualTo("客服跟进");
        assertThat(service.overview(7,null).bannedUsers()).isEqualTo(1);
        assertThat(views.devices(1,20,null,null,null,null,"IOS",null,true,null).items()).isEmpty();
    }
    @Test void issuedDownloadFactsIncludeFreeWallpaperAndDeduplicateTicketsWithoutSavingThem() {
        service.recordDownload(2,1,"test-ticket");service.recordDownload(2,1,"test-ticket");
        service.recordDownload(3,1,"another-ticket");service.recordDownload(6,1,"test-install-ticket");
        assertThat(service.overview(7,null).downloadRequestsToday()).isEqualTo(2);
        assertThat(service.overview(7,"ANDROID_OFFLINE").downloadRequestsToday()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM device_entitlement",Long.class)).isZero();
        assertThat(jdbc.queryForList("SELECT ticket_hash FROM device_download_request_event",String.class)).allSatisfy(hash->assertThat(hash).matches("[a-f0-9]{64}"));
    }
    @Test void devicePurchasesKeepEnvironmentRefundsRestorationAndMissingLegacyAmounts() {
        jdbc.update("DELETE FROM ios_credit_order_installation");jdbc.update("DELETE FROM ios_credit_order");jdbc.update("DELETE FROM ios_credit_account");jdbc.update("DELETE FROM ios_purchase_installation");jdbc.update("DELETE FROM ios_store_transaction");
        jdbc.update("INSERT INTO ios_credit_account(id,environment,bundle_id,app_transaction_id) VALUES(1,'PRODUCTION','com.qingjing.bizhi','test')");
        String order=UUID.randomUUID().toString();
        jdbc.update("INSERT INTO ios_credit_order(id,account_id,device_id,wallpaper_id,product_id,pack_credits,quantity,credits,amount,price_version,app_account_token,status,created_at,fulfilled_at) VALUES(?,1,6,1,'test-product',1,1,1,1,1,?,'FULFILLED',?,?)",order,UUID.randomUUID().toString(),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO ios_credit_order_installation(order_id,device_id,source_reference) VALUES(?,3,?)",order,"b".repeat(64));
        jdbc.update("INSERT INTO ios_store_transaction(environment,bundle_id,product_id,transaction_id,original_transaction_id,wallpaper_id,purchased_at,revoked_at,verified_at) VALUES('SANDBOX','com.qingjing.bizhi','old-product','old-transaction','original',1,?,?,?)",Timestamp.from(NOW.minus(Duration.ofDays(50))),Timestamp.from(NOW),Timestamp.from(NOW));
        jdbc.update("INSERT INTO ios_purchase_installation(environment,bundle_id,original_transaction_id,device_id,source_reference) VALUES('SANDBOX','com.qingjing.bizhi','original',3,?)","c".repeat(64));
        var result=service.purchases(3,1,1);assertThat(result.page().totalItems()).isEqualTo(2);assertThat(result.items().get(0).restored()).isTrue();assertThat(result.items().get(0).environment()).isEqualTo("PRODUCTION");
        var old=service.purchases(3,2,1).items().get(0);assertThat(old.status()).isEqualTo("REFUNDED");assertThat(old.environment()).isEqualTo("SANDBOX");assertThat(old.amount()).isNull();assertThat(old.restored()).isTrue();
        assertThat(service.purchases(1,1,20).items()).isEmpty();
        assertThatThrownBy(()->service.purchases(999,1,20)).isInstanceOf(ApiException.class);
    }
}
