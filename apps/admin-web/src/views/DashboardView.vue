<script setup lang="ts">
import { CollectionTag, Key, Picture, Promotion, TrendCharts } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { statusLabels, wallpaperKindLabels, type AdminDashboard, type Category, type Wallpaper } from '@/domain/admin';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';
import { ElMessage } from 'element-plus';

const router = useRouter();
const loading = ref(true);
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
  try {
    const [data, categoryItems] = await Promise.all([adminRepository.dashboard(), adminRepository.categories()]);
    summary.value = data.summary;
    wallpapers.value = data.wallpapers;
    categories.value = categoryItems;
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '工作台加载失败'));
  } finally {
    loading.value = false;
  }
};

const statusLabel = (value: Wallpaper['status']) => statusLabels[value];
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning', archived: 'info' }[value] as 'info' | 'success' | 'warning');
const kindLabel = (value: Wallpaper['kind']) => wallpaperKindLabels[value];
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

    <div class="stat-grid">
      <article v-for="item in stats" :key="item.label" class="surface stat-card">
        <div class="stat-card__icon"><ElIcon :size="19"><component :is="item.icon" /></ElIcon></div>
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </div>

    <div class="dashboard-grid">
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
                <div class="wallpaper-cell__text"><strong>{{ row.title }}</strong><small>{{ kindLabel(row.kind) }}</small></div>
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
              <span><ElIcon><Picture /></ElIcon></span><div><strong>上传新壁纸</strong><small>选择类型并上传对应平台资源</small></div><ElIcon><Promotion /></ElIcon>
            </button>
            <button class="quick-action" type="button" @click="router.push('/categories')">
              <span><ElIcon><CollectionTag /></ElIcon></span><div><strong>维护分类</strong><small>管理首页一级分类与排序</small></div><ElIcon><Promotion /></ElIcon>
            </button>
            <button class="quick-action" type="button" @click="router.push('/codes')">
              <span><ElIcon><Key /></ElIcon></span><div><strong>兑换码接口阶段</strong><small>WP-P08 将接入批次生成和一次性导出</small></div><ElIcon><Promotion /></ElIcon>
            </button>
          </div>
        </section>
        <section class="surface">
          <header class="panel-heading"><div><h2>上传规则</h2><p>三种壁纸的资源组合</p></div></header>
          <div class="kind-guide">
            <article class="kind-guide__item"><header><strong>4D 分层</strong><ElTag size="small">Android</ElTag></header><p>封面 + 背景层 + 透明前景层；可附景深 JSON，详情页直接合成预览。</p></article>
            <article class="kind-guide__item"><header><strong>动态壁纸</strong><ElTag size="small" type="warning">多平台</ElTag></header><p>Android 上传 MP4；iOS 上传 MOV 与 JPEG；鸿蒙上传资源包。</p></article>
            <article class="kind-guide__item"><header><strong>静态壁纸</strong><ElTag size="small" type="info">全平台</ElTag></header><p>封面 + 一张高清原图，客户端负责安全裁切。</p></article>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>
