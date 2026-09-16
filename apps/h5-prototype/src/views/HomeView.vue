<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjCatalogMore from '@/components/QjCatalogMore.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import { useWallpaperPage } from '@/composables/useWallpaperPage';
import QjBrandHeader from '@/design-system/components/QjBrandHeader.vue';
import QjCategoryTile from '@/design-system/components/QjCategoryTile.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjSubcategoryRail from '@/design-system/components/QjSubcategoryRail.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import type { PublicRootCategory } from '@/domain/catalog';
import { wallpaperTypeLabel } from '@/domain/catalog';
import { catalogErrorMessage } from '@/repositories/http/apiClient';
import { catalogRepository, type WallpaperListQuery } from '@/repositories/http/catalogRepository';

const router = useRouter();
const activeSubcategory = ref('all');
const searchKeyword = ref('');
const categories = ref<PublicRootCategory[]>([]);
const categoryLoading = ref(true);
const {
  items: visibleWallpapers, loading, loadingMore, errorMessage, moreErrorMessage,
  hasMore, load, loadMore
} = useWallpaperPage();
let catalogVersion = 0;

const systemViews = [
  { id: 'all', label: '精选推荐' },
  { id: 'depth', label: '4D动态' },
  { id: 'dynamic', label: '动态壁纸' },
  { id: 'static', label: '静态壁纸' }
];
const tones = ['amber', 'sage', 'blush', 'stone'] as const;
const sectionTitle = computed(() => activeSubcategory.value === 'all' ? '精选壁纸' : systemViews.find((item) => item.id === activeSubcategory.value)?.label || '壁纸');

const selectedQuery = (): WallpaperListQuery => {
  if (activeSubcategory.value === 'depth') return { pageSize: 20, kind: 'PARALLAX_4D' };
  if (activeSubcategory.value === 'dynamic') return { pageSize: 20, kind: 'DYNAMIC' };
  if (activeSubcategory.value === 'static') return { pageSize: 20, view: 'STATIC' };
  return { pageSize: 20, view: 'FEATURED' };
};

const loadCatalog = async () => {
  const version = ++catalogVersion;
  categoryLoading.value = true;
  try {
    const [categoryItems] = await Promise.all([
      catalogRepository.categories(),
      load(selectedQuery())
    ]);
    if (version !== catalogVersion) return;
    categories.value = categoryItems;
  } catch (error) {
    if (version === catalogVersion) errorMessage.value = catalogErrorMessage(error);
  } finally {
    if (version === catalogVersion) categoryLoading.value = false;
  }
};

onMounted(loadCatalog);
watch(activeSubcategory, loadCatalog);

const openCategory = (categoryId: string) => {
  void router.push(`/categories/${categoryId}`);
};

const openWallpaper = (wallpaperId: string) => {
  void router.push(`/wallpapers/${wallpaperId}`);
};

const search = () => {
  const keyword = searchKeyword.value.trim();
  if (keyword) void router.push({ path: '/search', query: { keyword } });
};
</script>

<template>
  <QjMobileShell active-tab="home">
    <QjBrandHeader @service="router.push('/customer-service')" />
    <QjSearchBar v-model="searchKeyword" @search="search" />

    <van-skeleton v-if="loading || categoryLoading" class="home-loading" title :row="8" />
    <QjStatePanel
      v-else-if="errorMessage"
      class="home-state"
      kind="error"
      :description="errorMessage"
      @action="loadCatalog"
    />

    <template v-else>
    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>壁纸分类</h2>
      </div>
      <div class="home-category-grid">
        <QjCategoryTile
          v-for="(category, index) in categories"
          :key="category.id"
          :label="category.name"
          :icon-src="category.icon.contentUrl"
          :tone="tones[index % tones.length]"
          @select="openCategory(category.id)"
        />
      </div>
    </section>

    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>{{ sectionTitle }}</h2>
      </div>
      <QjSubcategoryRail v-model="activeSubcategory" :items="systemViews" />
      <div v-if="visibleWallpapers.length" class="prototype-wallpaper-grid">
        <QjWallpaperCard
          v-for="wallpaper in visibleWallpapers"
          :key="wallpaper.id"
          :src="wallpaper.cover.contentUrl"
          :title="wallpaper.title"
          :type="wallpaperTypeLabel(wallpaper.kind)"
          @select="openWallpaper(wallpaper.id)"
        />
      </div>
      <QjStatePanel v-else class="home-state" description="暂时没有符合条件的已发布壁纸" action-label="刷新目录" @action="loadCatalog" />
      <QjCatalogMore :has-more="hasMore" :loading="loadingMore" :error-message="moreErrorMessage" @load="loadMore" />
    </section>
    </template>
  </QjMobileShell>
</template>

<style scoped>
.home-category-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--qj-space-2);
}

.home-loading,
.home-state {
  margin-top: var(--qj-space-7);
}
</style>
