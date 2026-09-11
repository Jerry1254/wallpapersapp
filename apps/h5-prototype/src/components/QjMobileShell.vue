<script setup lang="ts">
import { useRouter } from 'vue-router';

import QjBottomNav from '@/design-system/components/QjBottomNav.vue';

withDefaults(defineProps<{
  activeTab?: 'home' | 'mine';
  showNavigation?: boolean;
}>(), {
  activeTab: 'home',
  showNavigation: true
});

const router = useRouter();

const changeTab = (tab: 'home' | 'mine') => {
  void router.push(tab === 'home' ? '/home' : '/mine');
};
</script>

<template>
  <main class="qj-mobile-shell qj-design-system" :class="{ 'qj-mobile-shell--with-nav': showNavigation }">
    <slot />
    <div v-if="showNavigation" class="qj-mobile-shell__navigation">
      <QjBottomNav :model-value="activeTab" @update:model-value="changeTab" />
    </div>
  </main>
</template>

<style scoped>
.qj-mobile-shell {
  position: relative;
  width: min(100%, var(--qj-size-content-max));
  min-height: 100dvh;
  margin: 0 auto;
  padding: var(--qj-space-5) var(--qj-size-page-gutter) 40px;
  overflow: hidden;
  background: var(--qj-color-surface);
}

.qj-mobile-shell--with-nav {
  padding-bottom: calc(112px + env(safe-area-inset-bottom));
}

.qj-mobile-shell__navigation {
  position: fixed;
  z-index: 30;
  bottom: max(var(--qj-space-5), env(safe-area-inset-bottom));
  left: 50%;
  width: min(calc(100% - 40px), 390px);
  transform: translateX(-50%);
}

@media (min-width: 560px) {
  .qj-mobile-shell {
    box-shadow: var(--qj-shadow-card);
  }
}
</style>
