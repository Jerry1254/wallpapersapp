import { apiRequest } from './apiClient';
import type { PageMetadata } from '@/domain/admin';

export type UserChannel = 'ANDROID_ONLINE' | 'ANDROID_OFFLINE' | 'IOS' | 'HARMONYOS' | 'OTHER';
export const userChannelLabels: Record<UserChannel, string> = {
  ANDROID_ONLINE: '倾境壁纸 · 安卓线上版', ANDROID_OFFLINE: '吉意壁纸 · 安卓线下版',
  IOS: '倾境壁纸 · iOS', HARMONYOS: '倾境壁纸 · 鸿蒙', OTHER: '其他 / 联调'
};
export interface OperationsOverview {
  timeZone: string; today: string; trackedSince: string; generatedAt: string;
  totalUsers: number; newUsersToday: number; activeUsersToday: number; returningUsersToday: number;
  activeUsers7Days: number; activeUsers30Days: number; bannedUsers: number;
  redemptionsToday: number; downloadRequestsToday: number;
  trend: Array<{ date: string; newUsers: number; activeUsers: number | null }>;
  channels: Array<{ channel: UserChannel; totalUsers: number; newUsersToday: number; activeUsersToday: number }>;
}
export interface DevicePurchase {
  id: string; kind: 'CREDIT_ORDER' | 'LEGACY_PURCHASE'; wallpaperId: string; wallpaperTitle: string;
  productId: string; transactionId?: string | null; environment: 'PRODUCTION' | 'SANDBOX' | 'XCODE';
  status: 'OPEN' | 'CANCELLED' | 'FULFILLED' | 'REFUNDED'; amount: number | null; currency: string | null;
  createdAt: string; fulfilledAt?: string | null; revokedAt?: string | null; restored: boolean;
}
export const operationsRepository = {
  async overview(days: 7 | 30 = 7, channel: UserChannel | '' = '') {
    return (await apiRequest<OperationsOverview>(`/admin/operations/overview?${new URLSearchParams({ days: String(days), ...(channel && { channel }) })}`)).data;
  },
  async saveNote(id: string, note: string, version: number) {
    return (await apiRequest<{ note: string; version: number }>(`/admin/devices/${id}/note`, {
      method: 'PUT', csrf: true, body: JSON.stringify({ note, version })
    })).data;
  },
  async purchases(id: string, page = 1) {
    return (await apiRequest<{ items: DevicePurchase[]; page: PageMetadata }>(`/admin/devices/${id}/purchases?page=${page}&pageSize=20`)).data;
  }
};
