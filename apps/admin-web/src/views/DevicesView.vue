<script setup lang="ts">
import { Search } from '@element-plus/icons-vue';
import { computed, onMounted, ref } from 'vue';

import { platformLabels, type DeviceEntitlement, type Platform } from '@/domain/admin';
import { adminRepository } from '@/repositories/mock/adminRepository';

const devices = ref<DeviceEntitlement[]>([]);
const loading = ref(true);
const keyword = ref('');
const platform = ref<Platform | ''>('');
const filtered = computed(() => devices.value.filter((item) => (!keyword.value.trim() || item.deviceMask.toLowerCase().includes(keyword.value.trim().toLowerCase())) && (!platform.value || item.platform === platform.value)));
const platformLabel = (value: Platform) => platformLabels[value];
onMounted(async () => { loading.value = true; devices.value = await adminRepository.devices(); loading.value = false; });
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>设备权益</h1><p>以匿名设备为主体查看已获得壁纸数量，支持后续同设备免兑换码找回。</p></div></header>
    <ElAlert title="App 不要求用户登录" description="正式版由服务端签发设备凭证；重装后通过设备证明恢复已购权益，并记录找回审计。" type="info" show-icon :closable="false" />
    <section class="surface toolbar">
      <div class="toolbar__filters">
        <ElInput v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索匿名设备标识" style="width:250px" />
        <ElSelect v-model="platform" clearable placeholder="全部平台" style="width:145px"><ElOption v-for="(label, value) in platformLabels" :key="value" :label="label" :value="value" /></ElSelect>
      </div>
      <span class="toolbar__result">共 {{ filtered.length }} 台设备</span>
    </section>
    <section v-loading="loading" class="surface content-table">
      <ElTable :data="filtered" row-key="id">
        <ElTableColumn prop="deviceMask" label="匿名设备标识" min-width="220" />
        <ElTableColumn label="平台" width="130"><template #default="{ row }"><ElTag effect="plain">{{ platformLabel(row.platform) }}</ElTag></template></ElTableColumn>
        <ElTableColumn label="已获得壁纸" width="140"><template #default="{ row }"><strong>{{ row.wallpaperCount }}</strong> 张</template></ElTableColumn>
        <ElTableColumn prop="lastActiveAt" label="最后活跃" min-width="180" />
        <ElTableColumn label="状态" width="110"><template #default="{ row }"><ElTag :type="row.status === 'active' ? 'success' : 'info'">{{ row.status === 'active' ? '正常' : '停用' }}</ElTag></template></ElTableColumn>
      </ElTable>
    </section>
  </section>
</template>
