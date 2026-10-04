<script setup lang="ts">
import { Plus, Refresh, UploadFilled } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';

import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import type { AndroidAppPackageName, AppRelease, AppReleaseApplication, AppReleasePlatform } from '@/domain/appReleases';
import { androidAppLabels, appReleaseApplications, appReleaseMatchesApplication, appReleasePlatformLabels, appReleaseStatusLabels, compareAppReleaseVersions, defaultAndroidAppPackage, effectiveAppReleasePolicy, validStoreRelease } from '@/domain/appReleases';
import { apiResourceUrl, readableApiError } from '@/repositories/http/apiClient';
import { appReleaseRepository } from '@/repositories/http/appReleaseRepository';

const application = ref<AppReleaseApplication>('android');
const platform = computed(() => appReleaseApplications[application.value].platform);
const androidPackage = computed(() => appReleaseApplications[application.value].packageName);
const releases = ref<AppRelease[]>([]);
const loading = ref(true);
const loadError = ref('');
const busy = ref(false);
let loadSequence = 0;
const policy = computed(() => effectiveAppReleasePolicy(releases.value, platform.value, androidPackage.value));
const rows = computed(() => releases.value.filter((release) => appReleaseMatchesApplication(release, platform.value, androidPackage.value))
  .sort((a, b) => compareAppReleaseVersions(b, a)));
const createOpen = ref(false);
const createPlatform = ref<AppReleasePlatform>('android');
const createAndroidPackage = ref<AndroidAppPackageName>(defaultAndroidAppPackage);
const fileInput = ref<HTMLInputElement>();
const apk = ref<File>();
const createForm = reactive({ versionName: '', versionCode: 1, releaseNotes: '', storeUrl: '' });
const editing = ref<AppRelease>();
const editForm = reactive({ releaseNotes: '', forceUpdate: false, storeUrl: '' });
const publishing = ref<AppRelease>();
const publishAcknowledged = ref(false);
const reviewing = ref<AppRelease>();

