import type { AndroidAppPackageName, AppRelease, AppReleasePlatform, AppReleaseUpdate, StoreReleaseInput } from '@/domain/appReleases';
import { defaultAndroidAppPackage, validStoreRelease } from '@/domain/appReleases';
import { ApiError, apiRequest } from './apiClient';

interface ReleaseEnvelope<T> { data: T }
const root = '/admin/app-releases';

export const appReleaseRepository = {
  async list(platform: AppReleasePlatform, packageName: AndroidAppPackageName = defaultAndroidAppPackage): Promise<AppRelease[]> {
    const query = new URLSearchParams({ platform });
    if (platform === 'android') query.set('packageName', packageName);
    const { data } = await apiRequest<ReleaseEnvelope<{ items: AppRelease[] }>>(`${root}?${query}`);
    return data.data.items;
  },
  async uploadAndroid(file: File, releaseNotes: string, packageName: AndroidAppPackageName = defaultAndroidAppPackage): Promise<AppRelease> {
    if (!file.name.toLowerCase().endsWith('.apk')) throw new ApiError(422, 'APP_RELEASE_INVALID', '请选择正式 APK 安装包');
    if (!file.size) throw new ApiError(422, 'APP_RELEASE_INVALID', '安装包不能为空');
    const body = new FormData();
    body.set('file', file, file.name);
    body.set('releaseNotes', releaseNotes.trim());
    body.set('packageName', packageName);
    const { data } = await apiRequest<ReleaseEnvelope<AppRelease>>(`${root}/android`, {
      method: 'POST', body, csrf: true, timeoutMs: 180_000
    });
    return data.data;
  },
  async createStore(input: StoreReleaseInput): Promise<AppRelease> {
    const error = validStoreRelease(input);
    if (error) throw new ApiError(422, 'APP_RELEASE_INVALID', error);
    const { data } = await apiRequest<ReleaseEnvelope<AppRelease>>(root, {
      method: 'POST', csrf: true,
      body: JSON.stringify({ ...input, versionName: input.versionName.trim(), releaseNotes: input.releaseNotes.trim(), storeUrl: input.storeUrl.trim() })
    });
    return data.data;
  },
  async update(id: string, input: AppReleaseUpdate): Promise<AppRelease> {
    const { data } = await apiRequest<ReleaseEnvelope<AppRelease>>(`${root}/${encodeURIComponent(id)}`, {
      method: 'PUT', csrf: true, body: JSON.stringify(input)
    });
    return data.data;
  },
  async publish(id: string): Promise<AppRelease> {
    const { data } = await apiRequest<ReleaseEnvelope<AppRelease>>(`${root}/${encodeURIComponent(id)}/publish`, { method: 'POST', csrf: true });
    return data.data;
  },
  async deprecate(id: string): Promise<AppRelease> {
    const { data } = await apiRequest<ReleaseEnvelope<AppRelease>>(`${root}/${encodeURIComponent(id)}/deprecate`, { method: 'POST', csrf: true });
    return data.data;
  }
};
