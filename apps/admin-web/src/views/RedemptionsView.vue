<script setup lang="ts">
import { Search, View } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { ElMessage } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import type { PageMetadata, RedemptionDetail, RedemptionResult, RedemptionSummary } from '@/domain/admin';
import { adminRepository } from '@/repositories/http/adminRepository';
import { readableApiError } from '@/repositories/http/apiClient';

const loading = ref(true);
const rows = ref<RedemptionSummary[]>([]);
const page = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const filters = reactive({ codeSuffix: '', wallpaperId: '', deviceId: '', result: '' as RedemptionResult | '' });
const createdRange = ref<[Date, Date]>();
const detailOpen = ref(false);
const detailLoading = ref(false);
const detail = ref<RedemptionDetail>();

const resultLabels: Record<RedemptionResult, string> = {
  GRANTED: '授权成功',
  ALREADY_OWNED: '已拥有',
  CODE_NOT_FOUND: '兑换码无效',
  CODE_EXHAUSTED: '额度耗尽',
  WALLPAPER_UNAVAILABLE: '壁纸不可用',
  FAILED: '处理失败'
};
const resultLabel = (result: RedemptionResult) => resultLabels[result];
const resultTag = (result: RedemptionResult) => result === 'GRANTED' || result === 'ALREADY_OWNED'
  ? 'success'
  : (result === 'FAILED' ? 'danger' : 'warning');
const date = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm:ss') : '—';

const load = async () => {
  loading.value = true;
  try {
    const result = await adminRepository.redemptions({
      page: page.page,
      pageSize: page.pageSize,
      ...filters,
      createdFrom: createdRange.value?.[0].toISOString(),
      createdTo: createdRange.value?.[1].toISOString()
    });
    rows.value = result.items;
    Object.assign(page, result.page);
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '兑换记录加载失败'));
  } finally {
    loading.value = false;
  }
};
const search = () => { page.page = 1; load(); };
const reset = () => {
  Object.assign(filters, { codeSuffix: '', wallpaperId: '', deviceId: '', result: '' });
  createdRange.value = undefined;
  search();
};
const openDetail = async (row: RedemptionSummary) => {
  detailOpen.value = true;
  detailLoading.value = true;
  try {
    detail.value = await adminRepository.redemption(row.id);
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '兑换详情加载失败'));
  } finally {
    detailLoading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>兑换记录</h1><p>查询兑换码、壁纸和匿名设备之间已落库的兑换事实。</p></div></header>
    <section class="surface filter-panel">
      <div class="filter-grid">
        <ElInput v-model="filters.codeSuffix" clearable placeholder="兑换码末位" @keyup.enter="search" />
        <ElInput v-model="filters.wallpaperId" clearable placeholder="壁纸 ID" @keyup.enter="search" />
        <ElInput v-model="filters.deviceId" clearable placeholder="设备 ID" @keyup.enter="search" />
        <ElSelect v-model="filters.result" clearable placeholder="兑换结果">
          <ElOption v-for="(label, value) in resultLabels" :key="value" :label="label" :value="value" />
        </ElSelect>
        <ElDatePicker v-model="createdRange" type="datetimerange" range-separator="至" start-placeholder="开始时间" end-placeholder="结束时间" style="width:100%" />
        <div class="filter-actions"><ElButton :icon="Search" type="primary" @click="search">查询</ElButton><ElButton @click="reset">重置</ElButton></div>
      </div>
    </section>
    <section v-loading="loading" class="surface content-table">
      <ElTable :data="rows" empty-text="暂无兑换记录">
        <ElTableColumn label="结果" width="120"><template #default="{ row }"><ElTag :type="resultTag(row.result)" effect="plain">{{ resultLabel(row.result) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="壁纸" min-width="180"><template #default="{ row }"><strong>{{ row.wallpaper.title }}</strong><small class="cell-meta">ID {{ row.wallpaper.id }}</small></template></ElTableColumn>
        <ElTableColumn label="兑换码" min-width="210"><template #default="{ row }">{{ row.maskedCode || '—' }}</template></ElTableColumn>
        <ElTableColumn label="设备 ID" min-width="120"><template #default="{ row }">{{ row.deviceId }}</template></ElTableColumn>
        <ElTableColumn label="额度变化" width="100"><template #default="{ row }">{{ row.quotaDelta ? `+${row.quotaDelta}` : '0' }}</template></ElTableColumn>
        <ElTableColumn label="发生时间" width="180"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn label="操作" width="90"><template #default="{ row }"><ElButton link type="primary" :icon="View" @click="openDetail(row)">详情</ElButton></template></ElTableColumn>
      </ElTable>
      <div class="table-pagination"><ElPagination v-model:current-page="page.page" layout="total, prev, pager, next" :page-size="page.pageSize" :total="page.totalItems" @current-change="load" /></div>
    </section>

    <ElDrawer v-model="detailOpen" title="兑换事实详情" size="min(680px, 96vw)">
      <div v-loading="detailLoading" class="detail-stack">
        <template v-if="detail">
          <ElDescriptions :column="2" border>
            <ElDescriptionsItem label="结果"><ElTag :type="resultTag(detail.result)" effect="plain">{{ resultLabel(detail.result) }}</ElTag></ElDescriptionsItem>
            <ElDescriptionsItem label="发生时间">{{ date(detail.createdAt) }}</ElDescriptionsItem>
            <ElDescriptionsItem label="壁纸">{{ detail.wallpaper.title }}（{{ detail.wallpaper.id }}）</ElDescriptionsItem>
            <ElDescriptionsItem label="兑换码">{{ detail.maskedCode || '—' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="额度变化">{{ detail.quotaDelta }}</ElDescriptionsItem>
            <ElDescriptionsItem label="错误码">{{ detail.errorCode || '—' }}</ElDescriptionsItem>
            <ElDescriptionsItem label="请求事实 ID">{{ detail.redemptionRequestId }}</ElDescriptionsItem>
            <ElDescriptionsItem label="幂等键"><span class="break-value">{{ detail.idempotencyKey }}</span></ElDescriptionsItem>
          </ElDescriptions>
          <section class="detail-section">
            <h3>匿名设备</h3>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="设备 ID">{{ detail.device.id }}</ElDescriptionsItem>
              <ElDescriptionsItem label="平台">{{ detail.device.platform }}</ElDescriptionsItem>
              <ElDescriptionsItem label="状态">{{ detail.device.status }}</ElDescriptionsItem>
              <ElDescriptionsItem label="最后活跃">{{ date(detail.device.lastSeenAt) }}</ElDescriptionsItem>
            </ElDescriptions>
          </section>
          <section v-if="detail.entitlement" class="detail-section">
            <h3>授予权益</h3>
            <ElDescriptions :column="2" border>
              <ElDescriptionsItem label="权益 ID">{{ detail.entitlement.id }}</ElDescriptionsItem>
              <ElDescriptionsItem label="状态">{{ detail.entitlement.status }}</ElDescriptionsItem>
              <ElDescriptionsItem label="壁纸">{{ detail.entitlement.wallpaper.title }}</ElDescriptionsItem>
              <ElDescriptionsItem label="授予时间">{{ date(detail.entitlement.grantedAt) }}</ElDescriptionsItem>
            </ElDescriptions>
          </section>
        </template>
      </div>
    </ElDrawer>
  </section>
</template>
