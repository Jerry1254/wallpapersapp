<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { Refresh } from '@element-plus/icons-vue';
import { useRouter } from 'vue-router';
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { readableApiError } from '@/repositories/http/apiClient';
import { operationsRepository as api, userChannelLabels, type OperationsOverview, type UserChannel } from '@/repositories/http/operationsRepository';
import { securityTime } from '@/repositories/http/securityRepository';
const router = useRouter();
const loading = ref(false), error = ref(''), days = ref<7 | 30>(7), channel = ref<UserChannel | ''>('');
const data = ref<OperationsOverview>();
let generation = 0;
async function load() {
  const current = ++generation;
  loading.value = true; error.value = '';
  try { const result = await api.overview(days.value, channel.value); if (current === generation) data.value = result; }
  catch (cause) { if (current === generation) error.value = readableApiError(cause, '运营数据加载失败'); }
  finally { if (current === generation) loading.value = false; }
}
const stats = computed(() => data.value ? [
  { label: '累计用户', value: data.value.totalUsers, hint: '按设备安装身份累计' },
  { label: '今日新增', value: data.value.newUsersToday, hint: '今天首次建立身份' },
  { label: '今日使用人数', value: data.value.activeUsersToday, hint: '同一设备当天只计一次' },
  { label: '今日回访', value: data.value.returningUsersToday, hint: '今天使用的既有用户' },
  { label: '近 7 天使用人数', value: data.value.activeUsers7Days, hint: '7 天内设备去重' },
  { label: '近 30 天使用人数', value: data.value.activeUsers30Days, hint: '30 天内设备去重' },
  { label: '今日成功兑换', value: data.value.redemptionsToday, hint: '成功新增权益的兑换' },
  { label: '今日下载请求', value: data.value.downloadRequestsToday, hint: '取得下载票据，未表示设置成功' }
] : []);
const max = computed(() => Math.max(1, ...(data.value?.trend.flatMap(day => [day.newUsers, day.activeUsers ?? 0]) ?? [])));
function openUsers() { router.push({ path: '/devices', query: { ...(channel.value && { channel: channel.value }) } }); }
onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>运营概览</h1><p>查看用户增长、使用情况与四个 App 版本的分布。</p></div><div class="page-actions"><ElButton @click="openUsers">用户管理</ElButton><ElButton :icon="Refresh" :loading="loading" @click="load">刷新</ElButton></div></header>
    <section class="surface toolbar"><div class="toolbar__filters"><ElSelect v-model="channel" clearable placeholder="全部 App 版本" style="width:240px" @change="load"><ElOption v-for="(label, value) in userChannelLabels" :key="value" :label="label" :value="value" /></ElSelect><ElRadioGroup v-model="days" @change="load"><ElRadioButton :value="7">最近 7 天</ElRadioButton><ElRadioButton :value="30">最近 30 天</ElRadioButton></ElRadioGroup></div><span v-if="data" class="toolbar__result">北京时间 · {{ data.today }}</span></section>
    <AdminLoadNotice :error="error" :loading="loading" @retry="load" />
    <template v-if="data && !error">
      <ElAlert title="统计口径" :description="`用户按设备安装身份统计，同一人使用多设备可能重复；不计 H5 联调和已标记的 iOS 测试机。使用指通过安全验证后进入 App 前台，或成功访问业务内容。活跃采集自 ${securityTime(data.trackedSince)} 开始，首日及不足 7／30 天的周期仅统计已采集部分。`" type="info" show-icon :closable="false" />
      <div v-loading="loading" class="stat-grid"><article v-for="item in stats" :key="item.label" class="surface stat-card"><span>{{ item.label }}</span><strong>{{ item.value.toLocaleString() }}</strong><small>{{ item.hint }}</small></article></div>
      <section class="surface content-table"><header class="panel-heading"><div><h2>使用与新增趋势</h2><p>橙色为使用人数，灰色为新增用户；历史未采集的活跃数据留空。</p></div><span class="toolbar__result">设备去重</span></header>
        <div class="usage-chart" role="img" aria-label="每日使用与新增趋势，具体人数见下方表格">
          <div v-for="day in data.trend" :key="day.date" class="usage-chart__day" :title="`${day.date}：使用 ${day.activeUsers ?? '未采集'}，新增 ${day.newUsers}`">
            <div class="usage-chart__bars"><span v-if="day.activeUsers !== null" class="usage-chart__active" :style="{ height: `${day.activeUsers / max * 100}%` }" /><span class="usage-chart__new" :style="{ height: `${day.newUsers / max * 100}%` }" /></div><small>{{ day.date.slice(5) }}</small>
          </div>
        </div>
        <ElTable :data="data.trend" max-height="310"><ElTableColumn prop="date" label="日期" /><ElTableColumn prop="newUsers" label="新增用户" /><ElTableColumn label="使用人数"><template #default="{ row }">{{ row.activeUsers ?? '未采集' }}</template></ElTableColumn></ElTable>
      </section>
      <section class="surface content-table"><header class="panel-heading"><div><h2>App 版本分布</h2><p>设备注册时绑定的 App 归属</p></div><span>设备封禁 {{ data.bannedUsers }} 人</span></header><ElTable :data="data.channels" empty-text="尚无用户数据"><ElTableColumn label="App / 平台" min-width="200"><template #default="{ row }">{{ userChannelLabels[row.channel as UserChannel] }}</template></ElTableColumn><ElTableColumn prop="totalUsers" label="累计用户" /><ElTableColumn prop="newUsersToday" label="今日新增" /><ElTableColumn prop="activeUsersToday" label="今日使用" /></ElTable></section>
      <small class="toolbar__result">数据更新于 {{ securityTime(data.generatedAt) }}。仅展示已有事实记录；下载请求不等于下载或设置成功。</small>
    </template>
  </section>
</template>

<style scoped>
.stat-card small{color:var(--text-secondary);font-size:12px;line-height:1.5}
.usage-chart{display:flex;gap:8px;padding:12px 24px 24px;overflow-x:auto}
.usage-chart__day{min-width:24px;flex:1;text-align:center}
.usage-chart__bars{height:140px;display:flex;align-items:flex-end;justify-content:center;gap:3px;border-bottom:1px solid #e8e8e8}
.usage-chart__bars span{width:12px;min-height:0;border-radius:4px 4px 0 0}
.usage-chart__active{background:#db9348}.usage-chart__new{background:#b8bdc4}
.usage-chart small{display:block;margin-top:8px;font-size:10px;color:#757575}
</style>
