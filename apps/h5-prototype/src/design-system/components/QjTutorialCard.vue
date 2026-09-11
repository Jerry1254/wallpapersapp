<script setup lang="ts">
import { BookOpen, ChevronRight, Layers3 } from '@lucide/vue';

defineEmits<{
  open: [];
}>();
</script>

<template>
  <button type="button" class="qj-tutorial-card" @click="$emit('open')">
    <span class="qj-tutorial-card__icon">
      <Layers3 :size="24" :stroke-width="1.8" aria-hidden="true" />
    </span>
    <span class="qj-tutorial-card__copy">
      <small><BookOpen :size="13" aria-hidden="true" /> 使用指南</small>
      <strong>壁纸设计教程</strong>
      <span>了解不同手机的预览、下载和设置方式</span>
    </span>
    <ChevronRight :size="20" :stroke-width="1.8" aria-hidden="true" />
  </button>
</template>

<style scoped>
.qj-tutorial-card {
  display: grid;
  width: 100%;
  min-height: 118px;
  padding: var(--qj-space-4);
  grid-template-columns: 50px minmax(0, 1fr) 22px;
  align-items: center;
  gap: var(--qj-space-3);
  border: 0;
  border-radius: var(--qj-radius-card);
  color: var(--qj-color-ink);
  text-align: left;
  background:
    radial-gradient(circle at 88% 16%, rgba(244, 169, 30, 0.26), transparent 30%),
    var(--qj-color-surface-muted);
  cursor: pointer;
  box-shadow: var(--qj-shadow-soft);
}

.qj-tutorial-card__icon {
  display: grid;
  width: 50px;
  height: 50px;
  place-items: center;
  border-radius: 17px;
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
}

.qj-tutorial-card__copy {
  display: grid;
  min-width: 0;
  gap: 3px;
}

.qj-tutorial-card small {
  display: flex;
  align-items: center;
  gap: 4px;
  color: var(--qj-color-accent-strong);
  font-size: var(--qj-font-size-micro);
  font-weight: var(--qj-font-weight-bold);
}

.qj-tutorial-card strong {
  font-size: var(--qj-font-size-card-title);
}

.qj-tutorial-card__copy > span {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
  line-height: var(--qj-line-height-caption);
}
</style>
