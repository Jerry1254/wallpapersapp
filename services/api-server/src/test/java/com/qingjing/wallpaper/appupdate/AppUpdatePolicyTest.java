package com.qingjing.wallpaper.appupdate;

import static org.assertj.core.api.Assertions.*;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.ReleaseView;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AppUpdatePolicyTest {
    static ReleaseView release(String platform, String name, long code, boolean force, String status) {
        return new ReleaseView(Long.toString(code), platform, name, code, "更新说明", force, status,
                platform.equals("android") ? "apk" : "store", platform.equals("android") ? null : "https://apps.apple.com/app/id123",
                platform.equals("android") ? "com.qingjing.bizhi" : null, null, null,
                platform.equals("android") ? "arm64-v8a" : null, null, platform.equals("android") ? 24 : null,
                null, Instant.EPOCH, status.equals("DRAFT") ? null : Instant.EPOCH);
    }
    @Test void forcedThresholdAndLatestTargetAreSeparate() {
        var rows = List.of(release("android","2",2,false,"PUBLISHED"),release("android","3",3,true,"PUBLISHED"),release("android","4",4,false,"PUBLISHED"));
        for (long current : new long[]{1,2}) {
            var check = AppUpdatePolicy.evaluate("android",""+current,current,rows,"arm64-v8a",35);
            assertThat(check.mandatory()).isTrue();
            assertThat(check.minimumVersion().versionCode()).isEqualTo(3);
            assertThat(check.latestVersion().versionCode()).isEqualTo(4);
        }
        var supported = AppUpdatePolicy.evaluate("android","3",3,rows,null,null);
        assertThat(supported.mandatory()).isFalse();
        assertThat(supported.updateAvailable()).isTrue();
        assertThat(AppUpdatePolicy.evaluate("android","4",4,rows,null,null).updateAvailable()).isFalse();
    }
    @Test void draftAndDeprecatedReleasesNeverTriggerOrBecomeTargets() {
        var rows = List.of(release("harmony","2",2,true,"DEPRECATED"),release("harmony","3",3,true,"DEPRECATED"),release("harmony","4",4,true,"DRAFT"));
        var result = AppUpdatePolicy.evaluate("harmony","1",1,rows,null,null);
        assertThat(result.mandatory()).isFalse();
        assertThat(result.updateAvailable()).isFalse();
        assertThat(result.latestVersion()).isNull();
        assertThat(result.minimumVersion()).isNull();
    }
    @Test void disablingOneThresholdRetainsOtherPublishedForcedVersions() {
        var rows = List.of(release("harmony","2",2,true,"PUBLISHED"),release("harmony","3",3,false,"PUBLISHED"),release("harmony","4",4,false,"PUBLISHED"));
        assertThat(AppUpdatePolicy.evaluate("harmony","1",1,rows,null,null).mandatory()).isTrue();
        assertThat(AppUpdatePolicy.evaluate("harmony","2",2,rows,null,null).mandatory()).isFalse();
    }
    @Test void iosComparesNumericMarketingVersionsAndIgnoresBuildAtSameMarketingVersion() {
        var rows = List.of(release("ios","1.10",20,true,"PUBLISHED"));
        assertThat(AppUpdatePolicy.evaluate("ios","1.9",19,rows,null,null).mandatory()).isTrue();
        assertThat(AppUpdatePolicy.evaluate("ios","1.10.0",1,rows,null,null).mandatory()).isFalse();
        assertThat(AppUpdatePolicy.evaluate("ios","1.10.0",1,rows,null,null).updateAvailable()).isFalse();
        assertThat(AppVersionOrder.normalize("001.010.0")).isEqualTo("1.10");
    }
    @Test void higherIncompatibleApkCannotForceAnUnreachableTarget() {
        var compatible = release("android","2",2,false,"PUBLISHED");
        var forced64 = release("android","3",3,true,"PUBLISHED");
        var x86 = new ReleaseView("4","android","4",4,"更新",false,"PUBLISHED","apk",null,"com.qingjing.bizhi",null,null,"x86",null,35,null,Instant.EPOCH,Instant.EPOCH);
        var result = AppUpdatePolicy.evaluate("android","1",1,List.of(compatible,forced64,x86),"arm64-v8a",34);
        assertThat(result.latestVersion().versionCode()).isEqualTo(3);
        assertThat(result.mandatory()).isTrue();
        assertThat(AppUpdatePolicy.evaluate("android","1",1,List.of(compatible,forced64,x86),"x86",24).mandatory()).isFalse();
        assertThat(AppUpdatePolicy.evaluate("android","1",1,List.of(compatible,forced64,x86),"arm64-v8a",23).latestVersion()).isNull();
    }
    @Test void invalidStoreHostsAndVersionStringsAreRejected() {
        assertThatThrownBy(() -> AppReleaseService.validateStoreUrl("ios","https://apps.apple.com.evil.test/app/123")).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> AppReleaseService.validateStoreUrl("harmony","https://u:p@appgallery.huawei.com/app/123")).hasMessageContaining("HTTPS");
        assertThatThrownBy(() -> AppVersionOrder.normalize("1.0-beta")).hasMessageContaining("numeric");
        assertThatCode(() -> AppReleaseService.validateStoreUrl("harmony","https://appgallery.huawei.com/app/C123")).doesNotThrowAnyException();
    }
}
