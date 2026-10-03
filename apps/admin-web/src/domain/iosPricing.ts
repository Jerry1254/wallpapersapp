import type { IosAcquisitionConfiguration } from './admin';

export const iosPriceSyncLabel = (value: IosAcquisitionConfiguration) => {
  switch (value.priceSyncStatus) {
    case 'READY': return value.chinaReferencePrice ? `¥${value.chinaReferencePrice}` : '价格同步中';
    case 'ERROR': return '同步失败，请重试';
    case 'STALE': return '价格已过期，等待同步';
    case 'UNAVAILABLE': return '自动查价尚未启用';
    default: return '等待从 Apple 同步';
  }
};
