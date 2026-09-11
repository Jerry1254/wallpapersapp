<script setup lang="ts">
import { LoaderCircle } from '@lucide/vue';

withDefaults(defineProps<{
  label: string;
  loading?: boolean;
  disabled?: boolean;
  tone?: 'dark' | 'accent';
}>(), {
  loading: false,
  disabled: false,
  tone: 'dark'
});

defineEmits<{
  press: [];
}>();
</script>

<template>
  <button
    type="button"
    class="qj-primary-action"
    :data-tone="tone"
    :disabled="disabled || loading"
    @click="$emit('press')"
  >
    <LoaderCircle v-if="loading" class="spin" :size="19" aria-hidden="true" />
    <span>{{ label }}</span>
  </button>
</template>

<style scoped>
.qj-primary-action {
  display: inline-flex;
  width: 100%;
  min-height: var(--qj-size-primary-control);
  padding: 0 var(--qj-space-6);
  align-items: center;
  justify-content: center;
  gap: var(--qj-space-2);
  border: 0;
  border-radius: var(--qj-radius-pill);
  font-size: var(--qj-font-size-body-large);
  font-weight: var(--qj-font-weight-bold);
  cursor: pointer;
  transition:
    opacity var(--qj-duration-fast) var(--qj-ease-standard),
    background var(--qj-duration-fast) var(--qj-ease-standard);
}

.qj-primary-action[data-tone='dark'] {
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
}

.qj-primary-action[data-tone='accent'] {
  color: var(--qj-color-ink);
  background: var(--qj-color-accent);
}

.qj-primary-action:hover:not(:disabled) {
  opacity: 0.86;
}

.qj-primary-action:disabled {
  color: var(--qj-color-subtle-ink);
  background: var(--qj-color-surface-strong);
  cursor: not-allowed;
}

.spin {
  animation: spin 900ms linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}
</style>
