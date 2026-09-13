<script setup lang="ts">
import { Check, CheckCircle2, CircleAlert, LockKeyhole, PanelsTopLeft, Smartphone } from '@lucide/vue';
import { computed } from 'vue';

import QjPrimaryAction from './QjPrimaryAction.vue';

type WallpaperTarget = 'home' | 'lock' | 'both';

const props = withDefaults(defineProps<{
  modelValue: WallpaperTarget;
  availableTargets?: WallpaperTarget[];
  loading?: boolean;
  success?: boolean;
  failureReason?: string;
  permissionRequired?: boolean;
  demo?: boolean;
}>(), {
  availableTargets: () => ['home', 'lock', 'both'],
  loading: false,
  success: false,
  failureReason: '',
  permissionRequired: false,
  demo: false
});

const emit = defineEmits<{
  'update:modelValue': [value: WallpaperTarget];
  confirm: [];
  requestPermission: [];
}>();

const options = [
  { id: 'home' as const, label: '桌面壁纸', description: '显示在手机桌面', icon: PanelsTopLeft },
  { id: 'lock' as const, label: '锁屏壁纸', description: '显示在锁屏界面', icon: LockKeyhole },
  { id: 'both' as const, label: '桌面和锁屏', description: '两处使用同一张壁纸', icon: Smartphone }
];

const visibleOptions = computed(() => options.filter((item) => props.availableTargets.includes(item.id)));

const actionLabel = computed(() => {
  if (props.success) return '完成';
  if (props.permissionRequired) return props.demo ? '模拟授权并演示' : '获取动态壁纸权限';
  if (props.failureReason) return props.demo ? '重新演示' : '重新设置';
  return props.demo ? '确认演示' : '确认设置';
});

const handleAction = () => {
  if (props.permissionRequired) {
    emit('requestPermission');
    return;
  }
  emit('confirm');
};
</script>

<template>
  <section class="qj-target-sheet">
    <div class="qj-target-sheet__handle" aria-hidden="true"></div>

    <div v-if="success" class="qj-target-sheet__success" role="status">
      <span><CheckCircle2 :size="28" :stroke-width="2" aria-hidden="true" /></span>
      <div>
        <h3>{{ demo ? '设置演示完成' : '壁纸设置成功' }}</h3>
        <p>{{ demo ? '浏览器未修改系统壁纸，请在正式 App 中设置' : '请返回桌面观看效果' }}</p>
      </div>
    </div>

    <div v-else-if="failureReason" class="qj-target-sheet__failure" role="alert">
      <span><CircleAlert :size="28" :stroke-width="2" aria-hidden="true" /></span>
      <div>
        <h3>{{ demo ? '设置演示待授权' : '壁纸设置失败' }}</h3>
        <p>{{ failureReason }}</p>
      </div>
    </div>

    <template v-else>
      <header>
        <h3>设置到哪里</h3>
        <p>{{ demo ? '仅演示设置位置，浏览器不会修改系统壁纸' : '选项由当前手机的系统能力决定' }}</p>
      </header>

      <div class="qj-target-sheet__options" role="radiogroup" aria-label="壁纸设置位置">
        <button
          v-for="option in visibleOptions"
          :key="option.id"
          type="button"
          role="radio"
          :aria-checked="modelValue === option.id"
          :class="{ active: modelValue === option.id }"
          @click="emit('update:modelValue', option.id)"
        >
          <span class="qj-target-sheet__icon">
            <component :is="option.icon" :size="21" :stroke-width="1.9" aria-hidden="true" />
          </span>
          <span class="qj-target-sheet__copy">
            <strong>{{ option.label }}</strong>
            <small>{{ option.description }}</small>
          </span>
          <span class="qj-target-sheet__check" aria-hidden="true">
            <Check v-if="modelValue === option.id" :size="15" :stroke-width="2.5" />
          </span>
        </button>
      </div>
    </template>

    <QjPrimaryAction
      :label="actionLabel"
      :loading="loading"
      :tone="success || permissionRequired ? 'accent' : 'dark'"
      @press="handleAction"
    />
  </section>
</template>

<style scoped>
.qj-target-sheet {
  padding: 12px var(--qj-space-5) var(--qj-space-6);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-sheet) var(--qj-radius-sheet) 0 0;
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-card);
}

.qj-target-sheet__handle {
  width: 38px;
  height: 4px;
  margin: 0 auto var(--qj-space-5);
  border-radius: var(--qj-radius-pill);
  background: var(--qj-color-outline-strong);
}

.qj-target-sheet header {
  margin-bottom: var(--qj-space-4);
}

.qj-target-sheet h3,
.qj-target-sheet p {
  margin: 0;
}

.qj-target-sheet h3 {
  font-size: var(--qj-font-size-section-title);
}

.qj-target-sheet header p,
.qj-target-sheet__success p,
.qj-target-sheet__failure p {
  margin-top: 4px;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
}

.qj-target-sheet__options {
  display: grid;
  margin-bottom: var(--qj-space-5);
  gap: var(--qj-space-2);
}

.qj-target-sheet__options button {
  display: grid;
  min-height: 68px;
  padding: 10px 12px;
  grid-template-columns: 42px minmax(0, 1fr) 24px;
  align-items: center;
  gap: var(--qj-space-3);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-control);
  color: var(--qj-color-ink);
  text-align: left;
  background: var(--qj-color-surface-muted);
  cursor: pointer;
}

.qj-target-sheet__options button.active {
  border-color: var(--qj-color-accent);
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
}

.qj-target-sheet__icon,
.qj-target-sheet__check {
  display: grid;
  place-items: center;
}

.qj-target-sheet__icon {
  width: 42px;
  height: 42px;
  border-radius: 14px;
  color: var(--qj-color-ink-soft);
  background: var(--qj-color-surface-strong);
}

.qj-target-sheet__copy {
  display: grid;
  gap: 3px;
}

.qj-target-sheet__copy strong {
  font-size: var(--qj-font-size-body);
}

.qj-target-sheet__copy small {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}

.qj-target-sheet__check {
  width: 22px;
  height: 22px;
  border: 1px solid var(--qj-color-outline-strong);
  border-radius: 50%;
  color: var(--qj-color-ink);
}

.qj-target-sheet__options button.active .qj-target-sheet__check {
  border-color: var(--qj-color-accent);
  background: var(--qj-color-accent);
}

.qj-target-sheet__success,
.qj-target-sheet__failure {
  display: flex;
  min-height: 148px;
  margin-bottom: var(--qj-space-5);
  align-items: center;
  justify-content: center;
  gap: var(--qj-space-3);
  border-radius: var(--qj-radius-card);
  color: var(--qj-color-success);
  background: var(--qj-color-success-soft);
}

.qj-target-sheet__success > span,
.qj-target-sheet__failure > span {
  display: grid;
  width: 52px;
  height: 52px;
  place-items: center;
  border-radius: 18px;
  background: var(--qj-color-surface);
}

.qj-target-sheet__success h3,
.qj-target-sheet__failure h3 {
  color: var(--qj-color-ink);
}

.qj-target-sheet__failure {
  color: var(--qj-color-danger);
  background: var(--qj-color-danger-soft);
}
</style>
