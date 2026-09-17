<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { CollectionTag, Key, Picture, Promotion, TrendCharts } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { statusLabels, wallpaperCapabilityLabels, type AdminDashboard, type Category, type Wallpaper } from '@/domain/admin';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';

const router = useRouter();
const loading = ref(true);
const loadError = ref('');
const wallpapers = ref<Wallpaper[]>([]);
const categories = ref<Category[]>([]);
const summary = ref<AdminDashboard>({
  publishedWallpaperCount: 0,
  activeDeviceCount: 0,
  entitlementCount: 0,
  redemptionCountToday: 0,
  generatedAt: ''
});

const stats = computed(() => [
  { label: '已发布壁纸', value: summary.value.publishedWallpaperCount, icon: Picture },
  { label: '活跃设备', value: summary.value.activeDeviceCount, icon: Promotion },
  { label: '累计设备权益', value: summary.value.entitlementCount, icon: Key },
  { label: '今日兑换', value: summary.value.redemptionCountToday, icon: TrendCharts }
]);

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    const [data, categoryItems] = await Promise.all([adminRepository.dashboard(), adminRepository.categories()]);
    summary.value = data.summary;
    wallpapers.value = data.wallpapers;
    categories.value = categoryItems;
  } catch (cause) {
    loadError.value = readableApiError(cause, '工作台加载失败');
  } finally {
    loading.value = false;
  }
};

const statusLabel = (value: Wallpaper['status']) => statusLabels[value];
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning', archived: 'info' }[value] as 'info' | 'success' | 'warning');
const capabilitySummary = (value: Wallpaper) => value.capabilities
  .map((item) => wallpaperCapabilityLabels[item]).join(' / ') || '未配置能力';
const dataCategoryName = (id: string) => categories.value.find((item) => item.id === id)?.name || '—';

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="page-shell">
    <header class="page-heading">
      <div>
        <h1>工作台</h1>
        <p>查看服务端实时内容、设备和兑换汇总，快速进入内容维护。</p>
      </div>
      <div class="page-actions">
        <ElButton type="primary" :icon="Picture" @click="router.push({ path: '/wallpapers', query: { create: '1' } })">上传壁纸</ElButton>
      </div>
    </header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />

    <div v-if="!loadError" class="stat-grid">
      <article v-for="item in stats" :key="item.label" class="surface stat-card">
        <div class="stat-card__icon"><ElIcon :size="19"><component :is="item.icon" /></ElIcon></div>
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>

    <div v-if="!loadError" class="dashboard-grid">
      <section class="surface content-table">
        <header class="panel-heading">
          <div><h2>最近更新</h2><p>按最后编辑时间展示壁纸内容</p></div>
          <ElButton text type="primary" @click="router.push('/wallpapers')">查看全部</ElButton>
        </header>
        <ElTable :data="wallpapers.slice(0, 5)">
          <ElTableColumn label="壁纸" min-width="230">
            <template #default="{ row }">
              <div class="wallpaper-cell">
                <img class="wallpaper-thumb" :src="row.coverUrl" :alt="row.title" />
                <div class="wallpaper-cell__text"><strong>{{ row.title }}</strong><small>{{ capabilitySummary(row) }}</small></div>
              </div>
            </template>
          </ElTableColumn>
          <ElTableColumn label="二级分类" min-width="120"><template #default="{ row }">{{ dataCategoryName(row.subcategoryId) }}</template></ElTableColumn>
          <ElTableColumn label="状态" width="100">
            <template #default="{ row }"><ElTag :type="statusType(row.status)" effect="light">{{ statusLabel(row.status) }}</ElTag></template>
          </ElTableColumn>
          <ElTableColumn prop="updatedAt" label="更新时间" min-width="145" />
        </ElTable>
      </section>

      <div class="page-shell">
        <section class="surface">
          <header class="panel-heading"><div><h2>快速操作</h2><p>常用内容管理入口</p></div></header>
          <div class="quick-actions">
            <button class="quick-action" type="button" @click="router.push({ path: '/wallpapers', query: { create: '1' } })">
              <span><ElIcon><Picture /></ElIcon></span><div><strong>上传新壁纸</strong><small>勾选设置能力并上传对应资源</small></div><ElIcon><Promotion /></ElIcon>
            </button>
            <button class="quick-action" type="button" @click="router.push('/categories')">
              <span><ElIcon><CollectionTag /></ElIcon></span><div><strong>维护分类</strong><small>管理首页一级分类与排序</small></div><ElIcon><Promotion /></ElIcon>
            </button>
            <button class="quick-action" type="button" @click="router.push('/codes')">
              <span><ElIcon><Key /></ElIcon></span><div><strong>生成兑换码</strong><small>批次生成、额度快照和一次性交付</small></div><ElIcon><Promotion /></ElIcon>
            </button>
          </div>
        </section>
        <section class="surface">
          <header class="panel-heading"><div><h2>上传规则</h2><p>五种设置能力可独立组合</p></div></header>
          <div class="kind-guide">
            <article class="kind-guide__item"><header><strong>Android</strong><ElTag size="small">4D / 动态</ElTag></header><p>4D 上传模拟器导出的完整 ZIP；动态上传 MP4。两项能力可同时发布。</p></article>
            <article class="kind-guide__item"><header><strong>iOS / HarmonyOS</strong><ElTag size="small" type="warning">平台原生</ElTag></header><p>iOS 上传 MOV 与 JPEG；鸿蒙上传主题资源包。</p></article>
            <article class="kind-guide__item"><header><strong>全平台静态</strong><ElTag size="small" type="info">独立能力</ElTag></header><p>只有勾选并上传高清原图后，商品才具备静态设置能力。</p></article>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>
