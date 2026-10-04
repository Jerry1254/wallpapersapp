import { expect, it } from 'vitest';
import type { Wallpaper } from '@/domain/admin';
import { hasPreviewWatermark, mergePreviewGeneration, previewGenerationInProgress, previewGenerationLabel } from './previewWatermark';

it('always shows free resources without a watermark while retaining the paid setting', () => {
  expect(hasPreviewWatermark({ accessType: 'FREE', previewWatermarkEnabled: true })).toBe(false);
  expect(hasPreviewWatermark({ accessType: 'FREE', previewWatermarkEnabled: false })).toBe(false);
  expect(hasPreviewWatermark({ accessType: 'REDEEM' })).toBe(true);
  expect(hasPreviewWatermark({ accessType: 'REDEEM', previewWatermarkEnabled: false })).toBe(false);
});

it('reports generation progress and failures separately from the desired watermark policy', () => {
  expect(previewGenerationLabel({ previewGenerationStatus: 'PENDING' })).toBe('等待生成');
  expect(previewGenerationLabel({ previewGenerationStatus: 'PROCESSING' })).toBe('生成中');
  expect(previewGenerationLabel({ previewGenerationStatus: 'READY' })).toBe('已就绪');
  expect(previewGenerationLabel({ previewGenerationStatus: 'FAILED' })).toBe('生成失败');
});

it('only refreshes generation fields, preserving unsaved editor changes and the business lock', () => {
  const target = {
    title: '尚未保存的新名称', accessType: 'FREE', previewWatermarkEnabled: false,
    version: 4, previewRevision: 3, previewGenerationStatus: 'PROCESSING', previewGenerationError: null
  } as Wallpaper;
  const latest = {
    title: '服务端旧名称', accessType: 'REDEEM', previewWatermarkEnabled: true,
    version: 5, previewRevision: 8, previewGenerationStatus: 'FAILED' as const,
    previewGenerationError: 'PREVIEW_GENERATION_FAILED'
  };
  mergePreviewGeneration(target, latest);
  expect(target).toMatchObject({
    title: '尚未保存的新名称', accessType: 'FREE', previewWatermarkEnabled: false,
    version: 4, previewRevision: 8, previewGenerationStatus: 'FAILED', previewGenerationError: 'PREVIEW_GENERATION_FAILED'
  });
});

it('polls queued or processing previews and stops when generation finishes or fails', () => {
  expect(previewGenerationInProgress({ previewGenerationStatus: 'PENDING' })).toBe(true);
  expect(previewGenerationInProgress({ previewGenerationStatus: 'PROCESSING' })).toBe(true);
  expect(previewGenerationInProgress({ previewGenerationStatus: 'READY' })).toBe(false);
  expect(previewGenerationInProgress({ previewGenerationStatus: 'FAILED' })).toBe(false);
});

it('ignores a stale poll response after a newer rebuild has been queued', () => {
  const target = { previewGenerationStatus: 'PENDING', previewRevision: 9, previewGenerationError: null } as Wallpaper;
  mergePreviewGeneration(target, { previewGenerationStatus: 'READY', previewRevision: 8, previewGenerationError: null });
  expect(target).toMatchObject({ previewGenerationStatus: 'PENDING', previewRevision: 9 });
});
