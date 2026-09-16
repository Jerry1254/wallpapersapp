import type { WallpaperTutorial } from '@/domain/tutorial';

import { apiRequest } from './apiClient';

export const tutorialRepository = {
  async list() {
    const response = await apiRequest<{ items: WallpaperTutorial[] }>('/public/wallpaper-tutorials');
    return [...response.items].sort((left, right) => left.sortOrder - right.sortOrder);
  }
};
