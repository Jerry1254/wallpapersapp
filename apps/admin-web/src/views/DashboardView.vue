<script setup lang="ts">
import { CollectionTag, Key, Picture, Promotion, TrendCharts } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';

import { wallpaperKindLabels, type Category, type CodeBatch, type Wallpaper } from '@/domain/admin';
import { adminRepository } from '@/repositories/mock/adminRepository';

const router = useRouter();
const loading = ref(true);
const wallpapers = ref<Wallpaper[]>([]);
const batches = ref<CodeBatch[]>([]);
const categories = ref<Category[]>([]);
const redemptionCount = ref(0);

const stats = computed(() => [
  { label: '壁纸总数', value: wallpapers.value.length, icon: Picture },
  { label: '已发布', value: wallpapers.value.filter((item) => item.status === 'published').length, icon: Promotion },
  { label: '可用兑换额度', value: batches.value.reduce((sum, item) => sum + Math.max(0, item.codeCount * item.quotaPerCode - item.redeemed), 0), icon: Key },
  { label: '累计兑换', value: redemptionCount.value, icon: TrendCharts }
]);

const load = async () => {
  loading.value = true;
  const data = await adminRepository.dashboard();
  wallpapers.value = data.wallpapers;
  batches.value = data.batches;
  categories.value = data.categories;
  redemptionCount.value = data.redemptions.filter((item) => item.result === 'success').length;
  loading.value = false;
};

const statusLabel = (value: Wallpaper['status']) => ({ draft: '草稿', published: '已发布', offline: '已下架' }[value]);
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning' }[value] as 'info' | 'success' | 'warning');
const kindLabel = (value: Wallpaper['kind']) => wallpaperKindLabels[value];
const dataCategoryName = (id: string) => categories.value.find((item) => item.id === id)?.name || '—';

onMounted(load);
</script>

<template>
  <section v-loading="loading" class="page-shell">
    <header class="page-heading">
      <div>
        <h1>工作台</h1>
        <p>查看内容状态，快速进入壁纸上传和兑换码生成。</p>
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
              <span><ElIcon><Key /></ElIcon></span><div><strong>生成兑换码</strong><small>批量生成并设置兑换次数快照</small></div><ElIcon><Promotion /></ElIcon>
            </button>
          </div>
        </section>
        <section class="surface">
          <header class="panel-heading"><div><h2>上传规则</h2><p>三种壁纸的资源组合</p></div></header>
          <div class="kind-guide">
            <article class="kind-guide__item"><header><strong>4D 分层</strong><ElTag size="small">Android</ElTag></header><p>封面 + 背景层 + 透明前景层；可附景深 JSON，详情页直接合成预览。</p></article>
            <article class="kind-guide__item"><header><strong>动态壁纸</strong><ElTag size="small" type="warning">多平台</ElTag></header><p>Android 上传 MP4；iOS 上传 MOV 与 HEIC；鸿蒙上传资源包。</p></article>
            <article class="kind-guide__item"><header><strong>静态壁纸</strong><ElTag size="small" type="info">全平台</ElTag></header><p>封面 + 一张高清原图，客户端负责安全裁切。</p></article>
          </div>
        </section>
      </div>
    </div>
  </section>
</template>
