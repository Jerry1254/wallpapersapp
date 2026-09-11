export type WallpaperKind = 'four_d' | 'dynamic' | 'static';
export type PublishStatus = 'draft' | 'published' | 'offline';
export type Platform = 'android' | 'ios' | 'harmony';

export interface Category {
  id: string;
  name: string;
  parentId: string | null;
  iconUrl: string;
  sort: number;
  wallpaperCount: number;
}

export interface ResourceFile {
  name: string;
  size: number;
  mime: string;
  url?: string;
}

export interface WallpaperResources {
  cover?: ResourceFile;
  staticImage?: ResourceFile;
  backgroundLayer?: ResourceFile;
  foregroundLayer?: ResourceFile;
  depthConfig?: ResourceFile;
  androidVideo?: ResourceFile;
  iosMov?: ResourceFile;
  iosPhoto?: ResourceFile;
  harmonyPackage?: ResourceFile;
}

export interface Wallpaper {
  id: string;
  title: string;
  categoryId: string;
  subcategoryId: string;
  kind: WallpaperKind;
  platforms: Platform[];
  status: PublishStatus;
  sort: number;
  coverUrl: string;
  resources: WallpaperResources;
  downloads: number;
  updatedAt: string;
}

export interface CodeBatch {
  id: string;
  name: string;
  codeCount: number;
  quotaPerCode: number;
  redeemed: number;
  createdAt: string;
}

export interface RedemptionRecord {
  id: string;
  codeMask: string;
  wallpaperTitle: string;
  deviceMask: string;
  result: 'success' | 'failed';
  createdAt: string;
}

export interface DeviceEntitlement {
  id: string;
  deviceMask: string;
  platform: Platform;
  wallpaperCount: number;
  lastActiveAt: string;
  status: 'active' | 'disabled';
}

export const wallpaperKindLabels: Record<WallpaperKind, string> = {
  four_d: '4D 分层',
  dynamic: '动态壁纸',
  static: '静态壁纸'
};

export const platformLabels: Record<Platform, string> = {
  android: 'Android',
  ios: 'iOS',
  harmony: 'HarmonyOS'
};
