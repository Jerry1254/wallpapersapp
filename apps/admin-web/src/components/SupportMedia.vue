<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import type { SupportAttachment } from '@/domain/support';
import { supportLibraryRepository } from '@/repositories/http/supportRepository';
import { readableApiError } from '@/repositories/http/apiClient';
const props = defineProps<{ attachment: SupportAttachment }>();
const url = ref(''); const error = ref(''); let generation = 0;
async function load(refresh = false) {
  const token = ++generation; error.value = '';
  try { const result = await supportLibraryRepository.media(props.attachment.id, refresh); if (token === generation) url.value = result; }
  catch (cause) { if (token === generation) error.value = readableApiError(cause, '媒体暂时无法加载'); }
}
watch(() => props.attachment.id, () => { url.value = ''; void load(); }, { immediate: true });
onBeforeUnmount(() => generation++);
</script>
<template>
  <div class="support-media">
    <div v-if="error" class="support-media-error">{{ error }} <ElButton text @click="load(true)">重试</ElButton></div>
    <span v-else-if="!url">加载中…</span>
    <ElImage v-else-if="attachment.kind === 'IMAGE'" :src="url" :preview-src-list="[url]" preview-teleported fit="contain" @error="error = '图片加载失败，请重试'" />
    <video v-else :src="url" controls playsinline preload="metadata" @error="error = '视频加载失败，请重试'" />
  </div>
</template>
