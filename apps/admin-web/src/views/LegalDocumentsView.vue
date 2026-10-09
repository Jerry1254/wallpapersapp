<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { computed, onMounted, ref } from 'vue';
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { readableApiError } from '@/repositories/http/apiClient';
import { legalDocumentRepository, type AdminLegalDocument } from '@/repositories/http/legalDocumentRepository';

const documents = ref<AdminLegalDocument[]>([]);
const selectedKey = ref<'privacy' | 'terms'>('privacy');
const selected = computed(() => documents.value.find(d => d.key === selectedKey.value));
const loading = ref(true), busy = ref(false), preview = ref(false), loadError = ref('');
const valid = computed(() => {
  const c = selected.value?.draft;
  return c && c.title.trim() && c.effectiveDate.trim() && c.introduction.trim()
    && c.sections.length > 0 && c.sections.every(s => s.title.trim() && s.body.trim());
});
async function load() {
  loading.value = true; loadError.value = '';
  try { documents.value = await legalDocumentRepository.list(); }
  catch (e) { loadError.value = readableApiError(e, '协议加载失败'); }
  finally { loading.value = false; }
}
function replace(document: AdminLegalDocument) {
  const index = documents.value.findIndex(d => d.key === document.key);
  documents.value[index] = document;
}
async function save(publish: boolean) {
  if (!selected.value || busy.value || !valid.value) return;
  if (publish) {
    try { await ElMessageBox.confirm('发布后倾境 App 和官网将使用此内容。若勾选重新同意，用户下次启动将再次看到同意提示。', '确认发布协议', { confirmButtonText: '发布', cancelButtonText: '取消' }); }
    catch { return; }
  }
  busy.value = true;
  try {
    const draft = await legalDocumentRepository.save(selected.value);
    replace(draft);
    if (publish) replace(await legalDocumentRepository.publish(draft));
    ElMessage.success(publish ? '已发布，App 和官网将读取最新内容' : '草稿已保存，线上内容未改变');
  } catch (e) { ElMessage.error(readableApiError(e, '协议保存或发布失败，请刷新确认结果')); }
  finally { busy.value = false; }
}
onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>协议管理</h1><p>倾境 Android、iOS、鸿蒙与官网共用已发布内容；吉意继续使用原协议。</p></div>
      <ElButton :icon="Refresh" :loading="loading" :disabled="busy" @click="load">刷新</ElButton>
    </header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />
    <article v-if="selected && !loadError" class="surface legal-editor">
      <ElRadioGroup v-model="selectedKey" :disabled="busy"><ElRadioButton value="privacy">隐私政策</ElRadioButton><ElRadioButton value="terms">用户协议</ElRadioButton></ElRadioGroup>
      <div class="legal-status">已发布版本 {{ selected.published.revision }} · {{ selected.published.publishedAt }} · <a :href="`https://biguo66.top/${selected.key}/`" target="_blank" rel="noopener">查看官网</a></div>
      <ElForm label-position="top" :disabled="busy">
        <div class="legal-meta"><ElFormItem label="标题"><ElInput v-model="selected.draft.title" maxlength="100" /></ElFormItem><ElFormItem label="生效日期"><ElInput v-model="selected.draft.effectiveDate" maxlength="40" placeholder="例如：2026年10月9日" /></ElFormItem></div>
        <ElFormItem label="开头说明"><ElInput v-model="selected.draft.introduction" type="textarea" :autosize="{ minRows: 3, maxRows: 8 }" maxlength="10000" /></ElFormItem>
        <div v-for="(section, index) in selected.draft.sections" :key="index" class="legal-section">
          <header><strong>第 {{ index + 1 }} 节</strong><div><ElButton :disabled="index === 0 || busy" @click="selected.draft.sections.splice(index - 1, 0, selected.draft.sections.splice(index, 1)[0]!)">上移</ElButton><ElButton :disabled="selected.draft.sections.length === 1 || busy" type="danger" plain @click="selected.draft.sections.splice(index, 1)">删除本节</ElButton></div></header>
          <ElFormItem label="章节标题"><ElInput v-model="section.title" maxlength="200" /></ElFormItem>
          <ElFormItem label="正文"><ElInput v-model="section.body" type="textarea" :autosize="{ minRows: 4, maxRows: 16 }" maxlength="20000" /></ElFormItem>
        </div>
        <ElButton :disabled="selected.draft.sections.length >= 60 || busy" @click="selected.draft.sections.push({ title: '', body: '' })">添加章节</ElButton>
        <ElFormItem label="发布规则" class="legal-publish-rule"><ElCheckbox v-model="selected.requiresReconsent">重大变更，要求用户重新同意</ElCheckbox><p>修正文字无需勾选；涉及信息收集、用途、共享或用户权利的重大变化时勾选。</p></ElFormItem>
      </ElForm>
      <footer><ElButton :disabled="busy || !valid" @click="preview = true">预览草稿</ElButton><ElButton :loading="busy" :disabled="!valid" @click="save(false)">保存草稿</ElButton><ElButton type="primary" :loading="busy" :disabled="!valid" @click="save(true)">发布生效</ElButton></footer>
    </article>
    <ElDialog v-model="preview" title="草稿预览" width="min(760px, 94vw)"><article v-if="selected" class="legal-preview"><h1>{{ selected.draft.title }}</h1><p>生效日期：{{ selected.draft.effectiveDate }}</p><p>{{ selected.draft.introduction }}</p><section v-for="(section, index) in selected.draft.sections" :key="index"><h2>{{ section.title }}</h2><p>{{ section.body }}</p></section></article></ElDialog>
  </section>
</template>
<style scoped>
.legal-editor { padding: 24px; }
.legal-status { margin: 18px 0; color: var(--admin-muted); font-size: 13px; }
.legal-meta { display: grid; grid-template-columns: 1fr 1fr; gap: 20px; }
.legal-section { border: 1px solid var(--el-border-color); border-radius: 10px; padding: 18px; margin: 16px 0; }
.legal-section header { display: flex; justify-content: space-between; margin-bottom: 12px; gap: 12px; }
.legal-publish-rule { margin-top: 22px; }
.legal-publish-rule p { margin: 0; color: var(--admin-muted); width: 100%; }
footer { display: flex; gap: 12px; justify-content: flex-end; flex-wrap: wrap; border-top: 1px solid var(--el-border-color); padding-top: 20px; }
.legal-preview { max-height: 65vh; overflow: auto; padding-right: 12px; }
.legal-preview p { white-space: pre-wrap; line-height: 1.8; overflow-wrap: anywhere; }
.legal-preview h2 { font-size: 18px; margin-top: 28px; }
@media(max-width: 700px) { .legal-meta { grid-template-columns: 1fr; gap: 0; } .legal-editor { padding: 16px; } }
</style>
