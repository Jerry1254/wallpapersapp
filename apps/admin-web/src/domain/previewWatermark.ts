import type { Wallpaper } from '@/domain/admin';

export const hasPreviewWatermark = (wallpaper: Pick<Wallpaper, 'accessType' | 'previewWatermarkEnabled'>) =>
  wallpaper.accessType === 'REDEEM' && wallpaper.previewWatermarkEnabled !== false;

export const previewGenerationLabel = (wallpaper: Pick<Wallpaper, 'previewGenerationStatus'>) => ({
  PENDING: '等待生成',
  PROCESSING: '生成中',
  READY: '已就绪',
  FAILED: '生成失败'
}[wallpaper.previewGenerationStatus || 'PENDING']);

export const previewGenerationInProgress = (wallpaper: Pick<Wallpaper, 'previewGenerationStatus'>) =>
  wallpaper.previewGenerationStatus === 'PENDING' || wallpaper.previewGenerationStatus === 'PROCESSING';

export const mergePreviewGeneration = (target: Wallpaper, latest: Pick<Wallpaper, 'previewGenerationStatus' | 'previewRevision' | 'previewGenerationError'>) => {
  // A slow poll must not overwrite a rebuild that has already queued a newer revision.
  if ((latest.previewRevision ?? 0) < (target.previewRevision ?? 0)) return;
  target.previewGenerationStatus = latest.previewGenerationStatus;
  target.previewRevision = latest.previewRevision;
  target.previewGenerationError = latest.previewGenerationError ?? null;
};
