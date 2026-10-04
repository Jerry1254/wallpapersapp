export interface ApiAssetPayload {
  contentUrl: string;
}

export interface ApiCapabilityPayload {
  deliveryPlatform: string;
  resourceType: string;
  placements: string[];
}

export interface ApiWallpaperPayload {
  id: string;
  title: string;
  accessType: string;
  cover: ApiAssetPayload;
  availableCapabilities: ApiCapabilityPayload[];
  copyrightNote?: string;
  previewRevision?: number;
  previewGenerationStatus?: string;
}

export interface ApiCategoryPayload {
  id: string;
  name: string;
  icon?: ApiAssetPayload;
  children?: ApiCategoryPayload[];
}

export interface ApiTutorialPayload {
  key: string;
  title: string;
  platform: string;
  wallpaperKind: string;
  video: ApiAssetPayload;
  sortOrder: number;
}

export class WallpaperCapability {
  deliveryPlatform: string;
  resourceType: string;
  placements: string[];
  label: string;

  constructor(deliveryPlatform: string, resourceType: string, placements: string[] = []) {
    this.deliveryPlatform = deliveryPlatform;
    this.resourceType = resourceType;
    this.placements = placements;
    this.label = WallpaperCapability.resolveLabel(deliveryPlatform, resourceType);
  }

  static fromApi(payload: ApiCapabilityPayload): WallpaperCapability {
    return new WallpaperCapability(payload.deliveryPlatform, payload.resourceType, payload.placements ?? []);
  }

  static resolveLabel(deliveryPlatform: string, resourceType: string): string {
    if (deliveryPlatform === 'HARMONYOS' && resourceType === 'MOVING_PHOTO') return '鸿蒙动态壁纸';
    if (deliveryPlatform === 'UNIVERSAL' && resourceType === 'STATIC_IMAGE') return '静态壁纸';
    if (deliveryPlatform === 'ANDROID' && resourceType === 'LAYER_PARALLAX') return '4D壁纸';
    if (deliveryPlatform === 'ANDROID' && resourceType === 'VIDEO') return '安卓动态壁纸';
    if (deliveryPlatform === 'IOS' && resourceType === 'LIVE_PHOTO') return '苹果动态壁纸';
    return '其他壁纸';
  }
}

export class WallpaperSummary {
  id: string;
  title: string;
  coverUrl: string;
  free: boolean;
  capabilities: WallpaperCapability[];
  copyrightNote: string;
  previewRevision: number;
  previewGenerationStatus: string;

  constructor(id: string, title: string, coverUrl: string, free: boolean,
    capabilities: WallpaperCapability[], copyrightNote: string = '',
    previewRevision: number = 0, previewGenerationStatus: string = 'READY') {
    this.id = id;
    this.title = title;
    this.coverUrl = coverUrl;
    this.free = free;
    this.capabilities = capabilities;
    this.copyrightNote = copyrightNote;
    this.previewRevision = previewRevision;
    this.previewGenerationStatus = previewGenerationStatus;
  }

  previewReady(): boolean {
    return this.previewGenerationStatus === 'READY';
  }

  previewUnavailableMessage(): string {
    return this.previewGenerationStatus === 'FAILED' ?
      '预览资源生成失败，请稍后重试' : '预览资源生成中，请稍后';
  }

  static fromApi(payload: ApiWallpaperPayload): WallpaperSummary {
    const capabilities: WallpaperCapability[] = [];
    (payload.availableCapabilities ?? []).forEach((item: ApiCapabilityPayload) => {
      capabilities.push(WallpaperCapability.fromApi(item));
    });
    return new WallpaperSummary(payload.id, payload.title, payload.cover.contentUrl,
      payload.accessType === 'FREE', capabilities, payload.copyrightNote ?? '',
      payload.previewRevision ?? 0, payload.previewGenerationStatus ?? 'READY');
  }
}

export class WallpaperCategory {
  id: string;
  name: string;
  iconUrl: string;
  children: WallpaperCategory[];

  constructor(id: string, name: string, iconUrl: string = '', children: WallpaperCategory[] = []) {
    this.id = id;
    this.name = name;
    this.iconUrl = iconUrl;
    this.children = children;
  }

  static fromApi(payload: ApiCategoryPayload): WallpaperCategory {
    const children: WallpaperCategory[] = [];
    (payload.children ?? []).forEach((item: ApiCategoryPayload) => {
      children.push(WallpaperCategory.fromApi(item));
    });
    return new WallpaperCategory(payload.id, payload.name, payload.icon?.contentUrl ?? '', children);
  }
}

export class WallpaperTutorial {
  key: string;
  title: string;
  platform: string;
  wallpaperKind: string;
  videoUrl: string;
  sortOrder: number;

  constructor(key: string, title: string, platform: string, wallpaperKind: string,
    videoUrl: string, sortOrder: number) {
    this.key = key;
    this.title = title;
    this.platform = platform;
    this.wallpaperKind = wallpaperKind;
    this.videoUrl = videoUrl;
    this.sortOrder = sortOrder;
  }

  static fromApi(payload: ApiTutorialPayload): WallpaperTutorial {
    return new WallpaperTutorial(payload.key, payload.title, payload.platform,
      payload.wallpaperKind, payload.video.contentUrl, payload.sortOrder);
  }
}

export class HarmonyCatalogProjection {
  static readonly FILTER_FEATURED: string = '精选推荐';
  static readonly FILTER_FREE: string = '免费壁纸';
  static readonly FILTER_MOVING_PHOTO: string = '鸿蒙动态';
  static readonly FILTER_STATIC: string = '静态壁纸';

  static isSupportedCapability(capability: WallpaperCapability): boolean {
    const isHarmonyMovingPhoto: boolean = capability.deliveryPlatform === 'HARMONYOS' &&
      capability.resourceType === 'MOVING_PHOTO';
    const isUniversalStatic: boolean = capability.deliveryPlatform === 'UNIVERSAL' &&
      capability.resourceType === 'STATIC_IMAGE';
    return isHarmonyMovingPhoto || isUniversalStatic;
  }

  static projectWallpaper(item: WallpaperSummary): WallpaperSummary | null {
    const supportedCapabilities: WallpaperCapability[] = [];
    item.capabilities.forEach((capability: WallpaperCapability) => {
      if (HarmonyCatalogProjection.isSupportedCapability(capability)) supportedCapabilities.push(capability);
    });
    if (supportedCapabilities.length === 0) return null;
    return new WallpaperSummary(item.id, item.title, item.coverUrl, item.free,
      supportedCapabilities, item.copyrightNote, item.previewRevision, item.previewGenerationStatus);
  }

  static projectCatalog(items: WallpaperSummary[]): WallpaperSummary[] {
    const projectedItems: WallpaperSummary[] = [];
    items.forEach((item: WallpaperSummary) => {
      const projected: WallpaperSummary | null = HarmonyCatalogProjection.projectWallpaper(item);
      if (projected !== null) projectedItems.push(projected as WallpaperSummary);
    });
    return projectedItems;
  }
}
