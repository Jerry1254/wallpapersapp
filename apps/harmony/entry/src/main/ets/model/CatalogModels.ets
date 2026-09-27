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
