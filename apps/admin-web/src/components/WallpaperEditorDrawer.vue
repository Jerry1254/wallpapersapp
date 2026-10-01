<script setup lang="ts">
import { Check, PictureFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
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
import { readableApiError } from '@/repositories/http/apiClient';
import { adminRepository } from '@/repositories/http/adminRepository';

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

type WallpaperForm = Wallpaper & { iosAcquisition: NonNullable<Wallpaper['iosAcquisition']> };
const blank = (): WallpaperForm => ({
  id: '', title: '', slug: '', categoryId: '', subcategoryId: '',
  accessType: 'REDEEM', capabilities: [], status: 'draft', sort: 1,
  coverUrl: '', featuredRank: null, resources: {}, copyrightNote: '', updatedAt: '', version: 0,
  variants: [], iosAcquisition: {
    productId: '', firstFreeEligible: false, enabled: false, productIdLocked: false, verifiedTransactionAt: null
  }
});
const form = reactive<WallpaperForm>(blank());
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
  { value: 'ios_live_photo', title: 'iOS 实况', text: '原始 MP4 / MOV 生成 Live Photo' },
  { value: 'harmony_moving_photo', title: '鸿蒙动态', text: 'Moving Photo 视频 + 首帧' },
  { value: 'universal_static', title: '全平台静态', text: '高清静态原图' }
];

const pairForCapability: Record<WallpaperCapability, [string, string]> = {
  android_parallax: ['ANDROID', 'LAYER_PARALLAX'],
  android_video: ['ANDROID', 'VIDEO'],
  ios_live_photo: ['IOS', 'LIVE_PHOTO'],
  harmony_moving_photo: ['HARMONYOS', 'MOVING_PHOTO'],
  universal_static: ['UNIVERSAL', 'STATIC_IMAGE']
};
const hasCapability = (value: WallpaperCapability) => derivedCapabilities.value.includes(value);
const hasExisting = (value: WallpaperCapability) => {
  const [platform, resourceType] = pairForCapability[value];
  return form.variants.some((variant) => variant.enabled && variant.platform === platform && variant.resourceType === resourceType
    && variant.resourceVersions.some((version) => ['READY', 'PUBLISHED'].includes(version.status)
      && (value !== 'harmony_moving_photo' || version.movingPhoto?.publishable)
      && (value !== 'ios_live_photo' || version.livePhoto?.publishable)));
};
const derivedCapabilities = computed<WallpaperCapability[]>(() => capabilityOptions
  .map((item) => item.value)
  .filter((value) => {
    if (hasExisting(value)) return true;
    if (value === 'android_parallax') return Boolean(form.resources.parallaxPackage);
    if (value === 'android_video') return Boolean(form.resources.androidVideo);
    if (value === 'ios_live_photo') return Boolean(form.resources.iosVideo);
    if (value === 'harmony_moving_photo') return Boolean(form.resources.harmonyVideo);
    return Boolean(form.resources.staticImage);
  }));

const coverPreviewSrc = computed(() => form.resources.cover?.url
  || form.resources.staticImage?.url
  || form.coverUrl);
const dynamicPreviewSrc = computed(() => form.resources.androidVideo?.url || form.resources.iosVideo?.url);
const previewHasContent = computed(() => Boolean(dynamicPreviewSrc.value || coverPreviewSrc.value));

const cloneIntoForm = (value?: Wallpaper) => {
  const next = value ? structuredClone(toRaw(value)) : blank();
  Object.assign(form, blank(), next, {
    iosAcquisition: next.iosAcquisition || blank().iosAcquisition
  });
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
  if (!form.categoryId) next.categoryId = '请选择一级分类';
  if (!['REDEEM', 'FREE'].includes(form.accessType)) next.accessType = '请选择获取方式';
  if (derivedCapabilities.value.length === 0) next.capabilities = '请至少上传一种正式资源';
  if (hasCapability('android_parallax') && !form.resources.parallaxPackage && !hasExisting('android_parallax')) {
    next.parallaxPackage = '请上传 4D 固定资源包';
  }
  if (hasCapability('android_video') && !form.resources.androidVideo && !hasExisting('android_video')) {
    next.androidVideo = '请上传 Android MP4';
  }
  if (hasCapability('ios_live_photo') && !form.resources.iosVideo && !hasExisting('ios_live_photo')) next.iosVideo = '请上传 iOS 原始 MP4 或 MOV';
  if (hasCapability('harmony_moving_photo') && !form.resources.harmonyVideo && !hasExisting('harmony_moving_photo')) {
    next.harmonyVideo = '请上传 HarmonyOS 原始视频';
  }
  if (hasCapability('universal_static') && !form.resources.staticImage && !hasExisting('universal_static')) {
    next.staticImage = '请上传高清静态原图';
  }
  if (!form.resources.cover && !form.coverUrl) next.cover = '请单独上传列表封面';
  if ((form.iosAcquisition.enabled || form.iosAcquisition.firstFreeEligible) && !form.iosAcquisition.productId.trim()) {
    next.iosProductId = '请先填写 App Store Connect 中已创建的非消耗型 Product ID';
  }
  if (form.iosAcquisition.productId && !/^[A-Za-z0-9._-]+$/.test(form.iosAcquisition.productId)) {
    next.iosProductId = 'Product ID 只能包含字母、数字、点、下划线和连字符';
  }
  errors.value = next;
  return Object.keys(next).length === 0;
};
const save = (status: PublishStatus) => {
  if (!validate()) return;
  form.capabilities = [...derivedCapabilities.value];
  form.status = status;
  emit('saved', structuredClone(toRaw(form)));
};

