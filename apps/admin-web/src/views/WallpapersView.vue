<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { Delete, Edit, Plus, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import WallpaperEditorDrawer from '@/components/WallpaperEditorDrawer.vue';
import { platformLabels, statusLabels, wallpaperKindLabels, type Category, type Platform, type ResourceFile, type Wallpaper, type WallpaperKind } from '@/domain/admin';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository, WallpaperSaveError } from '@/repositories/http/adminRepository';

const route = useRoute();
const router = useRouter();
const loading = ref(true);
const loadError = ref('');
const keywords = ref('');
const kind = ref<WallpaperKind | ''>('');
const status = ref<Wallpaper['status'] | ''>('');
const categories = ref<Category[]>([]);
const wallpapers = ref<Wallpaper[]>([]);
const drawerOpen = ref(false);
const editing = ref<Wallpaper>();
const saving = ref(false);
const actionId = ref('');

const categoryMap = computed(() => Object.fromEntries(categories.value.map((item) => [item.id, item.name])));
const filtered = computed(() => wallpapers.value.filter((item) => {
  const keyword = keywords.value.trim().toLowerCase();
  return (!keyword || item.title.toLowerCase().includes(keyword) || (categoryMap.value[item.subcategoryId] || '').toLowerCase().includes(keyword))
    && (!kind.value || item.kind === kind.value)
    && (!status.value || item.status === status.value);
}));

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    [categories.value, wallpapers.value] = await Promise.all([adminRepository.categories(), adminRepository.wallpapers()]);
  } catch (cause) {
    loadError.value = readableApiError(cause, '壁纸加载失败');
  } finally {
    loading.value = false;
  }
};
const create = () => { editing.value = undefined; drawerOpen.value = true; };
const edit = (value: Wallpaper) => { editing.value = value; drawerOpen.value = true; };
const save = async (value: Wallpaper) => {
  saving.value = true;
  try {
    await adminRepository.saveWallpaper(value, value.status === 'published');
    ElMessage.success(value.status === 'published' ? '壁纸与资源版本已发布' : (value.id ? '壁纸修改已保存' : '壁纸草稿已保存'));
    drawerOpen.value = false;
    await load();
  } catch (cause) {
    if (cause instanceof WallpaperSaveError) {
      editing.value = cause.wallpaper;
      ElMessage.error(`${readableApiError(cause.originalError, '壁纸保存失败')}；已保留保存进度，请修正后继续保存`);
    } else {
      ElMessage.error(readableApiError(cause, '壁纸保存失败'));
    }
  } finally {
    saving.value = false;
  }
};
const changeStatus = async (value: Wallpaper, next: Wallpaper['status']) => {
  actionId.value = value.id;
  try {
    if (next === 'published') await adminRepository.publishWallpaper(value.id);
    else await adminRepository.offlineWallpaper(value.id);
    ElMessage.success(next === 'published' ? '壁纸已发布' : '壁纸已下架，已有权益仍可交付');
    await load();
  } catch (cause) {
    ElMessage.error(readableApiError(cause, next === 'published' ? '发布失败' : '下架失败'));
  } finally {
    actionId.value = '';
  }
};
const remove = async (value: Wallpaper) => {
  try {
    await ElMessageBox.confirm(
      removeIsArchive(value)
        ? `确定归档壁纸“${value.title}”吗？归档后不能重新发布。`
        : `确定删除草稿“${value.title}”吗？删除后无法恢复。`,
      removeIsArchive(value) ? '归档壁纸' : '删除草稿',
      { confirmButtonText: removeIsArchive(value) ? '确认归档' : '确认删除', cancelButtonText: '取消', type: 'warning' }
    );
    actionId.value = value.id;
    await adminRepository.deleteWallpaper(value);
    ElMessage.success(removeIsArchive(value) ? '壁纸已归档' : '壁纸草稿已删除');
    await load();
  } catch (cause) {
    if (cause === 'cancel' || cause === 'close') return;
    ElMessage.error(readableApiError(cause, '操作失败'));
  } finally {
    actionId.value = '';
  }
};

