import { expect, it } from 'vitest';
import { statusLabels, wallpaperListStatus, type PublishStatus } from './admin';

it('groups legacy drafts under offline while keeping published and deleted items separate', () => {
  const statuses: PublishStatus[] = ['draft', 'published', 'offline', 'archived'];
  expect(statuses.filter((value) => wallpaperListStatus(value) === 'published')).toEqual(['published']);
  expect(statuses.filter((value) => wallpaperListStatus(value) === 'offline')).toEqual(['draft', 'offline']);
  expect(statuses.filter((value) => wallpaperListStatus(value) === 'archived')).toEqual(['archived']);
  expect(new Set(Object.values(statusLabels))).toEqual(new Set(['上架', '下架', '删除']));
});
