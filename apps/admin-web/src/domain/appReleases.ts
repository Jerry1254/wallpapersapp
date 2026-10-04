export type AppReleasePlatform = 'android' | 'ios' | 'harmony';
export type AppReleaseApplication = AppReleasePlatform | 'jiyi';
export type AppReleaseStatus = 'DRAFT' | 'PUBLISHED' | 'DEPRECATED';
export type AndroidAppPackageName = 'com.qingjing.bizhi' | 'com.jiyi.wallpaper';
export const defaultAndroidAppPackage: AndroidAppPackageName = 'com.qingjing.bizhi';
export const androidAppLabels: Record<AndroidAppPackageName, string> = {
  'com.qingjing.bizhi': '倾境动态壁纸',
  'com.jiyi.wallpaper': '吉意壁纸（线下推广）'
};
export const appReleaseApplications: Record<AppReleaseApplication, { label: string; platform: AppReleasePlatform; packageName: AndroidAppPackageName }> = {
  android: { label: 'Android（倾境）', platform: 'android', packageName: defaultAndroidAppPackage },
  ios: { label: 'iOS', platform: 'ios', packageName: defaultAndroidAppPackage },
  harmony: { label: 'HarmonyOS', platform: 'harmony', packageName: defaultAndroidAppPackage },
  jiyi: { label: androidAppLabels['com.jiyi.wallpaper'], platform: 'android', packageName: 'com.jiyi.wallpaper' }
};

export interface AppRelease {
  id: string;
  platform: AppReleasePlatform;
  versionName: string;
  versionCode: number;
  releaseNotes: string;
  forceUpdate: boolean;
  status: AppReleaseStatus;
  deliveryType: 'apk' | 'store';
  storeUrl: string | null;
  packageName: string | null;
  sha256: string | null;
  fileSize: number | null;
  abi: string | null;
  minSdkVersion?: number | null;
  signerSha256: string | null;
  downloadUrl: string | null;
  createdAt: string;
  publishedAt: string | null;
}

export interface StoreReleaseInput {
  platform: 'ios' | 'harmony';
  versionName: string;
  versionCode: number;
  releaseNotes: string;
  storeUrl: string;
}

export interface AppReleaseUpdate {
  releaseNotes: string;
  forceUpdate: boolean;
  storeUrl: string | null;
}

export const appReleasePlatformLabels: Record<AppReleasePlatform, string> = {
  android: 'Android', ios: 'iOS', harmony: 'HarmonyOS'
};
export const appReleaseStatusLabels: Record<AppReleaseStatus, string> = {
  DRAFT: '草稿', PUBLISHED: '已发布', DEPRECATED: '已弃用'
};

export const compareAppReleaseVersions = (a: AppRelease, b: AppRelease) => {
  if (a.platform !== 'ios') return a.versionCode - b.versionCode;
  const left = a.versionName.split('.').map((part) => BigInt(part));
  const right = b.versionName.split('.').map((part) => BigInt(part));
  for (let index = 0; index < Math.max(left.length, right.length); index++) {
    const difference = (left[index] ?? BigInt(0)) - (right[index] ?? BigInt(0));
    if (difference !== BigInt(0)) return difference > BigInt(0) ? 1 : -1;
  }
  return 0;
};

export const appReleaseMatchesApplication = (release: AppRelease, platform: AppReleasePlatform, packageName: AndroidAppPackageName = defaultAndroidAppPackage) =>
  release.platform === platform && (platform !== 'android' || release.packageName === packageName);

export const effectiveAppReleasePolicy = (releases: AppRelease[], platform: AppReleasePlatform, packageName: AndroidAppPackageName = defaultAndroidAppPackage) => {
  const active = releases.filter((release) => appReleaseMatchesApplication(release, platform, packageName) && release.status === 'PUBLISHED')
    .sort((a, b) => compareAppReleaseVersions(b, a));
  return {
    latest: active[0] ?? null,
    minimum: active.find((release) => release.forceUpdate) ?? null
  };
};

export const validStoreRelease = (input: StoreReleaseInput): string | null => {
  if (input.versionName.trim().length > 64 || !/^\d+\.\d+(?:\.\d+)?$/.test(input.versionName.trim())) return '请输入真实商店版本号，例如 1.0.1';
  if (!Number.isSafeInteger(input.versionCode) || input.versionCode <= 0) return '请输入正整数版本编码';
  try {
    const url = new URL(input.storeUrl.trim());
    if (url.protocol !== 'https:' || url.username || url.password) return '请输入 HTTPS 应用商店链接';
    const host = input.platform === 'ios' ? 'apps.apple.com' : 'appgallery.huawei.com';
    if (url.hostname !== host) return input.platform === 'ios' ? '请填写 apps.apple.com 的应用详情链接' : '请填写 appgallery.huawei.com 的应用详情链接';
    if (url.href.includes('#') || (url.port !== '' && url.port !== '443') || url.pathname.length < 2) return '请填写有效的应用详情链接，不要包含锚点或自定义端口';
  } catch {
    return '请输入 HTTPS 应用商店链接';
  }
  if (!input.releaseNotes.trim()) return '请输入更新说明';
  if (input.releaseNotes.trim().length > 1000) return '更新说明不能超过 1000 字';
  return null;
};
