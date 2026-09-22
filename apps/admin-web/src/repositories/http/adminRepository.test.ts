import { afterEach, expect, it, vi } from 'vitest';
import type { Wallpaper, WallpaperTutorial } from '@/domain/admin';
import { setCsrfToken } from './apiClient';
import { adminRepository } from './adminRepository';

afterEach(() => { vi.unstubAllGlobals(); });

it('rejects a 4D wallpaper without the fixed ZIP before making a request', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  const wallpaper = { capabilities: ['android_parallax'], resources: {}, variants: [] } as unknown as Wallpaper;
  await expect(adminRepository.saveWallpaper(wallpaper, true)).rejects.toMatchObject({
    code: 'PARALLAX_PACKAGE_REQUIRED',
    message: '请上传 4D 固定资源包'
  });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('rejects a 4D wallpaper without an independent list cover before making a request', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  const wallpaper = {
    capabilities: ['android_parallax'],
    resources: { parallaxPackage: { name: 'wallpaper.zip', size: 3, mime: 'application/zip', nativeFile: new File(['zip'], 'wallpaper.zip') } },
    variants: []
  } as unknown as Wallpaper;
  await expect(adminRepository.saveWallpaper(wallpaper, true)).rejects.toMatchObject({
    code: 'ASSET_NOT_READY',
    message: '请单独上传列表封面'
  });
  expect(fetchMock).not.toHaveBeenCalled();
});

it('requests only free wallpapers when the management filter is selected', async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
    items: [], page: { page: 1, pageSize: 100, totalItems: 0, totalPages: 0 }
  }), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetchMock);

  await adminRepository.wallpapers({ accessType: 'FREE' });

  expect(String(fetchMock.mock.calls[0]?.[0])).toContain('/admin/wallpapers?page=1&pageSize=100&accessType=FREE');
});

it('updates a fixed tutorial slot with optimistic locking', async () => {
  setCsrfToken('csrf-token');
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
    key: 'ANDROID_DYNAMIC',
    title: '动态壁纸教程',
    platform: 'ANDROID',
    wallpaperKind: 'DYNAMIC',
    enabled: true,
    sortOrder: 20,
    video: {
      id: '902', originalFilename: 'dynamic.mp4', mimeType: 'video/mp4', sizeBytes: 1200,
      validationStatus: 'READY', previewUrl: '/api/v1/admin/assets/902/content'
    },
    updatedAt: '2026-09-15T12:00:00Z',
    version: 4
  }), { headers: { 'Content-Type': 'application/json', ETag: '"4"' } }));
  vi.stubGlobal('fetch', fetchMock);
  const tutorial = {
    key: 'ANDROID_DYNAMIC', title: '动态壁纸教程', platform: 'ANDROID', wallpaperKind: 'DYNAMIC',
    enabled: true, sortOrder: 20, version: 3,
    video: { name: 'dynamic.mp4', size: 1200, mime: 'video/mp4', assetId: '902' }
  } as WallpaperTutorial;

  const saved = await adminRepository.saveTutorial(tutorial);

  expect(saved.version).toBe(4);
  expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/v1/admin/wallpaper-tutorials/ANDROID_DYNAMIC');
  const options = fetchMock.mock.calls[0]?.[1] as RequestInit;
  expect(options.method).toBe('PUT');
  expect(new Headers(options.headers).get('If-Match')).toBe('"3"');
  expect(JSON.parse(String(options.body))).toMatchObject({ videoAssetId: '902', enabled: true, sortOrder: 20 });
});
