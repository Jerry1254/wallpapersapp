<script setup lang="ts">
import { MessageCircleMore } from '@lucide/vue';

withDefaults(defineProps<{
  title?: string;
}>(), {
  title: '倾境'
});

defineEmits<{
  service: [];
}>();
</script>

<template>
  <header class="qj-brand-header">
    <strong>{{ title }}</strong>
    <button type="button" aria-label="联系客服" @click="$emit('service')">
      <MessageCircleMore :size="21" :stroke-width="2.2" aria-hidden="true" />
    </button>
  </header>
</template>

<style scoped>
.qj-brand-header {
  position: relative;
  display: flex;
  min-height: var(--qj-size-top-bar);
  align-items: center;
  justify-content: space-between;
  isolation: isolate;
}

.qj-brand-header::before {
  position: absolute;
  z-index: -1;
  top: -20px;
  right: -20px;
  width: 280px;
  height: 205px;
  background:
    radial-gradient(circle at 78% 16%, rgba(222, 156, 149, 0.38) 0%, rgba(248, 232, 229, 0.55) 34%, rgba(255, 243, 216, 0.32) 54%, transparent 74%);
  content: '';
  pointer-events: none;
}

.qj-brand-header strong,
.qj-brand-header button {
  position: relative;
  z-index: 1;
}

.qj-brand-header strong {
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-page-title);
  font-weight: var(--qj-font-weight-heavy);
  letter-spacing: -0.04em;
}

.qj-brand-header button {
  display: grid;
  width: var(--qj-size-touch-target-min);
  height: var(--qj-size-touch-target-min);
  padding: 0;
  place-items: center;
  border: 0;
  border-radius: 50%;
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
  cursor: pointer;
  transition: opacity var(--qj-duration-fast) var(--qj-ease-standard);
}

.qj-brand-header button:hover {
  opacity: 0.82;
}
</style>
