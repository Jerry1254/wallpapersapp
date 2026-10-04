<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { Delete, Edit, Plus, Refresh, Search } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import WallpaperEditorDrawer from '@/components/WallpaperEditorDrawer.vue';
import { statusLabels, wallpaperCapabilityLabels, type Category, type ResourceFile, type Wallpaper, type WallpaperAccessType, type WallpaperCapability } from '@/domain/admin';
import { hasPreviewWatermark, mergePreviewGeneration, previewGenerationInProgress, previewGenerationLabel } from '@/domain/previewWatermark';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository, WallpaperSaveError } from '@/repositories/http/adminRepository';

const route = useRoute();
const router = useRouter();
const loading = ref(true);
const loadError = ref('');
const keywords = ref('');
const capability = ref<WallpaperCapability | ''>('');
const status = ref<Wallpaper['status'] | ''>('');
const accessType = ref<WallpaperAccessType | ''>('');
const categories = ref<Category[]>([]);
const wallpapers = ref<Wallpaper[]>([]);
const drawerOpen = ref(false);
const editing = ref<Wallpaper>();
const saving = ref(false);
const actionId = ref('');
const rebuildingAllPreviews = ref(false);
const previewStatusError = ref('');
let loadSequence = 0;
let stopped = false;
let previewPoll: ReturnType<typeof setTimeout> | undefined;

const stopPreviewPoll = () => { if (previewPoll !== undefined) clearTimeout(previewPoll); previewPoll = undefined; };
const updatePreviewStates = (values: Array<Pick<Wallpaper, 'id' | 'previewGenerationStatus' | 'previewRevision' | 'previewGenerationError'>>) => {
  const byId = new Map(values.map((value) => [value.id, value]));
  for (const wallpaper of wallpapers.value) {
    const latest = byId.get(wallpaper.id);
    if (latest) mergePreviewGeneration(wallpaper, latest);
  }
  const latestEditing = editing.value && byId.get(editing.value.id);
  if (editing.value && latestEditing) mergePreviewGeneration(editing.value, latestEditing);
};
const schedulePreviewPoll = () => {
  stopPreviewPoll();
  if (stopped || loading.value || !wallpapers.value.some((value) => value.status !== 'archived' && previewGenerationInProgress(value))) return;
  previewPoll = setTimeout(async () => {
    const sequence = loadSequence;
    try {
      const latest = await adminRepository.wallpaperPreviewStates({ accessType: accessType.value });
      if (!stopped && sequence === loadSequence) {
        updatePreviewStates(latest);
        previewStatusError.value = '';
      }
    } catch (cause) {
      if (!stopped && sequence === loadSequence) previewStatusError.value = readableApiError(cause, '预览状态刷新失败，请点击刷新重试');
    } finally { if (sequence === loadSequence) schedulePreviewPoll(); }
  }, 5000);
};

const categoryMap = computed(() => Object.fromEntries(categories.value.map((item) => [item.id, item.name])));
const filtered = computed(() => wallpapers.value.filter((item) => {
  const keyword = keywords.value.trim().toLowerCase();
  return (!keyword || item.title.toLowerCase().includes(keyword) || (categoryMap.value[item.subcategoryId] || '').toLowerCase().includes(keyword))
    && (!capability.value || item.capabilities.includes(capability.value))
    && (!status.value || item.status === status.value);
}));

