<script setup lang="ts">
import { onBeforeUnmount, ref, watch } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import SupportMedia from './SupportMedia.vue';
import { supportLibraryRepository as repository } from '@/repositories/http/supportRepository';
import { readableApiError } from '@/repositories/http/apiClient';
import type { LibraryKind, SupportAttachment, SupportLibraryItem } from '@/domain/support';
const props = defineProps<{ modelValue: boolean; manage?: boolean; initialKind?: LibraryKind }>();
const emit = defineEmits<{ 'update:modelValue': [value: boolean]; choose: [value: SupportLibraryItem] }>();
const kind = ref<LibraryKind>('IMAGE'); const search = ref(''); const page = ref(1); const total = ref(0);
const items = ref<SupportLibraryItem[]>([]); const loading = ref(false); const error = ref(''); let listToken = 0;
const editing = ref(false); const original = ref<SupportLibraryItem>(); const title = ref(''); const note = ref(''); const text = ref('');
const attachment = ref<SupportAttachment | null>(null); const saving = ref(false); const uploading = ref(false); let editorToken = 0;
async function load() {
  const token = ++listToken; loading.value = true; error.value = '';
  try { const result = await repository.list(kind.value, search.value, page.value); if (token === listToken) { items.value = result.items; total.value = result.total; } }
  catch (cause) { if (token === listToken) error.value = readableApiError(cause, '素材列表加载失败'); }
  finally { if (token === listToken) loading.value = false; }
}
function edit(item?: SupportLibraryItem) {
  editorToken++; original.value = item; title.value = item?.title || ''; note.value = item?.note || ''; text.value = item?.text || '';
  attachment.value = item?.attachment || null; editing.value = true; uploading.value = false; saving.value = false;
}
async function upload(event: Event) {
  const input = event.target as HTMLInputElement; const file = input.files?.[0]; input.value = ''; if (!file) return;
  const token = editorToken; const category = kind.value; if (category === 'PHRASE') return; uploading.value = true;
  try { const result = await repository.upload(file, category); if (token === editorToken && editing.value) attachment.value = result; }
  catch (cause) { if (token === editorToken && editing.value) ElMessage.error(readableApiError(cause, '上传失败，请重试')); }
  finally { if (token === editorToken) uploading.value = false; }
}
async function save() {
  if (!title.value.trim() || (kind.value === 'PHRASE' ? !text.value.trim() : !attachment.value)) { ElMessage.warning('请填写名称和内容'); return; }
  const token = editorToken; const body = { kind: kind.value, title: title.value.trim(), note: note.value.trim(), text: kind.value === 'PHRASE' ? text.value.trim() : null,
    attachmentId: attachment.value?.id || null, version: original.value?.version }; saving.value = true;
  try {
    if (original.value) await repository.update(original.value.id, body); else await repository.create(body);
    if (token === editorToken) { editing.value = false; ElMessage.success('已保存，手机客服端将同步更新'); await load(); }
  } catch (cause) { if (token === editorToken) ElMessage.error(readableApiError(cause, '保存失败，请刷新核对后重试')); }
  finally { if (token === editorToken) saving.value = false; }
}
async function remove(item: SupportLibraryItem) {
  try { await ElMessageBox.confirm(`删除“${item.title}”？已发送的聊天记录会保留。`, '删除素材', { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }); }
  catch { return; }
  try { await repository.delete(item); await load(); ElMessage.success('已删除'); }
  catch (cause) { ElMessage.error(readableApiError(cause, '删除失败，请刷新后重试')); }
}
watch(() => props.modelValue, open => { if (open) { kind.value = props.initialKind || 'IMAGE'; search.value = ''; page.value = 1; void load(); } else { listToken++; editing.value = false; editorToken++; } });
watch(kind, () => { page.value = 1; search.value = ''; if (props.modelValue) void load(); });
onBeforeUnmount(() => { listToken++; editorToken++; });
</script>
<template>
  <ElDialog :model-value="modelValue" :title="manage ? '客服素材与快捷短语' : '选择素材 / 快捷短语'" width="860px" class="support-library-dialog" @update:model-value="emit('update:modelValue', $event)">
    <div class="support-library-toolbar">
      <ElRadioGroup v-model="kind"><ElRadioButton value="IMAGE">图片</ElRadioButton><ElRadioButton value="VIDEO">视频</ElRadioButton><ElRadioButton value="PHRASE">快捷短语</ElRadioButton></ElRadioGroup>
      <ElInput v-model="search" placeholder="搜索名称或备注" clearable @keyup.enter="page = 1; load()" @clear="page = 1; load()" />
      <ElButton @click="page = 1; load()">搜索</ElButton><ElButton v-if="manage" type="primary" @click="edit()">新增</ElButton>
    </div>
    <p class="support-muted">后台统一编辑，手机客服端同步使用。{{ kind === 'IMAGE' ? '支持 JPG、PNG、WebP，最大 10 MB。' : kind === 'VIDEO' ? '支持 MP4，最大 100 MB、15 分钟。' : '点击短语后可编辑，再发送。' }}</p>
    <ElAlert v-if="error" :title="error" type="error" :closable="false"><ElButton text @click="load()">重试</ElButton></ElAlert>
    <div v-loading="loading" class="support-library-grid" :class="{ phrases: kind === 'PHRASE' }">
      <article v-for="item in items" :key="item.id" class="support-library-card">
        <SupportMedia v-if="item.attachment" :attachment="item.attachment" /><p v-else class="support-phrase-text">{{ item.text }}</p>
        <strong>{{ item.title }}</strong><small>{{ item.note || '无备注' }}</small>
        <div><template v-if="manage"><ElButton size="small" @click="edit(item)">编辑</ElButton><ElButton size="small" type="danger" plain @click="remove(item)">删除</ElButton></template>
          <ElButton v-else size="small" type="primary" @click="emit('choose', item); emit('update:modelValue', false)">{{ kind === 'PHRASE' ? '插入短语' : '选择' }}</ElButton></div>
      </article>
    </div>
    <ElEmpty v-if="!loading && !error && !items.length" description="暂无内容" />
    <ElPagination v-if="total > 50" v-model:current-page="page" :page-size="50" :total="total" layout="prev, pager, next" @current-change="load()" />
  </ElDialog>
  <ElDialog v-model="editing" :title="`${original ? '编辑' : '新增'}${kind === 'PHRASE' ? '快捷短语' : '素材'}`" width="560px" :close-on-click-modal="false" @closed="editorToken++">
    <ElForm label-position="top">
      <ElFormItem label="名称"><ElInput v-model="title" maxlength="60" show-word-limit /></ElFormItem>
      <ElFormItem label="备注"><ElInput v-model="note" maxlength="200" /></ElFormItem>
      <ElFormItem v-if="kind === 'PHRASE'" label="短语内容"><ElInput v-model="text" type="textarea" :rows="5" maxlength="2000" show-word-limit /></ElFormItem>
      <ElFormItem v-else label="文件"><label class="support-upload-button">{{ uploading ? '上传中…' : '选择 / 替换文件' }}<input type="file" :accept="kind === 'IMAGE' ? 'image/jpeg,image/png,image/webp' : 'video/mp4'" :disabled="uploading || saving" @change="upload" /></label></ElFormItem>
      <SupportMedia v-if="attachment" :attachment="attachment" />
    </ElForm>
    <template #footer><ElButton :disabled="saving" @click="editing = false">取消</ElButton><ElButton type="primary" :loading="saving" :disabled="uploading" @click="save">保存</ElButton></template>
  </ElDialog>
</template>
