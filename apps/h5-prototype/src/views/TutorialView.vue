<script setup lang="ts">
import { Layers3, PlaySquare, Smartphone, TabletSmartphone } from '@lucide/vue';
import type { Component } from 'vue';
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSettingTutorialPlayer from '@/components/QjSettingTutorialPlayer.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import type { WallpaperTutorial, WallpaperTutorialKey } from '@/domain/tutorial';
import { catalogErrorMessage } from '@/repositories/http/apiClient';
import { tutorialRepository } from '@/repositories/http/tutorialRepository';

const router = useRouter();
const tutorials = ref<WallpaperTutorial[]>([]);
const selected = ref<WallpaperTutorial>();
const playerVisible = ref(false);
const loading = ref(true);
const loadError = ref('');

const icons: Record<WallpaperTutorialKey, Component> = {
  ANDROID_PARALLAX_4D: Layers3,
  ANDROID_DYNAMIC: PlaySquare,
  STATIC: Smartphone,
  HARMONYOS_DYNAMIC: TabletSmartphone,
  IOS_DYNAMIC: TabletSmartphone
};

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    tutorials.value = await tutorialRepository.list();
  } catch (error) {
    loadError.value = catalogErrorMessage(error);
  } finally {
    loading.value = false;
  }
};

const open = (tutorial: WallpaperTutorial) => {
  selected.value = tutorial;
  playerVisible.value = true;
};

onMounted(load);
</script>

<template>
  <QjMobileShell :show-navigation="false">
    <QjPageHeader title="壁纸设置教程" action="service" @back="router.back()" @service="router.push('/customer-service')" />
    <van-skeleton v-if="loading" class="tutorial-state" title :row="7" />
    <QjStatePanel v-else-if="loadError" class="tutorial-state" kind="error" :description="loadError" @action="load" />
    <QjStatePanel v-else-if="!tutorials.length" class="tutorial-state" description="设置教程暂时还没有发布" action-label="重新加载" @action="load" />
    <div v-else class="tutorial-list">
      <button v-for="(tutorial, index) in tutorials" :key="tutorial.key" type="button" class="tutorial-entry" @click="open(tutorial)">
        <span class="tutorial-entry__icon"><component :is="icons[tutorial.key]" :size="24" :stroke-width="1.8" aria-hidden="true" /></span>
        <span class="tutorial-entry__copy">
          <strong>{{ tutorial.title }}</strong>
          <small>点击播放</small>
        </span>
        <span class="tutorial-entry__number">{{ String(index + 1).padStart(2, '0') }}</span>
      </button>
    </div>
    <QjSettingTutorialPlayer
      v-if="selected"
      v-model="playerVisible"
      :src="selected.video.contentUrl"
      :title="selected.title"
    />
  </QjMobileShell>
</template>

<style scoped>
.tutorial-state { margin-top: var(--qj-space-6); }
.tutorial-list { display: grid; margin-top: var(--qj-space-6); gap: var(--qj-space-3); }
.tutorial-entry {
  position: relative;
  display: grid;
  width: 100%;
  min-height: 88px;
  padding: var(--qj-space-4);
  grid-template-columns: 50px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--qj-space-4);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  color: var(--qj-color-ink);
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
  text-align: left;
  cursor: pointer;
}
.tutorial-entry__icon { display: grid; width: 50px; height: 50px; place-items: center; border-radius: 17px; background: var(--qj-color-accent); }
.tutorial-entry__copy { display: grid; gap: 5px; }
.tutorial-entry__copy strong { font-size: var(--qj-font-size-card-title); }
.tutorial-entry__copy small { color: var(--qj-color-muted-ink); font-size: var(--qj-font-size-caption); }
.tutorial-entry__number { color: var(--qj-color-outline-strong); font-size: 20px; font-weight: var(--qj-font-weight-heavy); }
</style>