const load = async () => {
  const sequence = ++loadSequence;
  stopPreviewPoll();
  loading.value = true;
  loadError.value = '';
  try {
    const [nextCategories, nextWallpapers] = await Promise.all([
      adminRepository.categories(),
      adminRepository.wallpapers({ accessType: accessType.value })
    ]);
    if (stopped || sequence !== loadSequence) return;
    categories.value = nextCategories;
    wallpapers.value = nextWallpapers;
    updatePreviewStates(nextWallpapers);
    previewStatusError.value = '';
  } catch (cause) {
    if (sequence === loadSequence) loadError.value = readableApiError(cause, '壁纸加载失败');
  } finally {
    if (sequence === loadSequence) { loading.value = false; schedulePreviewPoll(); }
  }
};
const rebuildAllPreviews = async () => {
  rebuildingAllPreviews.value = true;
  try {
    const plan = await adminRepository.previewRebuildPlan();
    if (plan.wallpaperCount === 0) { ElMessage.info('没有需要重新生成预览的壁纸'); return; }
    await ElMessageBox.confirm(
      `将重新生成 ${plan.wallpaperCount} 款壁纸、${plan.resourceVersionCount} 个资源版本的预览。其中 ${plan.watermarkedWallpaperCount} 款带水印、${plan.cleanWallpaperCount} 款无水印。仅重新生成预览，正式下载原文件保持不变。`,
      '重新生成全部预览', { confirmButtonText: '确认重新生成', cancelButtonText: '取消', type: 'warning' }
    );
    const result = await adminRepository.rebuildAllPreviews();
    ElMessage.success(`已将 ${result.wallpaperCount} 款壁纸加入预览生成队列`);
    await load();
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(readableApiError(cause, '全部预览重新生成失败'));
  } finally { rebuildingAllPreviews.value = false; }
};
const previewRebuilt = (value: Wallpaper) => {
  updatePreviewStates([value]);
  schedulePreviewPoll();
};
const rebuildPreview = async (value: Wallpaper) => {
  actionId.value = value.id;
  try {
    previewRebuilt(await adminRepository.rebuildWallpaperPreview(value.id));
    ElMessage.success('预览已加入重新生成队列，正式下载原文件保持不变');
  } catch (cause) { ElMessage.error(readableApiError(cause, '预览重新生成失败')); }
  finally { actionId.value = ''; }
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
  const result: (ResourceFile | string | undefined)[] = [];
  const existing = (type: string) => value.variants.some((variant) => variant.resourceType === type
    && variant.resourceVersions.some((version) => ['READY', 'PUBLISHED'].includes(version.status)));
  if (value.capabilities.includes('android_parallax')) result.push(value.resources.parallaxPackage || (existing('LAYER_PARALLAX') ? '现有 4D 资源' : undefined));
  if (value.capabilities.includes('android_video')) result.push(value.resources.androidVideo || (existing('VIDEO') ? '现有 Android 视频' : undefined));
  if (value.capabilities.includes('ios_live_photo')) result.push(value.resources.iosVideo || (existing('LIVE_PHOTO') ? '现有 iOS Live Photo' : undefined));
  if (value.capabilities.includes('harmony_moving_photo')) result.push(value.resources.harmonyVideo || (existing('MOVING_PHOTO') ? '现有鸿蒙动态' : undefined));
  if (value.capabilities.includes('universal_static')) result.push(value.resources.staticImage || (existing('STATIC_IMAGE') ? '现有静态原图' : undefined));
  return result;
};
const resourceSummary = (value: Wallpaper) => {
  const all = requiredResources(value);
  return { ready: all.filter(Boolean).length, total: all.length };
};
const statusLabel = (value: Wallpaper['status']) => statusLabels[value];
const statusType = (value: Wallpaper['status']) => ({ draft: 'info', published: 'success', offline: 'warning', archived: 'info' }[value] as 'info' | 'success' | 'warning');
const capabilityLabel = (value: WallpaperCapability) => wallpaperCapabilityLabels[value];
const hasResourceHistory = (value: Wallpaper) => value.variants.some((variant) => variant.resourceVersions.length > 0);
const removeIsArchive = (value: Wallpaper) => value.status === 'offline' || hasResourceHistory(value);

