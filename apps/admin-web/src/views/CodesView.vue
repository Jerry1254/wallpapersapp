<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { CopyDocument, Download, Plus, Search, View } from '@element-plus/icons-vue';
import dayjs from 'dayjs';
import { ElMessage, ElMessageBox } from 'element-plus';
import { onMounted, reactive, ref } from 'vue';

import type { CodeBatchDetail, CodeBatchSummary, CreateCodeBatchResponse, DeliveryStatus, PageMetadata, RedemptionCode, RedemptionCodeStatus } from '@/domain/admin';
import { adminRepository } from '@/repositories/http/adminRepository';
import { readableApiError } from '@/repositories/http/apiClient';

const loading = ref(true);
const loadError = ref('');
const batches = ref<CodeBatchSummary[]>([]);
const page = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const query = ref('');
const createOpen = ref(false);
const creating = ref(false);
const createKey = ref('');
const form = reactive({ name: '', generatedCount: 100, quotaPerCode: 1 });
const delivery = ref<CreateCodeBatchResponse>();
const delivering = ref(false);
const detailOpen = ref(false);
const detail = ref<CodeBatchDetail>();
const codes = ref<RedemptionCode[]>([]);
const codesPage = reactive<PageMetadata>({ page: 1, pageSize: 20, totalItems: 0, totalPages: 0 });
const codeStatus = ref<RedemptionCodeStatus | ''>('');
const codeSuffix = ref('');
const detailLoading = ref(false);
const detailError = ref('');
const codesError = ref('');
const selectedBatch = ref<CodeBatchSummary>();

const deliveryLabels = { AVAILABLE: '待确认', CONFIRMED: '已确认', EXPIRED: '已过期' } as const;
const deliveryTag = { AVAILABLE: 'warning', CONFIRMED: 'success', EXPIRED: 'info' } as const;
const deliveryLabel = (status: DeliveryStatus) => deliveryLabels[status];
const deliveryTagType = (status: DeliveryStatus) => deliveryTag[status];
const date = (value?: string | null) => value ? dayjs(value).format('YYYY-MM-DD HH:mm') : '—';
const copyCode = async (code: string) => {
  try {
    await navigator.clipboard.writeText(code);
    ElMessage.success('兑换码已复制');
  } catch {
    ElMessage.error('复制失败，请手动选择兑换码');
  }
};

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    const result = await adminRepository.codeBatches({ page: page.page, pageSize: page.pageSize, q: query.value });
    batches.value = result.items;
    Object.assign(page, result.page);
  } catch (cause) {
    loadError.value = readableApiError(cause, '兑换码批次加载失败');
  } finally {
    loading.value = false;
  }
};

const search = () => { page.page = 1; load(); };
const openCreate = () => {
  Object.assign(form, { name: '', generatedCount: 100, quotaPerCode: 1 });
  createKey.value = crypto.randomUUID();
  createOpen.value = true;
};
const createBatch = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入批次名称'); return; }
  creating.value = true;
  try {
    delivery.value = await adminRepository.createCodeBatch({
      name: form.name.trim(),
      generatedCount: form.generatedCount,
      quotaPerCode: form.quotaPerCode
    }, createKey.value);
    createOpen.value = false;
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '批次创建失败'));
  } finally {
    creating.value = false;
  }
};

const saveBlob = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};
const downloadDelivery = async () => {
  if (!delivery.value) return;
  delivering.value = true;
  try {
    const file = await adminRepository.downloadCodeBatch(delivery.value.batch.id, delivery.value.deliveryTicket);
    saveBlob(file.data, file.filename);
    await adminRepository.confirmCodeBatchDelivery(delivery.value.batch.id);
    delivery.value = undefined;
    ElMessage.success('已下载 CSV，兑换码仍可在批次详情中查看');
    await load();
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '兑换码交付失败'));
  } finally {
    delivering.value = false;
  }
};
const discardDelivery = async () => {
  if (!delivery.value) return;
  try {
    await ElMessageBox.confirm('暂不下载 CSV 后，仍可在批次详情中查看和复制兑换码。', '暂不下载', {
      confirmButtonText: '确认', cancelButtonText: '继续下载', type: 'info'
    });
    await adminRepository.confirmCodeBatchDelivery(delivery.value.batch.id);
    delivery.value = undefined;
    await load();
  } catch (cause) {
    if (cause !== 'cancel' && cause !== 'close') ElMessage.error(readableApiError(cause, '交付确认失败'));
  }
};

