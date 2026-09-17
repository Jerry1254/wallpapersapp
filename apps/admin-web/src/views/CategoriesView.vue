<script setup lang="ts">
import AdminLoadNotice from '@/components/AdminLoadNotice.vue';
import { Edit, Plus, Search, UploadFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, onMounted, reactive, ref, toRaw } from 'vue';

import type { Category } from '@/domain/admin';
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';

interface CategoryNode extends Category { children?: CategoryNode[] }

const categories = ref<Category[]>([]);
const loading = ref(true);
const loadError = ref('');
const keyword = ref('');
const dialogOpen = ref(false);
const editing = ref(false);
const saving = ref(false);
const iconInput = ref<HTMLInputElement>();
const form = reactive<Category>({ id: '', name: '', slug: '', parentId: null, iconUrl: '', sort: 100, wallpaperCount: 0, version: 0 });
const selectedNode = computed({
  get: () => form.parentId ?? '__root__',
  set: (value: string) => { form.parentId = value === '__root__' ? null : value; }
});

const rootCategories = computed(() => categories.value.filter((item) => item.parentId === null).sort((a, b) => a.sort - b.sort));
const categoryTree = computed<CategoryNode[]>(() => {
  const term = keyword.value.trim();
  return rootCategories.value
    .map((parent) => ({
      ...parent,
      children: categories.value
        .filter((item) => item.parentId === parent.id)
        .filter((item) => !term || item.name.includes(term))
        .sort((a, b) => a.sort - b.sort)
    }))
    .filter((item) => !term || item.name.includes(term) || Boolean(item.children?.length));
});

const load = async () => {
  loading.value = true;
  loadError.value = '';
  try {
    categories.value = await adminRepository.categories();
  } catch (cause) {
    loadError.value = readableApiError(cause, '分类加载失败');
  } finally {
    loading.value = false;
  }
};
const openCreate = () => {
  Object.assign(form, { id: '', name: '', slug: '', parentId: null, iconUrl: '', icon: undefined, sort: 100, wallpaperCount: 0, version: 0 });
  if (iconInput.value) iconInput.value.value = '';
  editing.value = false;
  dialogOpen.value = true;
};
const openEdit = (row: Category) => {
  const value = toRaw(row);
  Object.assign(form, {
    id: value.id,
    name: value.name,
    slug: value.slug,
    parentId: value.parentId,
    iconUrl: value.iconUrl,
    icon: value.icon,
    sort: value.sort,
    wallpaperCount: value.wallpaperCount,
    version: value.version
  });
  if (iconInput.value) iconInput.value.value = '';
  editing.value = true;
  dialogOpen.value = true;
};
const selectIcon = (event: Event) => {
  const selected = (event.target as HTMLInputElement).files?.[0];
  if (!selected) return;
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(selected.type)) { ElMessage.warning('请选择 PNG、JPG 或 WebP 图片'); return; }
  if (selected.size > 1024 * 1024) { ElMessage.warning('图标文件请控制在 1 MB 以内'); return; }
  form.iconUrl = URL.createObjectURL(selected);
  form.icon = { name: selected.name, size: selected.size, mime: selected.type, url: form.iconUrl, nativeFile: selected };
};
const save = async () => {
  if (!form.name.trim()) { ElMessage.warning('请输入分类名称'); return; }
  if (form.parentId === null && !form.icon) { ElMessage.warning('一级分类需要上传图标'); return; }
  if (form.parentId !== null) { form.iconUrl = ''; form.icon = undefined; }
  saving.value = true;
  try {
    await adminRepository.saveCategory(toRaw(form));
    dialogOpen.value = false;
    ElMessage.success(editing.value ? '分类已更新' : '分类已创建');
    await load();
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '分类保存失败'));
  } finally {
    saving.value = false;
  }
};

onMounted(load);
</script>

