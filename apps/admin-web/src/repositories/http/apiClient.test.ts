import { afterEach, describe, expect, it, vi } from 'vitest';

import type { Wallpaper } from '@/domain/admin';
import { ApiError, apiDownload, apiRequest, readableApiError, setCsrfToken } from '@/repositories/http/apiClient';
import { adminRepository, WallpaperSaveError } from '@/repositories/http/adminRepository';

const json = (value: unknown, status = 200, headers: HeadersInit = {}) => new Response(
  JSON.stringify(value),
  { status, headers: { 'Content-Type': 'application/json', ...headers } }
);

const asset = (id: string, name: string) => ({
  id,
  originalFilename: name,
  mimeType: 'image/png',
  sizeBytes: 68,
  previewUrl: `/api/v1/admin/assets/${id}/content`,
  validationStatus: 'READY'
});

const category = { id: '20', name: '风景', slug: 'scenery' };
const childCategory = { id: '21', name: '自然风光', slug: 'nature' };

const detail = (
  status: 'DRAFT' | 'PUBLISHED',
  version: number,
  variants: unknown[],
  cover = asset('1', 'cover.png')
) => ({
  id: '30',
  title: '晨雾山峦',
  slug: 'misty-mountains',
  kind: 'STATIC',
  rootCategory: category,
  childCategory,
  cover,
  featured: false,
  featuredRank: null,
  sortOrder: 10,
  capabilities: [],
  status,
  publishedAt: status === 'PUBLISHED' ? '2026-09-11T08:00:00Z' : null,
  createdAt: '2026-09-11T07:00:00Z',
  updatedAt: '2026-09-11T08:00:00Z',
  version,
  copyrightNote: '已获得授权',
  variants
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
  setCsrfToken('');
});

describe('apiRequest', () => {
  it('网络失败提供中文恢复提示，下载也遵循相同错误处理', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    for (const request of [() => apiRequest('/admin/categories'), () => apiDownload('/admin/code-batches/1/delivery')]) {
      await expect(request()).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 });
    }
    expect(readableApiError(new ApiError(409, 'DUPLICATE_SLUG', 'Already exists'))).toBe('此 Slug 已被使用，请换一个');
  });

  it('无响应超过 15 秒中止请求，提示先确认提交结果', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    })));
    const assertion = expect(apiRequest('/admin/categories')).rejects.toMatchObject({ code: 'REQUEST_TIMEOUT', status: 0 });
    await vi.advanceTimersByTimeAsync(15_000);
    await assertion;
  });
  it('在管理写请求上携带 Cookie、CSRF 与请求 ID', async () => {
    setCsrfToken('csrf-token');
    const fetchMock = vi.fn().mockResolvedValue(json({ ok: true }, 200, { ETag: '"3"' }));
    vi.stubGlobal('fetch', fetchMock);

    const response = await apiRequest<{ ok: boolean }>('/admin/example', {
      method: 'POST',
      body: JSON.stringify({ name: 'demo' }),
      headers: { 'Content-Type': 'application/json' },
      csrf: true
    });

    expect(response.etag).toBe('"3"');
    const [, options] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(options.credentials).toBe('include');
    expect(options.cache).toBe('no-store');
    const headers = options.headers as Headers;
    expect(headers.get('X-CSRF-Token')).toBe('csrf-token');
    expect(headers.get('X-Request-Id')).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('保留稳定错误码和字段错误', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      error: {
        code: 'VALIDATION_FAILED',
        message: 'invalid',
        requestId: '00000000-0000-0000-0000-000000000001',
        details: [{ field: 'slug', reason: 'invalid slug' }]
      }
    }, 400)));

    await expect(apiRequest('/admin/categories')).rejects.toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      details: [{ field: 'slug', reason: 'invalid slug' }]
    });
  });
});

