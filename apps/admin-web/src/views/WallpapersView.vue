<script setup lang="ts">
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import WallpaperEditorDrawer from '@/components/WallpaperEditorDrawer.vue';
import { platformLabels, wallpaperKindLabels, type Category, type Platform, type ResourceFile, type Wallpaper, type WallpaperKind } from '@/domain/admin';
import { adminRepository } from '@/repositories/mock/adminRepository';

const route = useRoute();
const router = useRouter();
const loading = ref(true);
const keywords = ref('');
const kind = ref<WallpaperKind | ''>('');
const status = ref<Wallpaper['status'] | ''>('');
const categories = ref<Category[]>([]);
const wallpapers = ref<Wallpaper[]>([]);
const drawerOpen = ref(false);
const editing = ref<Wallpaper>();

const categoryMap = computed(() => Object.fromEntries(categories.value.map((item) => [item.id, item.name])));
const filtered = computed(() => wallpapers.value.filter((item) => {
  const keyword = keywords.value.trim().toLowerCase();
  return (!keyword || item.title.toLowerCase().includes(keyword) || (categoryMap.value[item.subcategoryId] || '').toLowerCase().includes(keyword))
    && (!kind.value || item.kind === kind.value)
    && (!status.value || item.status === status.value);
}));

const load = async () => {
  loading.value = true;
  [categories.value, wallpapers.value] = await Promise.all([adminRepository.categories(), adminRepository.wallpapers()]);
  loading.value = false;
};
const create = () => { editing.value = undefined; drawerOpen.value = true; };
const edit = (value: Wallpaper) => { editing.value = value; drawerOpen.value = true; };
const save = async (value: Wallpaper) => {
  await adminRepository.saveWallpaper(value);
  ElMessage.success(value.status === 'published' ? '壁纸已保存并发布' : '草稿已保存');
  drawerOpen.value = false;
  await load();
};
const changeStatus = async (value: Wallpaper, next: Wallpaper['status']) => {
  await adminRepository.saveWallpaper({ ...value, status: next });
  ElMessage.success(next === 'published' ? '已发布' : '已下架');
  await load();
};
const remove = async (value: Wallpaper) => {
  try {
    await ElMessageBox.confirm(
      `确定删除壁纸“${value.title}”吗？删除后无法恢复。`,
      '删除壁纸',
      { confirmButtonText: '确认删除', cancelButtonText: '取消', type: 'warning' }
    );
    await adminRepository.deleteWallpaper(value.id);
    ElMessage.success('壁纸已删除');
    await load();
  } catch {
    // 用户取消时保留当前列表。
  }
};

const requiredResources = (value: Wallpaper) => {
  if (value.kind === 'four_d') return [value.resources.cover || value.coverUrl, value.resources.backgroundLayer, value.resources.foregroundLayer];
  if (value.kind === 'static') return [value.resources.cover || value.coverUrl, value.resources.staticImage];
  const result: (ResourceFile | string | undefined)[] = [value.resources.cover || value.coverUrl];
  if (value.platforms.includes('android')) result.push(value.resources.androidVideo);
  if (value.platforms.includes('ios')) result.push(value.resources.iosMov, value.resources.iosPhoto);
  if (value.platforms.includes('harmony')) result.push(value.resources.harmonyPackage);
  return result;
};
const resourceSummary = (value: Wallpaper) => {
  const all = requiredResources(value);
  return { ready: all.filter(Boolean).length, total: all.length };
};
const statusLabel = (value: Wallpaper['status']) => ({ draft: '草稿', published: '已发布', offline: '已下架' }[value]);
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning' }[value] as 'info' | 'success' | 'warning');
const kindLabel = (value: WallpaperKind) => wallpaperKindLabels[value];
const platformLabel = (value: Platform) => platformLabels[value];

watch(() => route.query.create, (value) => {
  if (value === '1') {
    create();
    router.replace({ path: '/wallpapers' });
  }
}, { immediate: true });
onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>壁纸管理</h1><p>上传、检查并发布 4D 分层、动态和静态壁纸。</p></div>
      <div class="page-actions"><ElButton type="primary" :icon="Plus" @click="create">上传壁纸</ElButton></div>
    </header>

    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="keywords" :prefix-icon="Search" clearable placeholder="搜索名称或二级分类" style="width: 250px" />
        <ElSelect v-model="kind" clearable placeholder="全部类型" style="width: 145px">
          <ElOption v-for="(label, value) in wallpaperKindLabels" :key="value" :label="label" :value="value" />
        </ElSelect>
        <ElSelect v-model="status" clearable placeholder="全部状态" style="width: 130px">
          <ElOption label="草稿" value="draft" /><ElOption label="已发布" value="published" /><ElOption label="已下架" value="offline" />
        </ElSelect>
      </div>
      <span class="toolbar__result">共 {{ filtered.length }} 条</span>
    </section>

    <section v-loading="loading" class="surface content-table">
      <ElTable :data="filtered" row-key="id">
        <ElTableColumn label="壁纸" min-width="230" fixed="left">
          <template #default="{ row }">
            <div class="wallpaper-cell">
              <img class="wallpaper-thumb" :src="row.coverUrl" :alt="row.title" />
              <div class="wallpaper-cell__text"><strong>{{ row.title }}</strong><small>ID: {{ row.id }}</small></div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="分类" min-width="130"><template #default="{ row }"><strong>{{ categoryMap[row.categoryId] || '未分类' }}</strong><br><small style="color:#817d77">{{ categoryMap[row.subcategoryId] || '—' }}</small></template></ElTableColumn>
        <ElTableColumn label="类型" width="115"><template #default="{ row }"><ElTag effect="plain">{{ kindLabel(row.kind) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="适用平台" min-width="165">
          <template #default="{ row }"><div class="platform-stack"><ElTag v-for="platform in row.platforms" :key="platform" size="small" type="info" effect="plain">{{ platformLabel(platform) }}</ElTag></div></template>
        </ElTableColumn>
        <ElTableColumn label="资源" min-width="135">
          <template #default="{ row }">
            <div class="resource-status"><strong :class="resourceSummary(row).ready === resourceSummary(row).total ? 'success-text' : 'warning-text'">{{ resourceSummary(row).ready }}/{{ resourceSummary(row).total }} 已就绪</strong><small>{{ resourceSummary(row).ready === resourceSummary(row).total ? '可发布' : '需要补充资源' }}</small></div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="100"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="downloads" label="下载" width="85" />
        <ElTableColumn prop="updatedAt" label="更新时间" min-width="150" />
        <ElTableColumn label="操作" width="250" fixed="right">
          <template #default="{ row }">
            <ElButton link type="primary" :icon="Edit" @click="edit(row)">编辑</ElButton>
            <ElButton v-if="row.status !== 'published'" link type="success" @click="changeStatus(row, 'published')">发布</ElButton>
            <ElButton v-else link type="warning" @click="changeStatus(row, 'offline')">下架</ElButton>
            <ElButton link type="danger" :icon="Delete" @click="remove(row)">删除</ElButton>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty class="table-empty" description="没有符合条件的壁纸" /></template>
      </ElTable>
    </section>

    <WallpaperEditorDrawer v-model="drawerOpen" :categories="categories" :wallpaper="editing" @saved="save" />
  </section>
</template>
