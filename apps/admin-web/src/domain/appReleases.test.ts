import { describe, expect, it } from 'vitest';
import type { AppRelease } from './appReleases';
import { appReleaseMatchesApplication, compareAppReleaseVersions, defaultAndroidAppPackage, effectiveAppReleasePolicy, validStoreRelease } from './appReleases';

const release = (versionCode: number, forceUpdate = false, platform: AppRelease['platform'] = 'android', status: AppRelease['status'] = 'PUBLISHED') => ({
  id: String(versionCode), versionName: `${versionCode}.0`, versionCode, platform, status, forceUpdate,
  packageName: platform === 'android' ? defaultAndroidAppPackage : null
} as AppRelease);

describe('effective platform release policy', () => {
  it('keeps the forced floor separate from the latest ordinary update', () => {
    const result = effectiveAppReleasePolicy([release(2), release(3, true), release(4)], 'android');
    expect(result.minimum?.versionCode).toBe(3);
    expect(result.latest?.versionCode).toBe(4);
  });
  it('removes deprecated releases from both the target and the forced floor', () => {
    const result = effectiveAppReleasePolicy([release(2, true, 'android', 'DEPRECATED'), release(3, true, 'android', 'DEPRECATED'), release(4, true, 'android', 'DEPRECATED')], 'android');
    expect(result).toEqual({ latest: null, minimum: null });
  });
  it('disabling the final force rule removes the floor while preserving optional updates', () => {
    const result = effectiveAppReleasePolicy([release(2), release(3, false), release(4)], 'android');
    expect(result.minimum).toBeNull();
    expect(result.latest?.versionCode).toBe(4);
  });
  it('separates platforms and ignores unpublished drafts', () => {
    const result = effectiveAppReleasePolicy([release(3, true), release(9, true, 'ios'), release(10, true, 'android', 'DRAFT')], 'android');
    expect(result.minimum?.versionCode).toBe(3);
    expect(result.latest?.versionCode).toBe(3);
  });
  it('uses the highest remaining forced floor', () => {
    const result = effectiveAppReleasePolicy([release(2, true), release(3, true, 'android', 'DEPRECATED'), release(4)], 'android');
    expect(result.minimum?.versionCode).toBe(2);
    expect(result.latest?.versionCode).toBe(4);
  });
  it('orders iOS by numeric store version rather than build number or lexical order', () => {
    const result = effectiveAppReleasePolicy([
      { ...release(10022, true, 'ios'), versionName: '1.0.0' },
      { ...release(1, false, 'ios'), versionName: '1.0.1' },
      { ...release(9, true, 'ios'), versionName: '1.9' },
      { ...release(2, false, 'ios'), versionName: '1.10' }
    ], 'ios');
    expect(result.minimum?.versionName).toBe('1.9');
    expect(result.latest?.versionName).toBe('1.10');
  });
  it('preserves exact iOS ordering for version segments beyond JavaScript number precision', () => {
    const smaller = { ...release(10022, true, 'ios'), versionName: '1.9007199254740992' };
    const larger = { ...release(1, false, 'ios'), versionName: '1.9007199254740993' };
    expect(compareAppReleaseVersions(larger, smaller)).toBeGreaterThan(0);
    expect(compareAppReleaseVersions({ ...smaller, versionName: '001.09007199254740992.0' }, smaller)).toBe(0);
    const result = effectiveAppReleasePolicy([smaller, larger], 'ios');
    expect(result.latest?.versionName).toBe(larger.versionName);
    expect(result.minimum?.versionName).toBe(smaller.versionName);
  });
  it('keeps the two Android applications latest versions and forced floors independent', () => {
    const releases = [
      release(10, true), release(20),
      { ...release(100, true), packageName: 'com.jiyi.wallpaper' },
      { ...release(200), packageName: 'com.jiyi.wallpaper' },
      { ...release(999, true), packageName: 'com.unknown.app' }
    ];
    const online = effectiveAppReleasePolicy(releases, 'android', defaultAndroidAppPackage);
    const offline = effectiveAppReleasePolicy(releases, 'android', 'com.jiyi.wallpaper');
    expect(online.minimum?.versionCode).toBe(10);
    expect(online.latest?.versionCode).toBe(20);
    expect(offline.minimum?.versionCode).toBe(100);
    expect(offline.latest?.versionCode).toBe(200);
    expect(appReleaseMatchesApplication(releases[0]!, 'android', 'com.jiyi.wallpaper')).toBe(false);
    expect(appReleaseMatchesApplication(releases[2]!, 'android', 'com.jiyi.wallpaper')).toBe(true);
  });
  it('leaves iOS and Harmony policies independent of the Android application selection', () => {
    const releases = [release(8, true, 'ios'), release(9, true, 'harmony')];
    expect(effectiveAppReleasePolicy(releases, 'ios', 'com.jiyi.wallpaper').minimum?.versionCode).toBe(8);
    expect(effectiveAppReleasePolicy(releases, 'harmony', 'com.jiyi.wallpaper').minimum?.versionCode).toBe(9);
  });
});

it('rejects unusable store releases before submission', () => {
  const valid = { platform: 'ios' as const, versionName: '1.0.1', versionCode: 10022, storeUrl: 'https://apps.apple.com/cn/app/id123', releaseNotes: '修复问题' };
  expect(validStoreRelease(valid)).toBeNull();
  expect(validStoreRelease({ ...valid, versionCode: 0 })).toBeTruthy();
  expect(validStoreRelease({ ...valid, versionCode: 3.1 })).toBeTruthy();
  expect(validStoreRelease({ ...valid, storeUrl: 'http://example.com' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, storeUrl: 'https://user:password@example.com' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, storeUrl: 'https://appstoreconnect.apple.com/apps' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, platform: 'harmony', storeUrl: 'https://appgallery.huawei.com/app/C123' })).toBeNull();
  expect(validStoreRelease({ ...valid, platform: 'harmony', storeUrl: valid.storeUrl })).toBeTruthy();
  expect(validStoreRelease({ ...valid, versionName: 'version x' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, versionName: '1' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, versionName: '1.0.0.0' })).toBeTruthy();
  expect(validStoreRelease({ ...valid, versionName: `${'1'.repeat(64)}.0` })).toBeTruthy();
  for (const storeUrl of ['https://apps.apple.com/', 'https://apps.apple.com', 'https://apps.apple.com:8443/app/id123', 'https://apps.apple.com/app/id123#details', 'https://apps.apple.com/app/id123#']) {
    expect(validStoreRelease({ ...valid, storeUrl })).toBeTruthy();
  }
  expect(validStoreRelease({ ...valid, storeUrl: 'https://apps.apple.com:443/app/id123' })).toBeNull();
});
