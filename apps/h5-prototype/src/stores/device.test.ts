import { createPinia, setActivePinia } from 'pinia';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { deviceRepository } from '@/repositories/http/deviceRepository';
import { memoryStorage } from '@/test/storage';
import { useDeviceStore } from './device';
import { usePrototypeStore } from './prototype';

vi.mock('@/repositories/http/deviceRepository', () => ({ deviceRepository: { entitlements: vi.fn() } }));
afterEach(() => { vi.unstubAllGlobals(); vi.clearAllMocks(); });
const page = (number: number, totalPages = 1) => ({ page: number, pageSize: 20, totalItems: totalPages, totalPages });
const item = (id: string) => ({ id, wallpaper: { id, title: `壁纸${id}` }, grantedAt: '2026-09-12T10:00:00Z' });

describe('server-owned entitlement state', () => {
  it('never grants ownership from a local demo download and removes stale ownership on refresh', async () => {
    vi.stubGlobal('window', { localStorage: memoryStorage() });
    setActivePinia(createPinia());
    usePrototypeStore().markDownloaded('1');
    const store = useDeviceStore();
    vi.mocked(deviceRepository.entitlements).mockResolvedValueOnce({ items: [item('1')], page: page(1) } as never);
    await store.refresh();
    expect(store.isOwned('1')).toBe(true);
    vi.mocked(deviceRepository.entitlements).mockResolvedValueOnce({ items: [], page: { ...page(1, 0), totalItems: 0 } });
    await store.refresh();
    expect(store.isOwned('1')).toBe(false);
    expect(usePrototypeStore().isDownloaded('1')).toBe(true);
  });

  it('looks beyond the first page for a detail and retries a failed page without losing previous items', async () => {
    vi.stubGlobal('window', { localStorage: memoryStorage() });
    setActivePinia(createPinia());
    const store = useDeviceStore();
    vi.mocked(deviceRepository.entitlements).mockResolvedValueOnce({ items: [item('1')], page: page(1, 2) } as never)
      .mockRejectedValueOnce(new Error('连接失败'))
      .mockResolvedValueOnce({ items: [item('2')], page: page(2, 2) } as never);
    await store.refresh();
    await expect(store.ensureOwned('2')).rejects.toThrow('连接失败');
    expect(store.isOwned('1')).toBe(true);
    expect(store.page!.page).toBe(1);
    expect((await store.ensureOwned('2'))!.wallpaper.id).toBe('2');
    expect(vi.mocked(deviceRepository.entitlements).mock.calls.map(call => call[0])).toEqual([1, 2, 2]);
  });
});
