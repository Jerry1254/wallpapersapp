<script setup lang="ts">
defineProps<{
  items: Array<{ id: string; label: string }>;
  modelValue: string;
}>();

defineEmits<{
  'update:modelValue': [value: string];
}>();
</script>

<template>
  <nav class="qj-subcategory-rail" aria-label="壁纸二级分类">
    <button
      v-for="item in items"
      :key="item.id"
      type="button"
      :class="{ active: item.id === modelValue }"
      :aria-current="item.id === modelValue ? 'page' : undefined"
      @click="$emit('update:modelValue', item.id)"
    >
      {{ item.label }}
    </button>
  </nav>
</template>

<style scoped>
.qj-subcategory-rail {
  display: flex;
  margin-right: calc(var(--qj-size-page-gutter) * -1);
  padding: 2px var(--qj-size-page-gutter) 4px 0;
  gap: var(--qj-space-2);
  overflow-x: auto;
  scrollbar-width: none;
  scroll-snap-type: x proximity;
}

.qj-subcategory-rail::-webkit-scrollbar {
  display: none;
}

.qj-subcategory-rail button {
  flex: 0 0 auto;
  min-height: 38px;
  padding: 0 17px;
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
  font-weight: var(--qj-font-weight-medium);
  background: var(--qj-color-surface);
  cursor: pointer;
  scroll-snap-align: start;
  transition:
    color var(--qj-duration-fast) var(--qj-ease-standard),
    background var(--qj-duration-fast) var(--qj-ease-standard),
    border-color var(--qj-duration-fast) var(--qj-ease-standard);
}

.qj-subcategory-rail button.active {
  border-color: var(--qj-color-navigation);
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
}
</style>