const loadCodes = async () => {
  if (!detail.value) return;
  codesError.value = '';
  detailLoading.value = true;
  try {
    const result = await adminRepository.redemptionCodes(detail.value.id, {
      page: codesPage.page,
      pageSize: codesPage.pageSize,
      status: codeStatus.value,
      suffix: codeSuffix.value
    });
    codes.value = result.items;
    Object.assign(codesPage, result.page);
  } catch (cause) {
    codesError.value = readableApiError(cause, '兑换码额度加载失败');
  } finally {
    detailLoading.value = false;
  }
};
const openDetail = async (row: CodeBatchSummary) => {
  selectedBatch.value = row;
  detail.value = undefined;
  codes.value = [];
  detailError.value = '';
  detailOpen.value = true;
  detailLoading.value = true;
  codeStatus.value = '';
  codeSuffix.value = '';
  codesPage.page = 1;
  try {
    detail.value = await adminRepository.codeBatch(row.id);
    await loadCodes();
  } catch (cause) {
    detailError.value = readableApiError(cause, '批次详情加载失败');
  } finally {
    detailLoading.value = false;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>兑换码</h1><p>生成兑换码批次，可在批次详情中查看、复制完整兑换码，也可下载 CSV。</p></div>
      <div class="page-actions"><ElButton type="primary" :icon="Plus" @click="openCreate">生成批次</ElButton></div>
    </header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />

    <ElAlert title="兑换码可长期查看" description="新生成的兑换码会加密保存，可在批次详情中查看和复制；历史批次若未保存原文，仍显示掩码。" type="success" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="query" :prefix-icon="Search" clearable placeholder="搜索批次号或名称" style="width:280px" @keyup.enter="search" />
        <ElButton @click="search">查询</ElButton>
      </div>
      <span v-if="!loadError" class="toolbar__result">共 {{ page.totalItems }} 个批次</span>
    </section>
    <section v-if="!loadError" v-loading="loading" class="surface content-table">
      <ElTable :data="batches" empty-text="暂无兑换码批次">
        <ElTableColumn prop="batchNo" label="批次号" min-width="220" />
        <ElTableColumn prop="name" label="批次名称" min-width="180" />
        <ElTableColumn label="码数 / 单码额度" width="150"><template #default="{ row }">{{ row.generatedCount }} / {{ row.quotaPerCodeSnapshot }}</template></ElTableColumn>
        <ElTableColumn label="已用额度" width="180"><template #default="{ row }"><ElProgress :percentage="row.usagePercent" :stroke-width="8" /></template></ElTableColumn>
        <ElTableColumn label="明文交付" width="110"><template #default="{ row }"><ElTag :type="deliveryTagType(row.deliveryStatus)" effect="plain">{{ deliveryLabel(row.deliveryStatus) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="创建时间" width="170"><template #default="{ row }">{{ date(row.createdAt) }}</template></ElTableColumn>
        <ElTableColumn label="操作" width="100"><template #default="{ row }"><ElButton link type="primary" :icon="View" @click="openDetail(row)">查看</ElButton></template></ElTableColumn>
      </ElTable>
      <div class="table-pagination"><ElPagination v-model:current-page="page.page" v-model:page-size="page.pageSize" layout="total, prev, pager, next" :total="page.totalItems" @current-change="load" /></div>
    </section>

    <ElDialog v-model="createOpen" title="生成兑换码批次" width="min(500px, 92vw)" :close-on-click-modal="false">
      <ElForm label-position="top">
        <ElFormItem label="批次名称"><ElInput v-model="form.name" maxlength="50" show-word-limit placeholder="例如：首发活动 A 组" /></ElFormItem>
        <div class="form-grid">
          <ElFormItem label="生成数量"><ElInputNumber v-model="form.generatedCount" :min="1" :max="10000" controls-position="right" style="width:100%" /></ElFormItem>
          <ElFormItem label="每码可兑换设备数"><ElInputNumber v-model="form.quotaPerCode" :min="1" :max="100" controls-position="right" style="width:100%" /></ElFormItem>
        </div>
      </ElForm>
      <template #footer><div class="dialog-actions"><ElButton @click="createOpen=false">取消</ElButton><ElButton type="primary" :loading="creating" @click="createBatch">生成并进入交付</ElButton></div></template>
    </ElDialog>

    <ElDialog :model-value="Boolean(delivery)" title="下载兑换码 CSV" width="min(560px, 92vw)" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false">
      <div v-if="delivery" class="code-result">
        <div class="code-result__notice">批次 {{ delivery.batch.batchNo }} 已生成，共 {{ delivery.batch.generatedCount }} 个兑换码。本次 CSV 在 {{ date(delivery.deliveryExpiresAt) }} 前可下载。</div>
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="批次名称">{{ delivery.batch.name }}</ElDescriptionsItem>
          <ElDescriptionsItem label="总额度">{{ delivery.batch.totalQuota }}</ElDescriptionsItem>
        </ElDescriptions>
        <p class="delivery-copy">下载窗口关闭后，完整兑换码仍可在批次详情中查看和复制。</p>
      </div>
      <template #footer><div class="dialog-actions"><ElButton :disabled="delivering" @click="discardDelivery">暂不下载</ElButton><ElButton type="primary" :icon="Download" :loading="delivering" @click="downloadDelivery">下载 CSV</ElButton></div></template>
    </ElDialog>

    <ElDrawer v-model="detailOpen" title="批次详情" size="min(820px, 96vw)">
      <AdminLoadNotice :error="detailError" :loading="detailLoading" @retry="selectedBatch && openDetail(selectedBatch)" />
      <div v-if="detail" class="detail-stack">
        <ElDescriptions :column="2" border>
          <ElDescriptionsItem label="批次号">{{ detail.batchNo }}</ElDescriptionsItem>
          <ElDescriptionsItem label="名称">{{ detail.name }}</ElDescriptionsItem>
          <ElDescriptionsItem label="可用码">{{ detail.availableCodeCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="耗尽码">{{ detail.exhaustedCodeCount }}</ElDescriptionsItem>
          <ElDescriptionsItem label="额度">{{ detail.usedQuota }} / {{ detail.totalQuota }}</ElDescriptionsItem>
          <ElDescriptionsItem label="交付状态">{{ deliveryLabel(detail.deliveryStatus) }}</ElDescriptionsItem>
        </ElDescriptions>
        <section class="detail-section">
          <div class="toolbar compact-toolbar">
            <div class="toolbar__filters">
              <ElSelect v-model="codeStatus" clearable placeholder="额度状态" style="width:130px" @change="codesPage.page=1; loadCodes()"><ElOption label="可用" value="AVAILABLE" /><ElOption label="已耗尽" value="EXHAUSTED" /></ElSelect>
              <ElInput v-model="codeSuffix" clearable placeholder="末位码" style="width:150px" @keyup.enter="codesPage.page=1; loadCodes()" />
              <ElButton :icon="Search" @click="codesPage.page=1; loadCodes()">查询</ElButton>
            </div>
          </div>
          <AdminLoadNotice :error="codesError" :loading="detailLoading" @retry="loadCodes" />
          <ElTable v-if="!codesError" v-loading="detailLoading" :data="codes" empty-text="没有符合条件的兑换码">
            <ElTableColumn label="兑换码" min-width="290">
              <template #default="{ row }">
                <span class="code-cell">
                  <code>{{ row.code || row.maskedCode }}</code>
                  <ElButton v-if="row.code" link type="primary" :icon="CopyDocument" @click="copyCode(row.code)">复制</ElButton>
                </span>
              </template>
            </ElTableColumn>
            <ElTableColumn label="额度" width="120"><template #default="{ row }">{{ row.usedQuota }} / {{ row.totalQuota }}</template></ElTableColumn>
            <ElTableColumn prop="remainingQuota" label="剩余" width="90" />
            <ElTableColumn label="状态" width="100"><template #default="{ row }"><ElTag :type="row.status === 'AVAILABLE' ? 'success' : 'info'" effect="plain">{{ row.status === 'AVAILABLE' ? '可用' : '已耗尽' }}</ElTag></template></ElTableColumn>
          </ElTable>
          <div class="table-pagination"><ElPagination v-model:current-page="codesPage.page" layout="total, prev, pager, next" :page-size="codesPage.pageSize" :total="codesPage.totalItems" @current-change="loadCodes" /></div>
        </section>
      </div>
    </ElDrawer>
  </section>
</template>

<style scoped>
.code-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.code-cell code {
  color: var(--el-text-color-primary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  letter-spacing: 0.04em;
}
</style>
