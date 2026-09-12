import { describe, expect, it, vi } from 'vitest';

import type { PublicWallpaperPage, PublicWallpaperSummary } from '@/domain/catalog';
import { ApiClientError } from '@/repositories/http/apiClient';
import { useWallpaperPage } from './useWallpaperPage';

const item = (id: string): PublicWallpaperSummary => ({
  id, title: id, slug: id, kind: 'STATIC',
  rootCategory: { id: '1', name: '风景', slug: 'scenery' },
  childCategory: null,
  cover: { assetId: '2', contentUrl: '/api/v1/public/assets/2/content', mimeType: 'image/png', widthPx: 3, heightPx: 4 },
  featured: true, sortOrder: 0,
  capabilities: [{ platform: 'UNIVERSAL', resourceType: 'STATIC_IMAGE', minimumOsVersion: null, capabilityRequirements: [] }]
});

const page = (ids: string[], number = 1, totalPages = 1): PublicWallpaperPage => ({
  items: ids.map(item),
  page: { page: number, pageSize: 20, totalItems: totalPages * 20, totalPages }
});

const deferred = () => {
  let resolve!: (value: PublicWallpaperPage) => void;
  const promise = new Promise<PublicWallpaperPage>((done) => { resolve = done; });
  return { promise, resolve };
};

describe('catalog page requests', () => {
  it('keeps the newest query when responses arrive in reverse order', async () => {
    const first = deferred();
    const second = deferred();
    const loader = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const state = useWallpaperPage(loader);
    const oldRequest = state.load({ q: '山' });
    const newRequest = state.load({ q: '海' });
    second.resolve(page(['sea']));
    await newRequest;
    first.resolve(page(['mountain']));
    await oldRequest;
    expect(state.items.value.map((value) => value.id)).toEqual(['sea']);
    expect(state.loading.value).toBe(false);
  });

  it('retries the same next page after a failure and avoids duplicate cards', async () => {
    const loader = vi.fn()
      .mockResolvedValueOnce(page(['one'], 1, 2))
      .mockRejectedValueOnce(new ApiClientError(0, 'NETWORK_ERROR', '网络不可用'))
      .mockResolvedValueOnce(page(['one', 'two'], 2, 2));
    const state = useWallpaperPage(loader);
    await state.load({ rootCategoryId: '1' });
    await state.loadMore();
    expect(state.page.value.page).toBe(1);
    expect(state.moreErrorMessage.value).toBe('网络不可用');
    await state.loadMore();
    expect(loader.mock.calls[1]?.[0]).toEqual(loader.mock.calls[2]?.[0]);
    expect(state.items.value.map((value) => value.id)).toEqual(['one', 'two']);
    expect(state.hasMore.value).toBe(false);
  });

  it('discards an old next page when the selected category changes', async () => {
    const next = deferred();
    const loader = vi.fn().mockResolvedValueOnce(page(['old'], 1, 2))
      .mockReturnValueOnce(next.promise).mockResolvedValueOnce(page(['new']));
    const state = useWallpaperPage(loader);
    await state.load({ rootCategoryId: '1' });
    const pending = state.loadMore();
    await state.load({ rootCategoryId: '2' });
    next.resolve(page(['old-next'], 2, 2));
    await pending;
    expect(state.items.value.map((value) => value.id)).toEqual(['new']);
    expect(state.loadingMore.value).toBe(false);
  });
});
