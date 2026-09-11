<script setup lang="ts">
import { Search, View } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { ElMessage } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import type { DeviceDetail, DevicePlatform, DeviceStatus, DeviceSummary, PageMetadata } from '@/domain/admin';
import { adminRepository } from '@/repositories/http/adminRepository';
import { readableApiError } from '@/repositories/http/apiClient';

const loading = ref(true);
const rows = ref<DeviceSummary[]>([]);
const page = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const platform = ref<DevicePlatform | ''>('');
const status = ref<DeviceStatus | ''>('');
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<DeviceDetail>();

const platformLabels: Record<DevicePlatform, string> = { ANDROID: 'Android', IOS: 'iOS', HARMONYOS: 'HarmonyOS', H5_TEST: 'H5 联调' };
const statusLabels: Record<DeviceStatus, string> = { ACTIVE: '正常', REVIEW: '待核查', DISABLED: '已停用' };
const platformLabel = (value: DevicePlatform) => platformLabels[value];
const statusLabel = (value: DeviceStatus) => statusLabels[value];
const date = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';

const load = async () => {
  loading.value = true;
  try {
    const result = await adminRepository.devices({ page: page.page, pageSize: page.pageSize, platform: platform.value, status: status.value });
    rows.value = result.items;
    Object.assign(page, result.page);
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '设备列表加载失败'));
  } finally {
    loading.value = false;
  }
};
const search = () => { page.page = 1; load(); };
const openDetail = async (row: DeviceSummary) => {
  detailOpen.value = true;
  detailLoading.value = true;
  try {
    detail.value = await adminRepository.device(row.id);
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '设备详情加载失败'));
  } finally {
    detailLoading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>设备权益</h1><p>以匿名设备为主体查看凭证活动与已获得壁纸权益。</p></div></header>
    <ElAlert title="App 不要求用户登录" description="设备身份由服务端凭证识别；管理端仅展示可审计元数据，不返回设备密钥、证据摘要或公钥材料。" type="info" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElSelect v-model="platform" clearable placeholder="全部平台" style="width:150px"><ElOption v-for="(label, value) in platformLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElSelect v-model="status" clearable placeholder="全部状态" style="width:140px"><ElOption v-for="(label, value) in statusLabels" :key="value" :label="label" :value="value" /></ElSelect>
        <ElButton type="primary" :icon="Search" @click="search">查询</ElButton>
      </div>
      <span class="toolbar__result">共 {{ page.totalItems }} 台匿名设备</span>
    </section>
    <section v-loading="loading" class="surface content-table">
      <ElTable :data="rows" empty-text="暂无设备记录">
        <ElTableColumn prop="id" label="设备 ID" min-width="110" />
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
      <div v-loading="detailLoading" class="detail-stack">
        <template v-if="detail">
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="设备 ID">{{ detail.id }}</ElDescriptionsItem>
            <ElDescriptionsItem label="平台">{{ platformLabel(detail.platform) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="安装范围"><span class="break-value">{{ detail.appInstallScope }}</span></ElDescriptionsItem>
            <ElDescriptionsItem label="状态">{{ statusLabel(detail.status) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="权益数">{{ detail.entitlementCount }}</ElDescriptionsItem>
            <ElDescriptionsItem label="最后活跃">{{ date(detail.lastSeenAt) }}</ElDescriptionsItem>
          </ElDescriptions>

          <section class="detail-section">
            <h3>设备凭证</h3>
            <ElTable :data="detail.credentials" empty-text="暂无凭证">
              <ElTableColumn prop="credentialKeyId" label="凭证标识" min-width="220" />
              <ElTableColumn label="类型" width="150"><template #default="{ row }">{{ row.credentialType === 'H5_TEST_SECRET' ? 'H5 联调密钥' : '平台公钥' }}</template></ElTableColumn>
              <ElTableColumn prop="status" label="状态" width="100" />
              <ElTableColumn label="最后使用" width="180"><template #default="{ row }">{{ date(row.lastUsedAt) }}</template></ElTableColumn>
            </ElTable>
          </section>

          <section class="detail-section">
            <h3>壁纸权益</h3>
            <ElTable :data="detail.entitlements" empty-text="该设备尚无权益">
              <ElTableColumn label="壁纸" min-width="220"><template #default="{ row }"><strong>{{ row.wallpaper.title }}</strong><small class="cell-meta">ID {{ row.wallpaper.id }}</small></template></ElTableColumn>
              <ElTableColumn prop="status" label="状态" width="100" />
              <ElTableColumn label="授予时间" width="180"><template #default="{ row }">{{ date(row.grantedAt) }}</template></ElTableColumn>
              <ElTableColumn label="撤销时间" width="180"><template #default="{ row }">{{ date(row.revokedAt) }}</template></ElTableColumn>
            </ElTable>
          </section>
        </template>
      </div>
    </ElDrawer>
  </section>
</template>
