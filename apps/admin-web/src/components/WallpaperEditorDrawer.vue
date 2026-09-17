<script setup lang="ts">
import { Check, PictureFilled } from '@element-plus/icons-vue';
import { computed, reactive, ref, toRaw, watch } from 'vue';

import ResourceFileField from '@/components/ResourceFileField.vue';
import {
  wallpaperCapabilityLabels,
  type Category,
  type PublishStatus,
  type ResourceFile,
  type Wallpaper,
  type WallpaperCapability
} from '@/domain/admin';

const props = defineProps<{
  modelValue: boolean;
  categories: Category[];
  wallpaper?: Wallpaper;
  saving?: boolean;
}>();
const emit = defineEmits<{
  'update:modelValue': [value: boolean];
  saved: [value: Wallpaper];
}>();

const blank = (): Wallpaper => ({
  id: '', title: '', slug: '', categoryId: '', subcategoryId: '',
  accessType: 'REDEEM', capabilities: [], status: 'draft', sort: 1,
  coverUrl: '', featuredRank: null, resources: {}, copyrightNote: '', updatedAt: '', version: 0,
  variants: []
});
const form = reactive<Wallpaper>(blank());
const errors = ref<Record<string, string>>({});
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
});
const isEditing = computed(() => Boolean(form.id));
const primaryCategories = computed(() => props.categories
  .filter((category) => category.parentId === null).sort((a, b) => a.sort - b.sort));
const secondaryCategories = computed(() => props.categories
  .filter((category) => category.parentId === form.categoryId).sort((a, b) => a.sort - b.sort));

const capabilityOptions: { value: WallpaperCapability; title: string; text: string }[] = [
  { value: 'android_parallax', title: 'Android 4D', text: '分层视差资源包' },
  { value: 'android_video', title: 'Android 动态', text: 'MP4 动态壁纸' },
  { value: 'ios_live_photo', title: 'iOS 实况', text: 'MOV + JPEG 实况照片' },
  { value: 'harmony_theme', title: '鸿蒙动态', text: 'HarmonyOS 主题资源包' },
  { value: 'universal_static', title: '全平台静态', text: '高清静态原图' }
];

const pairForCapability: Record<WallpaperCapability, [string, string]> = {
  android_parallax: ['ANDROID', 'LAYER_PARALLAX'],
  android_video: ['ANDROID', 'VIDEO'],
  ios_live_photo: ['IOS', 'LIVE_PHOTO'],
  harmony_theme: ['HARMONYOS', 'THEME_PACKAGE'],
  universal_static: ['UNIVERSAL', 'STATIC_IMAGE']
};
const hasCapability = (value: WallpaperCapability) => form.capabilities.includes(value);
const hasExisting = (value: WallpaperCapability) => {
  const [platform, resourceType] = pairForCapability[value];
  return form.variants.some((variant) => variant.platform === platform && variant.resourceType === resourceType
    && variant.resourceVersions.some((version) => ['READY', 'PUBLISHED'].includes(version.status)));
};

const coverPreviewSrc = computed(() => form.resources.cover?.url
  || form.resources.staticImage?.url
  || form.resources.parallaxPackage?.coverUrl
  || form.resources.iosPhoto?.url
  || form.coverUrl);
const dynamicPreviewSrc = computed(() => form.resources.androidVideo?.url || form.resources.iosMov?.url);
const previewHasContent = computed(() => Boolean(dynamicPreviewSrc.value || coverPreviewSrc.value));

const cloneIntoForm = (value?: Wallpaper) => {
  Object.assign(form, value ? structuredClone(toRaw(value)) : blank());
  errors.value = {};
};
watch(() => props.modelValue, (open) => { if (open) cloneIntoForm(props.wallpaper); }, { immediate: true });
watch(() => props.wallpaper, (value) => { if (props.modelValue) cloneIntoForm(value); });

const setResource = (key: keyof Wallpaper['resources'], value: ResourceFile | undefined) => {
  form.resources[key] = value;
  delete errors.value[String(key)];
  if (key === 'cover' && value?.url) form.coverUrl = value.url;
};
const changePrimaryCategory = () => {
  form.subcategoryId = '';
  delete errors.value.categoryId;
  delete errors.value.subcategoryId;
};

