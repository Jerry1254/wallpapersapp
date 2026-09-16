<script setup lang="ts">
import { showToast } from 'vant';
import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSettingTutorialPlayer from '@/components/QjSettingTutorialPlayer.vue';
import QjWallpaperTrial from '@/components/QjWallpaperTrial.vue';
import QjDownloadPanel from '@/design-system/components/QjDownloadPanel.vue';
import QjRedeemPanel from '@/design-system/components/QjRedeemPanel.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjWallpaperHero from '@/design-system/components/QjWallpaperHero.vue';
import QjWallpaperTargetSheet from '@/design-system/components/QjWallpaperTargetSheet.vue';
import type { PublicWallpaperDetail, PublicWallpaperSummary } from '@/domain/catalog';
import type { RedemptionResult } from '@/domain/device';
import type { WallpaperTutorial } from '@/domain/tutorial';
import { tutorialFor, tutorialPlatformForUserAgent } from '@/domain/tutorial';
import { wallpaperTypeLabel } from '@/domain/catalog';
import { ApiClientError, catalogErrorMessage } from '@/repositories/http/apiClient';
import { catalogRepository } from '@/repositories/http/catalogRepository';
import { deviceRepository } from '@/repositories/http/deviceRepository';
import { deviceErrorMessage } from '@/repositories/http/h5DeviceProvider';
import { tutorialRepository } from '@/repositories/http/tutorialRepository';
import { RedemptionUncertain, redemptionResultMessage } from '@/services/redemptionCoordinator';
import { useDeviceStore } from '@/stores/device';
import { usePrototypeStore } from '@/stores/prototype';

const route = useRoute();
const router = useRouter();
const store = usePrototypeStore();
const device = useDeviceStore();

const wallpaper = ref<PublicWallpaperSummary & Partial<PublicWallpaperDetail>>();
const loading = ref(true);
const errorMessage = ref('');
let detailVersion = 0;
const redeemVisible = ref(false);
const redeemCode = ref('');
const redeemStatus = ref<'idle' | 'validating' | 'success' | 'error' | 'unknown'>('idle');
const redeemError = ref('');
const redeemSuccess = ref('');
const primaryLoading = ref(false);
const downloadVisible = ref(false);
const downloadProgress = ref(0);
const downloadStatus = ref<'downloading' | 'verifying' | 'success' | 'error'>('downloading');
const downloadError = ref('');
const settingVisible = ref(false);
const settingTarget = ref<'home' | 'lock' | 'both'>('both');
const settingSuccess = ref(false);
const settingFailure = ref('');
const settingLoading = ref(false);
const tutorialVisible = ref(false);
const selectedTutorial = ref<WallpaperTutorial>();
const trialVisible = ref(false);
const dynamicPermissionGranted = ref(window.sessionStorage.getItem('qj-dynamic-wallpaper-permission') === 'granted');
let downloadTimer: number | undefined;
let transitionTimer: number | undefined;
let permissionTimer: number | undefined;
let tutorialRequest: Promise<WallpaperTutorial[]> | undefined;

const wallpaperCompatible = computed(() => Boolean(wallpaper.value?.capabilities.length) || isOwned.value);
const isOwned = computed(() => wallpaper.value ? device.isOwned(wallpaper.value.id) : false);
const isDownloaded = computed(() => Boolean(isOwned.value && wallpaper.value && store.isDownloaded(wallpaper.value.id)));
const isApplied = computed(() => Boolean(isOwned.value && wallpaper.value && store.isApplied(wallpaper.value.id)));
const pendingHere = computed(() => device.pending?.wallpaperId === wallpaper.value?.id);

const actionLabel = computed(() => {
  if (!wallpaperCompatible.value) return '当前设备不支持';
  if (pendingHere.value) return '确认兑换结果';
  if (isApplied.value) return '重新演示设置';
  if (isDownloaded.value) return '演示设置';
  return '下载壁纸';
});

