export type WallpaperKind = 'four_d' | 'dynamic' | 'static';
export type PublishStatus = 'draft' | 'published' | 'offline' | 'archived';
export type Platform = 'android' | 'ios' | 'harmony';
export type ApiPlatform = 'ANDROID' | 'IOS' | 'HARMONYOS' | 'UNIVERSAL';
export type ResourceType = 'LAYER_PARALLAX' | 'VIDEO' | 'LIVE_PHOTO' | 'STATIC_IMAGE' | 'THEME_PACKAGE';
export type ResourceVersionStatus = 'DRAFT' | 'VALIDATING' | 'READY' | 'PUBLISHED' | 'RETIRED' | 'REJECTED';

export interface ResourceFile {
  name: string;
  size: number;
  mime: string;
  url?: string;
  assetId?: string;
  nativeFile?: File;
}

export interface Category {
  id: string;
  name: string;
  slug: string;
  parentId: string | null;
  iconUrl: string;
  icon?: ResourceFile;
  sort: number;
  wallpaperCount: number;
  version: number;
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

export interface ResourceVersion {
  id: string;
  versionNo: number;
  status: ResourceVersionStatus;
}

export interface WallpaperVariant {
  id: string;
  platform: ApiPlatform;
  resourceType: ResourceType;
  resourceVersions: ResourceVersion[];
  version: number;
}

export interface Wallpaper {
  id: string;
  title: string;
  slug: string;
  categoryId: string;
  subcategoryId: string;
  kind: WallpaperKind;
  platforms: Platform[];
  status: PublishStatus;
  sort: number;
  coverUrl: string;
  resources: WallpaperResources;
  copyrightNote: string;
  updatedAt: string;
  version: number;
  variants: WallpaperVariant[];
}

export interface AdminDashboard {
  publishedWallpaperCount: number;
  activeDeviceCount: number;
  entitlementCount: number;
  redemptionCountToday: number;
  generatedAt: string;
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

export const statusLabels: Record<PublishStatus, string> = {
  draft: '草稿',
  published: '已发布',
  offline: '已下架',
  archived: '已归档'
};