const resourceRows = computed(() => {
  const rows: { label: string; ready: boolean }[] = [];
  if (hasCapability('android_parallax')) rows.push({ label: 'Android 4D ZIP', ready: Boolean(form.resources.parallaxPackage || hasExisting('android_parallax')) });
  if (hasCapability('android_video')) rows.push({ label: 'Android MP4', ready: Boolean(form.resources.androidVideo || hasExisting('android_video')) });
  if (hasCapability('ios_live_photo')) rows.push({ label: 'iOS Live Photo', ready: Boolean(form.resources.iosVideo || hasExisting('ios_live_photo')) });
  if (hasCapability('harmony_moving_photo')) rows.push({ label: '鸿蒙 Moving Photo', ready: Boolean(form.resources.harmonyVideo || hasExisting('harmony_moving_photo')) });
  if (hasCapability('universal_static')) rows.push({ label: '静态原图', ready: Boolean(form.resources.staticImage || hasExisting('universal_static')) });
  return rows;
});
const harmonyStatus = computed(() => form.variants
  .find((variant) => variant.platform === 'HARMONYOS' && variant.resourceType === 'MOVING_PHOTO')
  ?.resourceVersions.slice().sort((a, b) => b.versionNo - a.versionNo)[0]?.movingPhoto);
const harmonyVersion = computed(() => form.variants
  .find((variant) => variant.platform === 'HARMONYOS' && variant.resourceType === 'MOVING_PHOTO')
  ?.resourceVersions.slice().sort((a, b) => b.versionNo - a.versionNo)[0]);
const iosVersion = computed(() => form.variants
  .find((variant) => variant.platform === 'IOS' && variant.resourceType === 'LIVE_PHOTO')
  ?.resourceVersions.slice().sort((a, b) => b.versionNo - a.versionNo)[0]);
const iosStatus = computed(() => iosVersion.value?.livePhoto);
const rebuildingLivePhoto = ref(false);
const rebuildingMovingPhoto = ref(false);
const rebuildLivePhoto = async () => {
  if (!iosVersion.value) return;
  rebuildingLivePhoto.value = true;
  try {
    iosVersion.value.livePhoto = await adminRepository.rebuildLivePhoto(iosVersion.value.id);
    if (form.resources.iosVideo) form.resources.iosVideo.nativeFile = undefined;
    ElMessage.success('iOS Live Photo 已重新生成');
  } catch (cause) {
    ElMessage.error(readableApiError(cause, 'iOS Live Photo 重新生成失败'));
  } finally {
    rebuildingLivePhoto.value = false;
  }
};
const rebuildMovingPhoto = async () => {
  if (!harmonyVersion.value) return;
  rebuildingMovingPhoto.value = true;
  try {
    harmonyVersion.value.movingPhoto = await adminRepository.rebuildMovingPhoto(harmonyVersion.value.id);
    if (form.resources.harmonyVideo) form.resources.harmonyVideo.nativeFile = undefined;
    ElMessage.success('鸿蒙动态照片已重新生成');
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '鸿蒙动态照片重新生成失败'));
  } finally {
    rebuildingMovingPhoto.value = false;
  }
};
</script>

