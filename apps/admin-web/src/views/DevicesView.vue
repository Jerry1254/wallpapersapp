<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { Search, View } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';
import { useRouter, useRoute } from 'vue-router';
import { operationsRepository, userChannelLabels, type DevicePurchase, type UserChannel } from '@/repositories/http/operationsRepository';
import { securityTime } from '@/repositories/http/securityRepository';

import type { DeviceDetail, DevicePlatform, DeviceStatus, DeviceSummary, RedemptionSummary, PageMetadata } from '@/domain/admin';
import { adminRepository } from '@/repositories/http/adminRepository';
import { readableApiError } from '@/repositories/http/apiClient';

const loading = ref(true);
const loadError = ref('');
const rows = ref<DeviceSummary[]>([]);
const page = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const platform = ref<DevicePlatform | ''>('');
const status = ref<DeviceStatus | ''>('');
const publicId = ref('');
const router = useRouter(), route = useRoute();
const channel = ref<UserChannel | ''>(Object.hasOwn(userChannelLabels, String(route.query.channel)) ? String(route.query.channel) as UserChannel : '');
const activeToday = ref<boolean | ''>(''), banned = ref<boolean | ''>('');
const note = ref(''), noteSaving = ref(false);
const historyLoading = ref(false), historyError = ref('');
const redemptions = ref<RedemptionSummary[]>([]), purchases = ref<DevicePurchase[]>([]);
const redemptionPage = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const purchasePage = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
let detailGeneration = 0, historyGeneration = 0, listGeneration = 0;
const channelLabel = (value: UserChannel) => userChannelLabels[value] || '其他';
const purchaseStatus = (value: DevicePurchase['status']) => ({ OPEN: '待完成', CANCELLED: '已取消', FULFILLED: '已完成', REFUNDED: '已退款 / 撤销' }[value]);
const redemptionStatus: Record<string, string> = { GRANTED: '兑换成功', ALREADY_OWNED: '已拥有', CODE_NOT_FOUND: '兑换码不存在', CODE_EXHAUSTED: '额度用尽', WALLPAPER_UNAVAILABLE: '壁纸不可用', WALLPAPER_FREE: '免费壁纸', FAILED: '失败' };
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
const date = (value?: string | null) => securityTime(value || undefined);

