<script setup lang="ts">
import { ChevronRight, CirclePlay } from '@lucide/vue';

defineEmits<{
  open: [];
}>();
</script>

<template>
  <button type="button" class="qj-setting-tutorial-card" @click="$emit('open')">
    <span class="qj-setting-tutorial-card__icon">
      <CirclePlay :size="23" :stroke-width="1.9" aria-hidden="true" />
    </span>
    <span class="qj-setting-tutorial-card__copy">
      <strong>观看设置教程</strong>
      <small>了解如何设置桌面和锁屏壁纸</small>
    </span>
    <ChevronRight :size="20" aria-hidden="true" />
  </button>
</template>

<style scoped>
.qj-setting-tutorial-card {
  display: grid;
  width: 100%;
  min-height: 78px;
  padding: var(--qj-space-3) var(--qj-space-4);
  grid-template-columns: 44px minmax(0, 1fr) 20px;
  align-items: center;
  gap: var(--qj-space-3);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  color: var(--qj-color-ink);
  text-align: left;
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
  cursor: pointer;
}

.qj-setting-tutorial-card__icon {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border-radius: 15px;
  color: var(--qj-color-accent-strong);
  background: var(--qj-color-accent-soft);
}

.qj-setting-tutorial-card__copy {
  display: grid;
  gap: 4px;
}

.qj-setting-tutorial-card strong {
  font-size: var(--qj-font-size-body);
  font-weight: var(--qj-font-weight-bold);
}

.qj-setting-tutorial-card small {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}
</style>
