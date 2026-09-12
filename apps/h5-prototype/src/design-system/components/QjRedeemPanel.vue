<script setup lang="ts">
import { KeyRound } from '@lucide/vue';

import QjPrimaryAction from './QjPrimaryAction.vue';

withDefaults(defineProps<{
  modelValue?: string;
  status?: 'idle' | 'validating' | 'success' | 'error' | 'unknown';
  errorMessage?: string;
  successMessage?: string;
  pending?: boolean;
}>(), {
  modelValue: '',
  status: 'idle'
});

defineEmits<{
  redeem: [];
  confirm: [];
  'update:modelValue': [value: string];
}>();
</script>

<template>
  <section class="qj-redeem-panel">
    <div class="qj-redeem-panel__handle" aria-hidden="true"></div>
    <header>
      <span><KeyRound :size="21" :stroke-width="2" aria-hidden="true" /></span>
      <div>
        <h3>兑换这张壁纸</h3>
        <p>输入客服发送的兑换码</p>
      </div>
    </header>
    <label for="redeem-code-demo">兑换码</label>
    <input
      id="redeem-code-demo"
      :value="modelValue"
      placeholder="请输入兑换码"
      autocomplete="off"
      autocapitalize="characters"
      maxlength="32"
      :disabled="status === 'validating' || status === 'success'"
      @input="$emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <p v-if="status === 'success'" class="feedback feedback--success">{{ successMessage || '兑换成功，已绑定当前设备' }}</p>
    <p v-else-if="status === 'error' || status === 'unknown'" class="feedback feedback--error" role="status">{{ errorMessage || '兑换码无效或额度已用完' }}</p>
    <QjPrimaryAction v-if="pending" label="确认兑换结果" :loading="status === 'validating'" @press="$emit('confirm')" />
    <QjPrimaryAction
      :label="status === 'success' ? '下载壁纸' : pending ? '使用原码重试本次兑换' : '验证并兑换'"
      :loading="status === 'validating'"
      @press="$emit('redeem')"
    />
  </section>
</template>

<style scoped>
.qj-redeem-panel {
  padding: 12px var(--qj-space-5) var(--qj-space-6);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-sheet) var(--qj-radius-sheet) 0 0;
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-card);
}

.qj-redeem-panel__handle {
  width: 38px;
  height: 4px;
  margin: 0 auto var(--qj-space-5);
  border-radius: var(--qj-radius-pill);
  background: var(--qj-color-outline-strong);
}

.qj-redeem-panel header {
  display: flex;
  margin-bottom: var(--qj-space-5);
  align-items: center;
  gap: var(--qj-space-3);
}

.qj-redeem-panel header > span {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: var(--qj-color-accent-strong);
  background: var(--qj-color-accent-soft);
}

.qj-redeem-panel h3,
.qj-redeem-panel p {
  margin: 0;
}

.qj-redeem-panel h3 {
  font-size: var(--qj-font-size-section-title);
}

.qj-redeem-panel header p {
  margin-top: 3px;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
}

.qj-redeem-panel label {
  display: block;
  margin-bottom: 7px;
  color: var(--qj-color-ink-soft);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-semibold);
}

.qj-redeem-panel input {
  width: 100%;
  min-height: 52px;
  padding: 0 15px;
  border: 1px solid var(--qj-color-outline-strong);
  border-radius: var(--qj-radius-control);
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-body);
  font-weight: var(--qj-font-weight-semibold);
  letter-spacing: 0.04em;
  background: var(--qj-color-surface-muted);
}

.qj-redeem-panel input:focus {
  border-color: var(--qj-color-accent-strong);
  outline: 0;
  box-shadow: var(--qj-shadow-focus);
}

.feedback {
  min-height: 20px;
  padding: 9px 0 0;
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-semibold);
}

.qj-redeem-panel :deep(.qj-primary-action) {
  margin-top: var(--qj-space-4);
}

.feedback--success {
  color: var(--qj-color-success);
}

.feedback--error {
  color: var(--qj-color-danger);
}
</style>