const validate = () => {
  const next: Record<string, string> = {};
  if (!form.title.trim()) next.title = '请输入壁纸名称';
  if (!/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(form.slug)) next.slug = '请输入小写字母、数字和连字符组成的 Slug';
  if (!form.categoryId) next.categoryId = '请选择一级分类';
  if (!['REDEEM', 'FREE'].includes(form.accessType)) next.accessType = '请选择获取方式';
  if (form.capabilities.length === 0) next.capabilities = '请至少选择一种设置能力';
  if (hasCapability('android_parallax') && !form.resources.parallaxPackage && !hasExisting('android_parallax')) {
    next.parallaxPackage = '请上传 4D 固定资源包';
  }
  if (hasCapability('android_video') && !form.resources.androidVideo && !hasExisting('android_video')) {
    next.androidVideo = '请上传 Android MP4';
  }
  if (hasCapability('ios_live_photo') && !form.resources.iosMov && !hasExisting('ios_live_photo')) next.iosMov = '请上传 iOS MOV';
  if (hasCapability('ios_live_photo') && !form.resources.iosPhoto && !hasExisting('ios_live_photo')) next.iosPhoto = '请上传 iOS JPEG';
  if (hasCapability('harmony_theme') && !form.resources.harmonyPackage && !hasExisting('harmony_theme')) {
    next.harmonyPackage = '请上传 HarmonyOS 资源包';
  }
  if (hasCapability('universal_static') && !form.resources.staticImage && !hasExisting('universal_static')) {
    next.staticImage = '请上传高清静态原图';
  }
  const reusableCover = form.resources.cover || form.resources.staticImage
    || form.resources.parallaxPackage?.coverAssetId || form.resources.iosPhoto || form.coverUrl;
  if (!reusableCover) next.cover = '请上传列表封面';
  if (!form.copyrightNote.trim()) next.copyrightNote = '请输入版权说明';
  errors.value = next;
  return Object.keys(next).length === 0;
};
const save = (status: PublishStatus) => {
  if (!validate()) return;
  form.status = status;
  emit('saved', structuredClone(toRaw(form)));
};

const resourceRows = computed(() => {
  const rows: { label: string; ready: boolean }[] = [];
  if (hasCapability('android_parallax')) rows.push({ label: 'Android 4D ZIP', ready: Boolean(form.resources.parallaxPackage || hasExisting('android_parallax')) });
  if (hasCapability('android_video')) rows.push({ label: 'Android MP4', ready: Boolean(form.resources.androidVideo || hasExisting('android_video')) });
  if (hasCapability('ios_live_photo')) rows.push(
    { label: 'iOS MOV', ready: Boolean(form.resources.iosMov || hasExisting('ios_live_photo')) },
    { label: 'iOS JPEG', ready: Boolean(form.resources.iosPhoto || hasExisting('ios_live_photo')) }
  );
  if (hasCapability('harmony_theme')) rows.push({ label: '鸿蒙资源包', ready: Boolean(form.resources.harmonyPackage || hasExisting('harmony_theme')) });
  if (hasCapability('universal_static')) rows.push({ label: '静态原图', ready: Boolean(form.resources.staticImage || hasExisting('universal_static')) });
  return rows;
});
</script>

