import type { IosAcquisitionConfiguration } from './admin';

export const iosPriceSyncLabel = (value: IosAcquisitionConfiguration) => {
  switch (value.priceSyncStatus) {
    case 'READY': return value.acquisitionMode === 'CREDITS' ? '积分商品价格核验通过' : value.chinaReferencePrice ? `¥${value.chinaReferencePrice}` : '价格同步中';
    case 'ERROR': return '同步失败，请重试';
    case 'STALE': return '价格已过期，等待同步';
    case 'UNAVAILABLE': return '自动查价尚未启用';
    default: return '等待从 Apple 同步';
  }
};

export const iosCreditPack = (credits?: number | null) => {
  if (!credits || !Number.isInteger(credits) || credits < 1 || credits > 30) return null;
  for (let pack = 1; pack <= 3; pack++) if (credits % pack === 0 && credits / pack <= 10) return { pack, quantity: credits / pack };
  return null;
};
