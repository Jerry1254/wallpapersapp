import type { DeliveryPlatform, WallpaperKind } from './catalog';

export type WallpaperTutorialKey =
  | 'ANDROID_PARALLAX_4D'
  | 'ANDROID_DYNAMIC'
  | 'STATIC'
  | 'HARMONYOS_DYNAMIC'
  | 'IOS_DYNAMIC';

export interface WallpaperTutorialVideo {
  contentUrl: string;
  mimeType: 'video/mp4';
  durationMs: number;
  sizeBytes: number;
}

export interface WallpaperTutorial {
  key: WallpaperTutorialKey;
  title: string;
  platform: DeliveryPlatform;
  wallpaperKind: WallpaperKind;
  video: WallpaperTutorialVideo;
  sortOrder: number;
  updatedAt: string;
}

export const tutorialPlatformForUserAgent = (userAgent: string): DeliveryPlatform => {
  if (/HarmonyOS|OpenHarmony/i.test(userAgent)) return 'HARMONYOS';
  if (/iPhone|iPad|iPod/i.test(userAgent)) return 'IOS';
  return 'ANDROID';
};

export const tutorialKeyFor = (
  platform: DeliveryPlatform,
  wallpaperKind: WallpaperKind
): WallpaperTutorialKey | undefined => {
  if (wallpaperKind === 'STATIC') return 'STATIC';
  if (platform === 'ANDROID' && wallpaperKind === 'PARALLAX_4D') return 'ANDROID_PARALLAX_4D';
  if (platform === 'ANDROID' && wallpaperKind === 'DYNAMIC') return 'ANDROID_DYNAMIC';
  if (platform === 'HARMONYOS' && wallpaperKind === 'DYNAMIC') return 'HARMONYOS_DYNAMIC';
  if (platform === 'IOS' && wallpaperKind === 'DYNAMIC') return 'IOS_DYNAMIC';
  return undefined;
};

export const tutorialFor = (
  tutorials: WallpaperTutorial[],
  platform: DeliveryPlatform,
  wallpaperKind: WallpaperKind
) => {
  const key = tutorialKeyFor(platform, wallpaperKind);
  return key ? tutorials.find((item) => item.key === key) : undefined;
};
