import { afterEach, describe, expect, it, vi } from 'vitest';

import { tutorialFor, tutorialPlatformForUserAgent } from '@/domain/tutorial';

import { tutorialRepository } from './tutorialRepository';

afterEach(() => vi.unstubAllGlobals());

describe('wallpaper tutorial contract', () => {
  it('loads sorted tutorials and resolves the matching Android type', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({
      items: [
        {
          key: 'ANDROID_DYNAMIC', title: '动态壁纸教程', platform: 'ANDROID', wallpaperKind: 'DYNAMIC',
          video: { contentUrl: '/dynamic.mp4', mimeType: 'video/mp4', durationMs: 9000, sizeBytes: 100 },
          sortOrder: 20, updatedAt: '2026-09-15T00:00:00Z'
        },
        {
          key: 'ANDROID_PARALLAX_4D', title: '4D动态壁纸教程', platform: 'ANDROID', wallpaperKind: 'PARALLAX_4D',
          video: { contentUrl: '/4d.mp4', mimeType: 'video/mp4', durationMs: 10000, sizeBytes: 120 },
          sortOrder: 10, updatedAt: '2026-09-15T00:00:00Z'
        }
      ]
    }), { headers: { 'Content-Type': 'application/json' } }));
    vi.stubGlobal('fetch', fetchMock);

    const tutorials = await tutorialRepository.list();

    expect(tutorials.map((item) => item.key)).toEqual(['ANDROID_PARALLAX_4D', 'ANDROID_DYNAMIC']);
    expect(tutorialFor(tutorials, 'ANDROID', 'DYNAMIC')?.video.contentUrl).toBe('/dynamic.mp4');
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('/api/v1/public/wallpaper-tutorials');
  });

  it('selects the tutorial platform from the browser user agent', () => {
    expect(tutorialPlatformForUserAgent('Mozilla/5.0 (Linux; HarmonyOS 4.0)')).toBe('HARMONYOS');
    expect(tutorialPlatformForUserAgent('Mozilla/5.0 (iPhone; CPU iPhone OS 18_0)')).toBe('IOS');
    expect(tutorialPlatformForUserAgent('Mozilla/5.0 (Linux; Android 13)')).toBe('ANDROID');
  });
});