<template>
  <ElDrawer v-model="visible" class="wallpaper-drawer" size="min(980px, 96vw)" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <strong>{{ isEditing ? '编辑壁纸' : '上传新壁纸' }}</strong>
        <small>一个商品可同时发布多种设置能力</small>
      </div>
    </template>

    <div class="wallpaper-editor">
      <div class="wallpaper-editor__form">
        <section class="editor-section">
          <div class="editor-section__heading"><h3>基础信息</h3><p>用于 App 列表、分类和排序。</p></div>
          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="壁纸名称" :error="errors.title"><ElInput v-model="form.title" maxlength="40" show-word-limit @input="delete errors.title" /></ElFormItem>
              <ElFormItem label="Slug" :error="errors.slug"><ElInput v-model="form.slug" maxlength="64" placeholder="twilight-mountains" @input="delete errors.slug" /></ElFormItem>
              <ElFormItem label="一级分类" :error="errors.categoryId"><ElSelect v-model="form.categoryId" style="width:100%" @change="changePrimaryCategory"><ElOption v-for="item in primaryCategories" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></ElFormItem>
              <ElFormItem label="二级分类（可选）"><ElSelect v-model="form.subcategoryId" clearable :disabled="!form.categoryId || secondaryCategories.length === 0" style="width:100%"><ElOption v-for="item in secondaryCategories" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></ElFormItem>
              <ElFormItem label="排序值"><ElInputNumber v-model="form.sort" :min="0" :max="999999" controls-position="right" style="width:100%" /></ElFormItem>
              <ElFormItem label="精选推荐"><div class="featured-controls"><ElCheckbox :model-value="form.featuredRank !== null" @change="form.featuredRank = $event ? 1 : null">加入首页精选</ElCheckbox><ElInputNumber v-if="form.featuredRank !== null" v-model="form.featuredRank" :min="0" :max="999999" /></div></ElFormItem>
              <ElFormItem class="span-2" label="获取方式" :error="errors.accessType"><ElRadioGroup v-model="form.accessType"><ElRadio value="REDEEM">需要兑换</ElRadio><ElRadio value="FREE">免费</ElRadio></ElRadioGroup></ElFormItem>
              <ElFormItem class="span-2" label="版权说明" :error="errors.copyrightNote"><ElInput v-model="form.copyrightNote" type="textarea" :rows="2" maxlength="500" show-word-limit /></ElFormItem>
            </div>
          </ElForm>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading"><h3>设置能力</h3><p>按实际上传资源独立勾选，可任意组合；封面不会自动增加静态能力。</p></div>
          <ElCheckboxGroup v-model="form.capabilities" class="kind-picker" @change="delete errors.capabilities">
            <ElCheckboxButton v-for="item in capabilityOptions" :key="item.value" :value="item.value" class="kind-option">
              <strong>{{ item.title }}</strong><small>{{ item.text }}</small>
            </ElCheckboxButton>
          </ElCheckboxGroup>
          <p v-if="errors.capabilities" class="field-error">{{ errors.capabilities }}</p>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading"><h3>列表封面</h3><p>可单独上传；未上传时依次复用静态原图、4D 包封面或 iOS 实况照片。</p></div>
          <div class="resource-grid"><div class="resource-grid__item span-2">
            <ResourceFileField :model-value="form.resources.cover" label="独立列表封面（可选）" hint="JPG / PNG / WebP" accept="image/jpeg,image/png,image/webp" @update:model-value="setResource('cover', $event)" />
            <p v-if="errors.cover" class="field-error">{{ errors.cover }}</p>
          </div></div>
        </section>

        <section v-if="form.capabilities.length" class="editor-section">
          <div class="editor-section__heading"><h3>能力资源</h3><p>只需补齐已勾选能力对应的正式原资源。</p></div>
          <div class="resource-grid">
            <div v-if="hasCapability('android_parallax')" class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.parallaxPackage" label="Android 4D 固定资源包" hint="模拟器导出的完整 ZIP，包含 cover.jpg、config.json 和 1–12 层图" accept="application/zip,.zip" required @update:model-value="setResource('parallaxPackage', $event)" />
              <p v-if="errors.parallaxPackage" class="field-error">{{ errors.parallaxPackage }}</p>
              <small v-if="hasExisting('android_parallax')">不选择新 ZIP 时保留当前可用版本。</small>
            </div>
            <div v-if="hasCapability('android_video')" class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.androidVideo" label="Android 动态视频" hint="MP4，H.264" accept="video/mp4" required @update:model-value="setResource('androidVideo', $event)" />
              <p v-if="errors.androidVideo" class="field-error">{{ errors.androidVideo }}</p>
            </div>
            <div v-if="hasCapability('ios_live_photo')" class="resource-grid__item"><ResourceFileField :model-value="form.resources.iosMov" label="iOS 实况视频" hint="MOV" accept="video/quicktime,.mov" required @update:model-value="setResource('iosMov', $event)" /><p v-if="errors.iosMov" class="field-error">{{ errors.iosMov }}</p></div>
            <div v-if="hasCapability('ios_live_photo')" class="resource-grid__item"><ResourceFileField :model-value="form.resources.iosPhoto" label="iOS 实况照片" hint="JPEG" accept="image/jpeg" required @update:model-value="setResource('iosPhoto', $event)" /><p v-if="errors.iosPhoto" class="field-error">{{ errors.iosPhoto }}</p></div>
            <div v-if="hasCapability('harmony_theme')" class="resource-grid__item span-2"><ResourceFileField :model-value="form.resources.harmonyPackage" label="HarmonyOS 资源包" hint="ZIP" accept="application/zip,.zip" required @update:model-value="setResource('harmonyPackage', $event)" /><p v-if="errors.harmonyPackage" class="field-error">{{ errors.harmonyPackage }}</p></div>
            <div v-if="hasCapability('universal_static')" class="resource-grid__item span-2"><ResourceFileField :model-value="form.resources.staticImage" label="全平台高清静态原图" hint="JPG / PNG / WebP" accept="image/jpeg,image/png,image/webp" required @update:model-value="setResource('staticImage', $event)" /><p v-if="errors.staticImage" class="field-error">{{ errors.staticImage }}</p></div>
          </div>
        </section>
      </div>

      <aside class="wallpaper-editor__preview"><div class="preview-sticky">
        <h3>详情页效果预览</h3>
        <div class="preview-phone"><div class="preview-phone__screen">
          <video v-if="dynamicPreviewSrc" :src="dynamicPreviewSrc" autoplay loop muted playsinline></video>
          <img v-else-if="coverPreviewSrc" :src="coverPreviewSrc" alt="壁纸预览" />
          <ElIcon v-if="!previewHasContent" :size="42" style="position:absolute;inset:42% auto auto 40%;color:rgba(255,255,255,.7)"><PictureFilled /></ElIcon>
          <div class="preview-phone__meta"><strong>{{ form.title || '未命名壁纸' }}</strong><small>{{ form.capabilities.map((item) => wallpaperCapabilityLabels[item]).join(' / ') || '未选设置能力' }}</small></div>
        </div></div>
        <div class="preview-checklist"><div v-for="item in resourceRows" :key="item.label"><span>{{ item.label }}</span><span :class="item.ready ? 'success-text' : 'warning-text'"><ElIcon v-if="item.ready"><Check /></ElIcon>{{ item.ready ? '已选择' : '待上传' }}</span></div></div>
      </div></aside>
    </div>

    <template #footer><div class="dialog-actions"><ElButton :disabled="saving" @click="visible = false">取消</ElButton><ElButton :loading="saving" @click="save('draft')">{{ isEditing ? '保存修改' : '保存草稿' }}</ElButton><ElButton type="primary" :loading="saving" @click="save('published')">保存并发布</ElButton></div></template>
  </ElDrawer>
</template>
