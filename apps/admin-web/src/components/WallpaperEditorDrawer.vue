<script setup lang="ts">
import { Check, PictureFilled } from '@element-plus/icons-vue';
import { computed, reactive, ref, toRaw, watch } from 'vue';

import ResourceFileField from '@/components/ResourceFileField.vue';
import {
  platformLabels,
  wallpaperKindLabels,
  type Category,
  type Platform,
  type PublishStatus,
  type ResourceFile,
  type Wallpaper,
  type WallpaperKind
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
  id: '',
  title: '',
  slug: '',
  categoryId: '',
  subcategoryId: '',
  kind: 'four_d',
  platforms: ['android'],
  status: 'draft',
  sort: 100,
  coverUrl: '',
  featuredRank: null,
  resources: {},
  copyrightNote: '倾境壁纸已获得该资源的发布和交付授权',
  updatedAt: '',
  version: 0,
  variants: []
});

const form = reactive<Wallpaper>(blank());
const errors = ref<Record<string, string>>({});
const visible = computed({
  get: () => props.modelValue,
  set: (value) => emit('update:modelValue', value)
});
const isEditing = computed(() => Boolean(props.wallpaper?.id));
const resourceShapeLocked = computed(() => Boolean(props.wallpaper?.variants.some((item) => item.resourceVersions.length)));
const hasExistingParallax = computed(() => form.variants.some((variant) => variant.resourceType === 'LAYER_PARALLAX'
  && variant.resourceVersions.some((version) => ['READY', 'PUBLISHED'].includes(version.status))));
const coverPreviewSrc = computed(() => form.resources.parallaxPackage?.coverUrl || form.resources.cover?.url || form.coverUrl);
const dynamicPreviewSrc = computed(() => form.resources.androidVideo?.url || form.resources.iosMov?.url);
const staticPreviewSrc = computed(() => form.resources.staticImage?.url || coverPreviewSrc.value);
const previewHasContent = computed(() => {
  if (form.kind === 'four_d') return Boolean(coverPreviewSrc.value);
  if (form.kind === 'dynamic') return Boolean(dynamicPreviewSrc.value || coverPreviewSrc.value);
  return Boolean(staticPreviewSrc.value);
});
const primaryCategories = computed(() => props.categories.filter((category) => category.parentId === null).sort((a, b) => a.sort - b.sort));
const secondaryCategories = computed(() => props.categories.filter((category) => category.parentId === form.categoryId).sort((a, b) => a.sort - b.sort));

const kindOptions: { value: WallpaperKind; title: string; text: string }[] = [
  { value: 'four_d', title: '4D 分层壁纸', text: 'Android 前景、背景随姿态产生视差' },
  { value: 'dynamic', title: '动态壁纸', text: '按平台上传视频或实况照片资源' },
  { value: 'static', title: '静态壁纸', text: '一张高分辨率原图覆盖所有平台' }
];

const cloneIntoForm = (value?: Wallpaper) => {
  Object.assign(form, value ? structuredClone(toRaw(value)) : blank());
  errors.value = {};
};

watch(() => props.modelValue, (open) => {
  if (open) cloneIntoForm(props.wallpaper);
}, { immediate: true });
watch(() => props.wallpaper, (value) => {
  if (props.modelValue) cloneIntoForm(value);
});
watch(() => form.kind, (kind) => {
  if (kind === 'four_d') form.platforms = ['android'];
  if (kind === 'static') form.platforms = ['android', 'ios', 'harmony'];
});

const setResource = (key: keyof Wallpaper['resources'], value: ResourceFile | undefined) => {
  form.resources[key] = value;
  delete errors.value[String(key)];
  if (key === 'cover' && value?.url) form.coverUrl = value.url;
};

