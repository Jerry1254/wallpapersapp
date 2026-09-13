<script setup lang="ts">
defineProps<{ error: string; loading?: boolean }>();
defineEmits<{ retry: [] }>();
</script>

<template>
  <ElAlert v-if="error" title="加载失败" type="error" show-icon :closable="false">
    <p>{{ error }}</p>
    <ElButton :loading="loading" @click="$emit('retry')">重试加载</ElButton>
  </ElAlert>
</template>
