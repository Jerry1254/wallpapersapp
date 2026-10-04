export type WallpaperAccessType = 'REDEEM' | 'FREE';
export type PublishStatus = 'draft' | 'published' | 'offline' | 'archived';
export type ApiPlatform = 'ANDROID' | 'IOS' | 'HARMONYOS' | 'UNIVERSAL';
export type ResourceType = 'LAYER_PARALLAX' | 'VIDEO' | 'LIVE_PHOTO' | 'STATIC_IMAGE' | 'MOVING_PHOTO';
export type WallpaperCapability = 'android_parallax' | 'android_video' | 'ios_live_photo' | 'harmony_moving_photo' | 'universal_static';
export type ResourceVersionStatus = 'DRAFT' | 'VALIDATING' | 'READY' | 'PUBLISHED' | 'RETIRED' | 'REJECTED';
export type PreviewGenerationStatus = 'PENDING' | 'PROCESSING' | 'READY' | 'FAILED';
export interface PreviewRebuildPlan {
  wallpaperCount: number;
  resourceVersionCount: number;
  watermarkedWallpaperCount: number;
  cleanWallpaperCount: number;
}

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
  iosVideo?: ResourceFile;
  harmonyVideo?: ResourceFile;
}

export interface MovingPhotoStatus {
  status: 'PROCESSING' | 'READY' | 'REJECTED';
  video?: { mimeType: string; sizeBytes: number; sha256: string } | null;
  poster?: { mimeType: string; sizeBytes: number; sha256: string } | null;
  durationMs?: number | null;
  widthPx?: number | null;
  heightPx?: number | null;
  inputVideoCodec?: string | null;
  outputVideoCodec?: string | null;
  frameRate?: number | null;
  processingMode?: 'PASSTHROUGH' | 'REMUX' | 'TRANSCODE' | null;
  errorCode?: string | null;
  publishable: boolean;
}

export interface LivePhotoStatus {
  status: 'PROCESSING' | 'READY' | 'REJECTED';
  photo?: { mimeType: string; sizeBytes: number; sha256: string } | null;
  video?: { mimeType: string; sizeBytes: number; sha256: string } | null;
  assetIdentifier?: string | null;
  durationMs?: number | null;
  widthPx?: number | null;
  heightPx?: number | null;
  inputVideoCodec?: string | null;
  outputVideoCodec?: string | null;
  frameRate?: number | null;
  processingMode?: 'PASSTHROUGH' | 'REMUX' | 'TRANSCODE' | null;
  errorCode?: string | null;
  publishable: boolean;
}

export interface ResourceVersion {
  id: string;
  versionNo: number;
  status: ResourceVersionStatus;
  movingPhoto?: MovingPhotoStatus | null;
  livePhoto?: LivePhotoStatus | null;
}

export interface WallpaperVariant {
  id: string;
  platform: ApiPlatform;
  resourceType: ResourceType;
  enabled: boolean;
  resourceVersions: ResourceVersion[];
  version: number;
}

export interface Wallpaper {
  id: string;
  title: string;
  slug: string;
  categoryId: string;
  subcategoryId: string;
  accessType: WallpaperAccessType;
  offlinePromotionOnly?: boolean;
  previewWatermarkEnabled?: boolean;
  previewGenerationStatus?: PreviewGenerationStatus;
  previewRevision?: number;
  previewGenerationError?: string | null;
  capabilities: WallpaperCapability[];
  status: PublishStatus;
  sort: number;
  coverUrl: string;
  featuredRank: number | null;
  resources: WallpaperResources;
  copyrightNote: string;
  updatedAt: string;
  version: number;
  variants: WallpaperVariant[];
  iosAcquisition?: IosAcquisitionConfiguration;
}

export interface IosAcquisitionConfiguration {
  acquisitionMode?: 'CREDITS' | 'NON_CONSUMABLE';
  credits?: number | null;
  packCredits?: number | null;
  purchaseQuantity?: number | null;
  priceVersion?: number | null;
  productId: string;
  chinaReferencePrice?: string | null;
  priceSource?: 'APP_STORE_CONNECT';
  priceCurrency?: 'CNY';
  priceSyncStatus?: 'UNSYNCED' | 'READY' | 'STALE' | 'ERROR' | 'UNAVAILABLE';
  priceSyncedAt?: string | null;
  priceSyncError?: string | null;
  firstFreeEligible: boolean;
  enabled: boolean;
  productIdLocked: boolean;
  verifiedTransactionAt?: string | null;
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
  code?: string | null;
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
  publicId: string;
  platform: DevicePlatform;
  appInstallScope: string;
  status: DeviceStatus;
  iosTestDevice: boolean;
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
  sources: Array<'REDEMPTION' | 'IOS_FIRST_FREE' | 'IOS_IAP'>;
}

export interface IosResetOperation {
  resetId: string;
  deviceId: string;
  expectedGeneration: number;
  resultGeneration?: number | null;
  status: string;
  errorCode?: string | null;
  expiresAt: string;
  createdAt: string;
  completedAt?: string | null;
}

export interface AdminIosDeviceAcquisition {
  testDevice: boolean;
  freeGeneration: number;
  freeAllowance: 'AVAILABLE' | 'USED' | 'UNAVAILABLE' | 'PENDING_RESET';
  deviceCheckCheckedAt?: string | null;
  pendingReset?: IosResetOperation | null;
}

export interface DeviceDetail extends DeviceSummary {
  credentials: DeviceCredential[];
  entitlements: AdminEntitlement[];
  iosAcquisition?: AdminIosDeviceAcquisition | null;
}

export interface RedemptionDetail extends RedemptionSummary {
  device: DeviceSummary;
  entitlement?: AdminEntitlement | null;
}

export const wallpaperCapabilityLabels: Record<WallpaperCapability, string> = {
  android_parallax: 'Android 4D',
  android_video: 'Android 动态',
  ios_live_photo: 'iOS 实况',
  harmony_moving_photo: '鸿蒙动态',
  universal_static: '全平台静态'
};

export const statusLabels: Record<PublishStatus, string> = {
  draft: '草稿',
  published: '已发布',
  offline: '已下架',
  archived: '已归档'
};
