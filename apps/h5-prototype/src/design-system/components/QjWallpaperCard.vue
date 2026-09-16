<script setup lang="ts">
import QjTypeBadge from './QjTypeBadge.vue';

defineProps<{
  src: string;
  title: string;
  type: string;
  free?: boolean;
}>();

defineEmits<{
  select: [];
}>();
</script>

<template>
  <button type="button" class="qj-wallpaper-card" @click="$emit('select')">
    <span class="qj-wallpaper-card__media">
      <img :src="src" :alt="title + '壁纸预览'" />
      <span class="qj-wallpaper-card__badges">
        <QjTypeBadge :label="type" tone="light" />
        <QjTypeBadge v-if="free" label="免费" tone="amber" />
      </span>
    </span>
    <span class="qj-wallpaper-card__content">
      <strong>{{ title }}</strong>
    </span>
  </button>
</template>

<style scoped>
.qj-wallpaper-card {
  display: grid;
  min-width: 0;
  padding: 0;
  gap: var(--qj-space-3);
  border: 0;
  color: var(--qj-color-ink);
  text-align: left;
  background: transparent;
  cursor: pointer;
}

.qj-wallpaper-card__media {
  position: relative;
  display: block;
  aspect-ratio: 3 / 4.15;
  overflow: hidden;
  border-radius: var(--qj-radius-media);
  background: var(--qj-color-surface-strong);
  box-shadow: var(--qj-shadow-card);
}

.qj-wallpaper-card__media img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
  transition: opacity var(--qj-duration-base) var(--qj-ease-standard);
}

.qj-wallpaper-card:hover img {
  opacity: 0.92;
}

.qj-wallpaper-card__badges {
  position: absolute;
  top: 10px;
  left: 10px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.qj-wallpaper-card__content {
  display: grid;
  min-width: 0;
  padding-inline: 2px;
  gap: 4px;
}

.qj-wallpaper-card__content strong {
  overflow: hidden;
  font-size: var(--qj-font-size-card-title);
  font-weight: var(--qj-font-weight-bold);
  text-overflow: ellipsis;
  white-space: nowrap;
}
</style>