const hasPlatform = (platform: Platform) => form.platforms.includes(platform);
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
  if (form.kind !== 'four_d' && !form.resources.cover && !form.coverUrl) next.cover = '请上传列表封面';
  if (form.platforms.length === 0) next.platforms = '请至少选择一个平台';

  if (form.kind === 'four_d') {
    if (!form.resources.parallaxPackage && !hasExistingParallax.value) next.parallaxPackage = '请上传固定格式的 4D ZIP 资源包';
  }
  if (form.kind === 'dynamic') {
    if (hasPlatform('android') && !form.resources.androidVideo) next.androidVideo = 'Android 需要 MP4 视频';
    if (hasPlatform('ios') && !form.resources.iosMov) next.iosMov = 'iOS 需要 MOV 视频';
    if (hasPlatform('ios') && !form.resources.iosPhoto) next.iosPhoto = 'iOS 需要配套 JPEG 照片';
    if (hasPlatform('harmony') && !form.resources.harmonyPackage) next.harmonyPackage = 'HarmonyOS 需要平台资源包';
  }
  if (form.kind === 'static' && !form.resources.staticImage) next.staticImage = '请上传高清原图';
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
  if (form.kind === 'four_d') {
    rows.push({ label: '4D 资源', ready: Boolean(form.resources.parallaxPackage || hasExistingParallax.value) });
  } else if (form.kind === 'dynamic') {
    rows.push({ label: '列表封面', ready: Boolean(form.resources.cover || form.coverUrl) });
    if (hasPlatform('android')) rows.push({ label: 'Android MP4', ready: Boolean(form.resources.androidVideo) });
    if (hasPlatform('ios')) rows.push(
      { label: 'iOS MOV', ready: Boolean(form.resources.iosMov) },
      { label: 'iOS JPEG', ready: Boolean(form.resources.iosPhoto) }
    );
    if (hasPlatform('harmony')) rows.push({ label: 'HarmonyOS 资源包', ready: Boolean(form.resources.harmonyPackage) });
  } else {
    rows.push({ label: '列表封面', ready: Boolean(form.resources.cover || form.coverUrl) });
    rows.push({ label: '高清原图', ready: Boolean(form.resources.staticImage) });
  }
  return rows;
});
</script>