const requiredResources = (value: Wallpaper) => {
  if (value.kind === 'four_d') return [value.resources.cover || value.coverUrl, value.resources.backgroundLayer, value.resources.foregroundLayer, value.resources.depthConfig];
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
const statusLabel = (value: Wallpaper['status']) => statusLabels[value];
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning', archived: 'info' }[value] as 'info' | 'success' | 'warning');
const kindLabel = (value: WallpaperKind) => wallpaperKindLabels[value];
const platformLabel = (value: Platform) => platformLabels[value];
const hasResourceHistory = (value: Wallpaper) => value.variants.some((variant) => variant.resourceVersions.length > 0);
const removeIsArchive = (value: Wallpaper) => value.status === 'offline' || hasResourceHistory(value);

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
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />

    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="keywords" :prefix-icon="Search" clearable placeholder="搜索名称或二级分类" style="width: 250px" />
        <ElSelect v-model="kind" clearable placeholder="全部类型" style="width: 145px">
          <ElOption v-for="(label, value) in wallpaperKindLabels" :key="value" :label="label" :value="value" />
        </ElSelect>
        <ElSelect v-model="status" clearable placeholder="全部状态" style="width: 130px">
          <ElOption label="草稿" value="draft" /><ElOption label="已发布" value="published" /><ElOption label="已下架" value="offline" /><ElOption label="已归档" value="archived" />
        </ElSelect>
      </div>
      <span v-if="!loadError" class="toolbar__result">共 {{ filtered.length }} 条</span>
    </section>

    <section v-if="!loadError" v-loading="loading" class="surface content-table">
      <ElTable :data="filtered" row-key="id">
        <ElTableColumn label="壁纸" min-width="215" fixed="left">
          <template #default="{ row }">
            <div class="wallpaper-cell">
              <img class="wallpaper-thumb" :src="row.coverUrl" :alt="row.title" />
              <div class="wallpaper-cell__text"><strong>{{ row.title }}</strong><small>ID: {{ row.id }} · v{{ row.version }}</small></div>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="分类" min-width="120"><template #default="{ row }"><strong>{{ categoryMap[row.categoryId] || '未分类' }}</strong><br><small style="color:#817d77">{{ categoryMap[row.subcategoryId] || '—' }}</small></template></ElTableColumn>
        <ElTableColumn label="类型" width="110"><template #default="{ row }"><ElTag effect="plain">{{ kindLabel(row.kind) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="状态" width="95"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="资源" min-width="125">
          <template #default="{ row }">
            <div class="resource-status"><strong :class="resourceSummary(row).ready === resourceSummary(row).total ? 'success-text' : 'warning-text'">{{ resourceSummary(row).ready }}/{{ resourceSummary(row).total }} 已就绪</strong><small>{{ resourceSummary(row).ready === resourceSummary(row).total ? '可发布' : '需要补充资源' }}</small></div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="适用平台" min-width="145">
          <template #default="{ row }"><div class="platform-stack"><ElTag v-for="platform in row.platforms" :key="platform" size="small" type="info" effect="plain">{{ platformLabel(platform) }}</ElTag></div></template>
        </ElTableColumn>
        <ElTableColumn prop="updatedAt" label="更新时间" min-width="140" />
        <ElTableColumn label="操作" width="205" fixed="right">
          <template #default="{ row }">
            <ElButton v-if="row.status !== 'archived'" link type="primary" :icon="Edit" @click="edit(row)">编辑</ElButton>
            <ElButton v-if="row.status === 'draft' || row.status === 'offline'" link type="success" :loading="actionId === row.id" @click="changeStatus(row, 'published')">发布</ElButton>
            <ElButton v-else-if="row.status === 'published'" link type="warning" :loading="actionId === row.id" @click="changeStatus(row, 'offline')">下架</ElButton>
            <ElButton v-if="row.status === 'draft'" link type="danger" :icon="Delete" :loading="actionId === row.id" @click="remove(row)">{{ hasResourceHistory(row) ? '归档' : '删除' }}</ElButton>
            <ElButton v-else-if="row.status === 'offline'" link type="danger" :icon="Delete" :loading="actionId === row.id" @click="remove(row)">归档</ElButton>
          </template>
        </ElTableColumn>
        <template #empty><ElEmpty class="table-empty" description="没有符合条件的壁纸" /></template>
      </ElTable>
    </section>

    <WallpaperEditorDrawer v-model="drawerOpen" :categories="categories" :wallpaper="editing" :saving="saving" @saved="save" />
  </section>
</template>
