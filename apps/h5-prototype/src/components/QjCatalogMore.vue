<script setup lang="ts">
defineProps<{ hasMore: boolean; loading: boolean; errorMessage: string }>();
defineEmits<{ load: [] }>();
</script>

<template>
  <div v-if="hasMore" class="catalog-more">
    <p v-if="errorMessage" role="status">{{ errorMessage }}</p>
    <button type="button" :disabled="loading" @click="$emit('load')">
      {{ loading ? '正在加载…' : errorMessage ? '重试加载' : '加载更多' }}
    </button>
  </div>
</template>

<style scoped>
.catalog-more { display: grid; margin-top: var(--qj-space-6); gap: var(--qj-space-2); text-align: center; }
.catalog-more p { margin: 0; color: var(--qj-color-muted-ink); font-size: var(--qj-font-size-caption); }
.catalog-more button { min-height: 44px; border: 1px solid var(--qj-color-outline); border-radius: var(--qj-radius-pill); color: var(--qj-color-ink); background: var(--qj-color-surface); cursor: pointer; }
.catalog-more button:disabled { opacity: 0.6; cursor: wait; }
</style>
