import { describe, expect, it } from 'vitest';

import { coverFilename, fitCoverDimensions } from '@/utils/coverImage';

describe('列表封面压缩参数', () => {
  it('只缩小大图并保持原始比例', () => {
    expect(fitCoverDimensions(2160, 3840)).toEqual({ width: 720, height: 1280 });
    expect(fitCoverDimensions(1080, 1080)).toEqual({ width: 720, height: 720 });
    expect(fitCoverDimensions(360, 640)).toEqual({ width: 360, height: 640 });
  });

  it('统一使用 WebP 文件名', () => {
    expect(coverFilename('风景.原图.PNG')).toBe('风景.原图.webp');
    expect(coverFilename('cover')).toBe('cover.webp');
  });
});
