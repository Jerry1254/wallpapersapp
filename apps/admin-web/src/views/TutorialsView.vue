<script setup lang="ts">
import { Refresh, VideoPlay } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { onMounted, ref, toRaw } from 'vue';

import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import ResourceFileField from '@/components/ResourceFileField.vue';
import type { WallpaperTutorial, WallpaperTutorialKey } from '@/domain/admin';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';

const tutorials = ref<WallpaperTutorial[]>([]);
const loading = ref(true);
const loadError = ref('');
const savingKey = ref<WallpaperTutorialKey>();

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    tutorials.value = await adminRepository.tutorials();
  } catch (cause) {
    loadError.value = readableApiError(cause, '教程配置加载失败');
  } finally {
    loading.value = false;
  }
};

const save = async (tutorial: WallpaperTutorial) => {
  if (!tutorial.video) {
    ElMessage.warning('请先上传教程 MP4');
    return;
  }
  savingKey.value = tutorial.key;
  try {
    const saved = await adminRepository.saveTutorial(toRaw(tutorial));
    const index = tutorials.value.findIndex((item) => item.key === saved.key);
    if (index >= 0) tutorials.value[index] = saved;
    ElMessage.success(`${saved.title}已更新`);
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '教程保存失败'));
  } finally {
    savingKey.value = undefined;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div>
        <h1>壁纸设置教程</h1>
        <p>五类教程使用固定入口；上传新 MP4 并保存后，正式 App 自动读取最新版本。</p>
      </div>
      <div class="page-actions"><ElButton :icon="Refresh" :loading="loading" @click="load">刷新</ElButton></div>
    </header>

    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />

    <div v-if="!loadError" v-loading="loading" class="tutorial-admin-grid">
      <article v-for="tutorial in tutorials" :key="tutorial.key" class="surface tutorial-admin-card">
        <header>
          <span><ElIcon :size="22"><VideoPlay /></ElIcon></span>
          <div><h2>{{ tutorial.title }}</h2><small>{{ tutorial.key }}</small></div>
          <ElSwitch v-model="tutorial.enabled" inline-prompt active-text="启用" inactive-text="停用" />
        </header>

        <ResourceFileField
          v-model="tutorial.video"
          label="教程视频"
          hint="MP4 视频，建议使用 H.264 编码"
          accept="video/mp4,.mp4"
          required
        />

        <video v-if="tutorial.video?.url" class="tutorial-admin-preview" :src="tutorial.video.url" controls preload="metadata"></video>

        <footer>
          <label>显示排序 <ElInputNumber v-model="tutorial.sortOrder" :min="0" :max="9999" controls-position="right" /></label>
          <span>{{ tutorial.updatedAt ? `最近更新 ${tutorial.updatedAt}` : '尚未配置' }}</span>
          <ElButton type="primary" :loading="savingKey === tutorial.key" :disabled="Boolean(savingKey && savingKey !== tutorial.key)" @click="save(tutorial)">保存更新</ElButton>
        </footer>
      </article>
    </div>
  </section>
</template>

<style scoped>
.tutorial-admin-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; min-height: 180px; }
.tutorial-admin-card { display: grid; align-content: start; gap: 16px; padding: 20px; }
.tutorial-admin-card > header { display: grid; grid-template-columns: 42px minmax(0, 1fr) auto; align-items: center; gap: 12px; }
.tutorial-admin-card > header > span { display: grid; width: 42px; height: 42px; place-items: center; border-radius: 10px; color: #a46000; background: #fff1d5; }
.tutorial-admin-card h2 { margin: 0; font-size: 16px; }
.tutorial-admin-card small { display: block; margin-top: 4px; color: var(--admin-muted); font-size: 11px; }
.tutorial-admin-preview { width: 100%; max-height: 280px; border-radius: 10px; background: #111; }
.tutorial-admin-card > footer { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.tutorial-admin-card > footer label { display: flex; align-items: center; gap: 8px; color: var(--admin-muted); font-size: 13px; }
.tutorial-admin-card > footer > span { flex: 1; color: var(--admin-muted); font-size: 12px; }
@media (max-width: 980px) { .tutorial-admin-grid { grid-template-columns: 1fr; } }
</style>
