import { afterEach, expect, it, vi } from 'vitest';
import type { AppRelease } from '@/domain/appReleases';
import { setCsrfToken } from './apiClient';
import { appReleaseRepository } from './appReleaseRepository';

afterEach(() => vi.unstubAllGlobals());
const release = { id: '3', platform: 'android', packageName: 'com.qingjing.bizhi', versionName: '1.0.3', versionCode: 10023, status: 'DRAFT', forceUpdate: false, releaseNotes: '修复问题', storeUrl: null } as AppRelease;
const response = (value: unknown) => new Response(JSON.stringify({ data: value }), { headers: { 'Content-Type': 'application/json' } });

it('reads the platform list from the new API envelope', async () => {
  const fetchMock = vi.fn().mockResolvedValue(response({ items: [release] }));
  vi.stubGlobal('fetch', fetchMock);
  expect(await appReleaseRepository.list('android')).toEqual([release]);
  expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/admin/app-releases?platform=android&packageName=com.qingjing.bizhi');
});

it('selects the offline Android application while leaving store platform queries unchanged', async () => {
  const fetchMock = vi.fn().mockImplementation(async () => response({ items: [] }));
  vi.stubGlobal('fetch', fetchMock);
  await appReleaseRepository.list('android', 'com.jiyi.wallpaper');
  await appReleaseRepository.list('ios', 'com.jiyi.wallpaper');
  await appReleaseRepository.list('harmony', 'com.jiyi.wallpaper');
  expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([
    '/api/v1/admin/app-releases?platform=android&packageName=com.jiyi.wallpaper',
    '/api/v1/admin/app-releases?platform=ios',
    '/api/v1/admin/app-releases?platform=harmony'
  ]);
});

it('uploads the original APK for server metadata validation without client supplied version claims', async () => {
  setCsrfToken('test-csrf');
  const fetchMock = vi.fn().mockResolvedValue(response(release));
  vi.stubGlobal('fetch', fetchMock);
  const file = new File(['apk'], 'app-release.apk', { type: 'application/vnd.android.package-archive' });
  expect(await appReleaseRepository.uploadAndroid(file, ' 修复问题 ')).toEqual(release);
  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  const form = options.body as FormData;
  expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/admin/app-releases/android');
  expect(options.method).toBe('POST');
  expect(form.get('releaseNotes')).toBe('修复问题');
  expect(form.get('packageName')).toBe('com.qingjing.bizhi');
  expect((form.get('file') as File).name).toBe('app-release.apk');
  expect(form.has('versionCode')).toBe(false);
  expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('test-csrf');
});

it('sends the selected offline application with the APK so the server can reject a wrong upload', async () => {
  setCsrfToken('test-csrf');
  const offlineRelease = { ...release, packageName: 'com.jiyi.wallpaper' };
  const fetchMock = vi.fn().mockResolvedValue(response(offlineRelease));
  vi.stubGlobal('fetch', fetchMock);

  expect(await appReleaseRepository.uploadAndroid(new File(['apk'], 'jiyi-release.apk'), '吉意修复', 'com.jiyi.wallpaper'))
    .toEqual(offlineRelease);

  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  expect((options.body as FormData).get('packageName')).toBe('com.jiyi.wallpaper');
  expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('test-csrf');
});

it('refuses non APK or empty files before making requests', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  await expect(appReleaseRepository.uploadAndroid(new File(['x'], 'app.aab'), '修复')).rejects.toMatchObject({ code: 'APP_RELEASE_INVALID' });
  await expect(appReleaseRepository.uploadAndroid(new File([], 'empty.apk'), '修复')).rejects.toMatchObject({ code: 'APP_RELEASE_INVALID' });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('registers store versions as drafts and lets the server decide the final status', async () => {
  setCsrfToken('test-csrf');
  const fetchMock = vi.fn().mockResolvedValue(response({ ...release, platform: 'ios' }));
  vi.stubGlobal('fetch', fetchMock);
  await appReleaseRepository.createStore({ platform: 'ios', versionName: ' 1.0.3 ', versionCode: 10023, storeUrl: ' https://apps.apple.com/cn/app/id123 ', releaseNotes: ' 修复问题 ' });
  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  expect(JSON.parse(String(options.body))).toEqual({ platform: 'ios', versionName: '1.0.3', versionCode: 10023, storeUrl: 'https://apps.apple.com/cn/app/id123', releaseNotes: '修复问题' });
});

it('turns off force with an explicit false instead of retaining a separate minimum field', async () => {
  setCsrfToken('test-csrf');
  const fetchMock = vi.fn().mockResolvedValue(response({ ...release, forceUpdate: false }));
  vi.stubGlobal('fetch', fetchMock);
  await appReleaseRepository.update('3', { releaseNotes: '修复问题', forceUpdate: false, storeUrl: null });
  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  expect(fetchMock.mock.calls[0]?.[0]).toBe('/api/v1/admin/app-releases/3');
  expect(options.method).toBe('PUT');
  expect(JSON.parse(String(options.body))).toEqual({ releaseNotes: '修复问题', forceUpdate: false, storeUrl: null });
});

it('uses explicit publish and deprecate actions without deleting records', async () => {
  setCsrfToken('test-csrf');
  const fetchMock = vi.fn().mockImplementation(async () => response(release));
  vi.stubGlobal('fetch', fetchMock);
  await appReleaseRepository.publish('3');
  await appReleaseRepository.deprecate('3');
  expect(fetchMock.mock.calls.map(([url, options]) => [url, (options as RequestInit).method])).toEqual([
    ['/api/v1/admin/app-releases/3/publish', 'POST'], ['/api/v1/admin/app-releases/3/deprecate', 'POST']
  ]);
});
