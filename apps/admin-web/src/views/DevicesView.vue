<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { Search, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import dayjs from 'dayjs';
import { onMounted, reactive, ref } from 'vue';

import type { DeviceDetail, DevicePlatform, DeviceStatus, DeviceSummary, PageMetadata } from '@/domain/admin';
import { adminRepository } from '@/repositories/http/adminRepository';
import { readableApiError } from '@/repositories/http/apiClient';

const loading = ref(true);
const loadError = ref('');
const rows = ref<DeviceSummary[]>([]);
const page = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const platform = ref<DevicePlatform | ''>('');
const status = ref<DeviceStatus | ''>('');
const publicId = ref('');
const iosTestDevice = ref<boolean | ''>('');
const actionLoading = ref(false);
const detailOpen = ref(false);
const detailLoading = ref(false);
const detailError = ref('');
const selectedDevice = ref<DeviceSummary>();
const detail = ref<DeviceDetail>();

const platformLabels: Record<DevicePlatform, string> = { ANDROID: 'Android', IOS: 'iOS', HARMONYOS: 'HarmonyOS', H5_TEST: 'H5 联调' };
const statusLabels: Record<DeviceStatus, string> = { ACTIVE: '正常', REVIEW: '待核查', DISABLED: '已停用' };
const platformLabel = (value: DevicePlatform) => platformLabels[value];
const statusLabel = (value: DeviceStatus) => statusLabels[value];
const date = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await adminRepository.devices({
      page: page.page, pageSize: page.pageSize, platform: platform.value, status: status.value,
      publicId: publicId.value, iosTestDevice: iosTestDevice.value
    });
    rows.value = result.items;
    Object.assign(page, result.page);
  } catch (cause) {
    loadError.value = readableApiError(cause, '设备列表加载失败');
  } finally {
    loading.value = false;
  }
};
const refreshDetail = async () => {
  if (selectedDevice.value) await openDetail(selectedDevice.value);
  await load();
};
const changeTestStatus = async (enabled: boolean) => {
  if (!detail.value) return;
  try {
    const { value } = await ElMessageBox.prompt(
      enabled ? '请说明为什么将该 iOS 安装标记为测试机。' : '请说明取消测试机标记的原因。',
      enabled ? '标记 iOS 测试机' : '取消测试机',
      { inputPlaceholder: '必填，写入审计日志', inputValidator: (value) => Boolean(value.trim()) || '请填写原因' }
    );
    actionLoading.value = true;
    await adminRepository.setIosTestDevice(detail.value.id, enabled, value.trim());
    ElMessage.success(enabled ? '已标记为测试机' : '已取消测试机标记');
    await refreshDetail();
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(readableApiError(cause, '操作失败'));
  } finally { actionLoading.value = false; }
};
const createReset = async () => {
  if (!detail.value) return;
  try {
    const { value } = await ElMessageBox.prompt(
      '这会等待指定测试 App 提交新证明，随后清除 Apple DeviceCheck bit0，撤销旧代次首免来源。付费权益不受影响。请填写原因。',
      '发起一次 iOS 首免重置',
      { confirmButtonText: '确认发起', inputPlaceholder: '必填，写入审计日志', type: 'warning', inputValidator: (text) => Boolean(text.trim()) || '请填写原因' }
    );
    actionLoading.value = true;
    await adminRepository.createIosFreeReset(detail.value.id, detail.value.iosAcquisition?.freeGeneration ?? 0, value.trim());
    ElMessage.success('已发起，等待该测试 App 在线完成');
    await refreshDetail();
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(readableApiError(cause, '发起重置失败'));
  } finally { actionLoading.value = false; }
};
const cancelReset = async () => {
  const reset = detail.value?.iosAcquisition?.pendingReset;
  if (!detail.value || !reset) return;
  try {
    await ElMessageBox.confirm('只有尚未开始 Apple 写入的操作可取消。', '取消首免重置', { type: 'warning' });
    actionLoading.value = true;
    await adminRepository.cancelIosFreeReset(detail.value.id, reset.resetId);
    ElMessage.success('已取消');
    await refreshDetail();
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(readableApiError(cause, '取消失败'));
  } finally { actionLoading.value = false; }
};
const sourceLabel = (source: string) => ({ REDEMPTION: '兑换码', IOS_FIRST_FREE: 'iOS 首免', IOS_IAP: 'Apple 内购' }[source] || source);
const search = () => { page.page = 1; load(); };
const openDetail = async (row: DeviceSummary) => {
  selectedDevice.value = row;
  detail.value = undefined;
  detailError.value = '';
  detailOpen.value = true;
  detailLoading.value = true;
  try {
    detail.value = await adminRepository.device(row.id);
  } catch (cause) {
    detailError.value = readableApiError(cause, '设备详情加载失败');
  } finally {
    detailLoading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>设备权益</h1><p>以匿名设备为主体查看凭证活动与已获得壁纸权益。</p></div></header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />
    <ElAlert title="App 不要求用户登录" description="设备身份由服务端凭证识别；管理端仅展示可审计元数据，不返回设备密钥、证据摘要或公钥材料。" type="info" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElSelect v-model="platform" clearable placeholder="全部平台" style="width:150px"><ElOption v-for="(label, value) in platformLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElSelect v-model="status" clearable placeholder="全部状态" style="width:140px"><ElOption v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElInput v-model="publicId" clearable placeholder="安装编号 UUID" style="width:260px" />
        <ElSelect v-model="iosTestDevice" clearable placeholder="iOS 测试标记" style="width:150px"><ElOption label="是" :value="true" /><ElOption label="否" :value="false" /></ElSelect>
        <ElButton type="primary" :icon="Search" @click="search">查询</ElButton>
      </div>
      <span v-if="!loadError" class="toolbar__result">共 {{ page.totalItems }} 台匿名设备</span>
    </section>
    <section v-if="!loadError" v-loading="loading" class="surface content-table">
      <ElTable :data="rows" empty-text="暂无设备记录">
        <ElTableColumn prop="id" label="设备 ID" min-width="110" />
        <ElTableColumn prop="publicId" label="安装编号" min-width="300" />
        <ElTableColumn label="平台" width="130"><template #default="{ row }">{{ platformLabel(row.platform) }}</template></ElTableColumn>
        <ElTableColumn prop="appInstallScope" label="安装范围" min-width="180" />
        <ElTableColumn label="状态" width="100"><template #default="{ row }"><ElTag :type="row.status === 'ACTIVE' ? 'success' : (row.status === 'DISABLED' ? 'danger' : 'warning')" effect="plain">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="entitlementCount" label="权益数" width="100" />
        <ElTableColumn label="最后活跃" width="180"><template #default="{ row }">{{ date(row.lastSeenAt) }}</template></ElTableColumn>
        <ElTableColumn label="操作" width="90"><template #default="{ row }"><ElButton link type="primary" :icon="View" @click="openDetail(row)">详情</ElButton></template></ElTableColumn>
      </ElTable>
      <div class="table-pagination"><ElPagination v-model:current-page="page.page" layout="total, prev, pager, next" :page-size="page.pageSize" :total="page.totalItems" @current-change="load" /></div>
    </section>

    <ElDrawer v-model="detailOpen" title="匿名设备详情" size="min(760px, 96vw)">
      <AdminLoadNotice :error="detailError" :loading="detailLoading" @retry="selectedDevice && openDetail(selectedDevice)" />
      <div v-loading="detailLoading" class="detail-stack">
        <template v-if="detail">
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="设备 ID">{{ detail.id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安装编号"><span class="break-value">{{ detail.publicId }}</span></ElDescriptionsItem>
            <ElDescriptionsItem label="平台">{{ platformLabel(detail.platform) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安装范围"><span class="break-value">{{ detail.appInstallScope }}</span></ElDescriptionsItem>
            <ElDescriptionsItem label="状态">{{ statusLabel(detail.status) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="权益数">{{ detail.entitlementCount }}</ElDescriptionsItem>
            <ElDescriptionsItem label="最后活跃">{{ date(detail.lastSeenAt) }}</ElDescriptionsItem>
          </ElDescriptions>

          <section v-if="detail.platform === 'IOS'" class="detail-section">
            <div style="display:flex;align-items:center;justify-content:space-between;gap:12px"><h3>iOS 首免与内购</h3><ElButton :loading="actionLoading" @click="changeTestStatus(!detail.iosTestDevice)">{{ detail.iosTestDevice ? '取消测试机' : '标记测试机' }}</ElButton></div>
            <ElDescriptions v-if="detail.iosAcquisition" :column="2" border>
              <ElDescriptionsItem label="测试机">{{ detail.iosAcquisition.testDevice ? '是' : '否' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="首免代次">{{ detail.iosAcquisition.freeGeneration }}</ElDescriptionsItem>
              <ElDescriptionsItem label="免费资格">{{ detail.iosAcquisition.freeAllowance }}</ElDescriptionsItem>
              <ElDescriptionsItem label="Apple 核对时间">{{ date(detail.iosAcquisition.deviceCheckCheckedAt) }}</ElDescriptionsItem>
            </ElDescriptions>
            <ElAlert v-if="detail.iosAcquisition?.pendingReset" type="warning" :closable="false" show-icon :title="`重置 ${detail.iosAcquisition.pendingReset.status}`" :description="`操作 ${detail.iosAcquisition.pendingReset.resetId}，等待 App 完成新鲜证明。`" />
            <div style="display:flex;gap:12px;margin-top:12px">
              <ElButton type="warning" :disabled="!detail.iosTestDevice || Boolean(detail.iosAcquisition?.pendingReset)" :loading="actionLoading" @click="createReset">发起一次首免重置</ElButton>
              <ElButton v-if="detail.iosAcquisition?.pendingReset?.status === 'WAITING_DEVICE'" :loading="actionLoading" @click="cancelReset">取消等待</ElButton>
            </div>
          </section>

          <section class="detail-section">
            <h3>设备凭证</h3>
            <ElTable :data="detail.credentials" empty-text="暂无凭证">
              <ElTableColumn prop="credentialKeyId" label="凭证标识" min-width="220" />
              <ElTableColumn label="类型" width="150"><template #default="{ row }">{{ row.credentialType === 'H5_TEST_SECRET' ? 'H5 联调密钥' : '平台公钥' }}</template></ElTableColumn>
              <ElTableColumn label="状态" width="100"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已撤销' }}</template></ElTableColumn>
              <ElTableColumn label="最后使用" width="180"><template #default="{ row }">{{ date(row.lastUsedAt) }}</template></ElTableColumn>
            </ElTable>
          </section>

          <section class="detail-section">
            <h3>壁纸权益</h3>
            <ElTable :data="detail.entitlements" empty-text="该设备尚无权益">
              <ElTableColumn label="壁纸" min-width="220"><template #default="{ row }"><strong>{{ row.wallpaper.title }}</strong><small class="cell-meta">ID {{ row.wallpaper.id }}</small></template></ElTableColumn>
              <ElTableColumn label="状态" width="100"><template #default="{ row }">{{ row.status === 'ACTIVE' ? '有效' : '已撤销' }}</template></ElTableColumn>
              <ElTableColumn label="授予时间" width="180"><template #default="{ row }">{{ date(row.grantedAt) }}</template></ElTableColumn>
              <ElTableColumn label="权益来源" min-width="180"><template #default="{ row }"><ElTag v-for="source in row.sources" :key="source" effect="plain" style="margin-right:6px">{{ sourceLabel(source) }}</ElTag></template></ElTableColumn>
              <ElTableColumn label="撤销时间" width="180"><template #default="{ row }">{{ date(row.revokedAt) }}</template></ElTableColumn>
            </ElTable>
          </section>
        </template>
      </div>
    </ElDrawer>
  </section>
</template>
