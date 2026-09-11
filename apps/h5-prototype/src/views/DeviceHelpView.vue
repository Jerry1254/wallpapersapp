<script setup lang="ts">
import { Copy, RotateCcw, Smartphone } from '@lucide/vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';
import { useCustomerService } from '@/composables/useCustomerService';
import { usePrototypeStore } from '@/stores/prototype';

const router = useRouter();
const store = usePrototypeStore();
const { copySupportId } = useCustomerService();
</script>

<template>
  <QjMobileShell :show-navigation="false">
    <QjPageHeader title="设备恢复" action="service" @back="router.back()" @service="router.push('/customer-service')" />
    <section class="device-help-card">
      <span><Smartphone :size="28" aria-hidden="true" /></span>
      <small>当前设备支持编号</small>
      <strong>{{ store.deviceSupportId }}</strong>
      <button type="button" @click="copySupportId(store.deviceSupportId)"><Copy :size="16" aria-hidden="true" />复制编号</button>
    </section>
    <section class="restore-steps">
      <h2>恢复方式</h2>
      <ol>
        <li><b>自动识别</b><span>再次安装后，App 会识别当前设备并同步已购买壁纸。</span></li>
        <li><b>重新下载</b><span>在“我的”中打开对应壁纸，不需要再次输入兑换码。</span></li>
        <li><b>联系客服</b><span>自动恢复失败时，把上面的设备支持编号发给客服。</span></li>
      </ol>
      <button type="button" class="restore-button" @click="router.push('/mine')"><RotateCcw :size="17" aria-hidden="true" />返回我的壁纸</button>
    </section>
  </QjMobileShell>
</template>

<style scoped>
.device-help-card {
  display: grid;
  margin-top: var(--qj-space-6);
  padding: var(--qj-space-6);
  justify-items: center;
  gap: var(--qj-space-2);
  border-radius: var(--qj-radius-card);
  background: var(--qj-color-accent-soft);
}

.device-help-card > span {
  display: grid;
  width: 58px;
  height: 58px;
  margin-bottom: var(--qj-space-2);
  place-items: center;
  border-radius: 20px;
  background: var(--qj-color-surface);
}

.device-help-card small {
  color: var(--qj-color-muted-ink);
}

.device-help-card strong {
  font-size: 22px;
}

.device-help-card button,
.restore-button {
  display: flex;
  min-height: 44px;
  padding: 0 16px;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: var(--qj-radius-pill);
  font-weight: var(--qj-font-weight-bold);
}

.device-help-card button {
  margin-top: var(--qj-space-3);
  color: var(--qj-color-inverse-ink);
  background: var(--qj-color-navigation);
}

.restore-steps {
  margin-top: var(--qj-space-7);
}

.restore-steps h2 {
  margin: 0 0 var(--qj-space-4);
  font-size: var(--qj-font-size-section-title);
}

.restore-steps ol {
  display: grid;
  margin: 0;
  padding: 0;
  gap: var(--qj-space-3);
  list-style: none;
  counter-reset: step;
}

.restore-steps li {
  display: grid;
  padding: var(--qj-space-4);
  grid-template-columns: 1fr;
  gap: 5px;
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-control);
  counter-increment: step;
}

.restore-steps li span {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
  line-height: var(--qj-line-height-caption);
}

.restore-button {
  width: 100%;
  margin-top: var(--qj-space-5);
  justify-content: center;
  color: var(--qj-color-ink);
  background: var(--qj-color-accent);
}
</style>