<template>
  <ElDrawer v-model="visible" class="wallpaper-drawer" size="min(1400px, 98vw)" destroy-on-close>
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
              <ElFormItem label="一级分类" :error="errors.categoryId"><ElSelect v-model="form.categoryId" style="width:100%" @change="changePrimaryCategory"><ElOption v-for="item in primaryCategories" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></ElFormItem>
              <ElFormItem label="二级分类（可选）"><ElSelect v-model="form.subcategoryId" clearable :disabled="!form.categoryId || secondaryCategories.length === 0" style="width:100%"><ElOption v-for="item in secondaryCategories" :key="item.id" :label="item.name" :value="item.id" /></ElSelect></ElFormItem>
              <ElFormItem label="排序值"><ElInputNumber v-model="form.sort" :min="0" :max="999999" controls-position="right" style="width:100%" /></ElFormItem>
              <ElFormItem label="精选推荐"><div class="featured-controls"><ElCheckbox :model-value="form.featuredRank !== null" @change="form.featuredRank = $event ? 1 : null">加入首页精选</ElCheckbox><ElInputNumber v-if="form.featuredRank !== null" v-model="form.featuredRank" :min="0" :max="999999" /></div></ElFormItem>
              <ElFormItem label="获取方式" :error="errors.accessType"><ElRadioGroup v-model="form.accessType"><ElRadio value="REDEEM">需要兑换</ElRadio><ElRadio value="FREE">免费</ElRadio></ElRadioGroup></ElFormItem>
            </div>
          </ElForm>
        </section>

        <section v-if="hasCapability('ios_live_photo') || form.iosAcquisition.productId" class="editor-section">
          <div class="editor-section__heading"><h3>iOS 首免与内购</h3><p>Product ID 在 App Store Connect 创建；价格由 Apple 返回，后台不填价格。</p></div>
          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="非消耗型 Product ID" :error="errors.iosProductId">
                <ElInput v-model="form.iosAcquisition.productId" :disabled="form.iosAcquisition.productIdLocked" placeholder="例如 com.qingjing.bizhi.wallpaper.123" />
                <small v-if="form.iosAcquisition.productIdLocked">已有 Apple 验证交易，Product ID 已锁定。</small>
              </ElFormItem>
              <ElFormItem label="售卖状态"><ElSwitch v-model="form.iosAcquisition.enabled" active-text="允许购买" inactive-text="暂不售卖" /></ElFormItem>
              <ElFormItem label="首次免费"><ElSwitch v-model="form.iosAcquisition.firstFreeEligible" active-text="可作为首免选择" inactive-text="不参与首免" /></ElFormItem>
            </div>
          </ElForm>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading"><h3>发布平台</h3><p>根据已经上传且完整的正式资源自动识别，无需人工选择。</p></div>
          <div class="kind-picker">
            <div v-for="item in capabilityOptions" :key="item.value" class="capability-card" :class="{ 'is-checked': derivedCapabilities.includes(item.value) }">
              <span class="capability-card__copy"><strong>{{ item.title }}</strong><small>{{ derivedCapabilities.includes(item.value) ? '已识别' : item.text }}</small></span>
            </div>
          </div>
          <p v-if="errors.capabilities" class="field-error">{{ errors.capabilities }}</p>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading"><h3>列表封面</h3><p>必填，用于 App 列表和详情入口；4D ZIP 不再包含或提供封面。</p></div>
          <div class="resource-grid"><div class="resource-grid__item span-2">
            <ResourceFileField :model-value="form.resources.cover" label="列表封面（必填）" hint="JPG / PNG / WebP，保存时自动压缩为最大 720×1280" accept="image/jpeg,image/png,image/webp" required @update:model-value="setResource('cover', $event)" />
            <p v-if="errors.cover" class="field-error">{{ errors.cover }}</p>
          </div></div>
        </section>

        <section class="editor-section">
          <div class="editor-section__heading"><h3>正式资源</h3><p>上传哪种资源，就自动为商品增加对应安装包平台。</p></div>
          <div class="resource-grid">
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.parallaxPackage" label="Android 4D 固定资源包" hint="模拟器导出的完整 ZIP，包含 config.json 和 2–12 层图，不包含封面" accept="application/zip,.zip" @update:model-value="setResource('parallaxPackage', $event)" />
              <p v-if="errors.parallaxPackage" class="field-error">{{ errors.parallaxPackage }}</p>
              <small v-if="hasExisting('android_parallax')">不选择新 ZIP 时保留当前可用版本。</small>
            </div>
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.androidVideo" label="Android 动态视频" hint="MP4，H.264" accept="video/mp4" @update:model-value="setResource('androidVideo', $event)" />
              <p v-if="errors.androidVideo" class="field-error">{{ errors.androidVideo }}</p>
            </div>
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.iosVideo" label="iOS 实况原始视频" hint="MP4 / MOV，至少 1 秒；保存后自动生成 1 秒 HEIC + MOV Live Photo" accept="video/mp4,video/quicktime,.mp4,.mov" @update:model-value="setResource('iosVideo', $event)" />
              <p v-if="errors.iosVideo" class="field-error">{{ errors.iosVideo }}</p>
              <small v-if="iosStatus" :class="iosStatus.publishable ? 'success-text' : 'warning-text'">
                生成状态：{{ iosStatus.status }} · HEIC {{ iosStatus.photo ? '已生成' : '未生成' }} · MOV {{ iosStatus.video ? '已生成' : '未生成' }}
                <template v-if="iosStatus.durationMs"> · {{ iosStatus.durationMs }}ms</template>
                <template v-if="iosStatus.widthPx && iosStatus.heightPx"> · {{ iosStatus.widthPx }}×{{ iosStatus.heightPx }}</template>
                <template v-if="iosStatus.frameRate"> · {{ iosStatus.frameRate }}fps</template>
                <template v-if="iosStatus.processingMode"> · {{ iosStatus.processingMode }}</template>
                · {{ iosStatus.publishable ? '可发布' : '不可发布' }}
                <template v-if="iosStatus.errorCode"> · {{ iosStatus.errorCode }}</template>
              </small>
              <small v-else-if="form.resources.iosVideo">保存后由后端生成 HEIC 照片和包含 Apple 元数据轨的 MOV。</small>
              <div v-if="iosStatus?.status === 'REJECTED'" class="resource-retry"><ElButton :loading="rebuildingLivePhoto" @click="rebuildLivePhoto">重新生成</ElButton></div>
            </div>
            <div class="resource-grid__item span-2">
              <ResourceFileField :model-value="form.resources.harmonyVideo" label="HarmonyOS 动态原始视频" hint="MP4，至少 2 秒；保存后截取前 2 秒并生成 JPEG 首帧，优先保留原分辨率与帧率" accept="video/mp4" @update:model-value="setResource('harmonyVideo', $event)" />
              <p v-if="errors.harmonyVideo" class="field-error">{{ errors.harmonyVideo }}</p>
              <small v-if="harmonyStatus" :class="harmonyStatus.publishable ? 'success-text' : 'warning-text'">
                生成状态：{{ harmonyStatus.status }} · 视频 {{ harmonyStatus.video ? '已生成' : '未生成' }} · JPEG {{ harmonyStatus.poster ? '已生成' : '未生成' }}
                <template v-if="harmonyStatus.durationMs"> · {{ harmonyStatus.durationMs }}ms</template>
                <template v-if="harmonyStatus.widthPx && harmonyStatus.heightPx"> · {{ harmonyStatus.widthPx }}×{{ harmonyStatus.heightPx }}</template>
                <template v-if="harmonyStatus.frameRate"> · {{ harmonyStatus.frameRate }}fps</template>
                <template v-if="harmonyStatus.processingMode"> · {{ harmonyStatus.processingMode }}</template>
                · {{ harmonyStatus.publishable ? '可发布' : '不可发布' }}
                <template v-if="harmonyStatus.errorCode"> · {{ harmonyStatus.errorCode }}</template>
              </small>
              <small v-else-if="form.resources.harmonyVideo">保存后由后端生成视频和 JPEG 首帧。</small>
              <div v-if="harmonyStatus?.status === 'REJECTED'" class="resource-retry"><ElButton :loading="rebuildingMovingPhoto" @click="rebuildMovingPhoto">重新生成</ElButton></div>
            </div>
            <div class="resource-grid__item span-2"><ResourceFileField :model-value="form.resources.staticImage" label="全平台高清静态原图" hint="JPG / PNG / WebP；只有这里上传的图片才算静态壁纸" accept="image/jpeg,image/png,image/webp" @update:model-value="setResource('staticImage', $event)" /><p v-if="errors.staticImage" class="field-error">{{ errors.staticImage }}</p></div>
          </div>
        </section>
      </div>

      <aside class="wallpaper-editor__preview"><div class="preview-sticky">
        <h3>详情页效果预览</h3>
        <div class="preview-phone"><div class="preview-phone__screen">
          <video v-if="dynamicPreviewSrc" :src="dynamicPreviewSrc" autoplay loop muted playsinline></video>
          <img v-else-if="coverPreviewSrc" :src="coverPreviewSrc" alt="壁纸预览" />
          <ElIcon v-if="!previewHasContent" :size="42" style="position:absolute;inset:42% auto auto 40%;color:rgba(255,255,255,.7)"><PictureFilled /></ElIcon>
          <div class="preview-phone__meta"><strong>{{ form.title || '未命名壁纸' }}</strong><small>{{ derivedCapabilities.map((item) => wallpaperCapabilityLabels[item]).join(' / ') || '尚未上传正式资源' }}</small></div>
        </div></div>
        <div class="preview-checklist"><div v-for="item in resourceRows" :key="item.label"><span>{{ item.label }}</span><span :class="item.ready ? 'success-text' : 'warning-text'"><ElIcon v-if="item.ready"><Check /></ElIcon>{{ item.ready ? '已选择' : '待上传' }}</span></div></div>
      </div></aside>
    </div>

    <template #footer><div class="dialog-actions"><ElButton :disabled="saving" @click="visible = false">取消</ElButton><ElButton :loading="saving" @click="save('draft')">{{ isEditing ? '保存修改' : '保存草稿' }}</ElButton><ElButton type="primary" :loading="saving" @click="save('published')">保存并发布</ElButton></div></template>
  </ElDrawer>
</template>
