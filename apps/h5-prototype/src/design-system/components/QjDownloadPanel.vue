<script setup lang="ts">
import { CheckCircle2, CircleAlert, Download, ShieldCheck } from '@lucide/vue';
import { computed } from 'vue';

import QjPrimaryAction from './QjPrimaryAction.vue';

const props = withDefaults(defineProps<{
  progress?: number;
  status?: 'downloading' | 'verifying' | 'success' | 'error';
}>(), {
  progress: 0,
  status: 'downloading'
});

defineEmits<{
  action: [];
}>();

const title = computed(() => {
  if (props.status === 'verifying') return '正在校验资源';
  if (props.status === 'success') return '壁纸下载完成';
  if (props.status === 'error') return '下载失败';
  return '正在下载壁纸';
});

const description = computed(() => {
  if (props.status === 'verifying') return '确认文件完整性与资源签名';
  if (props.status === 'success') return '资源已安全保存到当前设备';
  if (props.status === 'error') return '网络中断，请重新尝试';
  return `下载进度 ${props.progress}%`;
});

const icon = computed(() => {
  if (props.status === 'verifying') return ShieldCheck;
  if (props.status === 'success') return CheckCircle2;
  if (props.status === 'error') return CircleAlert;
  return Download;
});
</script>

<template>
  <section class="qj-download-panel">
    <div class="qj-download-panel__handle" aria-hidden="true"></div>
    <span class="qj-download-panel__icon" :data-status="status">
      <component :is="icon" :size="28" :stroke-width="1.9" aria-hidden="true" />
    </span>
    <h3>{{ title }}</h3>
    <p>{{ description }}</p>
    <div v-if="status === 'downloading' || status === 'verifying'" class="qj-download-panel__track" role="progressbar" :aria-valuenow="progress" aria-valuemin="0" aria-valuemax="100">
      <span :style="{ width: progress + '%' }"></span>
    </div>
    <QjPrimaryAction
      v-if="status === 'success' || status === 'error'"
      :label="status === 'success' ? '设置壁纸' : '重新下载'"
      :tone="status === 'success' ? 'accent' : 'dark'"
      @press="$emit('action')"
    />
  </section>
</template>

<style scoped>
.qj-download-panel {
  padding: 12px var(--qj-space-5) calc(var(--qj-space-6) + env(safe-area-inset-bottom));
  border-radius: var(--qj-radius-sheet) var(--qj-radius-sheet) 0 0;
  text-align: center;
  background: var(--qj-color-surface);
}

.qj-download-panel__handle {
  width: 38px;
  height: 4px;
  margin: 0 auto var(--qj-space-6);
  border-radius: var(--qj-radius-pill);
  background: var(--qj-color-outline-strong);
}

.qj-download-panel__icon {
  display: grid;
  width: 58px;
  height: 58px;
  margin: 0 auto var(--qj-space-4);
  place-items: center;
  border-radius: 20px;
  color: var(--qj-color-accent-strong);
  background: var(--qj-color-accent-soft);
}

.qj-download-panel__icon[data-status='success'] {
  color: var(--qj-color-success);
  background: var(--qj-color-success-soft);
}

.qj-download-panel__icon[data-status='error'] {
  color: var(--qj-color-danger);
  background: var(--qj-color-danger-soft);
}

.qj-download-panel h3 {
  margin: 0;
  font-size: var(--qj-font-size-section-title);
}

.qj-download-panel p {
  margin: 7px 0 var(--qj-space-5);
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
}

.qj-download-panel__track {
  height: 8px;
  margin: 0 0 var(--qj-space-4);
  overflow: hidden;
  border-radius: var(--qj-radius-pill);
  background: var(--qj-color-surface-strong);
}

.qj-download-panel__track span {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--qj-color-accent);
  transition: width var(--qj-duration-base) var(--qj-ease-standard);
}
</style>