const loadWallpaper = async () => {
  const version = ++detailVersion;
  loading.value = true;
  errorMessage.value = '';
  wallpaper.value = undefined;
  redeemVisible.value = false;
  downloadVisible.value = false;
  settingVisible.value = false;
  redeemCode.value = '';
  redeemStatus.value = 'idle';
  primaryLoading.value = false;
  settingLoading.value = false;
  clearDownloadTimer();
  if (transitionTimer) window.clearTimeout(transitionTimer);
  if (permissionTimer) window.clearTimeout(permissionTimer);
  try {
    const detail = await catalogRepository.wallpaper(String(route.params.id));
    if (version === detailVersion) wallpaper.value = detail;
  } catch (error) {
    // Existing entitlements remain usable when a wallpaper leaves the public catalog.
    if (error instanceof ApiClientError && error.status === 404) {
      try {
        const owned = await device.ensureOwned(String(route.params.id));
        if (version === detailVersion && owned) wallpaper.value = owned.wallpaper;
      } catch { /* Show the public error until the user can synchronize ownership. */ }
    }
    if (version === detailVersion && !wallpaper.value) errorMessage.value = catalogErrorMessage(error);
  } finally {
    if (version === detailVersion) loading.value = false;
  }
  void device.ensureOwned(String(route.params.id)).catch(() => {});
};

const clearDownloadTimer = () => {
  if (downloadTimer) window.clearInterval(downloadTimer);
  downloadTimer = undefined;
};

const startDownload = async () => {
  if (!wallpaper.value) return;
  const id = wallpaper.value.id;
  const version = detailVersion;
  clearDownloadTimer();
  redeemVisible.value = false;
  downloadVisible.value = true;
  downloadProgress.value = 0;
  downloadStatus.value = 'downloading';
  downloadError.value = '';
  try {
    const descriptor = await deviceRepository.download(id);
    if (version !== detailVersion) return;
    if (descriptor.deliveryMode !== 'H5_PLACEHOLDER' || descriptor.wallpaperId !== id) throw new Error('当前浏览器不支持此交付方式');
  } catch (error) {
    if (version === detailVersion) {
      downloadError.value = deviceErrorMessage(error);
      downloadStatus.value = 'error';
    }
    return;
  }
  downloadTimer = window.setInterval(() => {
    if (downloadProgress.value < 88) {
      downloadProgress.value = Math.min(88, downloadProgress.value + 13);
      return;
    }
    if (downloadStatus.value === 'downloading') {
      downloadStatus.value = 'verifying';
      downloadProgress.value = 96;
      return;
    }
    clearDownloadTimer();
    downloadProgress.value = 100;
    downloadStatus.value = 'success';
    store.markDownloaded(id);
  }, 260);
};

const acceptRedemption = (result: RedemptionResult) => {
  redeemCode.value = '';
  if (['GRANTED', 'ALREADY_OWNED'].includes(result.result)) {
    redeemStatus.value = 'success';
    redeemSuccess.value = redemptionResultMessage(result);
  } else {
    redeemStatus.value = 'error';
    redeemError.value = redemptionResultMessage(result);
  }
};

const runRedemption = async (confirm: boolean) => {
  if (!wallpaper.value || redeemStatus.value === 'validating') return;
  if (redeemStatus.value === 'success') { await startDownload(); return; }
  const version = detailVersion;
  const id = wallpaper.value.id;
  redeemStatus.value = 'validating';
  redeemError.value = '';
  try {
    const result = confirm ? await device.confirmRedemption() : await device.redeem(id, redeemCode.value);
    if (version === detailVersion) acceptRedemption(result);
  } catch (error) {
    if (version !== detailVersion) return;
    redeemStatus.value = error instanceof RedemptionUncertain ? 'unknown' : 'error';
    redeemError.value = error instanceof RedemptionUncertain ? error.message : deviceErrorMessage(error);
    if (error instanceof RedemptionUncertain) redeemCode.value = '';
  }
};
const submitRedeem = () => { void runRedemption(false); };
const confirmRedemption = () => { void runRedemption(true); };

