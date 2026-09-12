import { computed, ref } from 'vue';

import type { PageMetadata, PublicWallpaperPage, PublicWallpaperSummary } from '@/domain/catalog';
import { catalogErrorMessage } from '@/repositories/http/apiClient';
import { catalogRepository, type WallpaperListQuery } from '@/repositories/http/catalogRepository';

type PageLoader = (query: WallpaperListQuery) => Promise<PublicWallpaperPage>;

const emptyPage = (): PageMetadata => ({ page: 0, pageSize: 20, totalItems: 0, totalPages: 0 });

export const useWallpaperPage = (loader: PageLoader = catalogRepository.listWallpapers) => {
  const items = ref<PublicWallpaperSummary[]>([]);
  const page = ref<PageMetadata>(emptyPage());
  const loading = ref(false);
  const loadingMore = ref(false);
  const errorMessage = ref('');
  const moreErrorMessage = ref('');
  const hasMore = computed(() => page.value.page < page.value.totalPages);
  let requestVersion = 0;
  let currentQuery: WallpaperListQuery = {};

  const clear = () => {
    requestVersion += 1;
    items.value = [];
    page.value = emptyPage();
    loading.value = false;
    loadingMore.value = false;
    errorMessage.value = '';
    moreErrorMessage.value = '';
  };

  const load = async (query: WallpaperListQuery) => {
    clear();
    const version = requestVersion;
    currentQuery = { pageSize: 20, ...query, page: 1 };
    loading.value = true;
    try {
      const response = await loader(currentQuery);
      if (version !== requestVersion) return;
      items.value = response.items;
      page.value = response.page;
    } catch (error) {
      if (version === requestVersion) errorMessage.value = catalogErrorMessage(error);
    } finally {
      if (version === requestVersion) loading.value = false;
    }
  };

  const loadMore = async () => {
    if (loading.value || loadingMore.value || !hasMore.value) return;
    const version = requestVersion;
    loadingMore.value = true;
    moreErrorMessage.value = '';
    try {
      const response = await loader({ ...currentQuery, page: page.value.page + 1 });
      if (version !== requestVersion) return;
      const existingIds = new Set(items.value.map((item) => item.id));
      items.value.push(...response.items.filter((item) => !existingIds.has(item.id)));
      page.value = response.page;
    } catch (error) {
      if (version === requestVersion) moreErrorMessage.value = catalogErrorMessage(error);
    } finally {
      if (version === requestVersion) loadingMore.value = false;
    }
  };

  return { items, page, loading, loadingMore, errorMessage, moreErrorMessage, hasMore, load, loadMore, clear };
};
