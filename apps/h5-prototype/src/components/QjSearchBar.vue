<script setup lang="ts">
import { Search, X } from '@lucide/vue';

defineProps<{ modelValue: string; placeholder?: string }>();
const emit = defineEmits<{ 'update:modelValue': [value: string]; search: [] }>();
</script>

<template>
  <form class="qj-search-bar" role="search" @submit.prevent="$emit('search')">
    <Search :size="19" aria-hidden="true" />
    <input
      :value="modelValue"
      type="search"
      maxlength="40"
      :placeholder="placeholder || '搜索壁纸名称'"
      aria-label="搜索壁纸"
      @input="emit('update:modelValue', ($event.target as HTMLInputElement).value)"
    />
    <button v-if="modelValue" class="qj-search-bar__clear" type="button" aria-label="清空搜索" @click="emit('update:modelValue', '')"><X :size="17" /></button>
    <button class="qj-search-bar__submit" type="submit">搜索</button>
  </form>
</template>

<style scoped>
.qj-search-bar { min-height: 50px; display: flex; align-items: center; gap: 10px; margin-top: var(--qj-space-5); padding: 5px 5px 5px 16px; border: 1px solid var(--qj-color-outline); border-radius: 18px; background: var(--qj-color-surface); box-shadow: 0 10px 28px rgba(52, 43, 34, .07); }
.qj-search-bar > svg { flex: 0 0 auto; color: var(--qj-color-muted-ink); }
.qj-search-bar input { min-width: 0; flex: 1; padding: 0; border: 0; outline: 0; color: var(--qj-color-ink); font-size: 14px; background: transparent; }
.qj-search-bar input::-webkit-search-cancel-button { display: none; }
.qj-search-bar button { border: 0; cursor: pointer; }
.qj-search-bar__clear { width: 30px; height: 30px; display: grid; flex: 0 0 30px; place-items: center; padding: 0; border-radius: 50%; color: var(--qj-color-muted-ink); background: var(--qj-color-surface-strong); }
.qj-search-bar__submit {
  align-self: stretch;
  min-width: 66px;
  padding: 0 16px;
  border-radius: 14px;
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-accent-strong);
  box-shadow: 0 5px 12px rgba(219, 142, 0, 0.2);
  font-size: 14px;
  font-weight: var(--qj-font-weight-bold);
}

.qj-search-bar__submit:active {
  background: var(--qj-color-accent);
  box-shadow: none;
}
</style>
