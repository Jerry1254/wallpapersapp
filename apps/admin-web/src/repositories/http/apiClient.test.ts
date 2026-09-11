import { afterEach, describe, expect, it, vi } from 'vitest';

import type { Wallpaper } from '@/domain/admin';
import { apiRequest, setCsrfToken } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';

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
  vi.unstubAllGlobals();
  setCsrfToken('');
});

describe('apiRequest', () => {
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
      coverUrl: '', copyrightNote: '已获得授权', updatedAt: '', version: 0, variants: [],
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
      'POST /admin/wallpapers/30/publish'
    ]);
    const variantRequest = requests[2].options.headers as Headers;
    expect(variantRequest.get('If-Match')).toBe('"0"');
    expect(variantRequest.get('Content-Type')).toBe('application/json');
    const publishRequest = requests[6].options.headers as Headers;
    expect(publishRequest.get('If-Match')).toBe('"1"');
    expect(JSON.parse(String(requests[6].options.body))).toEqual({ resourceVersionIds: ['50'] });
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