describe('adminRepository.saveWallpaper', () => {
  it('4D 固定 ZIP 解析后使用包 ID 创建资源版本并发布', async () => {
    setCsrfToken('csrf-token');
    const requests: { url: string; options: RequestInit }[] = [];
    const sourcePackage = {
      id: '90',
      originalFilename: 'wallpaper-4d.zip',
      mimeType: 'application/zip',
      sizeBytes: 2048,
      sha256: 'a'.repeat(64),
      validationStatus: 'READY',
      cover: { ...asset('1', 'cover.jpg'), mimeType: 'image/jpeg' },
      configFormatVersion: 2,
      canvas: { width: 1080, height: 2160 },
      layers: [
        { index: 1, originalFilename: 'layers/01.png', role: 'FOREGROUND', ordinal: 0 },
        { index: 2, originalFilename: 'layers/02.jpg', role: 'BACKGROUND', ordinal: 0 }
      ]
    };
    const readyVersion = { id: '50', versionNo: 1, status: 'READY', bindings: [], sourcePackage };
    const variant = { id: '40', platform: 'ANDROID', resourceType: 'LAYER_PARALLAX', version: 0, resourceVersions: [] };
    const wallpaper = { ...detail('DRAFT', 1, [variant], sourcePackage.cover), kind: 'PARALLAX_4D' };
    const fetchMock = vi.fn(async (urlValue: string | URL | Request, options: RequestInit = {}) => {
      const url = String(urlValue);
      requests.push({ url, options });
      if (url.endsWith('/admin/parallax-packages')) return json(sourcePackage, 201);
      if (url.endsWith('/admin/wallpapers/30') && options.method === 'PATCH') return json(wallpaper);
      if (url.endsWith('/admin/variants/40/parallax-resource-versions')) return json(readyVersion, 201);
      if (url.endsWith('/admin/resource-versions/50/secure-package')) return json(readyVersion);
      if (url.endsWith('/admin/wallpapers/30/publish')) {
        return json({
          ...wallpaper,
          status: 'PUBLISHED',
          version: 2,
          variants: [{ ...variant, resourceVersions: [{ ...readyVersion, status: 'PUBLISHED' }] }]
        });
      }
      throw new Error(`unexpected request: ${options.method || 'GET'} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    const zip = new File(['zip'], 'wallpaper-4d.zip', { type: 'application/zip' });
    const input: Wallpaper = {
      id: '30', title: '4D 多层风景', slug: 'parallax-landscape', categoryId: '20', subcategoryId: '',
      kind: 'four_d', platforms: ['android'], status: 'draft', sort: 1, featuredRank: null,
      coverUrl: '', copyrightNote: '本地测试', updatedAt: '', version: 0, variants: [], resources: {
        parallaxPackage: { name: zip.name, size: zip.size, mime: zip.type, nativeFile: zip }
      }
    };

    expect((await adminRepository.saveWallpaper(input, true)).status).toBe('published');
    expect(requests.map((item) => `${item.options.method || 'GET'} ${item.url.split('/api/v1')[1]}`)).toEqual([
      'POST /admin/parallax-packages',
      'PATCH /admin/wallpapers/30',
      'POST /admin/variants/40/parallax-resource-versions',
      'POST /admin/resource-versions/50/secure-package',
      'POST /admin/wallpapers/30/publish'
    ]);
    const uploaded = (requests[0].options.body as FormData).get('file') as File;
    expect(uploaded.name).toBe(zip.name);
    expect(await uploaded.text()).toBe(await zip.text());
    expect(JSON.parse(String(requests[1].options.body)).coverAssetId).toBe('1');
    expect(JSON.parse(String(requests[2].options.body))).toEqual({ versionNo: 1, sourcePackageId: '90' });
  });

  it('iOS 动态壁纸的多角色版本分别使用 ordinal 0 发布', async () => {
    setCsrfToken('csrf-token');
    const roles = ['LIVE_PHOTO_IMAGE', 'LIVE_PHOTO_VIDEO'];
    const bindings = roles.map((role, index) => ({ id: String(60 + index), role, ordinal: 0, asset: asset(String(index + 2), 'resource') }));
    const version = { id: '50', versionNo: 1, status: 'READY', bindings };
    const variant = { id: '40', platform: 'IOS', resourceType: 'LIVE_PHOTO', version: 0, resourceVersions: [] };
    const wallpaper = { ...detail('DRAFT', 0, [variant]), kind: 'DYNAMIC' };
    const fetchMock = vi.fn(async (url: string, options: RequestInit = {}) => {
      if (url.endsWith('/resource-versions')) {
        const body = JSON.parse(String(options.body));
        if (body.bindings.some((binding: { ordinal: number }) => binding.ordinal !== 0)) {
          return json({ error: { code: 'DOMAIN_RULE_VIOLATION', message: 'Every role must use ordinal zero' } }, 422);
        }
        return json(version, 201);
      }
      if (url.endsWith('/secure-package')) return json(version);
      if (url.endsWith('/publish')) return json({ ...wallpaper, status: 'PUBLISHED', variants: [{ ...variant, resourceVersions: [{ ...version, status: 'PUBLISHED' }] }] });
      return json(wallpaper);
    });
    vi.stubGlobal('fetch', fetchMock);
    const resources = Object.fromEntries(['iosPhoto', 'iosMov'].map((key, index) => [key, { name: key, assetId: String(index + 2), size: 68, mime: 'image/png' }]));
    const input: Wallpaper = { id: '30', title: '多角色发布', slug: 'multi-role', categoryId: '20', subcategoryId: '',
      kind: 'dynamic', platforms: ['ios'], status: 'draft', sort: 1, featuredRank: null,
      coverUrl: '', copyrightNote: '本地测试', updatedAt: '', version: 0, variants: [], resources: {
        ...resources, cover: { name: 'cover.png', assetId: '1', size: 68, mime: 'image/png' }
      } };
    expect((await adminRepository.saveWallpaper(input, true)).status).toBe('published');
    const created = fetchMock.mock.calls.find(([url]) => url.endsWith('/resource-versions'));
    expect(JSON.parse(String(created?.[1]?.body)).bindings).toEqual(roles.map((role, index) => ({ role, ordinal: 0, assetId: String(index + 2) })));
  });
  it.each([false, true])('4D 编辑保留现有素材，不重复上传或创建版本（含源包：%s）', async (withSource) => {
    setCsrfToken('csrf-token');
    const sourcePackage = withSource ? {
      id: '90', originalFilename: 'wallpaper.zip', mimeType: 'application/zip', sizeBytes: 100,
      sha256: 'a'.repeat(64), validationStatus: 'READY', cover: asset('1', 'cover.jpg'),
      canvas: { width: 1080, height: 2160 }, layers: [{ index: 1 }, { index: 2 }]
    } : null;
    const variant = { id: '40', platform: 'ANDROID', resourceType: 'LAYER_PARALLAX', version: 0,
      resourceVersions: [{ id: '50', versionNo: 1, status: 'PUBLISHED', sourcePackage, bindings: [] }] };
    const saved = { ...detail('PUBLISHED', 2, [variant]), kind: 'PARALLAX_4D' };
    const fetchMock = vi.fn(async (url: string, options: RequestInit = {}) => {
      if (url.includes('/admin/wallpapers?')) return json({ items: [saved], page: { totalPages: 1 } });
      if (url.endsWith('/admin/wallpapers/30')) return json(saved);
      throw new Error(`Unexpected resource mutation: ${options.method} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);
    const [input] = await adminRepository.wallpapers();
    input.title = '修改名称';
    await adminRepository.saveWallpaper(input, false);
    const writes = fetchMock.mock.calls.filter(([, options]) => options?.method);
    expect(writes).toHaveLength(1);
    expect(writes[0][1]?.method).toBe('PATCH');
    expect(JSON.parse(String(writes[0][1]?.body))).toMatchObject({ title: '修改名称', coverAssetId: '1' });
  });
  it('草稿创建后上传失败保留作品 ID 和最新版本，再次保存继续同一作品', async () => {
    setCsrfToken('csrf-token');
    const variant = { id: '40', platform: 'UNIVERSAL', resourceType: 'STATIC_IMAGE', version: 0, resourceVersions: [] };
    let created = false;
    let uploadFails = true;
    const fetchMock = vi.fn(async (url: string, options: RequestInit = {}) => {
      if (url.endsWith('/admin/assets')) {
        if ((options.body as FormData).get('purpose') === 'STATIC_IMAGE' && uploadFails) {
          return json({ error: { code: 'ASSET_VALIDATION_FAILED', message: 'Invalid image' } }, 422);
        }
        return json(asset('1', 'cover.png'), 201);
      }
      if (url.endsWith('/admin/wallpapers') && options.method === 'POST') {
        created = true;
        return json(detail('DRAFT', 0, []), 201);
      }
      if (url.endsWith('/variants')) return json(variant, 201);
      if (url.endsWith('/resource-versions')) return json({ id: '50', status: 'READY', versionNo: 1 }, 201);
      return json(detail('DRAFT', created ? 1 : 0, [variant]));
    });
    vi.stubGlobal('fetch', fetchMock);
    const file = new File(['invalid'], 'image.png', { type: 'image/png' });
    const input: Wallpaper = { id: '', title: '恢复草稿', slug: 'recover', categoryId: '20', subcategoryId: '',
      kind: 'static', platforms: ['android', 'ios', 'harmony'], status: 'draft', sort: 1, featuredRank: null,
      coverUrl: '', copyrightNote: '本地测试', updatedAt: '', version: 0, variants: [], resources: {
        cover: { name: file.name, size: file.size, mime: file.type, nativeFile: file },
        staticImage: { name: file.name, size: file.size, mime: file.type, nativeFile: file }
      } };
    const failure = await adminRepository.saveWallpaper(input, false).catch((cause: unknown) => cause);
    expect(failure).toBeInstanceOf(WallpaperSaveError);
    const recovery = (failure as WallpaperSaveError).wallpaper;
    expect(recovery).toMatchObject({ id: '30', version: 1 });
    expect(recovery.resources.staticImage?.nativeFile).toBe(file);
    uploadFails = false;
    await adminRepository.saveWallpaper(recovery, false);
    expect(fetchMock.mock.calls.filter(([url, options]) => url.endsWith('/admin/wallpapers') && options?.method === 'POST')).toHaveLength(1);
    const patch = fetchMock.mock.calls.find(([, options]) => options?.method === 'PATCH');
    expect((patch?.[1]?.headers as Headers).get('If-Match')).toBe('"1"');
  });
  it('重新读取并保存壁纸时保留服务器的精选排序和可空二级分类', async () => {
    setCsrfToken('csrf-token');
    const variant = { id: '40', platform: 'UNIVERSAL', resourceType: 'STATIC_IMAGE', version: 0,
      resourceVersions: [{ id: '50', versionNo: 1, status: 'PUBLISHED', bindings: [
        { id: '60', role: 'STATIC_IMAGE', ordinal: 0, asset: asset('2', 'wallpaper.png') }
      ] }] };
    const saved = { ...detail('PUBLISHED', 2, [variant]), childCategory: null, featuredRank: 7 };
    const fetchMock = vi.fn(async (url: string, options: RequestInit = {}) => {
      if (url.includes('/admin/wallpapers?')) return json({ items: [saved], page: { totalPages: 1 } });
      return json(saved);
    });
    vi.stubGlobal('fetch', fetchMock);
    const [input] = await adminRepository.wallpapers();
    expect(input.featuredRank).toBe(7);
    expect(input.subcategoryId).toBe('');
    await adminRepository.saveWallpaper(input, false);
    const patch = fetchMock.mock.calls.find(([, options]) => options?.method === 'PATCH');
    expect(JSON.parse(String(patch?.[1]?.body))).toMatchObject({ featuredRank: 7, childCategoryId: null });
  });
  it('按资源上传、草稿、变体、版本、发布的顺序完成静态壁纸闭环', async () => {
    setCsrfToken('csrf-token');
    const requests: { url: string; options: RequestInit }[] = [];
    const variantWithoutVersion = {
      id: '40', platform: 'UNIVERSAL', resourceType: 'STATIC_IMAGE',
      minimumOsVersion: null, capabilityRequirements: [], resourceVersions: [], version: 0
    };
    const staticAsset = asset('2', 'wallpaper.png');
    const publishedVersion = {
      id: '50', versionNo: 1, status: 'PUBLISHED', manifestSha256: 'a'.repeat(64),
      validationErrors: [], bindings: [{ id: '60', role: 'STATIC_IMAGE', ordinal: 0, asset: staticAsset }],
      publishedAt: '2026-09-11T08:00:00Z', retiredAt: null, createdAt: '2026-09-11T08:00:00Z', version: 1
    };

    const fetchMock = vi.fn(async (urlValue: string | URL | Request, options: RequestInit = {}) => {
      const url = String(urlValue);
      requests.push({ url, options });
      if (url.endsWith('/admin/assets')) {
        const form = options.body as FormData;
        const purpose = String(form.get('purpose'));
        return json(purpose === 'WALLPAPER_COVER' ? asset('1', 'cover.png') : staticAsset, 201);
      }
      if (url.endsWith('/admin/wallpapers') && options.method === 'POST') return json(detail('DRAFT', 0, []), 201, { ETag: '"0"' });
      if (url.endsWith('/admin/wallpapers/30/variants')) return json(variantWithoutVersion, 201);
      if (url.endsWith('/admin/wallpapers/30') && !options.method) return json(detail('DRAFT', 1, [variantWithoutVersion]), 200, { ETag: '"1"' });
      if (url.endsWith('/admin/variants/40/resource-versions')) return json({ ...publishedVersion, status: 'READY', version: 0 }, 201);
      if (url.endsWith('/admin/resource-versions/50/secure-package')) return json({ ...publishedVersion, status: 'READY' });
      if (url.endsWith('/admin/wallpapers/30/publish')) {
        return json(detail('PUBLISHED', 2, [{ ...variantWithoutVersion, resourceVersions: [publishedVersion] }]), 200, { ETag: '"2"' });
      }
      throw new Error(`unexpected request: ${options.method || 'GET'} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const png = new File([new Uint8Array([137, 80, 78, 71])], 'image.png', { type: 'image/png' });
    const input: Wallpaper = {
      id: '', title: '晨雾山峦', slug: 'misty-mountains', categoryId: '20', subcategoryId: '21',
      kind: 'static', platforms: ['android', 'ios', 'harmony'], status: 'published', sort: 10,
      coverUrl: '', featuredRank: 2, copyrightNote: '已获得授权', updatedAt: '', version: 0, variants: [],
      resources: {
        cover: { name: 'cover.png', size: png.size, mime: png.type, nativeFile: png },
        staticImage: { name: 'wallpaper.png', size: png.size, mime: png.type, nativeFile: png }
      }
    };

    const result = await adminRepository.saveWallpaper(input, true);

    expect(result.status).toBe('published');
    expect(requests.map((item) => `${item.options.method || 'GET'} ${item.url.split('/api/v1')[1]}`)).toEqual([
      'POST /admin/assets',
      'POST /admin/wallpapers',
      'POST /admin/wallpapers/30/variants',
      'GET /admin/wallpapers/30',
      'POST /admin/assets',
      'POST /admin/variants/40/resource-versions',
      'POST /admin/resource-versions/50/secure-package',
      'POST /admin/wallpapers/30/publish'
    ]);
    const variantRequest = requests[2].options.headers as Headers;
    expect(JSON.parse(String(requests[1].options.body)).featuredRank).toBe(2);
    expect(variantRequest.get('If-Match')).toBe('"0"');
    expect(variantRequest.get('Content-Type')).toBe('application/json');
    const publishRequest = requests[7].options.headers as Headers;
    expect(publishRequest.get('If-Match')).toBe('"1"');
    expect(JSON.parse(String(requests[7].options.body))).toEqual({ resourceVersionIds: ['50'] });
  });
});

describe('adminRepository.categories', () => {
  it('把服务端省略 parentId 的根分类归一化为根节点', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(json({
      items: [{
        id: '20', name: '风景', slug: 'scenery', icon: null,
        sortOrder: 10, wallpaperCount: 0, children: [{
          id: '21', parentId: '20', name: '自然风光', slug: 'nature', icon: null,
          sortOrder: 10, wallpaperCount: 0, children: [], version: 0
        }], version: 0
      }]
    })));

    await expect(adminRepository.categories()).resolves.toEqual([
      expect.objectContaining({ id: '20', parentId: null }),
      expect.objectContaining({ id: '21', parentId: '20' })
    ]);
  });
});

describe('adminRepository code delivery', () => {
  it('创建批次后用一次性交付票据下载并确认销毁', async () => {
    setCsrfToken('csrf-token');
    const requests: { url: string; options: RequestInit }[] = [];
    const fetchMock = vi.fn(async (urlValue: string | URL | Request, options: RequestInit = {}) => {
      const url = String(urlValue);
      requests.push({ url, options });
      if (url.endsWith('/admin/code-batches') && options.method === 'POST') {
        return json({
          batch: {
            id: '70', batchNo: 'BATCH000000000000000000001', name: '首发批次',
            generatedCount: 2, quotaPerCodeSnapshot: 3, totalQuota: 6, usedQuota: 0,
            usagePercent: 0, deliveryStatus: 'AVAILABLE', createdAt: '2026-09-11T08:00:00Z',
            availableCodeCount: 2, exhaustedCodeCount: 0
          },
          deliveryTicket: 'one-time-ticket',
          deliveryUrl: '/api/v1/admin/code-batches/70/delivery',
          deliveryExpiresAt: '2026-09-11T08:10:00Z'
        }, 201);
      }
      if (url.endsWith('/admin/code-batches/70/delivery') && !options.method) {
        return new Response('batchNo,code,totalQuota\r\nBATCH,AAAAA-BBBBB-CCCCC-DDDDD,3\r\n', {
          status: 200,
          headers: { 'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="batch.csv"' }
        });
      }
      if (url.endsWith('/admin/code-batches/70/delivery-confirmation') && options.method === 'POST') {
        return new Response(null, { status: 204 });
      }
      throw new Error(`unexpected request: ${options.method || 'GET'} ${url}`);
    });
    vi.stubGlobal('fetch', fetchMock);

    const created = await adminRepository.createCodeBatch(
      { name: '首发批次', generatedCount: 2, quotaPerCode: 3 },
      '00000000-0000-4000-8000-000000000008'
    );
    const downloaded = await adminRepository.downloadCodeBatch(created.batch.id, created.deliveryTicket);
    await adminRepository.confirmCodeBatchDelivery(created.batch.id);

    expect(downloaded.filename).toBe('batch.csv');
    await expect(downloaded.data.text()).resolves.toContain('AAAAA-BBBBB-CCCCC-DDDDD');
    const createHeaders = requests[0].options.headers as Headers;
    expect(createHeaders.get('Idempotency-Key')).toBe('00000000-0000-4000-8000-000000000008');
    expect(createHeaders.get('X-CSRF-Token')).toBe('csrf-token');
    const deliveryHeaders = requests[1].options.headers as Headers;
    expect(deliveryHeaders.get('X-Delivery-Ticket')).toBe('one-time-ticket');
    expect(deliveryHeaders.get('Accept')).toBe('text/csv');
    const confirmationHeaders = requests[2].options.headers as Headers;
    expect(confirmationHeaders.get('X-CSRF-Token')).toBe('csrf-token');
  });
});

it('安全包制作失败时不会继续发布 READY Android 兼容版本', async () => {
  setCsrfToken('csrf-token');
  const variant = { id: '40', platform: 'UNIVERSAL', resourceType: 'STATIC_IMAGE', resourceVersions: [
    { id: '50', status: 'READY', versionNo: 1, bindings: [] }
  ] };
  const fetchMock = vi.fn(async (url: string) => {
    if (url.endsWith('/admin/wallpapers/30')) return json(detail('DRAFT', 0, [variant]));
    if (url.endsWith('/secure-package')) return json({ error: { code: 'ASSET_VALIDATION_FAILED', message: 'invalid media' } }, 422);
    throw new Error(`Unexpected publish after validation failure: ${url}`);
  });
  vi.stubGlobal('fetch', fetchMock);
  await expect(adminRepository.publishWallpaper('30')).rejects.toMatchObject({ code: 'ASSET_VALIDATION_FAILED' });
  expect(fetchMock.mock.calls.some(([url]) => url.endsWith('/publish'))).toBe(false);
});
