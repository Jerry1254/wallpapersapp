<script setup lang="ts">
import { Layers3, PlaySquare, Send, ShieldCheck, Smartphone } from '@lucide/vue';
import type { Component } from 'vue';
import { useRouter } from 'vue-router';

import QjMobileShell from '@/components/QjMobileShell.vue';
import QjPageHeader from '@/components/QjPageHeader.vue';

const router = useRouter();

const lessons: Array<{ title: string; description: string; icon: Component; points: string[] }> = [
  {
    title: '静态壁纸',
    description: '准备适合手机竖屏显示的完整画面。',
    icon: Smartphone,
    points: ['推荐比例 1:2', '主体避开顶部时间和底部手势区域', '导出 JPG、PNG 或 WebP']
  },
  {
    title: 'Android 4D 分层',
    description: '把前景与背景拆开，给手机姿态变化留出移动范围。',
    icon: Layers3,
    points: ['至少包含背景层和透明前景层', '背景四周需要补全安全区域', '前景边缘使用透明 PNG']
  },
  {
    title: '动态效果素材',
    description: '使用短时、无声、可循环的演示素材。',
    icon: PlaySquare,
    points: ['建议 6–12 秒', '首尾画面衔接自然', '避免快速闪烁和强烈位移']
  },
  {
    title: '提交给客服',
    description: '整理原图、分层文件和效果说明后发送。',
    icon: Send,
    points: ['文件名写明壁纸名称', '说明希望支持的平台', '保留原始设计文件']
  },
  {
    title: '内容授权',
    description: '只提交自己拥有使用权的图片和角色素材。',
    icon: ShieldCheck,
    points: ['不得上传盗版影视或动漫素材', '人物照片需要获得肖像授权', '保留素材来源和授权记录']
  }
];
</script>

<template>
  <QjMobileShell :show-navigation="false">
    <QjPageHeader title="壁纸设计教程" action="service" @back="router.back()" @service="router.push('/customer-service')" />
    <div class="tutorial-list">
      <article v-for="(lesson, index) in lessons" :key="lesson.title" class="tutorial-step">
        <span class="tutorial-step__number">{{ String(index + 1).padStart(2, '0') }}</span>
        <span class="tutorial-step__icon"><component :is="lesson.icon" :size="24" :stroke-width="1.8" aria-hidden="true" /></span>
        <div>
          <h2>{{ lesson.title }}</h2>
          <p>{{ lesson.description }}</p>
          <ul>
            <li v-for="point in lesson.points" :key="point">{{ point }}</li>
          </ul>
        </div>
      </article>
    </div>
  </QjMobileShell>
</template>

<style scoped>
.tutorial-list {
  display: grid;
  margin-top: var(--qj-space-6);
  gap: var(--qj-space-4);
}

.tutorial-step {
  position: relative;
  display: grid;
  padding: var(--qj-space-5);
  grid-template-columns: 50px minmax(0, 1fr);
  gap: var(--qj-space-4);
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-card);
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
}

.tutorial-step__number {
  position: absolute;
  top: 14px;
  right: 16px;
  color: var(--qj-color-outline-strong);
  font-size: 22px;
  font-weight: var(--qj-font-weight-heavy);
}

.tutorial-step__icon {
  display: grid;
  width: 50px;
  height: 50px;
  place-items: center;
  border-radius: 17px;
  color: var(--qj-color-ink);
  background: var(--qj-color-accent);
}

.tutorial-step h2 {
  margin: 2px 36px 5px 0;
  font-size: var(--qj-font-size-card-title);
}

.tutorial-step p {
  margin: 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
  line-height: var(--qj-line-height-caption);
}

.tutorial-step ul {
  margin: var(--qj-space-3) 0 0;
  padding-left: 18px;
  color: var(--qj-color-ink-soft);
  font-size: var(--qj-font-size-caption-large);
  line-height: 1.8;
}
</style>
