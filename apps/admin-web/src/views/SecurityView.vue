<script setup lang="ts">
import { Refresh, Plus } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, reactive, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { readableApiError } from '@/repositories/http/apiClient';
import { securityRepository as api, securityTime, securityEventText, type SecurityPolicy, type SecurityRule, type SecurityBan, type SecurityWhitelist, type SecurityEvent } from '@/repositories/http/securityRepository';

const route = useRoute();
const tab = ref(route.query.deviceId ? 'bans' : 'rules'), policy = ref<SecurityPolicy>(), bans = ref<SecurityBan[]>([]), whitelist = ref<SecurityWhitelist[]>([]), events = ref<SecurityEvent[]>([]);
const loading = ref(false), busy = ref(false), error = ref(''), search = ref(String(route.query.deviceId || '')), status = ref('ACTIVE'), page = ref(1), total = ref(0);
const banDialog = ref(false), whiteDialog = ref(false), releaseDialog = ref(false), selected = ref<SecurityBan>();
const banForm = reactive({ device: '', ip: '', reason: '' });
const whiteForm = reactive({ type: 'DEVICE', value: '', note: '' });
const releaseForm = reactive({ reason: '', related: true });
const canBan = computed(() => (banForm.device.trim() || banForm.ip.trim()) && banForm.reason.trim());
const eventText = (event: SecurityEvent) => (policy.value?.rules || []).reduce((text, rule) => text.replace(rule.key, rule.title), securityEventText(event));
const platformNames: Record<string, string> = { ANDROID: '安卓', IOS: 'iOS', HARMONYOS: '鸿蒙' };
const platformName = (platform?: string) => platformNames[platform || ''] || '—';
const ruleName = (key: string) => policy.value?.rules.find(r => r.key === key)?.title || (key === 'MANUAL' ? '后台手动封禁' : key);
async function load() {
  if (busy.value || loading.value) return;
  loading.value = true; error.value = '';
  try {
    if (tab.value === 'rules') policy.value = await api.policy();
    else if (tab.value === 'bans') { const result = await api.bans(search.value.trim(), status.value, page.value); bans.value = result.items; total.value = result.total; }
    else if (tab.value === 'whitelist') whitelist.value = await api.whitelist();
    else events.value = await api.events();
  } catch (e) { error.value = readableApiError(e, '安全风控加载失败'); }
  finally { loading.value = false; }
}
async function mutate(action: () => Promise<void>, message: string) {
  if (busy.value) return;
  busy.value = true;
  try { await action(); ElMessage.success(message); }
  catch (e) { ElMessage.error(readableApiError(e, '操作失败，请刷新确认结果后重试')); }
  finally { busy.value = false; }
}
async function toggleMaster(newValue: string | number | boolean) {
  const value = Boolean(newValue);
  if (!policy.value) return;
  if (!value) {
    try { await ElMessageBox.confirm('关闭后停止新增自动封禁，已有永久黑名单仍然有效。', '关闭自动风控', { confirmButtonText: '关闭', cancelButtonText: '取消' }); }
    catch { return; }
  }
  await mutate(async () => { policy.value = await api.savePolicy(policy.value!, value); }, '自动风控设置已保存');
}
async function saveRule(rule: SecurityRule) {
  await mutate(async () => { let saved: SecurityRule;
    try { saved = await api.saveRule(rule); }
    catch (e) { try { policy.value = await api.policy(); } catch {} throw e; }
    const index = policy.value!.rules.findIndex(r => r.key === rule.key); policy.value!.rules[index] = saved; }, '规则已保存，历史封禁不变');
}
async function addBan() {
  if (!canBan.value) return;
  await mutate(async () => { await api.ban(banForm.device, banForm.ip, banForm.reason); banDialog.value = false; }, '已加入永久黑名单');
  await load();
}
function openRelease(ban: SecurityBan) { selected.value = ban; releaseForm.reason = ''; releaseForm.related = true; releaseDialog.value = true; }
async function release() {
  if (!selected.value || !releaseForm.reason.trim()) return;
  await mutate(async () => { await api.release(selected.value!, releaseForm.reason, releaseForm.related); releaseDialog.value = false; }, '已解除所选封禁');
  await load();
}
async function addWhite() {
  await mutate(async () => { await api.addWhitelist(whiteForm.type, whiteForm.value, whiteForm.note); whiteDialog.value = false; }, '白名单已添加');
  await load();
}
async function removeWhite(id: string) {
  try { await ElMessageBox.confirm('移除后恢复自动检测，已有封禁状态不变。', '移除白名单', { confirmButtonText: '移除', cancelButtonText: '取消' }); }
  catch { return; }
  await mutate(() => api.removeWhitelist(id), '白名单已移除'); await load();
}
function query() { page.value = 1; load(); }
watch(() => route.query.deviceId, (id) => { if (id) { tab.value = 'bans'; search.value = String(id); query(); } });
onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading"><div><h1>安全风控</h1><p>异常命中后永久封禁设备及整个 IP，只能由后台解除。</p></div><ElButton :icon="Refresh" :loading="loading" :disabled="busy" @click="load">刷新</ElButton></header>
    <article class="surface risk-panel">
      <ElTabs v-model="tab" @tab-change="load">
        <ElTabPane :disabled="loading || busy" label="防护规则" name="rules" /><ElTabPane :disabled="loading || busy" label="黑名单" name="bans" /><ElTabPane :disabled="loading || busy" label="测试白名单" name="whitelist" /><ElTabPane :disabled="loading || busy" label="操作记录" name="events" />
      </ElTabs>
      <AdminLoadNotice :error="error" :loading="loading" @retry="load" />
      <template v-if="!error">
        <div v-if="tab === 'rules' && policy" class="risk-rules">
          <div class="risk-master"><div><strong>自动风控总开关</strong><p>控制新增自动检测与封禁。关闭总开关或单项规则，都不会解除历史黑名单。</p></div><ElSwitch :model-value="policy.enabled" :disabled="busy || loading" @change="toggleMaster" /></div>
          <div class="risk-basics"><strong>基础素材保护持续启用</strong><span>设备身份与素材权限校验 · 短期下载票据 · 请求防重放 · 原图和预览分离 · 旧票据实时检查封禁</span></div>
          <p class="risk-muted">单项规则修改后，点击“保存规则”生效。</p>
          <div class="risk-rule-grid">
            <article v-for="rule in policy.rules" :key="rule.key" class="risk-rule">
              <header><strong>{{ rule.title }}</strong><ElTag v-if="!rule.available" type="info">暂不支持</ElTag><ElSwitch v-else v-model="rule.enabled" :disabled="busy || loading || !policy.enabled" /></header>
              <p>{{ rule.description }}</p>
              <small>适用：{{ rule.platforms.includes('ALL') ? '四个用户版本' : rule.platforms.map(p => p === 'ANDROID' ? '线上／线下安卓' : p === 'IOS' ? 'iOS' : '鸿蒙').join('、') || '待验证适配' }}</small>
              <div v-if="rule.platforms.includes('ALL')" class="risk-threshold"><label>触发阈值<ElInputNumber v-model="rule.threshold" :min="1" :max="100000" :disabled="busy || !policy.enabled" controls-position="right" /></label><label>窗口（秒）<ElInputNumber v-model="rule.windowSeconds" :min="10" :max="86400" :disabled="busy || !policy.enabled" controls-position="right" /></label></div>
              <footer v-if="rule.available"><span>命中后永久封禁设备＋IP</span><ElButton :disabled="busy || loading || !policy.enabled" @click="saveRule(rule)">保存规则</ElButton></footer>
            </article>
          </div>
        </div>
        <template v-if="tab === 'bans'">
          <div class="risk-toolbar"><ElInput v-model="search" :disabled="loading || busy" clearable placeholder="设备 ID 或完整 IP" @keyup.enter="query" /><ElSelect v-model="status" :disabled="loading || busy" @change="query"><ElOption label="封禁中" value="ACTIVE" /><ElOption label="已解除" value="RELEASED" /><ElOption label="全部记录" value="ALL" /></ElSelect><ElButton :disabled="busy || loading" @click="query">查询</ElButton><ElButton type="primary" :icon="Plus" :disabled="busy" @click="Object.assign(banForm, { device: '', ip: '', reason: '' }); banDialog = true">手动拉黑</ElButton></div>
          <ElTable :data="bans" v-loading="loading" empty-text="暂无封禁记录">
            <ElTableColumn label="封禁对象" min-width="180"><template #default="{ row }"><b>{{ row.subjectType === 'DEVICE' ? '用户／设备' : '整个 IP' }}</b><div>{{ row.subjectValue }}</div></template></ElTableColumn>
            <ElTableColumn label="触发来源" min-width="160"><template #default="{ row }"><div>设备 {{ row.deviceId || '—' }}</div><small>{{ row.ip || '—' }} · {{ platformName(row.platform) }}</small></template></ElTableColumn>
            <ElTableColumn label="原因" min-width="200"><template #default="{ row }"><strong>{{ ruleName(row.ruleKey) }}</strong><div>{{ row.reason }}</div></template></ElTableColumn>
            <ElTableColumn label="拉黑时间（北京时间）" min-width="190"><template #default="{ row }">{{ securityTime(row.bannedAt) }}<div class="risk-muted">{{ row.bannedBy || '自动风控' }}</div></template></ElTableColumn>
            <ElTableColumn label="状态／解除记录" min-width="220"><template #default="{ row }"><ElTag :type="row.releasedAt ? 'success' : 'danger'">{{ row.releasedAt ? '已解除' : '永久封禁' }}</ElTag><template v-if="row.releasedAt"><div>{{ securityTime(row.releasedAt) }} · {{ row.releasedBy }}</div><small>{{ row.releaseReason }}</small></template></template></ElTableColumn>
            <ElTableColumn label="操作" fixed="right" width="110"><template #default="{ row }"><ElButton v-if="!row.releasedAt" link type="primary" :disabled="busy" @click="openRelease(row)">解除封禁</ElButton><span v-else>—</span></template></ElTableColumn>
          </ElTable>
          <ElPagination v-model:current-page="page" :total="total" :page-size="50" layout="total, prev, pager, next" :disabled="loading || busy" @current-change="load" />
        </template>
        <template v-if="tab === 'whitelist'">
          <div class="risk-toolbar"><p>白名单只跳过新增自动封禁，已有黑名单仍需手动解除。</p><ElButton type="primary" :icon="Plus" :disabled="busy" @click="Object.assign(whiteForm, { type: 'DEVICE', value: '', note: '' }); whiteDialog = true">添加白名单</ElButton></div>
          <ElTable :data="whitelist" v-loading="loading" empty-text="暂无测试白名单"><ElTableColumn label="类型" width="110"><template #default="{ row }">{{ row.subjectType === 'DEVICE' ? '设备' : 'IP' }}</template></ElTableColumn><ElTableColumn prop="subjectValue" label="设备 ID／IP" min-width="200" /><ElTableColumn prop="note" label="备注" min-width="180" /><ElTableColumn label="添加时间（北京时间）" min-width="190"><template #default="{ row }">{{ securityTime(row.createdAt) }}</template></ElTableColumn><ElTableColumn label="操作" width="100"><template #default="{ row }"><ElButton link type="danger" :disabled="busy" @click="removeWhite(row.id)">移除</ElButton></template></ElTableColumn></ElTable>
        </template>
        <template v-if="tab === 'events'"><p class="risk-muted">最近 100 条后台操作；完整封禁与解除历史在黑名单中查询。</p><ElTable :data="events" v-loading="loading" empty-text="暂无后台操作记录"><ElTableColumn label="时间（北京时间）" width="210"><template #default="{ row }">{{ securityTime(row.createdAt) }}</template></ElTableColumn><ElTableColumn prop="actor" label="操作人" width="130" /><ElTableColumn label="操作内容" min-width="300"><template #default="{ row }">{{ eventText(row) }}</template></ElTableColumn><ElTableColumn label="结果" width="100"><template #default="{ row }"><ElTag :type="row.result === 'SUCCEEDED' ? 'success' : 'danger'">{{ row.result === 'SUCCEEDED' ? '成功' : '失败' }}</ElTag></template></ElTableColumn></ElTable></template>
      </template>
    </article>
    <ElDialog v-model="banDialog" title="手动永久拉黑" width="min(520px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy"><ElForm label-position="top"><ElFormItem label="设备 ID"><ElInput v-model="banForm.device" :disabled="busy" placeholder="与设备权益页面一致" /></ElFormItem><ElFormItem label="整个 IP"><ElInput v-model="banForm.ip" :disabled="busy" placeholder="填写完整 IPv4 或 IPv6 地址" /></ElFormItem><ElFormItem label="拉黑原因"><ElInput v-model="banForm.reason" :disabled="busy" type="textarea" maxlength="300" show-word-limit /></ElFormItem><p class="risk-muted">至少填写设备或 IP。填写两项时同时封禁，永久有效，只能后台解除。</p></ElForm><template #footer><ElButton :disabled="busy" @click="banDialog = false">取消</ElButton><ElButton type="danger" :loading="busy" :disabled="!canBan" @click="addBan">永久拉黑</ElButton></template></ElDialog>
    <ElDialog v-model="releaseDialog" title="解除封禁" width="min(520px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy"><template v-if="selected"><p>{{ selected.subjectType === 'DEVICE' ? '用户／设备' : 'IP' }}：{{ selected.subjectValue }}</p><p>拉黑时间：{{ securityTime(selected.bannedAt) }}</p><ElCheckbox v-model="releaseForm.related" :disabled="busy">同时解除本次关联的设备与 IP 封禁</ElCheckbox><p class="risk-muted">其他封禁记录仍然有效。任一设备或 IP 仍被封禁，用户就无法恢复访问。</p><ElInput v-model="releaseForm.reason" type="textarea" maxlength="300" show-word-limit placeholder="填写解除原因" :disabled="busy" /></template><template #footer><ElButton :disabled="busy" @click="releaseDialog = false">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!releaseForm.reason.trim()" @click="release">确认解除</ElButton></template></ElDialog>
    <ElDialog v-model="whiteDialog" title="添加测试白名单" width="min(520px, 94vw)" :close-on-click-modal="!busy" :show-close="!busy"><ElForm label-position="top"><ElFormItem label="类型"><ElRadioGroup v-model="whiteForm.type" :disabled="busy"><ElRadioButton value="DEVICE">设备</ElRadioButton><ElRadioButton value="IP">IP</ElRadioButton></ElRadioGroup></ElFormItem><ElFormItem :label="whiteForm.type === 'DEVICE' ? '设备 ID' : '整个 IP'"><ElInput v-model="whiteForm.value" :disabled="busy" /></ElFormItem><ElFormItem label="备注"><ElInput v-model="whiteForm.note" :disabled="busy" maxlength="300" /></ElFormItem></ElForm><template #footer><ElButton :disabled="busy" @click="whiteDialog = false">取消</ElButton><ElButton type="primary" :loading="busy" :disabled="!whiteForm.value.trim() || !whiteForm.note.trim()" @click="addWhite">添加</ElButton></template></ElDialog>
  </section>
