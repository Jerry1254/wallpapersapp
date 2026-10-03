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

it('keeps the Apple price read-only and omits it from admin writes', async () => {
  setCsrfToken('csrf-token');
  const config = {
    productId: 'com.example.wallpaper.1', chinaReferencePrice: '1.00',
    enabled: true, firstFreeEligible: true, productIdLocked: true,
    verifiedTransactionAt: '2026-10-03T00:00:00Z'
  };
  const detail = {
    id: '1', title: '壁纸', slug: 'wallpaper-test', accessType: 'REDEEM',
    rootCategory: { id: '1', name: '风景', slug: 'scenery' }, childCategory: null,
    cover: { id: '2', originalFilename: 'cover.png', mimeType: 'image/png', sizeBytes: 128, validationStatus: 'READY' },
    copyrightNote: '平台内容', variants: [], status: 'DRAFT', sortOrder: 1,
    version: 1, updatedAt: '2026-10-03T00:00:00Z', iosAcquisition: config
  };
  const fetchMock = vi.fn().mockImplementation(async (url: string, options: RequestInit) => {
    if (url.endsWith('/ios-acquisition')) {
      Object.assign(config, JSON.parse(String(options.body)));
      return new Response(JSON.stringify(config), { headers: { 'Content-Type': 'application/json' } });
    }
    return new Response(JSON.stringify(detail), { headers: { 'Content-Type': 'application/json' } });
  });
  vi.stubGlobal('fetch', fetchMock);
  const wallpaper = {
    id: '1', title: '壁纸', slug: 'wallpaper-test', accessType: 'REDEEM', categoryId: '1',
    sort: 1, copyrightNote: '平台内容', version: 1, capabilities: [], variants: [],
    resources: { cover: { assetId: '2', name: 'cover.png', size: 128, mime: 'image/png' } },
    iosAcquisition: { ...config, chinaReferencePrice: '18.8' }
  } as unknown as Wallpaper;

  const saved = await adminRepository.saveWallpaper(wallpaper, false);

  expect(saved.iosAcquisition?.chinaReferencePrice).toBe('1.00');
  expect(saved.iosAcquisition?.productIdLocked).toBe(true);
  const [, options] = fetchMock.mock.calls.find(([url]) => String(url).endsWith('/ios-acquisition'))!;
  expect(options.method).toBe('PUT');
  expect(JSON.parse(String(options.body))).toMatchObject({
    productId: config.productId, enabled: true
  });
  expect(JSON.parse(String(options.body))).not.toHaveProperty('chinaReferencePrice');
  expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-token');
});

it('synchronizes Apple prices through an authenticated CSRF-protected POST without an amount', async () => {
  setCsrfToken('csrf-token');
  const result = { productId: 'test.product', chinaReferencePrice: '6.00', priceSyncStatus: 'READY' };
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(result), { headers: { 'Content-Type': 'application/json' } }));
  vi.stubGlobal('fetch', fetchMock);
  expect(await adminRepository.syncIosPrice('1')).toEqual(result);
  const [url, options] = fetchMock.mock.calls[0]!;
  expect(url).toBe('/api/v1/admin/wallpapers/1/ios-acquisition/price-sync');
  expect(options.method).toBe('POST');
  expect(options.body).toBeUndefined();
  expect(new Headers(options.headers).get('X-CSRF-Token')).toBe('csrf-token');
});
