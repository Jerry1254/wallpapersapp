import { afterEach, describe, expect, it, vi } from 'vitest';

import { ApiClientError, catalogErrorMessage } from './apiClient';
import { catalogRepository } from './catalogRepository';

afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers(); });

describe('public catalog HTTP contract', () => {
  it('encodes keywords and string IDs without sending undefined filters', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ items: [], page: {} }), {
      headers: { 'Content-Type': 'application/json' }
    }));
    vi.stubGlobal('fetch', fetchMock);
    await catalogRepository.listWallpapers({ q: '海 & 山', rootCategoryId: '9007199254740993', childCategoryId: undefined });
    const url = String(fetchMock.mock.calls[0]?.[0]);
    const parameters = new URL(url, 'http://local.test').searchParams;
    expect(parameters.get('q')).toBe('海 & 山');
    expect(parameters.get('rootCategoryId')).toBe('9007199254740993');
    expect(parameters.has('childCategoryId')).toBe(false);
  });

  it('preserves stable server error codes and request IDs while displaying a local message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({
      error: { code: 'WALLPAPER_NOT_FOUND', message: 'The wallpaper was not found', requestId: 'request-1' }
    }), { status: 404 })));
    let failure: unknown;
    try { await catalogRepository.wallpaper('3'); } catch (error) { failure = error; }
    expect(failure).toBeInstanceOf(ApiClientError);
    expect(failure).toMatchObject({ code: 'WALLPAPER_NOT_FOUND', requestId: 'request-1', status: 404 });
    expect(catalogErrorMessage(failure)).toBe('内容不存在或已经下线');
  });

  it('turns a network rejection into a retryable client error', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    await expect(catalogRepository.categories()).rejects.toMatchObject({ status: 0, code: 'NETWORK_ERROR' });
  });

  it('ends a stalled request after fifteen seconds', async () => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
    })));
    const assertion = expect(catalogRepository.categories()).rejects.toMatchObject({ code: 'NETWORK_ERROR' });
    await vi.advanceTimersByTimeAsync(15000);
    await assertion;
  });
});
