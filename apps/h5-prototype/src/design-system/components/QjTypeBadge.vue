<script setup lang="ts">
withDefaults(defineProps<{
  label: string;
  tone?: 'dark' | 'amber' | 'success' | 'light';
}>(), {
  tone: 'dark'
});
</script>

<template>
  <span class="qj-type-badge" :data-tone="tone">{{ label }}</span>
</template>

<style scoped>
.qj-type-badge {
  display: inline-flex;
  min-height: 24px;
  padding: 0 9px;
  align-items: center;
  border-radius: var(--qj-radius-pill);
  font-size: var(--qj-font-size-micro);
  font-weight: var(--qj-font-weight-bold);
  letter-spacing: 0.02em;
}

.qj-type-badge[data-tone='dark'] {
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-ink);
}

.qj-type-badge[data-tone='amber'] {
  color: #855700;
  background: var(--qj-color-accent-soft);
}

.qj-type-badge[data-tone='success'] {
  color: var(--qj-color-success);
  background: var(--qj-color-success-soft);
}

.qj-type-badge[data-tone='light'] {
  color: var(--qj-color-ink-soft);
  background: rgba(255, 255, 255, 0.9);
}
</style>
