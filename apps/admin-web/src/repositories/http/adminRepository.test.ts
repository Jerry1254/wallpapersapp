import { afterEach, expect, it, vi } from 'vitest';
import type { Wallpaper } from '@/domain/admin';
import { adminRepository } from './adminRepository';

afterEach(() => { vi.unstubAllGlobals(); });

it('rejects a 4D resource version without config before uploading or creating a wallpaper', async () => {
  const fetchMock = vi.fn();
  vi.stubGlobal('fetch', fetchMock);
  const wallpaper = { kind: 'four_d', resources: { cover: { name: 'cover.png' }, backgroundLayer: { name: 'background.png' }, foregroundLayer: { name: 'foreground.png' } } } as Wallpaper;
  await expect(adminRepository.saveWallpaper(wallpaper, true)).rejects.toMatchObject({ code: 'ASSET_NOT_READY', message: '请上传景深配置 JSON' });
  expect(fetchMock).not.toHaveBeenCalled();
});
