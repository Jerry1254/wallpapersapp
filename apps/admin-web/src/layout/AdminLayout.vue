<script setup lang="ts">
import {
  CollectionTag,
  DataAnalysis,
  Fold,
  Iphone,
  Key,
  List,
  Menu as MenuIcon,
  Picture,
  SwitchButton
} from '@element-plus/icons-vue';
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const collapsed = ref(false);
const mobile = ref(false);

const items = [
  { path: '/dashboard', label: '工作台', icon: DataAnalysis },
  { path: '/categories', label: '分类管理', icon: CollectionTag },
  { path: '/wallpapers', label: '壁纸管理', icon: Picture },
  { path: '/codes', label: '兑换码', icon: Key },
  { path: '/redemptions', label: '兑换记录', icon: List },
  { path: '/devices', label: '设备权益', icon: Iphone }
];

const pageTitle = computed(() => String(route.meta.title || '工作台'));

const updateViewport = () => {
  mobile.value = window.innerWidth < 860;
  if (mobile.value) collapsed.value = true;
};

const handleMenuSelect = () => {
  if (mobile.value) collapsed.value = true;
};

const logout = async () => {
  try {
    await auth.logout();
  } finally {
    await router.replace('/login');
  }
};

onMounted(() => {
  updateViewport();
  window.addEventListener('resize', updateViewport);
});

onBeforeUnmount(() => window.removeEventListener('resize', updateViewport));
</script>

<template>
  <div class="admin-shell" :class="{ collapsed, mobile }">
    <button v-if="mobile && !collapsed" class="admin-sidebar-backdrop" aria-label="关闭导航" @click="collapsed = true"></button>
    <aside class="admin-sidebar">
      <div class="admin-brand">
        <span>倾</span>
        <div v-if="!collapsed">
          <strong>倾境壁纸</strong>
          <small>管理后台</small>
        </div>
      </div>
      <ElMenu :default-active="route.path" router :collapse="collapsed" :collapse-transition="false" @select="handleMenuSelect">
        <ElMenuItem v-for="item in items" :key="item.path" :index="item.path">
          <ElIcon><component :is="item.icon" /></ElIcon>
          <template #title>{{ item.label }}</template>
        </ElMenuItem>
      </ElMenu>
      <div class="admin-sidebar__footer" :class="{ compact: collapsed }">
        <span class="status-dot"></span>
        <small v-if="!collapsed">本地管理环境</small>
      </div>
    </aside>

    <section class="admin-main">
      <header class="admin-topbar">
        <div>
          <ElButton text circle :aria-label="collapsed ? '展开导航' : '收起导航'" @click="collapsed = !collapsed">
            <ElIcon :size="20"><component :is="collapsed ? MenuIcon : Fold" /></ElIcon>
          </ElButton>
          <span class="admin-crumb">内容运营</span>
          <b>/</b>
          <strong>{{ pageTitle }}</strong>
        </div>
        <div class="admin-identity">
          <span>A</span>
          <div>
            <strong>{{ auth.username }}</strong>
            <small>管理员</small>
          </div>
          <ElButton text :icon="SwitchButton" @click="logout">退出</ElButton>
        </div>
      </header>
      <main class="admin-content">
        <RouterView />
      </main>
    </section>
  </div>
</template>