const openPrimaryFlow = async () => {
  if (!wallpaperCompatible.value || !wallpaper.value || primaryLoading.value) return;
  device.syncPending();
  if (device.pending && !pendingHere.value) {
    showToast('请先确认上一次兑换结果');
    await router.push(`/wallpapers/${device.pending.wallpaperId}`);
    return;
  }
  if (pendingHere.value) {
    redeemStatus.value = 'unknown';
    redeemError.value = '上次兑换尚未确认，请先确认结果';
    redeemVisible.value = true;
    return;
  }
  const version = detailVersion;
  primaryLoading.value = true;
  try {
    await device.refresh();
    await device.ensureOwned(wallpaper.value.id);
  } catch (error) {
    if (version === detailVersion) showToast(deviceErrorMessage(error));
    return;
  } finally { primaryLoading.value = false; }
  if (version !== detailVersion) return;
  if (isDownloaded.value && isOwned.value) {
    try { await deviceRepository.download(wallpaper.value.id); }
    catch (error) { showToast(deviceErrorMessage(error)); return; }
    if (version !== detailVersion) return;
    settingSuccess.value = false;
    settingFailure.value = '';
    settingVisible.value = true;
    return;
  }
  if (isOwned.value) {
    await startDownload();
    return;
  }
  redeemStatus.value = 'idle';
  redeemError.value = '';
  redeemVisible.value = true;
};

const continueAfterDownload = () => {
  if (downloadStatus.value === 'error') {
    startDownload();
    return;
  }
  downloadVisible.value = false;
  settingSuccess.value = false;
  settingFailure.value = '';
  transitionTimer = window.setTimeout(() => {
    settingVisible.value = true;
  }, 320);
};

const confirmSetting = () => {
  if (!wallpaper.value) return;
  if (settingSuccess.value) {
    settingVisible.value = false;
    return;
  }
  if (settingFailure.value) {
    settingFailure.value = '';
    return;
  }
  if (wallpaper.value.kind !== 'STATIC' && !dynamicPermissionGranted.value) {
    settingFailure.value = '请先模拟动态壁纸授权，继续体验设置流程；浏览器不会获取系统权限';
    return;
  }
  store.markApplied(wallpaper.value.id, settingTarget.value);
  settingSuccess.value = true;
};

const requestDynamicPermission = () => {
  if (!wallpaper.value || settingLoading.value) return;
  settingLoading.value = true;
  permissionTimer = window.setTimeout(() => {
    dynamicPermissionGranted.value = true;
    window.sessionStorage.setItem('qj-dynamic-wallpaper-permission', 'granted');
    settingFailure.value = '';
    settingLoading.value = false;
    store.markApplied(wallpaper.value!.id, settingTarget.value);
    settingSuccess.value = true;
    showToast({ message: '模拟授权完成，未获取系统权限', position: 'bottom' });
  }, 650);
};

const openTutorial = async () => {
  if (!wallpaper.value) return;
  try {
    tutorialRequest ??= tutorialRepository.list();
    const tutorials = await tutorialRequest;
    const tutorial = tutorialFor(
      tutorials,
      tutorialPlatformForUserAgent(window.navigator.userAgent),
      wallpaper.value.kind
    );
    if (!tutorial) {
      showToast('对应的设置教程暂未发布');
      return;
    }
    selectedTutorial.value = tutorial;
    tutorialVisible.value = true;
  } catch (error) {
    tutorialRequest = undefined;
    showToast(catalogErrorMessage(error));
  }
};

onBeforeUnmount(() => {
  detailVersion += 1;
  clearDownloadTimer();
  if (transitionTimer) window.clearTimeout(transitionTimer);
  if (permissionTimer) window.clearTimeout(permissionTimer);
});

watch(() => route.params.id, loadWallpaper, { immediate: true });
</script>

