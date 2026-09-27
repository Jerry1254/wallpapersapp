export class WallpaperCapability {
  deliveryPlatform: string;
  resourceType: string;
  label: string;

  constructor(deliveryPlatform: string, resourceType: string, label: string) {
    this.deliveryPlatform = deliveryPlatform;
    this.resourceType = resourceType;
    this.label = label;
  }
}

export class WallpaperSummary {
  id: string;
  title: string;
  coverUrl: string;
  free: boolean;
  capabilities: WallpaperCapability[];

  constructor(id: string, title: string, coverUrl: string, free: boolean,
    capabilities: WallpaperCapability[]) {
    this.id = id;
    this.title = title;
    this.coverUrl = coverUrl;
    this.free = free;
    this.capabilities = capabilities;
  }
}

export class WallpaperCategory {
  id: string;
  name: string;

  constructor(id: string, name: string) {
    this.id = id;
    this.name = name;
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
      if (HarmonyCatalogProjection.isSupportedCapability(capability)) {
        supportedCapabilities.push(capability);
      }
    });
    if (supportedCapabilities.length === 0) {
      return null;
    }
    return new WallpaperSummary(item.id, item.title, item.coverUrl, item.free, supportedCapabilities);
  }

  static projectCatalog(items: WallpaperSummary[]): WallpaperSummary[] {
    const projectedItems: WallpaperSummary[] = [];
    items.forEach((item: WallpaperSummary) => {
      const projected: WallpaperSummary | null = HarmonyCatalogProjection.projectWallpaper(item);
      if (projected !== null) {
        projectedItems.push(projected as WallpaperSummary);
      }
    });
    return projectedItems;
  }

  static hasCapability(item: WallpaperSummary, deliveryPlatform: string, resourceType: string): boolean {
    return item.capabilities.some((capability: WallpaperCapability) =>
      capability.deliveryPlatform === deliveryPlatform && capability.resourceType === resourceType);
  }

  static filterCatalog(items: WallpaperSummary[], filter: string): WallpaperSummary[] {
    if (filter === HarmonyCatalogProjection.FILTER_FREE) {
      return items.filter((item: WallpaperSummary) => item.free);
    }
    if (filter === HarmonyCatalogProjection.FILTER_MOVING_PHOTO) {
      return items.filter((item: WallpaperSummary) =>
        HarmonyCatalogProjection.hasCapability(item, 'HARMONYOS', 'MOVING_PHOTO'));
    }
    if (filter === HarmonyCatalogProjection.FILTER_STATIC) {
      return items.filter((item: WallpaperSummary) =>
        HarmonyCatalogProjection.hasCapability(item, 'UNIVERSAL', 'STATIC_IMAGE'));
    }
    return items;
  }
}
