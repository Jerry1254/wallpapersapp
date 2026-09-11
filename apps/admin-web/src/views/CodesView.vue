<script setup lang="ts">
import { CopyDocument, Download, Plus, Search } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref } from 'vue';

import type { CodeBatch } from '@/domain/admin';
import { adminRepository } from '@/repositories/mock/adminRepository';

const batches = ref<CodeBatch[]>([]);
const loading = ref(true);
const keyword = ref('');
const createOpen = ref(false);
const resultOpen = ref(false);
const generating = ref(false);
const generatedCodes = ref<string[]>([]);
const resultName = ref('');
const form = reactive({ name: '', codeCount: 100, quotaPerCode: 3 });
const filtered = computed(() => batches.value.filter((item) => item.name.includes(keyword.value.trim())));

const load = async () => { loading.value = true; batches.value = await adminRepository.batches(); loading.value = false; };
const openCreate = () => { Object.assign(form, { name: `直播兑换码 ${new Date().toLocaleDateString('zh-CN')}`, codeCount: 100, quotaPerCode: 3 }); createOpen.value = true; };
const generate = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入批次名称'); return; }
  generating.value = true;
  const result = await adminRepository.generateBatch(form.name.trim(), form.codeCount, form.quotaPerCode);
  generating.value = false;
  generatedCodes.value = result.codes;
  resultName.value = result.batch.name;
  createOpen.value = false;
  resultOpen.value = true;
  await load();
};
const copy = async () => { await navigator.clipboard.writeText(generatedCodes.value.join('\n')); ElMessage.success('兑换码已复制'); };
const download = () => {
  const blob = new Blob([`兑换码,可兑换壁纸数\n${generatedCodes.value.map((code) => `${code},${form.quotaPerCode}`).join('\n')}`], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = `${resultName.value}.csv`;
  link.click();
  URL.revokeObjectURL(url);
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>兑换码</h1><p>批量生成全平台通用兑换码，并为每个批次固定可兑换壁纸数量。</p></div>
      <div class="page-actions"><ElButton type="primary" :icon="Plus" @click="openCreate">生成兑换码</ElButton></div>
    </header>
    <ElAlert title="兑换数量采用生成时快照" description="例如批次额度设为 3，已发出的兑换码始终可兑换 3 张壁纸；以后修改默认值不会影响旧批次。" type="warning" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters"><ElInput v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索批次名称" style="width:250px" /></div>
      <span class="toolbar__result">共 {{ filtered.length }} 个批次</span>
    </section>
    <section v-loading="loading" class="surface content-table">
      <ElTable :data="filtered" row-key="id">
        <ElTableColumn prop="name" label="批次名称" min-width="210" />
        <ElTableColumn prop="codeCount" label="兑换码数" width="110" />
        <ElTableColumn label="每码额度" width="120"><template #default="{ row }">{{ row.quotaPerCode }} 张</template></ElTableColumn>
        <ElTableColumn label="已兑换次数" width="130"><template #default="{ row }">{{ row.redeemed }} / {{ row.codeCount * row.quotaPerCode }}</template></ElTableColumn>
        <ElTableColumn label="使用进度" min-width="180">
          <template #default="{ row }"><ElProgress :percentage="Math.round(row.redeemed / (row.codeCount * row.quotaPerCode) * 100)" :stroke-width="8" /></template>
        </ElTableColumn>
        <ElTableColumn prop="createdAt" label="生成时间" min-width="160" />
      </ElTable>
    </section>

    <ElDialog v-model="createOpen" title="生成兑换码" width="min(500px, 92vw)">
      <ElForm label-position="top">
        <ElFormItem label="批次名称"><ElInput v-model="form.name" maxlength="30" show-word-limit /></ElFormItem>
        <ElFormItem label="生成数量"><ElInputNumber v-model="form.codeCount" :min="1" :max="1000" :step="10" controls-position="right" style="width:100%" /></ElFormItem>
        <ElFormItem label="每个兑换码可兑换壁纸数"><ElInputNumber v-model="form.quotaPerCode" :min="1" :max="20" controls-position="right" style="width:100%" /></ElFormItem>
        <ElAlert title="兑换码不限定具体壁纸，可由设备在额度内任选。" type="info" :closable="false" />
      </ElForm>
      <template #footer><div class="dialog-actions"><ElButton @click="createOpen=false">取消</ElButton><ElButton type="primary" :loading="generating" @click="generate">确认生成</ElButton></div></template>
    </ElDialog>

    <ElDialog v-model="resultOpen" title="兑换码已生成" width="min(620px, 94vw)" :close-on-click-modal="false">
      <div class="code-result">
        <div class="code-result__notice"><strong>请立即保存：</strong>为避免后台长期暴露完整兑换码，本页关闭后列表只保留批次统计，不再展示完整码。</div>
        <textarea readonly :value="generatedCodes.join('\n')" aria-label="本批次完整兑换码"></textarea>
        <div class="dialog-actions"><ElButton :icon="CopyDocument" @click="copy">复制全部</ElButton><ElButton type="primary" :icon="Download" @click="download">下载 CSV</ElButton></div>
      </div>
    </ElDialog>
  </section>
</template>
