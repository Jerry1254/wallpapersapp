import { createRouter, createWebHistory } from 'vue-router';
import { useAuthStore } from '@/stores/auth';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/login', name: 'login', component: () => import('@/views/LoginView.vue'), meta: { title: '管理员登录' } },
    {
      path: '/',
      component: () => import('@/layout/AdminLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        { path: 'dashboard', name: 'dashboard', component: () => import('@/views/DashboardView.vue'), meta: { title: '工作台' } },
        { path: 'categories', name: 'categories', component: () => import('@/views/CategoriesView.vue'), meta: { title: '分类管理' } },
        { path: 'wallpapers', name: 'wallpapers', component: () => import('@/views/WallpapersView.vue'), meta: { title: '壁纸管理' } },
        { path: 'codes', name: 'codes', component: () => import('@/views/CodesView.vue'), meta: { title: '兑换码' } },
        { path: 'redemptions', name: 'redemptions', component: () => import('@/views/RedemptionsView.vue'), meta: { title: '兑换记录' } },
        { path: 'devices', name: 'devices', component: () => import('@/views/DevicesView.vue'), meta: { title: '设备权益' } }
      ]
    },
    { path: '/:pathMatch(.*)*', redirect: '/dashboard' }
  ]
});

router.beforeEach((to) => {
  const auth = useAuthStore();
  if (to.name !== 'login' && !auth.authenticated) return { name: 'login', query: { redirect: to.fullPath } };
  if (to.name === 'login' && auth.authenticated) return { name: 'dashboard' };
  return true;
});

router.afterEach((to) => {
  document.title = `${String(to.meta.title || '管理后台')} · 倾境壁纸`;
});

export default router;
