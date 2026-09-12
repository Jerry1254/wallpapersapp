export type WallpaperKind = 'PARALLAX_4D' | 'DYNAMIC' | 'STATIC';
export type DeliveryPlatform = 'ANDROID' | 'IOS' | 'HARMONYOS' | 'UNIVERSAL';
export type ResourceType = 'LAYER_PARALLAX' | 'VIDEO' | 'LIVE_PHOTO' | 'STATIC_IMAGE' | 'THEME_PACKAGE';

export interface CategorySummary {
  id: string;
  name: string;
  slug: string;
}

export interface PublicMedia {
  assetId: string;
  contentUrl: string;
  mimeType: string;
  widthPx?: number | null;
  heightPx?: number | null;
}

export interface PublicChildCategory extends CategorySummary {
  sortOrder: number;
  wallpaperCount: number;
}

export interface PublicRootCategory extends CategorySummary {
  icon: PublicMedia;
  sortOrder: number;
  wallpaperCount: number;
  children: PublicChildCategory[];
}

export interface DeliveryCapability {
  platform: DeliveryPlatform;
  resourceType: ResourceType;
  minimumOsVersion?: string | null;
  capabilityRequirements: string[];
}

export interface PublicWallpaperSummary {
  id: string;
  title: string;
  slug: string;
  kind: WallpaperKind;
  rootCategory: CategorySummary;
  childCategory?: CategorySummary | null;
  cover: PublicMedia;
  featured: boolean;
  sortOrder: number;
  capabilities: DeliveryCapability[];
}

export interface PublicWallpaperDetail extends PublicWallpaperSummary {
  copyrightNote: string;
  publishedAt: string;
}

export interface PageMetadata {
  page: number;
  pageSize: number;
  totalItems: number;
  totalPages: number;
}

export interface PublicWallpaperPage {
  items: PublicWallpaperSummary[];
  page: PageMetadata;
}

export const wallpaperTypeLabel = (kind: WallpaperKind) => {
  if (kind === 'PARALLAX_4D') return '4D';
  if (kind === 'DYNAMIC') return '动态';
  return '静态';
};