<template>
  <ElDrawer v-model="visible" class="wallpaper-drawer" size="min(980px, 96vw)" destroy-on-close>
    <template #header>
      <div class="drawer-title">
        <strong>{{ isEditing ? '编辑壁纸' : '上传新壁纸' }}</strong>
        <small>选择类型后，系统只显示该类型需要的资源</small>
      </div>
    </template>

    <div class="wallpaper-editor">
      <div class="wallpaper-editor__form">
        <section class="editor-section">
          <div class="editor-section__heading">
            <h3>基础信息</h3>
            <p>用于 App 列表展示、分类筛选和内容排序。</p>
          </div>
          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="壁纸名称" :error="errors.title">
                <ElInput v-model="form.title" maxlength="40" show-word-limit placeholder="例如：暮色山峦" @input="delete errors.title" />
              </ElFormItem>
              <ElFormItem label="Slug" :error="errors.slug">
                <ElInput v-model="form.slug" maxlength="64" placeholder="例如：twilight-mountains" @input="delete errors.slug" />
              </ElFormItem>
              <ElFormItem label="一级分类" :error="errors.categoryId">
                <ElSelect v-model="form.categoryId" placeholder="请选择" style="width: 100%" @change="changePrimaryCategory">
                  <ElOption v-for="item in primaryCategories" :key="item.id" :label="item.name" :value="item.id" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="二级分类（可选）" :error="errors.subcategoryId">
                <ElSelect v-model="form.subcategoryId" clearable :disabled="!form.categoryId || secondaryCategories.length === 0" :placeholder="form.categoryId ? '可不选择二级分类' : '请先选择一级分类'" style="width:100%" @change="delete errors.subcategoryId">
                  <ElOption v-for="item in secondaryCategories" :key="item.id" :label="item.name" :value="item.id" />
                </ElSelect>
              </ElFormItem>
              <ElFormItem label="排序值">
                <ElInputNumber v-model="form.sort" :min="0" :max="9999" controls-position="right" style="width: 100%" />
              </ElFormItem>
              <ElFormItem label="精选推荐">
                <div class="featured-controls">
                  <ElCheckbox :model-value="form.featuredRank !== null" @change="form.featuredRank = $event ? 1 : null">加入首页精选</ElCheckbox>
                  <ElInputNumber v-if="form.featuredRank !== null" v-model="form.featuredRank" :min="0" :max="999999" aria-label="精选排序" controls-position="right" />
                  <small>排序值越小越靠前；取消勾选后不进入精选。</small>
                </div>
              </ElFormItem>
              <ElFormItem class="span-2" label="版权说明" :error="errors.copyrightNote">
                <ElInput v-model="form.copyrightNote" type="textarea" :rows="2" maxlength="500" show-word-limit @input="delete errors.copyrightNote" />
              </ElFormItem>
            </div>
          </ElForm>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading">
            <h3>选择壁纸类型</h3>
            <p>{{ resourceShapeLocked ? '已有资源版本，类型和平台组合已锁定；可上传新文件形成下一资源版本。' : '类型决定资源组合和客户端设置方式。' }}</p>
          </div>
          <div class="kind-picker" role="radiogroup" aria-label="壁纸类型">
            <button v-for="item in kindOptions" :key="item.value" type="button" class="kind-option" :class="{ 'is-active': form.kind === item.value }" :disabled="resourceShapeLocked" role="radio" :aria-checked="form.kind === item.value" @click="form.kind = item.value">
              <strong>{{ item.title }}</strong>
              <small>{{ item.text }}</small>
            </button>
          </div>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading">
            <h3>适用平台</h3>
            <p v-if="form.kind === 'four_d'">4D 分层效果当前仅支持 Android，平台已锁定。</p>
            <p v-else>勾选后请上传对应平台资源；静态壁纸可直接选择全部平台。</p>
          </div>
          <ElCheckboxGroup v-model="form.platforms" :disabled="form.kind === 'four_d' || form.kind === 'static' || resourceShapeLocked" @change="delete errors.platforms">
            <ElCheckboxButton value="android">Android</ElCheckboxButton>
            <ElCheckboxButton value="ios">iOS</ElCheckboxButton>
            <ElCheckboxButton value="harmony">HarmonyOS</ElCheckboxButton>
          </ElCheckboxGroup>
          <p v-if="errors.platforms" class="field-error">{{ errors.platforms }}</p>
        </section>

        <section v-if="form.kind !== 'four_d'" class="editor-section">
          <div class="editor-section__heading">
            <h3>列表资源</h3>
            <p>封面只用于首页和列表展示；详情页直接使用下方的正式壁纸资源呈现效果，无需额外上传预览视频。</p>
          </div>
          <div class="resource-grid">
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.cover" label="列表封面" hint="JPG / PNG / WebP，建议 1080 × 2160" accept="image/jpeg,image/png,image/webp" required @update:model-value="setResource('cover', $event)" />
              <p v-if="errors.cover" class="field-error">{{ errors.cover }}</p>
            </div>
          </div>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading">
            <h3>{{ wallpaperKindLabels[form.kind] }}资源</h3>
            <p v-if="form.kind === 'four_d'">上传 Web 模拟器导出的完整 ZIP；动画参数请返回模拟器调整后重新导出。</p>
            <p v-else-if="form.kind === 'dynamic'">每个平台的动态壁纸格式不同，请补齐已选平台的全部必填资源。</p>
            <p v-else>上传无文字水印的高清原图，客户端按屏幕比例安全裁切。</p>
          </div>
          <div v-if="form.kind === 'four_d'" class="resource-grid">
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.parallaxPackage" label="4D 固定资源包" hint="ZIP，根目录固定包含 cover.jpg、config.json 和 layers/01…12 图层" accept="application/zip,.zip" required @update:model-value="setResource('parallaxPackage', $event)" />
              <p v-if="errors.parallaxPackage" class="field-error">{{ errors.parallaxPackage }}</p>
              <p class="resource-help">
                <a href="/templates/parallax-3-layers.zip" download>下载三层示例 ZIP</a>
                · <a href="/templates/parallax-3-layers-config.json" download="config.json">下载配置模板</a>
              </p>
              <div class="package-format-note">
                <strong>固定目录</strong>
                <code>cover.jpg · config.json · layers/01.png … layers/NN.jpg（NN 为最后一层编号）</code>
                <small>01 为最前景，数字越大越靠后，最后一层为背景；服务端保存时完成解压、校验和资源版本制作。</small>
                <small v-if="hasExistingParallax">已有可用资源版本。不选择新 ZIP 时保留当前素材；替换时上传完整 ZIP。</small>
              </div>
              <div v-if="form.resources.parallaxPackage?.layerCount" class="package-analysis">
                配置 v{{ form.resources.parallaxPackage.configFormatVersion }} ·
                {{ form.resources.parallaxPackage.layerCount }} 层 ·
                {{ form.resources.parallaxPackage.canvasWidth }} × {{ form.resources.parallaxPackage.canvasHeight }} ·
                {{ Math.ceil(form.resources.parallaxPackage.size / 1024) }} KB
                <span v-if="form.resources.parallaxPackage.sha256" class="package-analysis__sha">SHA-256 {{ form.resources.parallaxPackage.sha256 }}</span>
              </div>
            </div>
          </div>

          <div v-else-if="form.kind === 'dynamic'" class="resource-grid">
            <div v-if="hasPlatform('android')" class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.androidVideo" label="Android 动态视频" hint="MP4，H.264，建议 1080 × 2160" accept="video/mp4" required @update:model-value="setResource('androidVideo', $event)" />
              <p v-if="errors.androidVideo" class="field-error">{{ errors.androidVideo }}</p>
            </div>
            <div v-if="hasPlatform('ios')" class="resource-grid__item">
              <ResourceFileField :model-value="form.resources.iosMov" label="iOS 实况视频" hint="MOV，与 JPEG 同一 Live Photo" accept="video/quicktime,.mov" required @update:model-value="setResource('iosMov', $event)" />
              <p v-if="errors.iosMov" class="field-error">{{ errors.iosMov }}</p>
            </div>
            <div v-if="hasPlatform('ios')" class="resource-grid__item">
              <ResourceFileField :model-value="form.resources.iosPhoto" label="iOS 实况照片" hint="JPEG，与 MOV 属于同一 Live Photo" accept="image/jpeg" required @update:model-value="setResource('iosPhoto', $event)" />
              <p v-if="errors.iosPhoto" class="field-error">{{ errors.iosPhoto }}</p>
            </div>
            <div v-if="hasPlatform('harmony')" class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.harmonyPackage" label="HarmonyOS 资源包" hint="ZIP，按鸿蒙客户端约定结构打包" accept="application/zip,.zip" required @update:model-value="setResource('harmonyPackage', $event)" />
              <p v-if="errors.harmonyPackage" class="field-error">{{ errors.harmonyPackage }}</p>
            </div>
          </div>

          <div v-else class="resource-grid">
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.staticImage" label="静态高清原图" hint="JPG / PNG / WebP，建议 1440 × 2880 或更高" accept="image/jpeg,image/png,image/webp" required @update:model-value="setResource('staticImage', $event)" />
              <p v-if="errors.staticImage" class="field-error">{{ errors.staticImage }}</p>
            </div>
          </div>
        </section>
      </div>

      <aside class="wallpaper-editor__preview">
          <div class="preview-sticky">
          <h3>{{ form.kind === 'four_d' ? '列表封面预览' : '详情页效果预览' }}</h3>
          <div class="preview-phone">
            <div class="preview-phone__screen">
              <img v-if="form.kind === 'four_d' && coverPreviewSrc" :src="coverPreviewSrc" alt="4D 列表封面预览" />
              <video v-else-if="form.kind === 'dynamic' && dynamicPreviewSrc" :src="dynamicPreviewSrc" autoplay loop muted playsinline aria-label="动态壁纸资源预览"></video>
              <img v-else-if="form.kind === 'dynamic' && coverPreviewSrc" :src="coverPreviewSrc" alt="动态壁纸封面预览" />
              <img v-else-if="form.kind === 'static' && staticPreviewSrc" :src="staticPreviewSrc" alt="静态壁纸预览" />
              <ElIcon v-if="!previewHasContent" :size="42" style="position:absolute;inset:42% auto auto 40%;color:rgba(255,255,255,.7)"><PictureFilled /></ElIcon>
              <div class="preview-phone__meta">
                <strong>{{ form.title || '未命名壁纸' }}</strong>
                <small>{{ wallpaperKindLabels[form.kind] }} · {{ form.platforms.map((item) => platformLabels[item]).join(' / ') || '未选平台' }}</small>
              </div>
            </div>
          </div>
          <div class="preview-checklist">
            <div v-for="item in resourceRows" :key="item.label">
              <span>{{ item.label }}</span>
              <span :class="item.ready ? 'success-text' : 'warning-text'">
                <ElIcon v-if="item.ready"><Check /></ElIcon>
                {{ item.ready ? '已选择' : '待上传' }}
              </span>
            </div>
          </div>
        </div>
      </aside>
    </div>

    <template #footer>
      <div class="dialog-actions">
        <ElButton :disabled="saving" @click="visible = false">取消</ElButton>
        <ElButton :loading="saving" @click="save('draft')">{{ isEditing ? '保存修改' : '保存草稿' }}</ElButton>
        <ElButton type="primary" :loading="saving" @click="save('published')">保存并发布</ElButton>
      </div>
    </template>
  </ElDrawer>
</template>
