<script setup lang="ts">
import { House, Images } from '@lucide/vue';

defineProps<{
  modelValue: 'home' | 'mine';
}>();

defineEmits<{
  'update:modelValue': [value: 'home' | 'mine'];
}>();

const items = [
  { id: 'home' as const, label: '首页', icon: House },
  { id: 'mine' as const, label: '我的', icon: Images }
];
</script>

<template>
  <nav class="qj-bottom-nav" aria-label="主要导航">
    <button
      v-for="item in items"
      :key="item.id"
      type="button"
      :class="{ active: modelValue === item.id }"
      :aria-current="modelValue === item.id ? 'page' : undefined"
      @click="$emit('update:modelValue', item.id)"
    >
      <component :is="item.icon" :size="20" :stroke-width="2" aria-hidden="true" />
      <span>{{ item.label }}</span>
    </button>
  </nav>
</template>

<style scoped>
.qj-bottom-nav {
  display: grid;
  min-height: var(--qj-size-bottom-navigation);
  padding: 7px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  border-radius: var(--qj-radius-navigation);
  background: var(--qj-color-navigation);
  box-shadow: var(--qj-shadow-floating);
}

.qj-bottom-nav button {
  display: flex;
  min-width: 0;
  min-height: 52px;
  align-items: center;
  justify-content: center;
  gap: 7px;
  border: 0;
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-navigation-inactive);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-semibold);
  background: transparent;
  cursor: pointer;
  transition:
    color var(--qj-duration-fast) var(--qj-ease-standard),
    background var(--qj-duration-fast) var(--qj-ease-standard);
}

.qj-bottom-nav button.active {
  color: var(--qj-color-ink);
  background: var(--qj-color-accent);
}
</style>
