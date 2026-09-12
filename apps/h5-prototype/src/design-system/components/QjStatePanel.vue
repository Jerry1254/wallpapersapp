<script setup lang="ts">
import { CircleAlert, PackageOpen, WifiOff } from '@lucide/vue';
import { computed } from 'vue';

defineEmits<{
  action: [];
}>();

const props = withDefaults(defineProps<{
  kind?: 'empty' | 'error' | 'offline';
  title?: string;
  description?: string;
  actionLabel?: string;
}>(), {
  kind: 'empty',
  title: '',
  description: ''
});

const icon = computed(() => {
  if (props.kind === 'error') return CircleAlert;
  if (props.kind === 'offline') return WifiOff;
  return PackageOpen;
});

const defaultTitle = computed(() => {
  if (props.title) return props.title;
  if (props.kind === 'error') return '加载失败';
  if (props.kind === 'offline') return '网络不可用';
  return '还没有壁纸';
});
</script>

<template>
  <section class="qj-state-panel" role="status">
    <span><component :is="icon" :size="26" :stroke-width="1.7" aria-hidden="true" /></span>
    <strong>{{ defaultTitle }}</strong>
    <p>{{ description || '稍后再来看看' }}</p>
    <button type="button" @click="$emit('action')">{{ actionLabel || (kind === 'empty' ? '返回首页' : '重新加载') }}</button>
  </section>
</template>

<style scoped>
.qj-state-panel {
  display: grid;
  min-height: 220px;
  padding: var(--qj-space-6);
  place-items: center;
  align-content: center;
  gap: var(--qj-space-2);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  text-align: center;
  background: var(--qj-color-surface);
}

.qj-state-panel > span {
  display: grid;
  width: 54px;
  height: 54px;
  margin-bottom: var(--qj-space-2);
  place-items: center;
  border-radius: 18px;
  color: var(--qj-color-muted-ink);
  background: var(--qj-color-surface-muted);
}

.qj-state-panel strong {
  font-size: var(--qj-font-size-card-title);
}

.qj-state-panel p {
  margin: 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
}

.qj-state-panel button {
  min-height: 40px;
  margin-top: var(--qj-space-2);
  padding: 0 16px;
  border: 1px solid var(--qj-color-outline-strong);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-bold);
  background: var(--qj-color-surface);
  cursor: pointer;
}
</style>
