<script setup lang="ts">
import { ChevronLeft, CirclePlay, Timer } from '@lucide/vue';
import { ref, watch } from 'vue';

import QjPrimaryAction from './QjPrimaryAction.vue';

const props = withDefaults(defineProps<{
  src: string;
  title: string;
  actionLabel?: string;
  actionLoading?: boolean;
  actionDisabled?: boolean;
  showTrial?: boolean;
  trialDisabled?: boolean;
}>(), {
  actionLabel: '下载壁纸',
  actionLoading: false,
  actionDisabled: false,
  showTrial: true,
  trialDisabled: false
});
const imageFailed = ref(false);
watch(() => props.src, () => { imageFailed.value = false; });

defineEmits<{
  back: [];
  action: [];
  tutorial: [];
  trial: [];
}>();
</script>

<template>
  <article class="qj-wallpaper-hero">
    <header class="qj-wallpaper-hero__top">
      <button type="button" class="qj-wallpaper-hero__back" aria-label="返回" @click="$emit('back')">
        <ChevronLeft :size="22" aria-hidden="true" />
      </button>
      <h3>{{ title }}</h3>
      <button type="button" class="qj-wallpaper-hero__tutorial" @click="$emit('tutorial')">
        <CirclePlay :size="16" aria-hidden="true" />
        观看教程
      </button>
    </header>
    <div class="qj-wallpaper-hero__media">
      <img v-if="!imageFailed" :src="src" :alt="title + '大图预览'" @error="imageFailed = true" />
      <p v-else class="qj-wallpaper-hero__fallback">封面暂不可用，已获得权益仍可核验</p>
      <div class="qj-wallpaper-hero__actions">
        <button
          v-if="showTrial"
          type="button"
          class="qj-wallpaper-hero__trial"
          :disabled="trialDisabled"
          @click="$emit('trial')"
        >
          <Timer :size="18" aria-hidden="true" />
          试用 2 分钟
        </button>
        <QjPrimaryAction
          :label="actionLabel"
          :loading="actionLoading"
          :disabled="actionDisabled"
          tone="accent"
          @press="$emit('action')"
        />
      </div>
    </div>
  </article>
</template>

<style scoped>
.qj-wallpaper-hero__fallback { display: grid; min-height: 440px; margin: 0; padding: 24px; place-items: center; color: var(--qj-color-muted-ink); text-align: center; }
.qj-wallpaper-hero {
  display: grid;
  gap: var(--qj-space-4);
}

.qj-wallpaper-hero__top {
  display: grid;
  min-height: 46px;
  grid-template-columns: var(--qj-size-touch-target-min) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--qj-space-2);
}

.qj-wallpaper-hero__back {
  display: grid;
  width: var(--qj-size-touch-target-min);
  height: var(--qj-size-touch-target-min);
  padding: 0;
  place-items: center;
  border: 1px solid var(--qj-color-outline);
  border-radius: 50%;
  color: var(--qj-color-ink);
  background: var(--qj-color-surface);
  cursor: pointer;
}

.qj-wallpaper-hero__tutorial {
  display: inline-flex;
  min-height: var(--qj-size-touch-target-min);
  padding: 0 12px;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--qj-color-accent);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-accent-strong);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-bold);
  white-space: nowrap;
  background: var(--qj-color-accent-soft);
  cursor: pointer;
}

.qj-wallpaper-hero__top h3 {
  overflow: hidden;
  margin: 0;
  font-size: var(--qj-font-size-section-title);
  font-weight: var(--qj-font-weight-heavy);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.qj-wallpaper-hero__media {
  position: relative;
  aspect-ratio: 1 / 2;
  overflow: hidden;
  border-radius: var(--qj-radius-card);
  background: var(--qj-color-surface-strong);
  box-shadow: var(--qj-shadow-card);
}

.qj-wallpaper-hero__media img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: cover;
}

.qj-wallpaper-hero__media::after {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  height: 132px;
  background: linear-gradient(to bottom, rgba(25, 24, 23, 0), rgba(25, 24, 23, 0.28));
  content: '';
  pointer-events: none;
}

.qj-wallpaper-hero__actions {
  position: absolute;
  z-index: 1;
  right: var(--qj-space-4);
  bottom: max(var(--qj-space-4), env(safe-area-inset-bottom));
  left: var(--qj-space-4);
  display: grid;
  gap: 10px;
}

.qj-wallpaper-hero__trial {
  display: inline-flex;
  width: 100%;
  min-height: 50px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 1px solid rgba(255, 255, 255, 0.68);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-body);
  font-weight: var(--qj-font-weight-bold);
  background: rgba(255, 255, 255, 0.88);
  box-shadow: var(--qj-shadow-soft);
  backdrop-filter: blur(12px);
  cursor: pointer;
}

.qj-wallpaper-hero__trial:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
