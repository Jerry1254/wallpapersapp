<script setup lang="ts">
import { ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjCatalogMore from '@/components/QjCatalogMore.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSearchBar from '@/components/QjSearchBar.vue';
import { useWallpaperPage } from '@/composables/useWallpaperPage';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import { isFreeWallpaper, wallpaperTypeLabel } from '@/domain/catalog';

const route = useRoute();
const router = useRouter();
const keyword = ref(String(route.query.keyword || ''));
const submittedKeyword = ref('');
const {
  items: results, page, loading, loadingMore, errorMessage, moreErrorMessage,
  hasMore, load, loadMore, clear
} = useWallpaperPage();

const runSearch = async () => {
  const value = String(route.query.keyword || '').trim();
  submittedKeyword.value = value;
  keyword.value = value;
  if (!value) {
    clear();
    return;
  }
  await load({ q: value });
};

watch(() => route.query.keyword, runSearch, { immediate: true });

const search = () => {
  const value = keyword.value.trim();
  if (value === submittedKeyword.value) void runSearch();
  else void router.replace({ path: '/search', query: value ? { keyword: value } : {} });
};
</script>

<template>
  <QjMobileShell active-tab="home">
    <QjPageHeader title="搜索壁纸" action="service" @back="router.back()" @service="router.push('/customer-service')" />
    <QjSearchBar v-model="keyword" @search="search" />

    <section class="search-results">
      <div class="prototype-section__header">
        <h2>{{ submittedKeyword ? `“${submittedKeyword}”` : '搜索结果' }}</h2>
        <span v-if="!loading && !errorMessage">{{ page.totalItems }} 张壁纸</span>
      </div>
      <van-skeleton v-if="loading" title :row="6" />
      <QjStatePanel
        v-else-if="errorMessage"
        kind="error"
        :description="errorMessage"
        @action="runSearch"
      />
      <div v-else-if="results.length" class="prototype-wallpaper-grid">
        <QjWallpaperCard
          v-for="wallpaper in results"
          :key="wallpaper.id"
          :src="wallpaper.cover.contentUrl"
          :title="wallpaper.title"
          :type="wallpaperTypeLabel(wallpaper.kind)"
          :free="isFreeWallpaper(wallpaper)"
          @select="router.push(`/wallpapers/${wallpaper.id}`)"
        />
      </div>
      <QjStatePanel
        v-else
        class="search-empty"
        :description="submittedKeyword ? '没有找到相关壁纸，换个关键词试试' : '输入壁纸名称开始搜索'"
        @action="router.push('/home')"
      />
      <QjCatalogMore :has-more="hasMore" :loading="loadingMore" :error-message="moreErrorMessage" @load="loadMore" />
    </section>
  </QjMobileShell>
</template>

<style scoped>
.search-results { margin-top: var(--qj-space-7); }
.search-empty { margin-top: var(--qj-space-6); }
</style>
