<script setup lang="ts">
import { computed, onMounted } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjOwnedWallpaperRow from '@/components/QjOwnedWallpaperRow.vue';
import { useCustomerService } from '@/composables/useCustomerService';
import QjBrandHeader from '@/design-system/components/QjBrandHeader.vue';
import QjCustomerServiceCard from '@/design-system/components/QjCustomerServiceCard.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjTutorialCard from '@/design-system/components/QjTutorialCard.vue';
import { wallpapers } from '@/mocks/catalog';
import { usePrototypeStore } from '@/stores/prototype';

const router = useRouter();
const store = usePrototypeStore();
const { wechatId, qrCodeUrl, ensureQrCode, previewQrCode, saveQrCode, copyWechat } = useCustomerService();

const ownedWallpapers = computed(() => store.ownedIds
  .map((id) => wallpapers.find((item) => item.id === id))
  .filter((item): item is NonNullable<typeof item> => Boolean(item)));

onMounted(() => {
  void ensureQrCode();
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
        <h2>已购买壁纸</h2>
        <span>{{ ownedWallpapers.length }} 张</span>
      </div>
      <div v-if="ownedWallpapers.length" class="mine-owned-list">
        <QjOwnedWallpaperRow
          v-for="wallpaper in ownedWallpapers"
          :key="wallpaper.id"
          :src="wallpaper.image"
          :title="wallpaper.title"
          :type="wallpaper.type"
          @select="router.push(`/wallpapers/${wallpaper.id}`)"
        />
      </div>
      <QjStatePanel v-else description="兑换壁纸后会显示在这里" @action="router.push('/home')" />
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

</style>
