<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjCatalogMore from '@/components/QjCatalogMore.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import { useWallpaperPage } from '@/composables/useWallpaperPage';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjSubcategoryRail from '@/design-system/components/QjSubcategoryRail.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import type { PublicRootCategory } from '@/domain/catalog';
import { wallpaperTypeLabel } from '@/domain/catalog';
import { catalogErrorMessage } from '@/repositories/http/apiClient';
import { catalogRepository } from '@/repositories/http/catalogRepository';

const route = useRoute();
const router = useRouter();
const activeSubcategory = ref('all');
const searchKeyword = ref('');
const categories = ref<PublicRootCategory[]>([]);
const categoryLoading = ref(true);
const {
  items: visibleWallpapers, loading, loadingMore, errorMessage, moreErrorMessage,
  hasMore, load, loadMore
} = useWallpaperPage();
let categoryVersion = 0;

const categoryId = computed(() => String(route.params.id || ''));
const category = computed(() => categories.value.find((item) => item.id === categoryId.value));
const subcategories = computed(() => [
  { id: 'all', label: '全部' },
  ...(category.value?.children.map((child) => ({ id: child.id, label: child.name })) ?? [])
]);

const loadCategory = async () => {
  const version = ++categoryVersion;
  categoryLoading.value = true;
  try {
    const [categoryItems] = await Promise.all([
      catalogRepository.categories(),
      load({
        rootCategoryId: categoryId.value,
        childCategoryId: activeSubcategory.value === 'all' ? undefined : activeSubcategory.value
      })
    ]);
    if (version !== categoryVersion) return;
    categories.value = categoryItems;
    if (!categoryItems.some((item) => item.id === categoryId.value)) {
      errorMessage.value = '这个分类不存在或暂时没有已发布内容';
    }
  } catch (error) {
    if (version === categoryVersion) errorMessage.value = catalogErrorMessage(error);
  } finally {
    if (version === categoryVersion) categoryLoading.value = false;
  }
};

watch(categoryId, () => {
  if (activeSubcategory.value !== 'all') {
    activeSubcategory.value = 'all';
  } else {
    void loadCategory();
  }
}, { immediate: true });

watch(activeSubcategory, () => {
  void loadCategory();
});

const search = () => {
  const keyword = searchKeyword.value.trim();
  if (keyword) void router.push({ path: '/search', query: { keyword } });
};
</script>

<template>
  <QjMobileShell active-tab="home">
    <QjPageHeader
      :title="category?.name || '壁纸分类'"
      action="service"
      @back="router.push('/home')"
      @service="router.push('/customer-service')"
    />
    <QjSearchBar v-model="searchKeyword" @search="search" />

    <section class="category-content">
      <van-skeleton v-if="loading || categoryLoading" title :row="7" />
      <QjStatePanel
        v-else-if="errorMessage"
        kind="error"
        :description="errorMessage"
        @action="loadCategory"
      />
      <template v-else>
        <QjSubcategoryRail v-model="activeSubcategory" :items="subcategories" />
        <div v-if="visibleWallpapers.length" class="prototype-wallpaper-grid">
          <QjWallpaperCard
            v-for="wallpaper in visibleWallpapers"
            :key="wallpaper.id"
            :src="wallpaper.cover.contentUrl"
            :title="wallpaper.title"
            :type="wallpaperTypeLabel(wallpaper.kind)"
            @select="router.push(`/wallpapers/${wallpaper.id}`)"
          />
        </div>
        <QjStatePanel
          v-else
          class="category-empty"
          description="这个分类正在补充壁纸"
          @action="router.push('/home')"
        />
        <QjCatalogMore :has-more="hasMore" :loading="loadingMore" :error-message="moreErrorMessage" @load="loadMore" />
      </template>
    </section>
  </QjMobileShell>
</template>

<style scoped>
.category-content {
  margin-top: var(--qj-space-6);
}

.category-empty {
  margin-top: var(--qj-space-6);
}
</style>
