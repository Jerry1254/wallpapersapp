package com.qingjing.wallpaper.appupdate;

import com.qingjing.wallpaper.appupdate.AppReleaseDtos.CheckResult;
import com.qingjing.wallpaper.appupdate.AppReleaseDtos.ReleaseView;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;

/** The threshold is independent of the newest install target: version 3 may be forced while 4 is optional. */
final class AppUpdatePolicy {
    private AppUpdatePolicy() {}
    static CheckResult evaluate(String platform, String versionName, long versionCode,
            List<ReleaseView> releases, String abi, Integer androidSdk) {
        Comparator<ReleaseView> order = (a, b) -> AppVersionOrder.compare(platform, a.versionName(), a.versionCode(), b.versionName(), b.versionCode());
        List<ReleaseView> published = releases.stream().filter(r -> r.status().equals("PUBLISHED")).toList();
        ReleaseView latest = published.stream()
                .filter(release -> !platform.equals("android") || compatible(release, abi, androidSdk))
                .max(order).orElse(null);
        ReleaseView minimum = published.stream().filter(ReleaseView::forceUpdate).max(order).orElse(null);
        boolean available = latest != null && AppVersionOrder.compare(platform, versionName, versionCode, latest.versionName(), latest.versionCode()) < 0;
        // Never trap a device when every compatible installer predates the mandatory threshold.
        boolean mandatory = minimum != null && latest != null && order.compare(latest, minimum) >= 0
                && AppVersionOrder.compare(platform, versionName, versionCode, minimum.versionName(), minimum.versionCode()) < 0;
        return new CheckResult(platform, available, mandatory, minimum, latest, Instant.now());
    }
    static boolean compatible(ReleaseView release, String abi, Integer sdk) {
        boolean sdkCompatible = sdk == null || release.minSdkVersion() == null || sdk >= release.minSdkVersion();
        boolean abiCompatible = abi == null || release.abi() == null || release.abi().equals("universal")
                || java.util.Arrays.asList(release.abi().split(",")).contains(abi);
        return sdkCompatible && abiCompatible;
    }
}