const date = (value: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
const size = (value: number | null) => value === null ? '—' : `${(value / 1024 / 1024).toFixed(1)} MB`;
const tagType = (status: AppRelease['status']) => status === 'PUBLISHED' ? 'success' : status === 'DEPRECATED' ? 'info' : 'warning';
const downloadHref = (value: string | null) => value ? (/^https:\/\//i.test(value) ? value : value.startsWith('/') ? apiResourceUrl(value) : '') : '';
const releaseApplicationLabel = (release: AppRelease) => release.platform === 'android'
  ? androidAppLabels[release.packageName as AndroidAppPackageName] ?? release.packageName ?? 'Android'
  : appReleasePlatformLabels[release.platform];

const load = async () => {
  const sequence = ++loadSequence;
  const selected = platform.value;
  const selectedPackage = androidPackage.value;
  loading.value = true;
  loadError.value = '';
  try {
    const result = await appReleaseRepository.list(selected, selectedPackage);
    if (sequence === loadSequence) releases.value = result;
  } catch (cause) {
    if (sequence === loadSequence) { releases.value = []; loadError.value = readableApiError(cause, '版本列表加载失败'); }
  } finally {
    if (sequence === loadSequence) loading.value = false;
  }
};
const changeApplication = () => {
  releases.value = [];
  createOpen.value = false;
  editing.value = undefined;
  reviewing.value = undefined;
  publishing.value = undefined;
  void load();
};
const replace = (value: AppRelease) => {
  if (!appReleaseMatchesApplication(value, platform.value, androidPackage.value)) return;
  const index = releases.value.findIndex((release) => release.id === value.id);
  if (index === -1) releases.value.push(value);
  else releases.value[index] = value;
};
const openCreate = () => {
  createPlatform.value = platform.value;
  createAndroidPackage.value = androidPackage.value;
  Object.assign(createForm, { versionName: '', versionCode: 1, releaseNotes: '', storeUrl: policy.value.latest?.storeUrl ?? '' });
  apk.value = undefined;
  if (fileInput.value) fileInput.value.value = '';
  createOpen.value = true;
};
const selectApk = (file?: File) => {
  if (!file) return;
  if (!file.name.toLowerCase().endsWith('.apk')) { ElMessage.warning('请选择正式 APK 安装包'); return; }
  apk.value = file;
};
const create = async () => {
  if (!createForm.releaseNotes.trim()) { ElMessage.warning('请输入更新说明'); return; }
  if (createPlatform.value === 'android' && !apk.value) { ElMessage.warning('请先选择正式 APK 安装包'); return; }
  if (createPlatform.value !== 'android') {
    const error = validStoreRelease({ ...createForm, platform: createPlatform.value });
    if (error) { ElMessage.warning(error); return; }
  }
  busy.value = true;
  try {
    const value = createPlatform.value === 'android'
      ? await appReleaseRepository.uploadAndroid(apk.value!, createForm.releaseNotes, createAndroidPackage.value)
      : await appReleaseRepository.createStore({ ...createForm, platform: createPlatform.value });
    replace(value);
    createOpen.value = false;
    reviewing.value = value;
    ElMessage.success('版本草稿已创建，请确认版本信息后发布');
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '版本草稿创建失败'));
  } finally { busy.value = false; }
};
const openEdit = (release: AppRelease) => {
  editing.value = release;
  Object.assign(editForm, { releaseNotes: release.releaseNotes, forceUpdate: release.forceUpdate, storeUrl: release.storeUrl ?? '' });
};
const confirmForceChange = async (release: AppRelease, force: boolean) => {
  if (release.status !== 'PUBLISHED' || release.forceUpdate === force) return true;
  try {
    await ElMessageBox.confirm(force
      ? `启用后，低于 ${release.versionName} 的用户将被要求更新，弹窗不可关闭。确认启用？`
      : `关闭后，${release.versionName} 转为普通更新。其他已发布版本的强制规则仍生效；若没有其他强制规则，旧版拦截将解除。确认关闭？`,
    force ? '启用强制更新' : '关闭强制更新', { type: 'warning', confirmButtonText: '确认', cancelButtonText: '取消' });
    return true;
  } catch { return false; }
};
const saveEdit = async () => {
  const release = editing.value;
  if (!release) return;
  if (!editForm.releaseNotes.trim()) { ElMessage.warning('请输入更新说明'); return; }
  if (release.deliveryType === 'store') {
    const error = validStoreRelease({ platform: release.platform as 'ios' | 'harmony', versionName: release.versionName, versionCode: release.versionCode, releaseNotes: editForm.releaseNotes, storeUrl: editForm.storeUrl });
    if (error) { ElMessage.warning(error); return; }
  }
  if (!await confirmForceChange(release, editForm.forceUpdate)) return;
  busy.value = true;
  try {
    replace(await appReleaseRepository.update(release.id, { releaseNotes: editForm.releaseNotes.trim(), forceUpdate: editForm.forceUpdate, storeUrl: release.deliveryType === 'store' ? editForm.storeUrl.trim() : null }));
    editing.value = undefined;
    ElMessage.success('版本配置已保存');
  } catch (cause) { ElMessage.error(readableApiError(cause, '版本配置保存失败')); }
  finally { busy.value = false; }
};
const toggleForce = async (release: AppRelease) => {
  const force = !release.forceUpdate;
  if (!await confirmForceChange(release, force)) return;
  busy.value = true;
  try {
    replace(await appReleaseRepository.update(release.id, { releaseNotes: release.releaseNotes, forceUpdate: force, storeUrl: release.storeUrl }));
    ElMessage.success(force ? '强制更新已启用' : '已改为普通更新');
  } catch (cause) { ElMessage.error(readableApiError(cause, '更新策略保存失败')); }
  finally { busy.value = false; }
};
const openPublish = (release: AppRelease) => {
  reviewing.value = undefined;
  publishing.value = release;
  publishAcknowledged.value = false;
};
const publish = async () => {
  if (!publishing.value || !publishAcknowledged.value) return;
  busy.value = true;
  try {
    replace(await appReleaseRepository.publish(publishing.value.id));
    publishing.value = undefined;
    ElMessage.success('版本已发布，App 下次检查时生效');
  } catch (cause) { ElMessage.error(readableApiError(cause, '版本发布失败')); }
  finally { busy.value = false; }
};
const deprecate = async (release: AppRelease) => {
  try {
    await ElMessageBox.confirm(`弃用 ${release.versionName} 后，它不再作为更新目标，也不再触发任何更新提醒。${release.forceUpdate && release.status === 'PUBLISHED' ? '此版本的强制规则也会移除，可能解除旧版本拦截。' : ''}记录会保留，弃用后不能重新发布。确认弃用？`, '弃用版本', { type: 'warning', confirmButtonText: '确认弃用', cancelButtonText: '取消' });
  } catch { return; }
  busy.value = true;
  try {
    replace(await appReleaseRepository.deprecate(release.id));
    ElMessage.success('版本已弃用');
  } catch (cause) { ElMessage.error(readableApiError(cause, '版本弃用失败')); }
  finally { busy.value = false; }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>App 版本管理</h1><p>倾境三端与吉意线下推广版分别控制更新。上传或登记只生成草稿，发布后才生效。</p></div>
      <div class="page-actions"><ElButton :icon="Refresh" :disabled="busy" :loading="loading" @click="load">刷新</ElButton><ElButton type="primary" :icon="Plus" :disabled="busy" @click="openCreate">{{ platform === 'android' ? '上传正式 APK' : '登记商店版本' }}</ElButton></div>
    </header>

    <section class="surface release-platforms"><ElTabs v-model="application" @tab-change="changeApplication"><ElTabPane v-for="(app, key) in appReleaseApplications" :key="key" :name="key" :label="app.label" :disabled="busy" /></ElTabs></section>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />
    <template v-if="!loadError">
      <section v-loading="loading" class="release-policy">
        <article class="surface"><span>最新可用版本</span><strong>{{ policy.latest?.versionName ?? '暂无' }}</strong><small>{{ platform === 'android' ? '安卓直接下载此版本 APK，确认后覆盖安装' : '苹果和鸿蒙前往应用商店更新实际最新版本' }}</small></article>
        <article class="surface"><span>最低允许版本</span><strong>{{ policy.minimum?.versionName ?? '不限制旧版本' }}</strong><small>{{ policy.minimum ? `低于 ${policy.minimum.versionName} 不可继续使用，更新弹窗不可关闭` : '当前没有有效强制规则，更新提示可以关闭' }}</small></article>
      </section>
      <ElAlert title="只有已发布且未弃用的版本参与判断；最新普通版本不会提高强制门槛。关闭一个强制规则后，其他版本的强制规则仍独立生效。" type="info" :closable="false" show-icon />
      <section v-loading="loading" class="surface content-table">
        <ElTable :data="rows" row-key="id" empty-text="尚未登记此应用的版本">
          <ElTableColumn label="版本" width="150"><template #default="{ row }"><strong>{{ row.versionName }}</strong><small class="release-table-small">{{ platform === 'ios' ? '构建号' : 'versionCode' }} {{ row.versionCode }}</small><small v-if="row.id === policy.latest?.id" class="release-table-small release-current">最新可用</small></template></ElTableColumn>
          <ElTableColumn label="更新策略" width="165"><template #default="{ row }"><ElTag :type="row.forceUpdate && row.status !== 'DEPRECATED' ? 'danger' : 'info'" effect="plain">{{ row.forceUpdate ? '强制更新' : '普通更新' }}</ElTag><small v-if="row.id === policy.minimum?.id" class="release-table-small">当前有效门槛</small><small v-else-if="row.status !== 'PUBLISHED'" class="release-table-small">不生效</small></template></ElTableColumn>
          <ElTableColumn label="状态" width="110"><template #default="{ row }"><ElTag :type="tagType(row.status)">{{ appReleaseStatusLabels[row.status as AppRelease['status']] }}</ElTag></template></ElTableColumn>
          <ElTableColumn label="更新文件 / 商店" min-width="200"><template #default="{ row }"><template v-if="row.deliveryType === 'apk'"><span>{{ row.packageName }}</span><small class="release-table-small">{{ row.abi }} · {{ size(row.fileSize) }}</small></template><ElLink v-else :href="row.storeUrl" target="_blank" rel="noopener noreferrer" type="primary">查看应用商店</ElLink></template></ElTableColumn>
          <ElTableColumn prop="releaseNotes" label="更新说明" min-width="180" show-overflow-tooltip />
          <ElTableColumn label="发布时间" width="160"><template #default="{ row }">{{ date(row.publishedAt) }}</template></ElTableColumn>
          <ElTableColumn label="操作" min-width="270" fixed="right"><template #default="{ row }"><div class="release-row-actions"><ElButton link type="primary" :disabled="busy" @click="reviewing = row">详情</ElButton><ElButton v-if="row.status !== 'DEPRECATED'" link type="primary" :disabled="busy" @click="openEdit(row)">编辑</ElButton><ElButton v-if="row.status === 'DRAFT'" link type="success" :disabled="busy" @click="openPublish(row)">发布</ElButton><ElButton v-if="row.status === 'PUBLISHED'" link :type="row.forceUpdate ? 'warning' : 'danger'" :disabled="busy" @click="toggleForce(row)">{{ row.forceUpdate ? '关闭强制' : '设为强制' }}</ElButton><ElButton v-if="row.status !== 'DEPRECATED'" link type="danger" :disabled="busy" @click="deprecate(row)">弃用</ElButton></div></template></ElTableColumn>
        </ElTable>
      </section>
    </template>

    <ElDialog v-model="createOpen" :title="createPlatform === 'android' ? `上传 ${androidAppLabels[createAndroidPackage]} 正式 APK` : `登记 ${appReleasePlatformLabels[createPlatform]} 商店版本`" width="min(600px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy">
      <ElForm label-position="top" :disabled="busy">
        <ElFormItem v-if="createPlatform === 'android'" label="正式安装包" required>
          <input ref="fileInput" hidden type="file" accept=".apk,application/vnd.android.package-archive" @change="selectApk(($event.target as HTMLInputElement).files?.[0])" />
          <button class="release-upload" type="button" :disabled="busy" @click="fileInput?.click()" @dragover.prevent @drop.prevent="!busy && selectApk($event.dataTransfer?.files[0])"><ElIcon :size="34"><UploadFilled /></ElIcon><strong>{{ apk?.name ?? '拖拽 APK 到此处或点击选择' }}</strong><small>{{ apk ? size(apk.size) : '后台解析真实版本、包名、架构、签名及 SHA-256；不接受 Debug 包' }}</small></button>
          <small class="release-table-small">安装包必须属于 {{ androidAppLabels[createAndroidPackage] }}，后台会校验应用是否一致。</small>
        </ElFormItem>
        <template v-else>
          <ElAlert title="请填写已经在应用商店发布、用户能够实际下载的版本。登记草稿不会立即拦截用户。" type="info" :closable="false" class="release-form-alert" />
          <ElFormItem label="商店版本号" required><ElInput v-model="createForm.versionName" placeholder="例如 1.0.1" maxlength="64" /></ElFormItem>
          <ElFormItem :label="createPlatform === 'ios' ? '真实构建号（CFBundleVersion）' : '真实版本编码（versionCode）'" required><ElInputNumber v-model="createForm.versionCode" :min="1" :max="2147483647" :precision="0" controls-position="right" style="width:100%" /><small v-if="createPlatform === 'ios'" class="release-table-small">iOS 按商店版本号判断高低；构建号仅用于记录。</small></ElFormItem>
          <ElFormItem label="HTTPS 应用商店链接" required><ElInput v-model="createForm.storeUrl" :placeholder="createPlatform === 'ios' ? 'https://apps.apple.com/cn/app/id…' : '华为应用市场应用详情链接'" maxlength="1000" /></ElFormItem>
        </template>
        <ElFormItem label="更新说明" required><ElInput v-model="createForm.releaseNotes" type="textarea" :rows="4" maxlength="1000" show-word-limit placeholder="告诉用户本次更新的内容" /></ElFormItem>
      </ElForm>
      <template #footer><div class="dialog-actions"><ElButton :disabled="busy" @click="createOpen = false">取消</ElButton><ElButton type="primary" :loading="busy" @click="create">{{ createPlatform === 'android' ? '上传并创建草稿' : '创建草稿' }}</ElButton></div></template>
    </ElDialog>

    <ElDialog :model-value="Boolean(editing)" title="编辑版本配置" width="min(560px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="!busy && (editing = undefined)">
      <ElForm v-if="editing" label-position="top" :disabled="busy">
        <ElFormItem label="应用"><strong>{{ releaseApplicationLabel(editing) }}</strong></ElFormItem>
        <ElFormItem label="版本"><strong>{{ editing.versionName }} · {{ editing.versionCode }}</strong></ElFormItem>
        <ElFormItem label="更新说明" required><ElInput v-model="editForm.releaseNotes" type="textarea" :rows="4" maxlength="1000" show-word-limit /></ElFormItem>
        <ElFormItem v-if="editing.deliveryType === 'store'" label="HTTPS 应用商店链接" required><ElInput v-model="editForm.storeUrl" maxlength="1000" /></ElFormItem>
        <ElFormItem label="强制更新"><ElSwitch v-model="editForm.forceUpdate" /><small class="release-force-hint">开启后，低于此版本的用户不可继续使用。{{ editing.status === 'DRAFT' ? '草稿发布后才生效。' : '保存后 App 下次检查时生效。' }}</small></ElFormItem>
      </ElForm>
      <template #footer><div class="dialog-actions"><ElButton :disabled="busy" @click="editing = undefined">取消</ElButton><ElButton type="primary" :loading="busy" @click="saveEdit">保存</ElButton></div></template>
    </ElDialog>

    <ElDialog :model-value="Boolean(reviewing)" title="版本信息" width="min(680px, 94vw)" @update:model-value="reviewing = undefined">
      <ElDescriptions v-if="reviewing" :column="1" border>
        <ElDescriptionsItem label="应用">{{ releaseApplicationLabel(reviewing) }}</ElDescriptionsItem>
        <ElDescriptionsItem label="平台 / 版本">{{ appReleasePlatformLabels[reviewing.platform] }} · {{ reviewing.versionName }} · {{ reviewing.versionCode }}</ElDescriptionsItem>
        <ElDescriptionsItem label="状态 / 策略">{{ appReleaseStatusLabels[reviewing.status] }} · {{ reviewing.forceUpdate ? '强制更新' : '普通更新' }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'apk'" label="包名">{{ reviewing.packageName }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'apk'" label="文件 / 架构">{{ size(reviewing.fileSize) }} · {{ reviewing.abi }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'apk' && reviewing.minSdkVersion" label="最低 Android 系统">API {{ reviewing.minSdkVersion }}</ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'apk'" label="SHA-256"><code class="release-hash">{{ reviewing.sha256 }}</code></ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'apk'" label="签名证书 SHA-256"><code class="release-hash">{{ reviewing.signerSha256 }}</code></ElDescriptionsItem>
        <ElDescriptionsItem v-if="reviewing.deliveryType === 'store'" label="应用商店"><ElLink :href="reviewing.storeUrl ?? ''" type="primary" target="_blank" rel="noopener noreferrer">{{ reviewing.storeUrl }}</ElLink></ElDescriptionsItem>
        <ElDescriptionsItem label="更新说明"><span class="release-notes">{{ reviewing.releaseNotes }}</span></ElDescriptionsItem>
        <ElDescriptionsItem label="创建 / 发布">{{ date(reviewing.createdAt) }} / {{ date(reviewing.publishedAt) }}</ElDescriptionsItem>
      </ElDescriptions>
      <template #footer><ElButton @click="reviewing = undefined">关闭</ElButton><ElButton v-if="reviewing?.status === 'DRAFT'" type="primary" :disabled="busy" @click="openPublish(reviewing)">确认并发布</ElButton><ElLink v-if="reviewing?.status === 'PUBLISHED' && downloadHref(reviewing.downloadUrl)" :href="downloadHref(reviewing.downloadUrl)" target="_blank" rel="noopener noreferrer" type="primary" class="release-download">下载 APK</ElLink></template>
    </ElDialog>

    <ElDialog :model-value="Boolean(publishing)" title="确认发布版本" width="min(650px, 94vw)" :close-on-click-modal="!busy" :close-on-press-escape="!busy" :show-close="!busy" @update:model-value="!busy && (publishing = undefined)">
      <template v-if="publishing">
        <ElDescriptions :column="1" border>
          <ElDescriptionsItem label="应用">{{ releaseApplicationLabel(publishing) }}</ElDescriptionsItem>
          <ElDescriptionsItem label="平台 / 版本">{{ appReleasePlatformLabels[publishing.platform] }} · {{ publishing.versionName }} · {{ publishing.versionCode }}</ElDescriptionsItem>
          <ElDescriptionsItem label="更新策略">{{ publishing.forceUpdate ? '强制更新：低于此版本的用户将不可继续使用' : '普通更新：用户可以关闭提醒并继续使用' }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="publishing.deliveryType === 'apk'" label="包名 / 架构">{{ publishing.packageName }} · {{ publishing.abi }}</ElDescriptionsItem>
          <ElDescriptionsItem v-if="publishing.deliveryType === 'apk'" label="签名证书 SHA-256"><code class="release-hash">{{ publishing.signerSha256 }}</code></ElDescriptionsItem>
          <ElDescriptionsItem v-if="publishing.deliveryType === 'store'" label="应用商店"><ElLink :href="publishing.storeUrl ?? ''" type="primary" target="_blank" rel="noopener noreferrer">{{ publishing.storeUrl }}</ElLink></ElDescriptionsItem>
          <ElDescriptionsItem label="更新说明"><span class="release-notes">{{ publishing.releaseNotes }}</span></ElDescriptionsItem>
        </ElDescriptions>
        <ElCheckbox v-model="publishAcknowledged" :disabled="busy" class="release-acknowledgment">{{ publishing.deliveryType === 'apk' ? '已确认这是正式 APK，包名和签名与用户已安装版本一致，可以覆盖更新。' : '已确认此版本在应用商店通过审核并已上架，目标用户能够实际下载更新。' }}</ElCheckbox>
      </template>
      <template #footer><div class="dialog-actions"><ElButton :disabled="busy" @click="publishing = undefined">取消</ElButton><ElButton type="primary" :disabled="!publishAcknowledged" :loading="busy" @click="publish">发布并生效</ElButton></div></template>
    </ElDialog>
  </section>
</template>

<style scoped>
.release-platforms { padding: 4px 20px 0; }
.release-platforms :deep(.el-tabs__header) { margin-bottom: 0; }
.release-platforms :deep(.el-tabs__nav-wrap::after) { height: 0; }
.release-policy { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.release-policy article { display: grid; gap: 8px; padding: 20px; }
.release-policy span { font-size: 13px; color: var(--admin-muted); }
.release-policy strong { font-size: 24px; }
.release-policy small, .release-table-small { font-size: 12px; color: var(--admin-muted); }
.release-table-small { display: block; margin-top: 5px; line-height: 1.5; }
.release-current { color: #17804a; }
.release-row-actions { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.release-row-actions :deep(.el-button + .el-button) { margin-left: 0; }
.release-upload { width: 100%; display: grid; place-items: center; gap: 12px; padding: 28px 16px; border: 1px dashed var(--el-border-color); border-radius: 10px; background: #fafafa; color: var(--admin-muted); cursor: pointer; }
.release-upload strong { color: var(--admin-text); overflow-wrap: anywhere; }
.release-upload small { font-size: 12px; line-height: 1.7; }
.release-upload:disabled { cursor: wait; }
.release-force-hint { margin-left: 12px; color: var(--admin-muted); line-height: 1.7; }
.release-form-alert { margin-bottom: 18px; }
.release-hash { word-break: break-all; font-size: 12px; }
.release-notes { white-space: pre-wrap; overflow-wrap: anywhere; }
.release-acknowledgment { height: auto; align-items: flex-start; margin-top: 20px; }
.release-acknowledgment :deep(.el-checkbox__label) { white-space: normal; line-height: 1.6; }
.release-acknowledgment :deep(.el-checkbox__input) { margin-top: 4px; }
.release-download { margin-left: 14px; }
@media (max-width: 760px) { .release-policy { grid-template-columns: 1fr; } }
</style>
