<script setup lang="ts">
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import { searchWallpapers } from '@/mocks/catalog';

const route = useRoute();
const router = useRouter();
const keyword = ref(String(route.query.keyword || ''));
const submittedKeyword = computed(() => String(route.query.keyword || '').trim());
const results = computed(() => searchWallpapers(submittedKeyword.value));

watch(() => route.query.keyword, (value) => { keyword.value = String(value || ''); });

const search = () => {
  const value = keyword.value.trim();
  if (value) void router.replace({ path: '/search', query: { keyword: value } });
};
</script>

<template>
  <QjMobileShell active-tab="home">
    <QjPageHeader title="搜索壁纸" action="service" @back="router.back()" @service="router.push('/customer-service')" />
    <QjSearchBar v-model="keyword" @search="search" />

    <section class="search-results">
      <div class="prototype-section__header">
        <h2>{{ submittedKeyword ? `“${submittedKeyword}”` : '搜索结果' }}</h2>
        <span>{{ results.length }} 张壁纸</span>
      </div>
      <div v-if="results.length" class="prototype-wallpaper-grid">
        <QjWallpaperCard v-for="wallpaper in results" :key="wallpaper.id" :src="wallpaper.image" :title="wallpaper.title" :type="wallpaper.type" @select="router.push(`/wallpapers/${wallpaper.id}`)" />
      </div>
      <QjStatePanel v-else class="search-empty" :description="submittedKeyword ? '没有找到相关壁纸，换个关键词试试' : '输入壁纸名称、分类或风格开始搜索'" />
    </section>
  </QjMobileShell>
</template>

<style scoped>
.search-results { margin-top: var(--qj-space-7); }
.search-empty { margin-top: var(--qj-space-6); }
</style>
