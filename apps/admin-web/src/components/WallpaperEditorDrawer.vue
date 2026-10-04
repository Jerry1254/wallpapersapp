<script setup lang="ts">
import { Check, PictureFilled } from '@element-plus/icons-vue';
import { ElMessage } from 'element-plus';
import { computed, reactive, ref, toRaw, watch } from 'vue';

import ResourceFileField from '@/components/ResourceFileField.vue';
import { iosPriceSyncLabel, iosCreditPack as creditPack } from '@/domain/iosPricing';
import { hasPreviewWatermark, mergePreviewGeneration, previewGenerationLabel } from '@/domain/previewWatermark';
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
  'preview-rebuilt': [value: Wallpaper];
}>();

type WallpaperForm = Wallpaper & { iosAcquisition: NonNullable<Wallpaper['iosAcquisition']> };
const blank = (): WallpaperForm => ({
  id: '', title: '', slug: '', categoryId: '', subcategoryId: '',
  accessType: 'REDEEM', capabilities: [], status: 'draft', sort: 1,
  previewWatermarkEnabled: true, previewGenerationStatus: undefined, previewRevision: 0, previewGenerationError: null,
  coverUrl: '', featuredRank: null, resources: {}, copyrightNote: '', updatedAt: '', version: 0,
  variants: [], iosAcquisition: {
    acquisitionMode: 'CREDITS', credits: null, productId: '', chinaReferencePrice: null, firstFreeEligible: true, enabled: true, productIdLocked: false, verifiedTransactionAt: null
  }
});
const creditSelection = computed(() => creditPack(form.iosAcquisition.credits));
const form = reactive<WallpaperForm>(blank());
const errors = ref<Record<string, string>>({});
const syncingIosPrice = ref(false);
const canSyncIosPrice = computed(() => Boolean(form.id && (form.iosAcquisition.acquisitionMode === 'CREDITS' || (form.iosAcquisition.productId
  && form.iosAcquisition.productId === props.wallpaper?.iosAcquisition?.productId))));
const syncIosPrice = async () => {
  if (!canSyncIosPrice.value) return;
  syncingIosPrice.value = true;
  try {
    const result = await adminRepository.syncIosPrice(form.id);
    // Keep other unsaved fields intact when refreshing the price only.
    for (const field of ['chinaReferencePrice', 'priceSource', 'priceCurrency', 'priceSyncStatus', 'priceSyncedAt', 'priceSyncError'] as const) {
      Object.assign(form.iosAcquisition, { [field]: result[field] });
    }
    if (result.priceSyncStatus === 'READY') ElMessage.success('已同步 Apple 中国区价格');
    else ElMessage.warning(iosPriceSyncLabel(result));
  } catch (error) { ElMessage.error(readableApiError(error)); }
  finally { syncingIosPrice.value = false; }
};
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
const hasIosAcquisition = computed(() => hasCapability('ios_live_photo') || Boolean(form.iosAcquisition.productId));

const coverPreviewSrc = computed(() => form.resources.cover?.url
  || form.resources.staticImage?.url
  || form.coverUrl);
const dynamicPreviewSrc = computed(() => form.resources.androidVideo?.url || form.resources.iosVideo?.url);
const previewHasContent = computed(() => Boolean(dynamicPreviewSrc.value || coverPreviewSrc.value));
const showPreviewWatermark = computed(() => hasPreviewWatermark(form));

