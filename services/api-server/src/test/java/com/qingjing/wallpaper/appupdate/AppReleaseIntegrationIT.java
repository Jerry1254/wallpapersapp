package com.qingjing.wallpaper.appupdate;

import static org.assertj.core.api.Assertions.*;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.*;
import com.qingjing.wallpaper.asset.application.FileStorage;
import com.qingjing.wallpaper.asset.infrastructure.LocalFileStorage;
import com.qingjing.wallpaper.shared.web.ApiException;
import com.qingjing.wallpaper.device.DevicePrincipal;
import com.qingjing.wallpaper.device.DeviceDtos.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Exercises real Flyway/MySQL constraints and transactional policy changes in an isolated database. */
@Testcontainers(disabledWithoutDocker = true)
class AppReleaseIntegrationIT {
    @Container static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("app_updates_test").withUsername("updates_test").withPassword(UUID.randomUUID().toString());
    @TempDir static Path temp;
    static AnnotationConfigApplicationContext context;
    static AppReleaseService releases;
    static JdbcTemplate jdbc;
    @BeforeAll static void initialize() {
        var datasource = new DriverManagerDataSource(MYSQL.getJdbcUrl(),MYSQL.getUsername(),MYSQL.getPassword());
        Flyway.configure().dataSource(datasource).locations("classpath:db/migration").load().migrate();
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class,() -> datasource);
        context.registerBean(FileStorage.class,() -> new LocalFileStorage(temp.resolve("storage")));
        context.register(TestConfig.class);
        context.refresh();
        releases = context.getBean(AppReleaseService.class);
        jdbc = context.getBean(JdbcTemplate.class);
        jdbc.update("INSERT INTO admin_account(id,username,password_hash,password_changed_at) VALUES(1,'update-test','unused-test-password-hash',UTC_TIMESTAMP(6))");
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void clear() { jdbc.update("DELETE FROM app_release"); jdbc.update("DELETE FROM anonymous_device"); }
    @Configuration(proxyBeanMethods=false)
    @EnableTransactionManagement
    static class TestConfig {
        @Bean JdbcTemplate jdbc(DataSource datasource) { return new JdbcTemplate(datasource); }
        @Bean PlatformTransactionManager transactions(DataSource datasource) { return new DataSourceTransactionManager(datasource); }
        @Bean AndroidApkInspector inspector(FileStorage storage) { return new AndroidApkInspector(storage,"com.qingjing.bizhi"); }
        @Bean AppReleaseService releases(JdbcTemplate jdbc,FileStorage storage,AndroidApkInspector inspector,PlatformTransactionManager tx) {
            return new AppReleaseService(jdbc,storage,inspector,tx);
        }
    }
    @Test void publishCloseForceAndDeprecateFollowTheAgreedSequenceWithoutCrossPlatformInterference() {
        var two = store("harmony","2.0",2);
        var three = store("harmony","3.0",3);
        var four = store("harmony","4.0",4);
        assertThat(releases.check("harmony","1.0",1,null,null).updateAvailable()).isFalse();
        releases.publish(two.id()); releases.publish(three.id()); releases.publish(four.id());
        releases.update(three.id(),new UpdateReleaseRequest("重要修复",true,three.storeUrl()));
        var forced = releases.check("harmony","1.0",1,null,null);
        assertThat(forced.mandatory()).isTrue();
        assertThat(forced.minimumVersion().id()).isEqualTo(three.id());
        assertThat(forced.latestVersion().id()).isEqualTo(four.id());
        assertThat(releases.check("harmony","3.0",3,null,null).mandatory()).isFalse();
        assertThat(releases.check("ios","1.0",1,null,null).mandatory()).isFalse();
        assertThatThrownBy(() -> releases.requireSupported("harmony",null,null,null,null)).isInstanceOf(ApiException.class)
                .satisfies(error -> assertThat(((ApiException)error).code()).isEqualTo("APP_UPDATE_REQUIRED"));
        releases.update(three.id(),new UpdateReleaseRequest("普通更新",false,three.storeUrl()));
        assertThat(releases.check("harmony","1.0",1,null,null).mandatory()).isFalse();
        releases.deprecate(two.id()); releases.deprecate(three.id()); releases.deprecate(four.id());
        assertThat(releases.check("harmony","1.0",1,null,null).updateAvailable()).isFalse();
        assertThat(releases.list("harmony")).allMatch(view -> view.status().equals("DEPRECATED"));
        assertThatThrownBy(() -> releases.publish(three.id())).isInstanceOf(ApiException.class);
    }
    @Test void iosVersionIdentityIsNormalizedAndOlderDraftsCannotRollbackPublishedVersions() {
        var one = store("ios","1.0",1);
        assertThatThrownBy(() -> store("ios","1.0.0",2)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        var two = store("ios","2.0",2);
        releases.publish(two.id());
        assertThatThrownBy(() -> releases.publish(one.id())).isInstanceOf(ApiException.class).hasMessageContaining("increase");
        assertThat(releases.list("ios").stream().filter(r -> r.id().equals(one.id())).findFirst().orElseThrow().status()).isEqualTo("DRAFT");
    }
    @Test void iosCanRestartOrReuseBuildNumbersInANewerMarketingVersion() {
        var first = store("ios","1.0.0",10022);
        releases.publish(first.id());
        var newer = store("ios","1.0.1",1);
        releases.publish(newer.id());
        var newest = store("ios","1.1.0",1);
        releases.publish(newest.id());
        assertThat(releases.check("ios","1.0.0",10022,null,null).latestVersion().id()).isEqualTo(newest.id());
    }
    @Test void scopeComesFromAuthenticatedDeviceAndInternalToolsAreNotBlockedByTheRetailPolicy() {
        var release = store("ios","2.0",2);
        releases.publish(release.id());
        releases.update(release.id(),new UpdateReleaseRequest("强制更新",true,release.storeUrl()));
        for (long id : new long[]{70001,70002}) {
            jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,'IOS',?,?,UTC_TIMESTAMP(6))",
                    id,UUID.randomUUID().toString(),id == 70001 ? "com.qingjing.bizhi" : "com.qingjing.livephotolab","a".repeat(64));
        }
        assertThatThrownBy(() -> releases.requireForDevice(new DevicePrincipal(70001,"retail",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY),null,null,null,null))
                .isInstanceOf(ApiException.class).satisfies(error -> assertThat(((ApiException)error).code()).isEqualTo("APP_UPDATE_REQUIRED"));
        assertThatCode(() -> releases.requireForDevice(new DevicePrincipal(70002,"lab",DevicePlatform.IOS,CredentialType.PLATFORM_PUBLIC_KEY),null,null,null,null))
                .doesNotThrowAnyException();
        assertThat(releases.check("ios","1.0",1,null,null,"com.qingjing.livephotolab").updateAvailable()).isFalse();
    }
    @Test void actualApkUploadIsDraftUntilPublishedAndSigningIdentityCannotChange() throws Exception {
        var signer = ApkTestFixtures.signer();
        var one = upload("unsigned-release.apk.fixture",signer);
        assertThat(one.versionCode()).isEqualTo(100);
        assertThat(one.forceUpdate()).isFalse();
        assertThat(one.status()).isEqualTo("DRAFT");
        assertThat(one.downloadUrl()).isNull();
        assertThatThrownBy(() -> releases.downloadable(one.id())).isInstanceOf(ApiException.class);
        var published = releases.publish(one.id());
        assertThat(published.downloadUrl()).isEqualTo("/api/v1/app-updates/packages/"+one.id());
        try (var content = releases.openPackage(one.id())) {
            assertThat(content.inputStream().readAllBytes()).hasSize(one.fileSize().intValue());
        }
        var two = upload("unsigned-release-v2.apk.fixture",ApkTestFixtures.signer());
        assertThatThrownBy(() -> releases.publish(two.id())).isInstanceOf(ApiException.class).hasMessageContaining("signing certificate");
        releases.deprecate(one.id());
        assertThatThrownBy(() -> releases.openPackage(one.id())).isInstanceOf(ApiException.class);
    }
    @Test void twoAndroidBrandsHaveIndependentSignedInstallersVersionsAndMandatoryThresholds() throws Exception {
        var online = upload("unsigned-release.apk.fixture",ApkTestFixtures.signer());
        releases.publish(online.id());
        var signer = ApkTestFixtures.signer();
        var offlineOne = uploadOffline("unsigned-release.apk.fixture",signer);
        releases.publish(offlineOne.id());
        var offlineTwo = uploadOffline("unsigned-release-v2.apk.fixture",signer);
        releases.publish(offlineTwo.id());
        releases.update(offlineTwo.id(),new UpdateReleaseRequest("线下重要修复",true,null));
        assertThat(online.versionCode()).isEqualTo(offlineOne.versionCode());
        assertThat(releases.list("android")).extracting(ReleaseView::id).containsExactly(online.id());
        assertThat(releases.list("android","com.jiyi.wallpaper")).hasSize(2);
        var offlineCheck = releases.check("android","1.0.0",100,"arm64-v8a",36,"com.jiyi.wallpaper");
        assertThat(offlineCheck.mandatory()).isTrue();
        assertThat(offlineCheck.latestVersion().packageName()).isEqualTo("com.jiyi.wallpaper");
        assertThat(releases.check("android","1.0.0",100,"arm64-v8a",36,"com.qingjing.bizhi").mandatory()).isFalse();
        assertThat(releases.check("ios","1.0.0",1,null,null,"com.jiyi.wallpaper").updateAvailable()).isFalse();
        for (long id : new long[]{80001,80002}) {
            jdbc.update("INSERT INTO anonymous_device(id,public_id,platform,app_install_scope,evidence_hash,last_seen_at) VALUES(?,?,'ANDROID',?,?,UTC_TIMESTAMP(6))",
                    id,UUID.randomUUID().toString(),id==80001?"com.qingjing.bizhi":"com.jiyi.wallpaper","b".repeat(64));
        }
        assertThatCode(() -> releases.requireForDevice(new DevicePrincipal(80001,"online",DevicePlatform.ANDROID,CredentialType.PLATFORM_PUBLIC_KEY),"1.0.0","100","arm64-v8a","36")).doesNotThrowAnyException();
        assertThatThrownBy(() -> releases.requireForDevice(new DevicePrincipal(80002,"offline",DevicePlatform.ANDROID,CredentialType.PLATFORM_PUBLIC_KEY),"1.0.0","100","arm64-v8a","36"))
                .isInstanceOf(ApiException.class).satisfies(error -> assertThat(((ApiException)error).code()).isEqualTo("APP_UPDATE_REQUIRED"));
        releases.deprecate(offlineTwo.id());
        assertThat(releases.check("android","1.0.0",100,null,null,"com.jiyi.wallpaper").mandatory()).isFalse();
    }
    @Test void uploadRejectsAnApkForTheOtherSelectedBrand() throws Exception {
        var signed = ApkTestFixtures.signed(temp,"unsigned-release.apk.fixture",ApkTestFixtures.signer(),"com.jiyi.wallpaper");
        var file = new MockMultipartFile("file","jiyi.apk","application/vnd.android.package-archive",Files.readAllBytes(signed));
        assertThatThrownBy(() -> releases.uploadAndroid(file,"更新",1,"com.qingjing.bizhi"))
                .isInstanceOf(ApiException.class).hasMessageContaining("selected App");
        assertThat(releases.list("android")).isEmpty();
        assertThat(releases.list("android","com.jiyi.wallpaper")).isEmpty();
    }
    private ReleaseView uploadOffline(String fixture,ApkTestFixtures.Signer signer) throws Exception {
        Path signed = ApkTestFixtures.signed(temp,fixture,signer,"com.jiyi.wallpaper");
        return releases.uploadAndroid(new MockMultipartFile("file","jiyi.apk","application/vnd.android.package-archive",Files.readAllBytes(signed)),"线下更新",1,"com.jiyi.wallpaper");
    }
    private ReleaseView store(String platform,String name,long code) {
        return releases.createStore(new StoreReleaseRequest(platform,name,code,"版本更新",platform.equals("ios") ? "https://apps.apple.com/app/id123" : "https://appgallery.huawei.com/app/C123"),1);
    }
    private ReleaseView upload(String fixture,ApkTestFixtures.Signer signer) throws Exception {
        Path signed = ApkTestFixtures.signed(temp,fixture,signer);
        return releases.uploadAndroid(new MockMultipartFile("file","test-release.apk","application/vnd.android.package-archive",Files.readAllBytes(signed)),"正式更新",1);
    }
}
