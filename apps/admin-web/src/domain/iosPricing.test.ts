import { describe, expect, it } from 'vitest';
import { iosPriceSyncLabel, iosCreditPack } from './iosPricing';
import type { IosAcquisitionConfiguration } from './admin';
const config = { productId: 'test.product', enabled: true, firstFreeEligible: true, productIdLocked: false };
it('shows only Apple-confirmed prices and distinguishes stale or failed synchronization', () => {
  expect(iosPriceSyncLabel({ ...config, priceSyncStatus: 'READY', chinaReferencePrice: '0.10' })).toBe('¥0.10');
  for (const status of ['UNSYNCED', 'STALE', 'ERROR', 'UNAVAILABLE'] as const) {
    expect(iosPriceSyncLabel({ ...config, priceSyncStatus: status, chinaReferencePrice: '99.00' })).not.toContain('99.00');
  }
  expect(iosPriceSyncLabel(config as IosAcquisitionConfiguration)).toContain('等待');
});


describe('iOS credit pack selection', () => {
  it('matches exact prices without exceeding Apple quantity limits', () => {
    const supported = [1,2,3,4,5,6,7,8,9,10,12,14,15,16,18,20,21,24,27,30];
    for (let amount=1; amount<=31; amount++) {
      const selected=iosCreditPack(amount);
      if (supported.includes(amount)) {
        expect(selected!.pack * selected!.quantity).toBe(amount);
        expect(selected!.quantity).toBeLessThanOrEqual(10);
      } else expect(selected).toBeNull();
    }
    expect(iosCreditPack(1.5)).toBeNull(); expect(iosCreditPack(-1)).toBeNull();
  });
});
