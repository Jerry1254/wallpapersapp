import type {
  DeliveryPlatform,
  PublicRootCategory,
  PublicWallpaperDetail,
  PublicWallpaperPage,
  WallpaperKind
} from '@/domain/catalog';

import { apiRequest } from './apiClient';

export interface WallpaperListQuery {
  page?: number;
  pageSize?: number;
  rootCategoryId?: string;
  childCategoryId?: string;
  view?: 'FEATURED' | 'STATIC';
  kind?: WallpaperKind;
  platform?: DeliveryPlatform;
  q?: string;
  sort?: 'DEFAULT' | 'NEWEST';
}

const queryString = (query: WallpaperListQuery) => {
  const parameters = new URLSearchParams();
  for (const [key, value] of Object.entries(query)) {
    if (value !== undefined) parameters.set(key, String(value));
  }
  const encoded = parameters.toString();
  return encoded ? `?${encoded}` : '';
};

export const catalogRepository = {
  async categories() {
    const response = await apiRequest<{ items: PublicRootCategory[] }>('/public/categories');
    return response.items;
  },

  listWallpapers(query: WallpaperListQuery = {}) {
    return apiRequest<PublicWallpaperPage>(`/public/wallpapers${queryString(query)}`);
  },

  wallpaper(wallpaperId: string, platform?: DeliveryPlatform) {
    const query = platform ? `?platform=${encodeURIComponent(platform)}` : '';
    return apiRequest<PublicWallpaperDetail>(`/public/wallpapers/${encodeURIComponent(wallpaperId)}${query}`);
  }
};
