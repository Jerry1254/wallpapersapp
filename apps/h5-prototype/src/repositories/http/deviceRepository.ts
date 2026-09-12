import type { EntitlementPage, H5DownloadDescriptor, RedemptionResponse } from '@/domain/device';
import { getH5DeviceProvider } from './h5DeviceProvider';

export const deviceRepository = {
  entitlements: (page = 1) => getH5DeviceProvider().request<EntitlementPage>(`/device/me/entitlements?page=${page}&pageSize=20`),
  redeem: (body: string, key: string) => getH5DeviceProvider().request<RedemptionResponse>('/device/redemptions', {
    method: 'POST', body, acceptedStatuses: [422],
    headers: { 'Content-Type': 'application/json', 'Idempotency-Key': key }
  }),
  redemptionResult: (key: string) => getH5DeviceProvider().request<RedemptionResponse>(`/device/redemptions/${encodeURIComponent(key)}`),
  download: (wallpaperId: string) => getH5DeviceProvider().request<H5DownloadDescriptor>(`/device/wallpapers/${encodeURIComponent(wallpaperId)}/download-tickets`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ platform: 'H5_TEST', supportedResourceTypes: ['STATIC_IMAGE', 'LAYER_PARALLAX', 'VIDEO', 'LIVE_PHOTO', 'THEME_PACKAGE'] })
  })
};
