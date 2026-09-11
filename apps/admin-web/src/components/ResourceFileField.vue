<script setup lang="ts">
import { Delete, Document, UploadFilled } from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, ref } from 'vue';

import type { ResourceFile } from '@/domain/admin';

const props = defineProps<{
  modelValue?: ResourceFile;
  label: string;
  hint: string;
  accept: string;
  required?: boolean;
}>();

const emit = defineEmits<{ 'update:modelValue': [value: ResourceFile | undefined] }>();
const input = ref<HTMLInputElement>();
const dragging = ref(false);
const localObjectUrl = ref('');

const isImage = computed(() => props.modelValue?.mime.startsWith('image/') && props.modelValue.url);
const readableSize = computed(() => {
  const size = props.modelValue?.size ?? 0;
  if (!size) return '资源已登记';
  if (size < 1024 * 1024) return `${Math.max(1, Math.round(size / 1024))} KB`;
  return `${(size / 1024 / 1024).toFixed(1)} MB`;
});

const setFile = (selected?: File) => {
  if (!selected) return;
  if (localObjectUrl.value) URL.revokeObjectURL(localObjectUrl.value);
  localObjectUrl.value = URL.createObjectURL(selected);
  emit('update:modelValue', {
    name: selected.name,
    size: selected.size,
    mime: selected.type || 'application/octet-stream',
    url: localObjectUrl.value
  });
};

const onInput = (event: Event) => setFile((event.target as HTMLInputElement).files?.[0]);
const onDrop = (event: DragEvent) => {
  dragging.value = false;
  setFile(event.dataTransfer?.files?.[0]);
};
const remove = () => {
  if (localObjectUrl.value) URL.revokeObjectURL(localObjectUrl.value);
  localObjectUrl.value = '';
  if (input.value) input.value.value = '';
  emit('update:modelValue', undefined);
};

onBeforeUnmount(() => {
  if (localObjectUrl.value) URL.revokeObjectURL(localObjectUrl.value);
});
</script>

<template>
  <div class="resource-field" :class="{ 'is-dragging': dragging }" @dragover.prevent="dragging = true" @dragleave.prevent="dragging = false" @drop.prevent="onDrop">
    <input ref="input" hidden type="file" :accept="accept" @change="onInput" />
    <button v-if="!modelValue" class="resource-field__empty" type="button" @click="input?.click()">
      <ElIcon :size="28"><UploadFilled /></ElIcon>
      <span class="resource-field__copy">
        <strong>{{ label }}<i v-if="required" class="resource-field__required">*</i></strong>
        <small>{{ hint }}；点击选择或拖入文件</small>
      </span>
    </button>
    <div v-else class="resource-field__file">
      <img v-if="isImage" class="resource-field__preview" :src="modelValue.url" :alt="label" />
      <ElIcon v-else :size="22"><Document /></ElIcon>
      <div>
        <strong>{{ modelValue.name }}</strong>
        <small>{{ readableSize }} · {{ modelValue.mime || '未知格式' }}</small>
      </div>
      <ElButton text circle :icon="Delete" :aria-label="`移除${label}`" @click="remove" />
    </div>
  </div>
</template>

<style scoped>
.resource-field__empty { width: 100%; border: 0; color: inherit; background: transparent; text-align: left; }
</style>
