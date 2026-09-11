<script setup lang="ts">
import { ChevronLeft, MessageCircleMore } from '@lucide/vue';

withDefaults(defineProps<{
  title: string;
  action?: 'service' | 'none';
}>(), {
  action: 'none'
});

defineEmits<{
  back: [];
  service: [];
}>();
</script>

<template>
  <header class="qj-page-header">
    <button type="button" aria-label="返回" @click="$emit('back')">
      <ChevronLeft :size="22" aria-hidden="true" />
    </button>
    <h1>{{ title }}</h1>
    <button
      v-if="action === 'service'"
      type="button"
      class="qj-page-header__service"
      aria-label="联系客服"
      @click="$emit('service')"
    >
      <MessageCircleMore :size="20" aria-hidden="true" />
    </button>
    <span v-else aria-hidden="true"></span>
  </header>
</template>

<style scoped>
.qj-page-header {
  display: grid;
  min-height: 52px;
  grid-template-columns: 44px minmax(0, 1fr) 44px;
  align-items: center;
  gap: var(--qj-space-2);
}

.qj-page-header button,
.qj-page-header > span {
  display: grid;
  width: 44px;
  height: 44px;
  padding: 0;
  place-items: center;
  border: 1px solid var(--qj-color-outline);
  border-radius: 50%;
  color: var(--qj-color-ink);
  background: var(--qj-color-surface);
}

.qj-page-header h1 {
  overflow: hidden;
  margin: 0;
  font-size: var(--qj-font-size-section-title);
  font-weight: var(--qj-font-weight-heavy);
  text-align: center;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qj-page-header .qj-page-header__service {
  border-color: var(--qj-color-navigation);
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
}

.qj-page-header > span {
  border-color: transparent;
  background: transparent;
}
</style>