const load = async () => {
  const current = ++listGeneration;
  loading.value = true;
  loadError.value = '';
  try {
    const result = await adminRepository.devices({
      page: page.page, pageSize: page.pageSize, platform: platform.value, status: status.value,
      search: publicId.value, iosTestDevice: iosTestDevice.value, channel: channel.value, activeToday: activeToday.value, banned: banned.value
    });
    if (current !== listGeneration) return;
    rows.value = result.items;
    Object.assign(page, result.page);
  } catch (cause) {
    if (current === listGeneration) loadError.value = readableApiError(cause, '用户列表加载失败');
  } finally {
    if (current === listGeneration) loading.value = false;
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
const resetDescription = (reset: NonNullable<DeviceDetail['iosAcquisition']>['pendingReset']) => {
  if (!reset) return '';
  if (reset.errorCode) return `操作 ${reset.resetId}。Apple 写入结果待核查：${reset.errorCode}，请保留此操作，不要重复重置。`;
  if (reset.status === 'WAITING_DEVICE') return `操作 ${reset.resetId}。等待 App 完成新鲜证明。`;
  return `操作 ${reset.resetId}。正在处理，完成后刷新资格。`;
};
const search = () => { page.page = 1; load(); };
async function loadHistory(generation = detailGeneration, id = selectedDevice.value?.id) {
  if (!id) return;
  const history = ++historyGeneration;
  historyLoading.value = true; historyError.value = '';
  try {
    const [redemptionData, purchaseData] = await Promise.all([
      adminRepository.redemptions({ deviceId: id, page: redemptionPage.page, pageSize: 20 }),
      operationsRepository.purchases(id, purchasePage.page)
    ]);
    if (generation !== detailGeneration || history !== historyGeneration) return;
    redemptions.value = redemptionData.items; Object.assign(redemptionPage, redemptionData.page);
    purchases.value = purchaseData.items; Object.assign(purchasePage, purchaseData.page);
  } catch (cause) {
    if (generation === detailGeneration && history === historyGeneration) historyError.value = readableApiError(cause, '历史记录加载失败');
  } finally { if (generation === detailGeneration && history === historyGeneration) historyLoading.value = false; }
}
const openDetail = async (row: DeviceSummary) => {
  const generation = ++detailGeneration;
  selectedDevice.value = row;
  detail.value = undefined; redemptions.value = []; purchases.value = [];
  redemptionPage.page = 1; purchasePage.page = 1;
  detailError.value = ''; historyError.value = ''; note.value = '';
  detailOpen.value = true; detailLoading.value = true;
  try {
    const result = await adminRepository.device(row.id);
    if (generation !== detailGeneration) return;
    detail.value = result; note.value = result.note;
    await loadHistory(generation, row.id);
  } catch (cause) {
    if (generation === detailGeneration) detailError.value = readableApiError(cause, '用户详情加载失败');
  } finally { if (generation === detailGeneration) detailLoading.value = false; }
};
async function saveNote() {
  const selected = detail.value;
  if (!selected || noteSaving.value) return;
  noteSaving.value = true;
  try {
    const result = await operationsRepository.saveNote(selected.id, note.value, selected.noteVersion);
    if (selected !== detail.value) return;
    selected.note = result.note; selected.noteVersion = result.version; note.value = result.note;
    ElMessage.success('备注已保存'); await load();
  } catch (cause) { ElMessage.error(readableApiError(cause, '备注保存失败，请刷新详情后重试')); }
  finally { noteSaving.value = false; }
}
function openSecurity() { router.push({ path: '/security', query: { deviceId: detail.value?.id } }); }

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>用户管理</h1><p>按设备身份查看 App 归属、使用情况、兑换购买与壁纸权益。</p></div></header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />
    <ElAlert title="App 不要求用户登录" description="一条用户记录对应一个设备安装身份，同一人的多个设备会分开显示；运营统计不计 H5 联调和 iOS 测试机。" type="info" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElSelect v-model="channel" clearable placeholder="全部 App 版本" style="width:240px"><ElOption v-for="(label, value) in userChannelLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElSelect v-model="platform" clearable placeholder="全部平台" style="width:150px"><ElOption v-for="(label, value) in platformLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElSelect v-model="status" clearable placeholder="身份状态" style="width:140px"><ElOption v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElInput v-model="publicId" clearable placeholder="用户 ID / 安装编号 / 备注" style="width:260px" />
        <ElSelect v-model="iosTestDevice" clearable placeholder="iOS 测试标记" style="width:150px"><ElOption label="是" :value="true" /><ElOption label="否" :value="false" /></ElSelect>
        <ElSelect v-model="activeToday" clearable placeholder="今日使用" style="width:130px"><ElOption label="今天使用过" :value="true" /><ElOption label="今天未使用" :value="false" /></ElSelect>
        <ElSelect v-model="banned" clearable placeholder="设备封禁" style="width:130px"><ElOption label="已封禁" :value="true" /><ElOption label="未封禁" :value="false" /></ElSelect>
        <ElButton type="primary" :icon="Search" @click="search">查询</ElButton>
      </div>
      <span v-if="!loadError" class="toolbar__result">共 {{ page.totalItems }} 条用户记录</span>
    </section>
    <section v-if="!loadError" v-loading="loading" class="surface content-table">
      <ElTable :data="rows" empty-text="暂无用户记录">
        <ElTableColumn prop="id" label="用户 ID" min-width="90" />
        <ElTableColumn prop="publicId" label="安装编号" min-width="300" />
        <ElTableColumn label="平台" width="130"><template #default="{ row }">{{ platformLabel(row.platform) }}</template></ElTableColumn>
        <ElTableColumn label="App / 版本" min-width="200"><template #default="{ row }"><strong>{{ channelLabel(row.channel) }}</strong><small class="cell-meta">{{ row.appVersionName || '版本未上报' }}{{ row.appVersionCode ? ` (${row.appVersionCode})` : '' }}</small></template></ElTableColumn>
        <ElTableColumn label="设备" min-width="150"><template #default="{ row }">{{ row.model || '型号未上报' }}<small class="cell-meta">{{ row.osVersion || '系统未上报' }}</small></template></ElTableColumn>
        <ElTableColumn label="身份状态" width="100"><template #default="{ row }"><ElTag :type="row.status === 'ACTIVE' ? 'success' : (row.status === 'DISABLED' ? 'danger' : 'warning')" effect="plain">{{ statusLabel(row.status) }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="entitlementCount" label="权益数" width="100" />
        <ElTableColumn label="首次建立身份" width="180"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn label="最近使用" width="180"><template #default="{ row }">{{ date(row.lastActiveAt) }}</template></ElTableColumn>
        <ElTableColumn label="设备封禁" width="110"><template #default="{ row }"><ElTag :type="row.bannedAt ? 'danger' : 'info'" effect="plain">{{ row.bannedAt ? '已封禁' : '未封禁' }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="note" label="备注" min-width="150" show-overflow-tooltip />
        <ElTableColumn label="操作" fixed="right" width="90"><template #default="{ row }"><ElButton link type="primary" :icon="View" @click="openDetail(row)">详情</ElButton></template></ElTableColumn>
      </ElTable>
      <div class="table-pagination"><ElPagination v-model:current-page="page.page" layout="total, prev, pager, next" :page-size="page.pageSize" :total="page.totalItems" @current-change="load" /></div>
    </section>

    <ElDrawer v-model="detailOpen" title="用户与设备详情" size="min(960px, 96vw)">
      <AdminLoadNotice :error="detailError" :loading="detailLoading" @retry="selectedDevice && openDetail(selectedDevice)" />
      <div v-loading="detailLoading" class="detail-stack">
        <template v-if="detail">
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="设备 ID">{{ detail.id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安装编号"><span class="break-value">{{ detail.publicId }}</span></ElDescriptionsItem>
            <ElDescriptionsItem label="平台">{{ platformLabel(detail.platform) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安装范围"><span class="break-value">{{ detail.appInstallScope }}</span></ElDescriptionsItem>
            <ElDescriptionsItem label="身份状态">{{ statusLabel(detail.status) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="权益数">{{ detail.entitlementCount }}</ElDescriptionsItem>
            <ElDescriptionsItem label="App">{{ channelLabel(detail.channel) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="App 版本">{{ detail.appVersionName || '未上报' }}{{ detail.appVersionCode ? ` (${detail.appVersionCode})` : '' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="设备品牌 / 型号">{{ detail.manufacturer || '未上报' }} / {{ detail.model || '未上报' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="系统版本">{{ detail.osVersion || '未上报' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="首次建立身份">{{ date(detail.createdAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="最近使用">{{ date(detail.lastActiveAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="最近身份验证">{{ date(detail.lastSeenAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="封禁时间">{{ date(detail.bannedAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem v-if="detail.banReason" label="封禁原因" :span="2">{{ detail.banReason }}</ElDescriptionsItem>
          </ElDescriptions>

          <section class="detail-section"><h3>运营备注</h3><ElInput v-model="note" type="textarea" :rows="3" maxlength="500" show-word-limit placeholder="记录跟进情况或设备说明" /><div class="page-actions" style="margin-top:12px"><ElButton type="primary" :loading="noteSaving" :disabled="note === detail.note" @click="saveNote">保存备注</ElButton><ElButton @click="openSecurity">查看 / 管理封禁</ElButton></div><small class="cell-meta">型号与系统为 App 上报信息。未上报时留空；封禁解除在安全风控中处理。</small></section>
          <section class="detail-section"><h3>兑换与购买记录</h3><AdminLoadNotice :error="historyError" :loading="historyLoading" @retry="loadHistory()" />
            <div v-if="!historyError" v-loading="historyLoading">
              <h4>兑换记录</h4><ElTable :data="redemptions" empty-text="暂无兑换记录"><ElTableColumn label="壁纸" min-width="160"><template #default="{ row }">{{ row.wallpaper.title }}</template></ElTableColumn><ElTableColumn prop="codeSuffix" label="兑换码尾号" width="120" /><ElTableColumn label="结果" width="140"><template #default="{ row }">{{ redemptionStatus[row.result] || row.result }}</template></ElTableColumn><ElTableColumn label="时间" width="180"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn><ElTableColumn prop="errorCode" label="失败原因" min-width="160" /></ElTable>
              <ElPagination v-model:current-page="redemptionPage.page" :total="redemptionPage.totalItems" :page-size="20" layout="total, prev, pager, next" @current-change="loadHistory()" />
              <h4>购买订单 / 历史内购</h4><ElAlert title="仅展示后台已有的购买事实。正式与测试环境分开标记；订单金额不作为全平台实收收入。旧版内购未记录金额时显示“未记录”。" type="info" :closable="false" />
              <ElTable :data="purchases" empty-text="暂无后台购买记录；线下付款不会自动成为 App 订单"><ElTableColumn label="壁纸 / 订单" min-width="220"><template #default="{ row }"><strong>{{ row.wallpaperTitle }}</strong><small class="cell-meta">{{ row.id }}</small><small class="cell-meta">交易 {{ row.transactionId || '未完成验证' }}</small><small v-if="row.restored" class="cell-meta">关联恢复购买</small></template></ElTableColumn><ElTableColumn label="状态 / 环境" width="160"><template #default="{ row }">{{ purchaseStatus(row.status) }}<small class="cell-meta">{{ row.environment === 'PRODUCTION' ? '正式环境' : `测试环境 (${row.environment})` }}</small></template></ElTableColumn><ElTableColumn label="订单金额" width="120"><template #default="{ row }">{{ row.amount === null ? '未记录' : `${row.currency} ${Number(row.amount).toFixed(2)}` }}</template></ElTableColumn><ElTableColumn label="创建 / 购买时间" width="180"><template #default="{ row }">{{ date(row.createdAt) }}<small v-if="row.revokedAt" class="cell-meta">退款 {{ date(row.revokedAt) }}</small></template></ElTableColumn><ElTableColumn prop="productId" label="商品" min-width="170" show-overflow-tooltip /></ElTable>
              <ElPagination v-model:current-page="purchasePage.page" :total="purchasePage.totalItems" :page-size="20" layout="total, prev, pager, next" @current-change="loadHistory()" />
            </div>
          </section>
          <section v-if="detail.platform === 'IOS'" class="detail-section">
            <div style="display:flex;align-items:center;justify-content:space-between;gap:12px"><h3>iOS 首免与内购</h3><ElButton :loading="actionLoading" @click="changeTestStatus(!detail.iosTestDevice)">{{ detail.iosTestDevice ? '取消测试机' : '标记测试机' }}</ElButton></div>
            <ElDescriptions v-if="detail.iosAcquisition" :column="2" border>
              <ElDescriptionsItem label="测试机">{{ detail.iosAcquisition.testDevice ? '是' : '否' }}</ElDescriptionsItem>
              <ElDescriptionsItem label="首免代次">{{ detail.iosAcquisition.freeGeneration }}</ElDescriptionsItem>
              <ElDescriptionsItem label="免费资格">{{ detail.iosAcquisition.freeAllowance }}</ElDescriptionsItem>
              <ElDescriptionsItem label="Apple 核对时间">{{ date(detail.iosAcquisition.deviceCheckCheckedAt) }}</ElDescriptionsItem>
            </ElDescriptions>
            <ElAlert v-if="detail.iosAcquisition?.pendingReset" type="warning" :closable="false" show-icon :title="`重置 ${detail.iosAcquisition.pendingReset.status}`" :description="resetDescription(detail.iosAcquisition.pendingReset)" />
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
