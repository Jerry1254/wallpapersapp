import { bundleManager } from '@kit.AbilityKit';

// Every device request reports the installed bundle, including registration and download requests.
export class InstalledAppVersion {
  private static bundle: bundleManager.BundleInfo | null = null;

  static info(): bundleManager.BundleInfo {
    if (InstalledAppVersion.bundle === null) {
      InstalledAppVersion.bundle = bundleManager.getBundleInfoForSelfSync(
        bundleManager.BundleFlag.GET_BUNDLE_INFO_DEFAULT);
    }
    return InstalledAppVersion.bundle as bundleManager.BundleInfo;
  }

  static headers(): Record<string, string> {
    const info: bundleManager.BundleInfo = InstalledAppVersion.info();
    return {
      'X-App-Platform': 'HARMONYOS',
      'X-App-Version-Name': info.versionName,
      'X-App-Version-Code': info.versionCode.toString()
    };
  }
}

export class AppUpdateEvents {
  static onRequired: (() => void) | null = null;

  static notifyRequired(): void {
    if (AppUpdateEvents.onRequired !== null) (AppUpdateEvents.onRequired as () => void)();
  }
}
