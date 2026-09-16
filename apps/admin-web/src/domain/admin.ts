export type WallpaperKind = 'four_d' | 'dynamic' | 'static';
export type WallpaperAccessType = 'REDEEM' | 'FREE';
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

export interface ParallaxPackageFile extends ResourceFile {
  packageId?: string;
  coverAssetId?: string;
  coverUrl?: string;
  sha256?: string;
  configFormatVersion?: number;
  layerCount?: number;
  canvasWidth?: number;
  canvasHeight?: number;
}

export type WallpaperTutorialKey =
  | 'ANDROID_PARALLAX_4D'
  | 'ANDROID_DYNAMIC'
  | 'STATIC'
  | 'HARMONYOS_DYNAMIC'
  | 'IOS_DYNAMIC';

export interface WallpaperTutorial {
  key: WallpaperTutorialKey;
  title: string;
  platform: ApiPlatform;
  wallpaperKind: 'PARALLAX_4D' | 'DYNAMIC' | 'STATIC';
  enabled: boolean;
  sortOrder: number;
  video?: ResourceFile;
  updatedAt?: string | null;
  version: number;
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
  parallaxPackage?: ParallaxPackageFile;
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
  accessType: WallpaperAccessType;
  platforms: Platform[];
  status: PublishStatus;
  sort: number;
  coverUrl: string;
  featuredRank: number | null;
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

export interface PageMetadata {
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export type DeliveryStatus = 'AVAILABLE' | 'CONFIRMED' | 'EXPIRED';
export type RedemptionCodeStatus = 'AVAILABLE' | 'EXHAUSTED';
export type RedemptionResult = 'GRANTED' | 'ALREADY_OWNED' | 'CODE_NOT_FOUND' | 'CODE_EXHAUSTED' | 'WALLPAPER_UNAVAILABLE' | 'WALLPAPER_FREE' | 'FAILED';
export type DevicePlatform = 'ANDROID' | 'IOS' | 'HARMONYOS' | 'H5_TEST';
export type DeviceStatus = 'ACTIVE' | 'REVIEW' | 'DISABLED';
export const devicePlatformLabels: Record<DevicePlatform, string> = { ANDROID: 'Android', IOS: 'iOS', HARMONYOS: 'HarmonyOS', H5_TEST: 'H5 联调' };
export const deviceStatusLabels: Record<DeviceStatus, string> = { ACTIVE: '正常', REVIEW: '待核查', DISABLED: '已停用' };

export interface CodeBatchSummary {
  id: string;
  batchNo: string;
  name: string;
  generatedCount: number;
  quotaPerCodeSnapshot: number;
  totalQuota: number;
  usedQuota: number;
  usagePercent: number;
  deliveryStatus: DeliveryStatus;
  deliveryConfirmedAt?: string | null;
  createdAt: string;
}

export interface CodeBatchDetail extends CodeBatchSummary {
  availableCodeCount: number;
  exhaustedCodeCount: number;
}

export interface CreateCodeBatchResponse {
  batch: CodeBatchDetail;
  deliveryTicket: string;
  deliveryUrl: string;
  deliveryExpiresAt: string;
}

export interface RedemptionCode {
  id: string;
  maskedCode: string;
  codeSuffix: string;
  totalQuota: number;
  usedQuota: number;
  remainingQuota: number;
  status: RedemptionCodeStatus;
}

export interface WallpaperRef {
  id: string;
  title: string;
  slug: string;
}

export interface RedemptionSummary {
  id: string;
  redemptionRequestId: string;
  idempotencyKey: string;
  deviceId: string;
  codeId?: string | null;
  maskedCode?: string | null;
  codeSuffix?: string | null;
  wallpaper: WallpaperRef;
  result: RedemptionResult;
  quotaDelta: number;
  errorCode?: string | null;
  createdAt: string;
}

export interface DeviceSummary {
  id: string;
  platform: DevicePlatform;
  appInstallScope: string;
  status: DeviceStatus;
  entitlementCount: number;
  lastSeenAt: string;
  createdAt: string;
}

export interface DeviceCredential {
  credentialKeyId: string;
  credentialType: 'PLATFORM_PUBLIC_KEY' | 'H5_TEST_SECRET';
  status: 'ACTIVE' | 'REVOKED';
  lastUsedAt?: string | null;
  revokedAt?: string | null;
}

export interface AdminEntitlement {
  id: string;
  wallpaper: WallpaperRef;
  status: 'ACTIVE' | 'REVOKED';
  grantedAt: string;
  revokedAt?: string | null;
}

export interface DeviceDetail extends DeviceSummary {
  credentials: DeviceCredential[];
  entitlements: AdminEntitlement[];
}

export interface RedemptionDetail extends RedemptionSummary {
  device: DeviceSummary;
  entitlement?: AdminEntitlement | null;
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
