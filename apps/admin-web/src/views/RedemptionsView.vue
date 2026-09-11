<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';

import type { RedemptionRecord } from '@/domain/admin';
import { adminRepository } from '@/repositories/mock/adminRepository';

const records = ref<RedemptionRecord[]>([]);
const loading = ref(true);
const keyword = ref('');
const result = ref<RedemptionRecord['result'] | ''>('');
const filtered = computed(() => records.value.filter((item) => {
  const query = keyword.value.trim().toLowerCase();
  return (!query || `${item.codeMask}${item.wallpaperTitle}${item.deviceMask}`.toLowerCase().includes(query)) && (!result.value || item.result === result.value);
}));
onMounted(async () => { loading.value = true; records.value = await adminRepository.redemptions(); loading.value = false; });
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>兑换记录</h1><p>查询兑换码、壁纸和设备之间的兑换结果。</p></div></header>
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索兑换码、壁纸或设备" style="width:290px" />
        <ElSelect v-model="result" clearable placeholder="全部结果" style="width:130px"><ElOption label="成功" value="success" /><ElOption label="失败" value="failed" /></ElSelect>
      </div>
      <span class="toolbar__result">共 {{ filtered.length }} 条</span>
    </section>
    <section v-loading="loading" class="surface content-table">
      <ElTable :data="filtered" row-key="id">
        <ElTableColumn prop="codeMask" label="兑换码" min-width="170" />
        <ElTableColumn prop="wallpaperTitle" label="兑换壁纸" min-width="180" />
        <ElTableColumn prop="deviceMask" label="绑定设备" min-width="180" />
        <ElTableColumn label="结果" width="100"><template #default="{ row }"><ElTag :type="row.result === 'success' ? 'success' : 'danger'">{{ row.result === 'success' ? '成功' : '失败' }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="createdAt" label="兑换时间" min-width="170" />
      </ElTable>
    </section>
  </section>
</template>
