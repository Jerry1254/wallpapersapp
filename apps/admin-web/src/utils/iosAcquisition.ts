import type { IosAcquisitionConfiguration } from '@/domain/admin';

export const iosCreditPrices = [1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 15, 16, 18, 20, 21, 24, 27, 30];

export function iosAcquisitionMode(value: IosAcquisitionConfiguration) {
  return value.acquisitionMode || (value.productId ? 'NON_CONSUMABLE' : 'CREDITS');
}

export function iosAcquisitionPayload(value: IosAcquisitionConfiguration) {
  const acquisitionMode = iosAcquisitionMode(value);
  return {
    acquisitionMode,
    ...(acquisitionMode === 'CREDITS' ? { credits: value.credits } : { productId: value.productId.trim() }),
    firstFreeEligible: value.firstFreeEligible,
    enabled: value.enabled
  };
}
