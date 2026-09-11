<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjSubcategoryRail from '@/design-system/components/QjSubcategoryRail.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import { getCategory, getWallpapersByCategory } from '@/mocks/catalog';

const route = useRoute();
const router = useRouter();
const activeSubcategory = ref('all');
const searchKeyword = ref('');

const categoryId = computed(() => String(route.params.id || 'recommend'));
const category = computed(() => getCategory(categoryId.value));
const visibleWallpapers = computed(() => getWallpapersByCategory(category.value.id, activeSubcategory.value));

watch(categoryId, () => {
  activeSubcategory.value = 'all';
});

const search = () => {
  const keyword = searchKeyword.value.trim();
  if (keyword) void router.push({ path: '/search', query: { keyword } });
};
</script>

<template>
  <QjMobileShell active-tab="home">
    <QjPageHeader
      :title="category.label"
      action="service"
      @back="router.push('/home')"
      @service="router.push('/customer-service')"
    />
    <QjSearchBar v-model="searchKeyword" @search="search" />

    <section class="category-content">
      <QjSubcategoryRail v-model="activeSubcategory" :items="category.subcategories" />
      <div v-if="visibleWallpapers.length" class="prototype-wallpaper-grid">
        <QjWallpaperCard
          v-for="wallpaper in visibleWallpapers"
          :key="wallpaper.id"
          :src="wallpaper.image"
          :title="wallpaper.title"
          :type="wallpaper.type"
          @select="router.push(`/wallpapers/${wallpaper.id}`)"
        />
      </div>
      <QjStatePanel
        v-else
        class="category-empty"
        description="这个分类正在补充壁纸"
        @action="activeSubcategory = 'all'"
      />
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
