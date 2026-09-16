import { createRouter, createWebHistory } from 'vue-router';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/home'
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '倾境壁纸' }
    },
    {
      path: '/categories/:id',
      name: 'category',
      component: () => import('@/views/CategoryView.vue'),
      meta: { title: '壁纸分类 · 倾境' }
    },
    {
      path: '/search',
      name: 'search',
      component: () => import('@/views/SearchView.vue'),
      meta: { title: '搜索壁纸 · 倾境' }
    },
    {
      path: '/wallpapers/:id',
      name: 'wallpaper-detail',
      component: () => import('@/views/WallpaperDetailView.vue'),
      meta: { title: '壁纸详情 · 倾境' }
    },
    {
      path: '/mine',
      name: 'mine',
      component: () => import('@/views/MineView.vue'),
      meta: { title: '我的 · 倾境' }
    },
    {
      path: '/tutorial',
      name: 'tutorial',
      component: () => import('@/views/TutorialView.vue'),
      meta: { title: '壁纸设置教程 · 倾境' }
    },
    {
      path: '/customer-service',
      name: 'customer-service',
      component: () => import('@/views/CustomerServiceView.vue'),
      meta: { title: '微信客服 · 倾境' }
    },
    {
      path: '/device-help',
      name: 'device-help',
      component: () => import('@/views/DeviceHelpView.vue'),
      meta: { title: '设备恢复 · 倾境' }
    },
    {
      path: '/design-system',
      name: 'design-system',
      component: () => import('@/views/DesignSystemView.vue'),
      meta: { title: '倾境壁纸设计系统' }
    },
    {
      path: '/:pathMatch(.*)*',
      redirect: '/home'
    }
  ],
  scrollBehavior() {
    return { top: 0 };
  }
});

router.afterEach((to) => {
  document.title = typeof to.meta.title === 'string' ? to.meta.title : '倾境壁纸';
});

export default router;
