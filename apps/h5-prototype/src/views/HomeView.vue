<script setup lang="ts">
import { Flame, Flower2, Image, Mountain, Sparkles } from '@lucide/vue';
import type { Component } from 'vue';
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import QjBrandHeader from '@/design-system/components/QjBrandHeader.vue';
import QjCategoryTile from '@/design-system/components/QjCategoryTile.vue';
import QjSubcategoryRail from '@/design-system/components/QjSubcategoryRail.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import { categories, getCategory, getWallpapersByCategory } from '@/mocks/catalog';

const router = useRouter();
const activeSubcategory = ref('all');
const searchKeyword = ref('');
const homeCategory = getCategory('recommend');

const categoryIcons: Record<string, Component> = {
  recommend: Sparkles,
  scenery: Mountain,
  zen: Flower2,
  character: Flame,
  static: Image
};

const visibleWallpapers = computed(() => getWallpapersByCategory('recommend', activeSubcategory.value));

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

    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>壁纸分类</h2>
      </div>
      <div class="home-category-grid">
        <QjCategoryTile
          v-for="category in categories"
          :key="category.id"
          :label="category.label"
          :icon="categoryIcons[category.id]!"
          :tone="category.tone"
          :active="category.id === 'recommend'"
          @select="openCategory(category.id)"
        />
      </div>
    </section>

    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>精选壁纸</h2>
        <button type="button" @click="openCategory('recommend')">查看全部</button>
      </div>
      <QjSubcategoryRail v-model="activeSubcategory" :items="homeCategory.subcategories" />
      <div class="prototype-wallpaper-grid">
        <QjWallpaperCard
          v-for="wallpaper in visibleWallpapers"
          :key="wallpaper.id"
          :src="wallpaper.image"
          :title="wallpaper.title"
          :type="wallpaper.type"
          @select="openWallpaper(wallpaper.id)"
        />
      </div>
    </section>
  </QjMobileShell>
</template>

<style scoped>
.home-category-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: var(--qj-space-2);
}
</style>