<template>
  <section class="page-shell">
    <header class="page-heading">
      <div><h1>分类管理</h1><p>维护首页一级分类和其下二级分类；只有一级分类需要上传图标。</p></div>
      <div class="page-actions"><ElButton type="primary" :icon="Plus" @click="openCreate">新增分类</ElButton></div>
    </header>
    <AdminLoadNotice :error="loadError" :loading="loading" @retry="load" />
    <section class="surface toolbar">
      <div class="toolbar__filters"><ElInput v-model="keyword" :prefix-icon="Search" clearable placeholder="搜索一级或二级分类" style="width:250px" /></div>
      <span v-if="!loadError" class="toolbar__result">共 {{ categories.length }} 个节点</span>
    </section>
    <section v-if="!loadError" v-loading="loading" class="surface content-table">
      <ElTable :data="categoryTree" empty-text="暂无分类" row-key="id" default-expand-all :tree-props="{ children: 'children' }">
        <ElTableColumn label="图标" width="100">
          <template #default="{ row }"><img v-if="row.parentId === null && row.iconUrl" class="category-image" :src="row.iconUrl" :alt="`${row.name}图标`" /><span v-else class="category-no-icon">—</span></template>
        </ElTableColumn>
        <ElTableColumn prop="name" label="分类名称" min-width="220" />
        <ElTableColumn label="节点类型" width="120"><template #default="{ row }"><ElTag :type="row.parentId === null ? 'warning' : 'info'" effect="plain">{{ row.parentId === null ? '一级分类' : '二级分类' }}</ElTag></template></ElTableColumn>
        <ElTableColumn prop="wallpaperCount" label="壁纸数量" width="120" />
        <ElTableColumn prop="sort" label="同级排序" width="110" />
        <ElTableColumn label="操作" width="120"><template #default="{ row }"><ElButton link type="primary" :icon="Edit" @click="openEdit(row)">编辑</ElButton></template></ElTableColumn>
      </ElTable>
    </section>

    <ElDialog v-model="dialogOpen" :title="editing ? '编辑分类' : '新增分类'" width="min(500px, 92vw)">
      <ElForm label-position="top">
        <ElFormItem label="所属节点">
          <ElSelect v-model="selectedNode" :disabled="editing" style="width:100%" placeholder="请选择节点">
            <ElOption label="一级分类（根节点）" value="__root__" />
            <ElOption v-for="item in rootCategories.filter((category) => category.id !== form.id)" :key="item.id" :label="`${item.name} / 二级分类`" :value="item.id" />
          </ElSelect>
          <div class="category-node-hint">{{ form.parentId === null ? '将显示在 App 首页金刚区' : '将显示在所选一级分类的横向筛选栏' }}</div>
        </ElFormItem>
        <ElFormItem label="分类名称"><ElInput v-model="form.name" maxlength="20" show-word-limit placeholder="例如：萌宠" /></ElFormItem>
        <ElFormItem v-if="form.parentId === null" label="分类图标">
          <input ref="iconInput" hidden type="file" accept="image/png,image/jpeg,image/webp" @change="selectIcon" />
          <button class="category-icon-upload" type="button" @click="iconInput?.click()">
            <img v-if="form.iconUrl" :src="form.iconUrl" alt="分类图标预览" />
            <span v-else><ElIcon :size="24"><UploadFilled /></ElIcon></span>
            <div><strong>{{ form.iconUrl ? '更换图标' : '上传图标' }}</strong><small>PNG / JPG / WebP，不超过 1 MB</small></div>
          </button>
        </ElFormItem>
        <ElFormItem label="同级排序"><ElInputNumber v-model="form.sort" :min="0" :max="9999" controls-position="right" style="width:100%" /></ElFormItem>
      </ElForm>
      <template #footer><div class="dialog-actions"><ElButton @click="dialogOpen=false">取消</ElButton><ElButton type="primary" :loading="saving" @click="save">保存</ElButton></div></template>
    </ElDialog>
  </section>
</template>