watch(() => route.query.create, (value) => {
  if (value === '1') {
    create();
    router.replace({ path: '/wallpapers' });
  }
}, { immediate: true });
onMounted(load);
onBeforeUnmount(() => { stopped = true; ++loadSequence; stopPreviewPoll(); });
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>壁纸管理</h1><p>一个商品可独立组合 Android 4D、Android 动态、iOS 实况、鸿蒙动态和全平台静态。</p></div>
      <div class="page-actions">
        <ElButton :icon="Refresh" :loading="loading" @click="load">刷新</ElButton>
        <ElButton :loading="rebuildingAllPreviews" :disabled="saving" @click="rebuildAllPreviews">重新生成全部预览</ElButton>
        <ElButton type="primary" :icon="Plus" @click="create">上传壁纸</ElButton>
      </div>
    </header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />

    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="keywords" :prefix-icon="Search" clearable placeholder="搜索名称或二级分类" style="width: 250px" />
        <ElSelect v-model="capability" clearable placeholder="全部设置能力" style="width: 160px">
          <ElOption v-for="(label, value) in wallpaperCapabilityLabels" :key="value" :label="label" :value="value" />
        </ElSelect>
        <ElSelect v-model="status" clearable placeholder="全部状态" style="width: 130px">
          <ElOption label="草稿" value="draft" /><ElOption label="已发布" value="published" /><ElOption label="已下架" value="offline" /><ElOption label="已归档" value="archived" />
        </ElSelect>
        <ElSelect v-model="accessType" clearable placeholder="全部获取方式" style="width: 150px" @change="load">
          <ElOption label="免费" value="FREE" /><ElOption label="需兑换" value="REDEEM" />
        </ElSelect>
      </div>
      <span v-if="!loadError" class="toolbar__result">共 {{ filtered.length }} 条</span>
    </section>
    <p v-if="previewStatusError" class="warning-text">{{ previewStatusError }}</p>

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
        <ElTableColumn label="获取方式" width="105"><template #default="{ row }"><ElTag :type="row.accessType === 'FREE' ? 'success' : 'info'" effect="plain">{{ row.accessType === 'FREE' ? '免费' : '需兑换' }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="展示范围" min-width="150">
          <template #default="{ row }">
            <div class="resource-status">
              <ElTag :type="row.offlinePromotionOnly ? 'warning' : 'info'" effect="plain">{{ row.offlinePromotionOnly ? '仅线下推广' : '线上可展示' }}</ElTag>
              <small v-if="row.offlinePromotionOnly">吉意壁纸 Android 线下版</small>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="预览资源" min-width="130">
          <template #default="{ row }">
            <div class="resource-status">
              <strong>{{ hasPreviewWatermark(row) ? '带水印' : '无水印' }}</strong>
              <small v-if="row.previewGenerationStatus" :class="row.previewGenerationStatus === 'FAILED' ? 'warning-text' : undefined" :title="row.previewGenerationError || ''">{{ previewGenerationLabel(row) }}</small>
              <ElButton v-if="row.status !== 'archived' && !previewGenerationInProgress(row)" link size="small" :loading="actionId === row.id" @click="rebuildPreview(row)">{{ row.previewGenerationStatus === 'FAILED' ? '重试生成' : '重新生成' }}</ElButton>
            </div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="状态" width="95"><template #default="{ row }"><ElTag :type="statusType(row.status)">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="资源" min-width="125">
          <template #default="{ row }">
            <div class="resource-status"><strong :class="resourceSummary(row).ready === resourceSummary(row).total ? 'success-text' : 'warning-text'">{{ resourceSummary(row).ready }}/{{ resourceSummary(row).total }} 已就绪</strong><small>{{ resourceSummary(row).ready === resourceSummary(row).total ? '可发布' : '需要补充资源' }}</small></div>
          </template>
        </ElTableColumn>
        <ElTableColumn label="设置能力" min-width="250">
          <template #default="{ row }"><div class="platform-stack"><ElTag v-for="item in row.capabilities" :key="item" size="small" type="info" effect="plain">{{ capabilityLabel(item) }}</ElTag></div></template>
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

    <WallpaperEditorDrawer v-model="drawerOpen" :categories="categories" :wallpaper="editing" :saving="saving" @saved="save" @preview-rebuilt="previewRebuilt" />
  </section>
</template>
