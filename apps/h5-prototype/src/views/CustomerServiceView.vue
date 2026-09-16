<script setup lang="ts">
import { onMounted } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import QjSettingTutorialCard from '@/components/QjSettingTutorialCard.vue';
import { useCustomerService } from '@/composables/useCustomerService';
import QjCustomerServiceCard from '@/design-system/components/QjCustomerServiceCard.vue';

const router = useRouter();
const { wechatId, qrCodeUrl, ensureQrCode, previewQrCode, saveQrCode, copyWechat } = useCustomerService();

onMounted(() => {
  void ensureQrCode();
});
</script>

<template>
  <QjMobileShell :show-navigation="false">
    <QjPageHeader title="微信客服" @back="router.back()" />
    <section class="customer-content">
      <QjCustomerServiceCard
        :wechat-id="wechatId"
        :qr-code-url="qrCodeUrl"
        @copy="copyWechat"
        @preview="previewQrCode"
        @save="saveQrCode"
      />

      <QjSettingTutorialCard @open="router.push('/tutorial')" />
    </section>
  </QjMobileShell>
</template>

<style scoped>
.customer-content {
  display: grid;
  margin-top: var(--qj-space-6);
  gap: var(--qj-space-4);
}

</style>
