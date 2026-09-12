<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjOwnedWallpaperRow from '@/components/QjOwnedWallpaperRow.vue';
import QjCatalogMore from '@/components/QjCatalogMore.vue';
import { useCustomerService } from '@/composables/useCustomerService';
import QjBrandHeader from '@/design-system/components/QjBrandHeader.vue';
import QjCustomerServiceCard from '@/design-system/components/QjCustomerServiceCard.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjTutorialCard from '@/design-system/components/QjTutorialCard.vue';
import { wallpaperTypeLabel } from '@/domain/catalog';
import { useDeviceStore } from '@/stores/device';

const router = useRouter();
const store = useDeviceStore();
const { wechatId, qrCodeUrl, ensureQrCode, previewQrCode, saveQrCode, copyWechat } = useCustomerService();

const refresh = () => { void store.refresh().catch(() => {}); };
const loadMore = () => { void store.loadMore().catch(() => {}); };

onMounted(() => {
  void ensureQrCode();
  store.syncPending();
  refresh();
});
</script>

<template>
  <QjMobileShell active-tab="mine">
    <QjBrandHeader title="我的" @service="router.push('/customer-service')" />

    <section class="prototype-section mine-tutorial">
      <QjTutorialCard @open="router.push('/tutorial')" />
    </section>

    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>已获得壁纸</h2>
        <button type="button" class="mine-refresh" :disabled="store.loading" @click="refresh">刷新权益</button>
      </div>
      <p class="mine-device-note">权益属于当前浏览器测试设备。清除浏览器数据后将创建新设备。</p>
      <p v-if="store.pending" class="mine-device-note">上次兑换尚未确认。<router-link :to="`/wallpapers/${store.pending.wallpaperId}`">查看并确认结果</router-link></p>
      <van-skeleton v-if="store.loading && !store.loaded" title :row="5" />
      <QjStatePanel v-else-if="store.errorMessage && !store.items.length" kind="error" :description="store.errorMessage" @action="refresh" />
      <div v-else-if="store.items.length" class="mine-owned-list">
        <QjOwnedWallpaperRow
          v-for="item in store.items"
          :key="item.id"
          :src="item.wallpaper.cover.contentUrl"
          :title="item.wallpaper.title"
          :type="wallpaperTypeLabel(item.wallpaper.kind)"
          @select="router.push(`/wallpapers/${item.wallpaper.id}`)"
        />
      </div>
      <QjStatePanel v-else description="兑换壁纸后会显示在这里" @action="router.push('/home')" />
      <p v-if="store.errorMessage && store.items.length" role="status" class="mine-device-note">{{ store.errorMessage }}</p>
      <QjCatalogMore :has-more="store.hasMore" :loading="store.loading" :error-message="store.errorMessage" @load="loadMore" />
    </section>

    <section class="prototype-section">
      <div class="prototype-section__header">
        <h2>微信客服</h2>
      </div>
      <QjCustomerServiceCard
        :wechat-id="wechatId"
        :qr-code-url="qrCodeUrl"
        @copy="copyWechat"
        @preview="previewQrCode"
        @save="saveQrCode"
      />
    </section>

  </QjMobileShell>
</template>

<style scoped>
.mine-tutorial {
  margin-top: var(--qj-space-4);
}

.mine-owned-list {
  display: grid;
  gap: var(--qj-space-3);
}

.mine-device-note { color: var(--qj-color-muted-ink); font-size: var(--qj-font-size-caption); line-height: 1.6; }
.mine-refresh { min-height: 44px; padding: 0 12px; border: 1px solid var(--qj-color-outline); border-radius: var(--qj-radius-pill); color: var(--qj-color-ink); background: var(--qj-color-surface); }

</style>
