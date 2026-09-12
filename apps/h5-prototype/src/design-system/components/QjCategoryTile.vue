<script setup lang="ts">
import type { Component } from 'vue';

withDefaults(defineProps<{
  label: string;
  icon?: Component;
  iconSrc?: string;
  active?: boolean;
  tone?: 'amber' | 'blush' | 'sage' | 'stone';
}>(), {
  active: false,
  tone: 'stone'
});

defineEmits<{
  select: [];
}>();
</script>

<template>
  <button
    type="button"
    class="qj-category-tile"
    :class="{ 'qj-category-tile--active': active }"
    :data-tone="tone"
    :aria-pressed="active"
    @click="$emit('select')"
  >
    <span class="qj-category-tile__icon">
      <img v-if="iconSrc" :src="iconSrc" alt="" />
      <component v-else-if="icon" :is="icon" :size="23" :stroke-width="1.8" aria-hidden="true" />
    </span>
    <span>{{ label }}</span>
  </button>
</template>

<style scoped>
.qj-category-tile {
  display: grid;
  min-width: 0;
  padding: 0;
  justify-items: center;
  gap: var(--qj-space-2);
  border: 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-medium);
  background: transparent;
  cursor: pointer;
}

.qj-category-tile__icon {
  display: grid;
  width: var(--qj-size-category-icon);
  height: var(--qj-size-category-icon);
  place-items: center;
  border: 1px solid transparent;
  border-radius: 19px;
  color: var(--tile-ink);
  background: var(--tile-background);
  transition:
    border-color var(--qj-duration-fast) var(--qj-ease-standard),
    box-shadow var(--qj-duration-fast) var(--qj-ease-standard);
}

.qj-category-tile__icon img {
  width: 100%;
  height: 100%;
  border-radius: inherit;
  object-fit: cover;
}

.qj-category-tile[data-tone='amber'] {
  --tile-background: var(--qj-color-accent-soft);
  --tile-ink: var(--qj-color-accent-strong);
}

.qj-category-tile[data-tone='blush'] {
  --tile-background: var(--qj-color-blush-soft);
  --tile-ink: #a5524b;
}

.qj-category-tile[data-tone='sage'] {
  --tile-background: var(--qj-color-success-soft);
  --tile-ink: var(--qj-color-success);
}

.qj-category-tile[data-tone='stone'] {
  --tile-background: var(--qj-color-surface-muted);
  --tile-ink: var(--qj-color-ink-soft);
}

.qj-category-tile--active {
  color: var(--qj-color-ink);
  font-weight: var(--qj-font-weight-bold);
}

.qj-category-tile--active .qj-category-tile__icon {
  border-color: var(--qj-color-accent);
  box-shadow: var(--qj-shadow-soft);
}
</style>