const cloneIntoForm = (value?: Wallpaper) => {
  const next = value ? structuredClone(toRaw(value)) : blank();
  Object.assign(form, blank(), next, {
    iosAcquisition: {
      ...blank().iosAcquisition, ...next.iosAcquisition, firstFreeEligible: true, enabled: true
    }
  });
  errors.value = {};
};
watch(() => props.modelValue, (open) => { if (open) cloneIntoForm(props.wallpaper); }, { immediate: true });
watch(() => props.wallpaper, (value) => { if (props.modelValue) cloneIntoForm(value); });
watch(() => [props.wallpaper?.previewGenerationStatus, props.wallpaper?.previewRevision, props.wallpaper?.previewGenerationError], () => {
  const value = props.wallpaper;
  if (props.modelValue && value && value.id === form.id) mergePreviewGeneration(form, value);
});
const rebuildingPreview = ref(false);
const rebuildPreview = async () => {
  if (!form.id) return;
  rebuildingPreview.value = true;
  try {
    const result = await adminRepository.rebuildWallpaperPreview(form.id);
    mergePreviewGeneration(form, result);
    emit('preview-rebuilt', result);
    ElMessage.success('预览已加入重新生成队列，正式下载原文件保持不变');
  } catch (cause) {
    ElMessage.error(readableApiError(cause, '预览重新生成失败'));
  } finally { rebuildingPreview.value = false; }
};

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
  if (hasIosAcquisition.value && form.iosAcquisition.acquisitionMode === 'CREDITS') {
    if (!creditPack(form.iosAcquisition.credits)) {
      next.iosCredits = '请选择支持的整数价格：1～10、12、14、15、16、18、20、21、24、27、30元';
    }
  } else if (hasIosAcquisition.value && !form.iosAcquisition.productId.trim()) {
    next.iosProductId = '请填写已有非消耗型 Product ID，或切换为下载积分';
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
              <ElFormItem v-if="form.accessType === 'REDEEM'" label="预览加水印">
                <ElSwitch v-model="form.previewWatermarkEnabled" active-text="开启" inactive-text="关闭" />
                <small>付费默认开启。保存后自动重新生成预览；4D 仅最前一层加水印，正式下载不受影响。</small>
              </ElFormItem>
              <ElFormItem v-if="form.id && form.previewGenerationStatus" label="预览资源状态">
                <span :class="form.previewGenerationStatus === 'FAILED' ? 'warning-text' : undefined">{{ previewGenerationLabel(form) }}</span>
                <ElButton v-if="form.previewGenerationStatus === 'FAILED' && form.status !== 'archived'" :loading="rebuildingPreview" :disabled="saving" @click="rebuildPreview">重新生成预览</ElButton>
                <small v-if="form.previewGenerationError" class="warning-text">{{ form.previewGenerationError }}</small>
                <small v-else-if="['PENDING', 'PROCESSING'].includes(form.previewGenerationStatus)">预览正在重新生成，完成后自动供 App 使用。</small>
              </ElFormItem>
            </div>
          </ElForm>
        </section>

        <section v-if="hasIosAcquisition" class="editor-section">
          <div class="editor-section__heading"><h3>iOS 首免与内购</h3><p>壁纸整数售价在此设置，自动转换为积分。1积分＝1元；共用三档 Apple 积分商品。</p></div>
          <ElForm label-position="top">
            <div class="form-grid">
              <ElFormItem label="购买方式">
                <ElSelect v-model="form.iosAcquisition.acquisitionMode">
                  <ElOption label="下载积分（推荐）" value="CREDITS" />
                  <ElOption v-if="form.iosAcquisition.acquisitionMode !== 'CREDITS'" label="旧非消耗型商品" value="NON_CONSUMABLE" />
                </ElSelect>
                <small>切换积分后，旧交易仍可恢复；后续上新无须逐张创建 Apple 商品。</small>
              </ElFormItem>
              <ElFormItem v-if="form.iosAcquisition.acquisitionMode === 'CREDITS'" label="iOS 售价（元）" :error="errors.iosCredits">
                <ElInputNumber v-model="form.iosAcquisition.credits" :min="1" :max="30" :precision="0" placeholder="填写整数售价" />
                <small v-if="creditSelection">{{ form.iosAcquisition.credits }}个积分兑换壁纸：{{ creditSelection.pack }}积分商品 × {{ creditSelection.quantity }}份。1积分＝1元。</small>
                <small>支持1～10、12、14、15、16、18、20、21、24、27、30元。订单按下单时价格完成，不向上取整。</small>
              </ElFormItem>
              <ElFormItem v-else label="旧非消耗型 Product ID" :error="errors.iosProductId">
                <ElInput v-model="form.iosAcquisition.productId" :disabled="form.iosAcquisition.productIdLocked" />
              </ElFormItem>
              <ElFormItem label="Apple 积分商品核验">
                <span>{{ iosPriceSyncLabel(form.iosAcquisition) }}</span>
                <ElButton :loading="syncingIosPrice" :disabled="!canSyncIosPrice" @click="syncIosPrice">立即同步</ElButton>
                <small v-if="form.iosAcquisition.priceSyncedAt">最近成功同步：{{ form.iosAcquisition.priceSyncedAt }}</small>
                <small>核验积分包中国区价格：1、2、3元。修改壁纸价格后保存即可；积分包价格保持固定。</small>
              </ElFormItem>
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
          <span v-if="showPreviewWatermark && previewHasContent" class="preview-phone__watermark" aria-hidden="true">预览专用</span>
          <div class="preview-phone__meta"><strong>{{ form.title || '未命名壁纸' }}</strong><small>{{ derivedCapabilities.map((item) => wallpaperCapabilityLabels[item]).join(' / ') || '尚未上传正式资源' }}</small></div>
        </div></div>
        <div class="preview-checklist"><div v-for="item in resourceRows" :key="item.label"><span>{{ item.label }}</span><span :class="item.ready ? 'success-text' : 'warning-text'"><ElIcon v-if="item.ready"><Check /></ElIcon>{{ item.ready ? '已选择' : '待上传' }}</span></div></div>
      </div></aside>
    </div>

    <template #footer><div class="dialog-actions"><ElButton :disabled="saving" @click="visible = false">取消</ElButton><ElButton :loading="saving" @click="save('draft')">{{ isEditing ? '保存修改' : '保存草稿' }}</ElButton><ElButton type="primary" :loading="saving" @click="save('published')">保存并发布</ElButton></div></template>
  </ElDrawer>
</template>