<template>
  <QjMobileShell :show-navigation="false" class="detail-shell">
    <QjPageHeader v-if="loading || errorMessage" title="壁纸详情" @back="router.push('/home')" />
    <van-skeleton v-if="loading" class="detail-loading" title avatar :row="8" />
    <QjStatePanel
      v-else-if="errorMessage"
      class="detail-loading"
      kind="error"
      :description="errorMessage"
      @action="loadWallpaper"
    />
    <QjWallpaperHero
      v-else-if="wallpaper"
      :src="wallpaper.cover.contentUrl"
      :title="wallpaper.title"
      :action-label="actionLabel"
      :action-loading="primaryLoading"
      :action-disabled="!wallpaperCompatible"
      :show-trial="!isOwned"
      :trial-disabled="!wallpaperCompatible"
      @back="router.back()"
      @tutorial="openTutorial"
      @trial="trialVisible = true"
      @action="openPrimaryFlow"
    />
    <section v-if="wallpaper" class="wallpaper-facts">
      <span>{{ wallpaperTypeLabel(wallpaper.kind) }}</span>
      <span>{{ wallpaper.rootCategory.name }}<template v-if="wallpaper.childCategory"> · {{ wallpaper.childCategory.name }}</template></span>
      <p>{{ wallpaper.copyrightNote }}</p>
      <p>浏览器联调环境：预览、下载与系统设置为演示交互。</p>
      <button v-if="isOwned" type="button" class="detail-repeat" @click="startDownload">重新准备下载演示</button>
    </section>

    <van-popup v-model:show="redeemVisible" position="bottom" round>
      <QjRedeemPanel
        v-model="redeemCode"
        :status="redeemStatus"
        :error-message="redeemError"
        :success-message="redeemSuccess"
        :pending="pendingHere"
        @redeem="submitRedeem"
        @confirm="confirmRedemption"
      />
    </van-popup>

    <van-popup v-model:show="downloadVisible" position="bottom" round :close-on-click-overlay="downloadStatus === 'success' || downloadStatus === 'error'">
      <QjDownloadPanel :progress="downloadProgress" :status="downloadStatus" placeholder :error-message="downloadError" @action="continueAfterDownload" />
    </van-popup>

    <van-popup v-model:show="settingVisible" position="bottom" round>
      <QjWallpaperTargetSheet
        demo
        v-model="settingTarget"
        :loading="settingLoading"
        :success="settingSuccess"
        :failure-reason="settingFailure"
        :permission-required="Boolean(settingFailure) && wallpaper?.kind !== 'STATIC' && !dynamicPermissionGranted"
        @confirm="confirmSetting"
        @request-permission="requestDynamicPermission"
      />
    </van-popup>
    <QjSettingTutorialPlayer
      v-if="selectedTutorial"
      v-model="tutorialVisible"
      :src="selectedTutorial.video.contentUrl"
      :title="selectedTutorial.title"
    />
    <QjWallpaperTrial
      v-if="wallpaper"
      v-model="trialVisible"
      :src="wallpaper.cover.contentUrl"
      :title="wallpaper.title"
      @expired="showToast({ message: '2 分钟试用已结束', position: 'bottom' })"
    />
  </QjMobileShell>
</template>

<style scoped>
.detail-repeat { min-height: 44px; padding: 0 16px; border: 1px solid var(--qj-color-outline); border-radius: var(--qj-radius-pill); color: var(--qj-color-ink); background: var(--qj-color-surface); }
.detail-shell {
  padding-bottom: var(--qj-space-5);
}

.detail-loading {
  margin-top: var(--qj-space-6);
}

.wallpaper-facts {
  display: flex;
  margin-top: var(--qj-space-4);
  flex-wrap: wrap;
  gap: var(--qj-space-2);
}

.wallpaper-facts span {
  padding: 6px 10px;
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
  background: var(--qj-color-surface-muted);
}

.wallpaper-facts p {
  width: 100%;
  margin: var(--qj-space-2) 0 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
  line-height: 1.7;
}

</style>
