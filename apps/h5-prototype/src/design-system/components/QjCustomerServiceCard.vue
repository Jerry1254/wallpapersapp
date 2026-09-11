<script setup lang="ts">
import { Clock3, Copy, Download, MessageCircleMore, QrCode } from '@lucide/vue';

defineProps<{
  wechatId: string;
  qrCodeUrl?: string;
  hours?: string;
}>();

defineEmits<{
  copy: [];
  preview: [];
  save: [];
}>();
</script>

<template>
  <section class="qj-customer-card">
    <header>
      <span><MessageCircleMore :size="22" aria-hidden="true" /></span>
      <div>
        <h3>微信客服</h3>
        <p>添加客服获取兑换码或处理设备恢复</p>
      </div>
    </header>
    <button type="button" class="qj-customer-card__qr" @click="$emit('preview')">
      <img v-if="qrCodeUrl" :src="qrCodeUrl" alt="倾境壁纸客服微信二维码" />
      <QrCode v-else :size="68" :stroke-width="1.25" aria-hidden="true" />
      <span>点击查看二维码</span>
    </button>
    <button type="button" class="qj-customer-card__save" @click="$emit('save')">
      <Download :size="16" aria-hidden="true" />
      保存二维码
    </button>
    <div class="qj-customer-card__row">
      <div>
        <span>微信号</span>
        <strong>{{ wechatId }}</strong>
      </div>
      <button type="button" @click="$emit('copy')">
        <Copy :size="15" aria-hidden="true" />
        复制
      </button>
    </div>
    <div class="qj-customer-card__hours">
      <Clock3 :size="16" aria-hidden="true" />
      服务时间 {{ hours || '09:00–21:00' }}
    </div>
  </section>
</template>

<style scoped>
.qj-customer-card {
  padding: var(--qj-space-5);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
}

.qj-customer-card header {
  display: flex;
  align-items: center;
  gap: var(--qj-space-3);
}

.qj-customer-card header > span {
  display: grid;
  width: 44px;
  height: 44px;
  flex: 0 0 auto;
  place-items: center;
  border-radius: 15px;
  color: var(--qj-color-accent-strong);
  background: var(--qj-color-accent-soft);
}

.qj-customer-card h3,
.qj-customer-card p {
  margin: 0;
}

.qj-customer-card h3 {
  font-size: var(--qj-font-size-card-title);
}

.qj-customer-card p {
  margin-top: 3px;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}

.qj-customer-card__qr {
  display: grid;
  width: 164px;
  min-height: 184px;
  margin: var(--qj-space-5) auto;
  padding: 14px;
  place-items: center;
  gap: 8px;
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-media);
  color: var(--qj-color-ink);
  background: var(--qj-color-surface-muted);
  cursor: pointer;
}

.qj-customer-card__qr img {
  display: block;
  width: 132px;
  height: 132px;
  object-fit: contain;
}

.qj-customer-card__qr span {
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-semibold);
}

.qj-customer-card__save {
  display: flex;
  min-height: 42px;
  margin: calc(var(--qj-space-3) * -1) auto var(--qj-space-5);
  padding: 0 15px;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--qj-color-outline-strong);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink-soft);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-semibold);
  background: var(--qj-color-surface);
  cursor: pointer;
}

.qj-customer-card__row {
  display: flex;
  min-height: 58px;
  padding: 0 var(--qj-space-4);
  align-items: center;
  justify-content: space-between;
  border-radius: var(--qj-radius-control);
  background: var(--qj-color-surface-muted);
}

.qj-customer-card__row div {
  display: grid;
  gap: 2px;
}

.qj-customer-card__row span {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-micro);
}

.qj-customer-card__row strong {
  font-size: var(--qj-font-size-body);
}

.qj-customer-card__row button {
  display: inline-flex;
  min-height: 38px;
  padding: 0 13px;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-inverse-ink);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-bold);
  background: var(--qj-color-navigation);
  cursor: pointer;
}

.qj-customer-card__hours {
  display: flex;
  margin-top: var(--qj-space-3);
  align-items: center;
  justify-content: center;
  gap: 6px;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}
</style>
