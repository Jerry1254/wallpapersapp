<script setup lang="ts">
import { showToast } from 'vant';
import { computed, onBeforeUnmount, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjSettingTutorialPlayer from '@/components/QjSettingTutorialPlayer.vue';
import QjWallpaperTrial from '@/components/QjWallpaperTrial.vue';
import QjDownloadPanel from '@/design-system/components/QjDownloadPanel.vue';
import QjRedeemPanel from '@/design-system/components/QjRedeemPanel.vue';
import QjWallpaperHero from '@/design-system/components/QjWallpaperHero.vue';
import QjWallpaperTargetSheet from '@/design-system/components/QjWallpaperTargetSheet.vue';
import { getWallpaper } from '@/mocks/catalog';
import { usePrototypeStore } from '@/stores/prototype';

const route = useRoute();
const router = useRouter();
const store = usePrototypeStore();

const wallpaper = computed(() => getWallpaper(String(route.params.id)));
const redeemVisible = ref(false);
const redeemCode = ref('QJ8FT-W2C6P-9MR4K-X7D3A');
const redeemStatus = ref<'idle' | 'validating' | 'success' | 'error'>('idle');
const redeemError = ref('');
const downloadVisible = ref(false);
const downloadProgress = ref(0);
const downloadStatus = ref<'downloading' | 'verifying' | 'success' | 'error'>('downloading');
const settingVisible = ref(false);
const settingTarget = ref<'home' | 'lock' | 'both'>('both');
const settingSuccess = ref(false);
const settingFailure = ref('');
const settingLoading = ref(false);
const tutorialVisible = ref(false);
const trialVisible = ref(false);
const dynamicPermissionGranted = ref(window.sessionStorage.getItem('qj-dynamic-wallpaper-permission') === 'granted');
const forceDownloadError = ref(false);
let downloadTimer: number | undefined;
let transitionTimer: number | undefined;
let permissionTimer: number | undefined;

const isOwned = computed(() => wallpaper.value ? store.isOwned(wallpaper.value.id) : false);
const isDownloaded = computed(() => wallpaper.value ? store.isDownloaded(wallpaper.value.id) : false);
const isApplied = computed(() => wallpaper.value ? store.isApplied(wallpaper.value.id) : false);

const actionLabel = computed(() => {
  if (!wallpaper.value?.compatible) return '当前设备不支持';
  if (isApplied.value) return '重新设置';
  if (isDownloaded.value) return '设置壁纸';
  return '下载壁纸';
});

const clearDownloadTimer = () => {
  if (downloadTimer) window.clearInterval(downloadTimer);
  downloadTimer = undefined;
};

const startDownload = () => {
  if (!wallpaper.value) return;
  clearDownloadTimer();
  redeemVisible.value = false;
  downloadVisible.value = true;
  downloadProgress.value = 8;
  downloadStatus.value = 'downloading';
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
    if (forceDownloadError.value) {
      clearDownloadTimer();
      forceDownloadError.value = false;
      downloadStatus.value = 'error';
      return;
    }
    clearDownloadTimer();
    downloadProgress.value = 100;
    downloadStatus.value = 'success';
    store.markDownloaded(wallpaper.value!.id);
  }, 260);
};

const submitRedeem = () => {
  if (redeemStatus.value === 'validating') return;
  const code = redeemCode.value.trim().toUpperCase();
  redeemStatus.value = 'validating';
  redeemError.value = '';
  window.setTimeout(() => {
    if (code === 'INVALID') {
      redeemError.value = '兑换码无效，请检查后重试';
      redeemStatus.value = 'error';
      return;
    }
    if (code === 'USEDUP') {
      redeemError.value = '兑换额度已用完，请联系客服';
      redeemStatus.value = 'error';
      return;
    }
    if (code === 'UNKNOWN') {
      redeemError.value = '兑换结果确认中，请稍后再次查询';
      redeemStatus.value = 'error';
      return;
    }
    if (code.length < 6) {
      redeemError.value = '请输入客服发送的兑换码';
      redeemStatus.value = 'error';
      return;
    }
    forceDownloadError.value = code === 'NETERR';
    store.grantWallpaper(wallpaper.value!.id);
    redeemStatus.value = 'success';
    transitionTimer = window.setTimeout(() => {
      redeemVisible.value = false;
      transitionTimer = window.setTimeout(startDownload, 320);
    }, 420);
  }, 650);
};

const openPrimaryFlow = () => {
  if (!wallpaper.value?.compatible) return;
  if (isDownloaded.value) {
    settingSuccess.value = false;
    settingFailure.value = '';
    settingVisible.value = true;
    return;
  }
  if (isOwned.value) {
    startDownload();
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
  if (wallpaper.value.type !== '静态' && !dynamicPermissionGranted.value) {
    settingFailure.value = '未获得动态壁纸权限，暂时无法应用动态效果';
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
    showToast({ message: '已获取动态壁纸权限', position: 'bottom' });
  }, 650);
};

onBeforeUnmount(() => {
  clearDownloadTimer();
  if (transitionTimer) window.clearTimeout(transitionTimer);
  if (permissionTimer) window.clearTimeout(permissionTimer);
});
</script>

<template>
  <QjMobileShell :show-navigation="false" class="detail-shell">
    <QjWallpaperHero
      v-if="wallpaper"
      :src="wallpaper.image"
      :title="wallpaper.title"
      :action-label="actionLabel"
      :action-disabled="!wallpaper.compatible"
      :show-trial="!isOwned"
      :trial-disabled="!wallpaper.compatible"
      @back="router.back()"
      @tutorial="tutorialVisible = true"
      @trial="trialVisible = true"
      @action="openPrimaryFlow"
    />
    <section v-else class="missing-wallpaper">
      <strong>壁纸不存在</strong>
      <button type="button" @click="router.replace('/home')">返回首页</button>
    </section>

    <van-popup v-model:show="redeemVisible" position="bottom" round>
      <QjRedeemPanel
        v-model="redeemCode"
        :status="redeemStatus"
        :error-message="redeemError"
        @redeem="submitRedeem"
      />
    </van-popup>

    <van-popup v-model:show="downloadVisible" position="bottom" round :close-on-click-overlay="downloadStatus === 'success' || downloadStatus === 'error'">
      <QjDownloadPanel :progress="downloadProgress" :status="downloadStatus" @action="continueAfterDownload" />
    </van-popup>

    <van-popup v-model:show="settingVisible" position="bottom" round>
      <QjWallpaperTargetSheet
        v-model="settingTarget"
        :loading="settingLoading"
        :success="settingSuccess"
        :failure-reason="settingFailure"
        :permission-required="Boolean(settingFailure) && wallpaper?.type !== '静态' && !dynamicPermissionGranted"
        @confirm="confirmSetting"
        @request-permission="requestDynamicPermission"
      />
    </van-popup>
    <QjSettingTutorialPlayer v-model="tutorialVisible" />
    <QjWallpaperTrial
      v-if="wallpaper"
      v-model="trialVisible"
      :src="wallpaper.image"
      :title="wallpaper.title"
      @expired="showToast({ message: '2 分钟试用已结束', position: 'bottom' })"
    />
  </QjMobileShell>
</template>

<style scoped>
.detail-shell {
  padding-bottom: var(--qj-space-5);
}

.missing-wallpaper {
  display: grid;
  min-height: 70dvh;
  place-items: center;
  align-content: center;
  gap: var(--qj-space-4);
}

.missing-wallpaper button {
  min-height: 44px;
  padding: 0 18px;
  border: 0;
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink);
  background: var(--qj-color-accent);
}
</style>
