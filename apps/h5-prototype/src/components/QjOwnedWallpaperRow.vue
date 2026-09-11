<script setup lang="ts">
import { ChevronRight } from '@lucide/vue';

import QjTypeBadge from '@/design-system/components/QjTypeBadge.vue';

defineProps<{
  src: string;
  title: string;
  type: string;
}>();

defineEmits<{
  select: [];
}>();
</script>

<template>
  <button type="button" class="qj-owned-row" @click="$emit('select')">
    <img :src="src" :alt="title + '壁纸缩略图'" />
    <span class="qj-owned-row__content">
      <span><QjTypeBadge :label="type" tone="light" /></span>
      <strong>{{ title }}</strong>
    </span>
    <ChevronRight :size="20" :stroke-width="1.8" aria-hidden="true" />
  </button>
</template>

<style scoped>
.qj-owned-row {
  display: grid;
  width: 100%;
  min-height: 118px;
  padding: var(--qj-space-3);
  grid-template-columns: 76px minmax(0, 1fr) 22px;
  align-items: center;
  gap: var(--qj-space-3);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  color: var(--qj-color-ink);
  text-align: left;
  background: var(--qj-color-surface);
  cursor: pointer;
}

.qj-owned-row > img {
  width: 76px;
  height: 96px;
  border-radius: 16px;
  object-fit: cover;
}

.qj-owned-row__content {
  display: grid;
  min-width: 0;
  gap: 5px;
}

.qj-owned-row__content > span {
  justify-self: start;
}

.qj-owned-row strong {
  overflow: hidden;
  font-size: var(--qj-font-size-card-title);
  text-overflow: ellipsis;
  white-space: nowrap;
}

</style>
