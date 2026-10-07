import { expect, it } from 'vitest';
import { iosAcquisitionMode, iosAcquisitionPayload, iosCreditPrices } from './iosAcquisition';

it('keeps an existing credit price when the response also includes a shared Apple product', () => {
  expect(iosAcquisitionPayload({
    acquisitionMode: 'CREDITS', credits: 2, productId: 'com.qingjing.bizhi.credits.1',
    enabled: true, firstFreeEligible: true, productIdLocked: false
  })).toEqual({ acquisitionMode: 'CREDITS', credits: 2, enabled: true, firstFreeEligible: true });
});

it('continues legacy product mappings without turning them into unconfigured credit purchases', () => {
  const legacy = { productId: 'old.wallpaper.1', enabled: true, firstFreeEligible: false, productIdLocked: true };
  expect(iosAcquisitionMode(legacy)).toBe('NON_CONSUMABLE');
  expect(iosAcquisitionPayload(legacy)).toEqual({
    acquisitionMode: 'NON_CONSUMABLE', productId: 'old.wallpaper.1', enabled: true, firstFreeEligible: false
  });
});

it('allows only prices representable by supported packs and at most ten units', () => {
  const representable = Array.from({ length: 30 }, (_, i) => i + 1)
    .filter(price => [1, 2, 3].some(pack => price % pack === 0 && price / pack <= 10));
  expect(iosCreditPrices).toEqual(representable);
});
