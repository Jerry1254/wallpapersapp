import { common } from '@kit.AbilityKit';
import { preferences } from '@kit.ArkData';
import { productViewManager } from '@kit.AppGalleryKit';
import { http } from '@kit.NetworkKit';
import { JsonHttp } from '../network/JsonHttp';
import { InstalledAppVersion } from './AppUpdateEvents';

export interface AppRelease {
  id: string;
  platform: string;
  versionName: string;
  versionCode: number;
  releaseNotes: string;
  forceUpdate: boolean;
  status: string;
  deliveryType: string;
  storeUrl?: string;
}

export interface AppUpdatePolicy {
  platform: string;
  updateAvailable: boolean;
  mandatory: boolean;
  minimumVersion: AppRelease | null;
  latestVersion: AppRelease | null;
  checkedAt: string;
}

interface UpdateEnvelope {
  data?: AppUpdatePolicy;
  platform?: string;
}

export class AppUpdateService {
  private static readonly BASE_URL: string = 'https://wallpaper.biguo66.top/api/v1';
  private static readonly PREFERENCES_NAME: string = 'qingjing_app_update_state';
  private static readonly CACHED_POLICY: string = 'confirmed_mandatory_policy';
  private context: common.UIAbilityContext;
  private onPolicy: (policy: AppUpdatePolicy) => void;
  private cacheLoaded: boolean = false;
  private checking: Promise<AppUpdatePolicy | null> | null = null;
  private policy: AppUpdatePolicy | null = null;

  constructor(context: common.UIAbilityContext, onPolicy: (policy: AppUpdatePolicy) => void) {
    this.context = context;
    this.onPolicy = onPolicy;
  }

  async check(): Promise<AppUpdatePolicy | null> {
    if (this.checking !== null) return await (this.checking as Promise<AppUpdatePolicy | null>);
    const pending: Promise<AppUpdatePolicy | null> = this.performCheck();
    this.checking = pending;
    try {
      return await pending;
    } finally {
      this.checking = null;
    }
  }

  async confirmServerRestriction(): Promise<void> {
    const knownMinimum: AppRelease | null = this.policy?.minimumVersion ?? null;
    const policy: AppUpdatePolicy = {
      platform: 'harmony',
      updateAvailable: true,
      mandatory: true,
      minimumVersion: knownMinimum !== null && InstalledAppVersion.info().versionCode < knownMinimum.versionCode ?
        knownMinimum : null,
      latestVersion: this.policy?.latestVersion ?? null,
      checkedAt: new Date().toISOString()
    };
    this.policy = policy;
    this.onPolicy(policy);
    await this.saveCachedPolicy(policy);
  }

  private async performCheck(): Promise<AppUpdatePolicy | null> {
    await this.loadCachedPolicy();
    const installed = InstalledAppVersion.info();
    const path: string = '/app-updates/check?platform=harmony&versionName=' +
      encodeURIComponent(installed.versionName) + '&versionCode=' + installed.versionCode.toString();
    // The update endpoint deliberately runs before creating a device identity or session.
    const response: Object = await JsonHttp.request(AppUpdateService.BASE_URL + path, http.RequestMethod.GET);
    const envelope: UpdateEnvelope = response as UpdateEnvelope;
    const policy: AppUpdatePolicy = envelope.data === undefined ? response as AppUpdatePolicy : envelope.data;
    if (policy.platform.toLowerCase() !== 'harmony' || typeof policy.mandatory !== 'boolean' ||
      typeof policy.updateAvailable !== 'boolean') throw new Error('更新信息不完整，请重新检查');
    if (policy.mandatory && (policy.minimumVersion === null || policy.latestVersion === null)) {
      throw new Error('更新信息不完整，请重新检查');
    }
    this.policy = policy;
    this.onPolicy(policy);
    await this.saveCachedPolicy(policy);
    return policy;
  }

  private async loadCachedPolicy(): Promise<void> {
    if (this.cacheLoaded) return;
    this.cacheLoaded = true;
    try {
      const store: preferences.Preferences = await preferences.getPreferences(
        this.context, AppUpdateService.PREFERENCES_NAME);
      const raw: string = String(await store.get(AppUpdateService.CACHED_POLICY, ''));
      if (raw.length === 0) return;
      const policy: AppUpdatePolicy = JSON.parse(raw) as AppUpdatePolicy;
      const minimum: AppRelease | null = policy.minimumVersion;
      if (policy.platform.toLowerCase() !== 'harmony' || !policy.mandatory) return;
      // A genuinely upgraded installed bundle can satisfy a cached minimum even without a network connection.
      if (minimum !== null && InstalledAppVersion.info().versionCode >= minimum.versionCode) return;
      this.policy = policy;
      this.onPolicy(policy);
    } catch (error) {
      // A fresh installation or unreadable cache never establishes a forced update by itself.
    }
  }

  private async saveCachedPolicy(policy: AppUpdatePolicy): Promise<void> {
    const store: preferences.Preferences = await preferences.getPreferences(
      this.context, AppUpdateService.PREFERENCES_NAME);
    if (policy.mandatory) {
      await store.put(AppUpdateService.CACHED_POLICY, JSON.stringify(policy));
    } else {
      await store.delete(AppUpdateService.CACHED_POLICY);
    }
    await store.flush();
  }

  openStore(onError: (message: string) => void, onReturn: () => void): void {
    // AppGalleryKit resolves our installed bundle directly. Never open a remotely supplied arbitrary URI.
    productViewManager.loadProduct(this.context, {
      parameters: { bundleName: InstalledAppVersion.info().name }
    }, {
      onError: () => onError('暂时无法打开应用市场，请稍后重试'),
      onDisappear: () => onReturn()
    });
  }
}
