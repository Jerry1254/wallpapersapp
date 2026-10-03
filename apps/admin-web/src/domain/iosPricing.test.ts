import { expect, it } from 'vitest';
import { iosPriceSyncLabel } from './iosPricing';
import type { IosAcquisitionConfiguration } from './admin';
const config = { productId: 'test.product', enabled: true, firstFreeEligible: true, productIdLocked: false };
it('shows only Apple-confirmed prices and distinguishes stale or failed synchronization', () => {
  expect(iosPriceSyncLabel({ ...config, priceSyncStatus: 'READY', chinaReferencePrice: '0.10' })).toBe('¥0.10');
  for (const status of ['UNSYNCED', 'STALE', 'ERROR', 'UNAVAILABLE'] as const) {
    expect(iosPriceSyncLabel({ ...config, priceSyncStatus: status, chinaReferencePrice: '99.00' })).not.toContain('99.00');
  }
  expect(iosPriceSyncLabel(config as IosAcquisitionConfiguration)).toContain('等待');
});