</template>

<style scoped>
.page-shell { min-width: 0; grid-template-columns: minmax(0,1fr); }.risk-panel { min-width: 0; padding: 20px 24px; }.risk-master,.risk-rule header,.risk-rule footer,.risk-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; }.risk-master p,.risk-muted,.risk-rule p,.risk-rule small { color: var(--admin-muted); }.risk-master { padding: 12px 0 20px; }.risk-master p { margin-bottom: 0; }.risk-basics { background: #fff8dc; border-radius: 10px; padding: 16px; display: grid; gap: 8px; margin-bottom: 20px; }.risk-basics span { font-size: 13px; line-height: 1.8; }.risk-rule-grid { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 16px; }.risk-rule { border: 1px solid var(--el-border-color); border-radius: 12px; padding: 18px; }.risk-rule p { font-size: 13px; line-height: 1.7; min-height: 44px; }.risk-rule footer { padding-top: 16px; font-size: 12px; }.risk-threshold { display: flex; gap: 16px; margin-top: 16px; flex-wrap: wrap; }.risk-threshold label { display: grid; gap: 8px; font-size: 12px; }.risk-toolbar { margin: 16px 0; flex-wrap: wrap; justify-content: flex-start; }.risk-toolbar .el-input { width: 270px; }.risk-toolbar .el-select { width: 140px; }.risk-toolbar p { flex: 1; }.el-pagination { margin-top: 20px; justify-content: flex-end; }.risk-panel :deep(.cell) { overflow-wrap: anywhere; }.risk-panel :deep(.cell div) { margin-top: 5px; }
@media(max-width: 900px) { .risk-rule-grid { grid-template-columns: 1fr; }.risk-panel { padding: 16px; } }
</style>
