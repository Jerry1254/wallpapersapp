<script setup lang="ts">
import {
  Flame,
  Flower2,
  Image,
  Layers3,
  Mountain,
  Sparkles
} from '@lucide/vue';
import QRCode from 'qrcode';
import { showImagePreview, showToast } from 'vant';
import { computed, onMounted, ref } from 'vue';

import cityImage from '@/assets/demo/city.svg';
import coastImage from '@/assets/demo/coast.svg';
import mountainImage from '@/assets/demo/mountain.svg';
import zenImage from '@/assets/demo/zen.svg';
import QjSettingTutorialCard from '@/components/QjSettingTutorialCard.vue';
import QjSettingTutorialPlayer from '@/components/QjSettingTutorialPlayer.vue';
import QjWallpaperTrial from '@/components/QjWallpaperTrial.vue';
import QjBottomNav from '@/design-system/components/QjBottomNav.vue';
import QjBrandHeader from '@/design-system/components/QjBrandHeader.vue';
import QjCategoryTile from '@/design-system/components/QjCategoryTile.vue';
import QjCustomerServiceCard from '@/design-system/components/QjCustomerServiceCard.vue';
import QjPrimaryAction from '@/design-system/components/QjPrimaryAction.vue';
import QjRedeemPanel from '@/design-system/components/QjRedeemPanel.vue';
import QjStatePanel from '@/design-system/components/QjStatePanel.vue';
import QjSubcategoryRail from '@/design-system/components/QjSubcategoryRail.vue';
import QjTutorialCard from '@/design-system/components/QjTutorialCard.vue';
import QjTypeBadge from '@/design-system/components/QjTypeBadge.vue';
import QjWallpaperCard from '@/design-system/components/QjWallpaperCard.vue';
import QjWallpaperHero from '@/design-system/components/QjWallpaperHero.vue';
import QjWallpaperTargetSheet from '@/design-system/components/QjWallpaperTargetSheet.vue';

const activeCategory = ref('推荐');
const activeSubcategory = ref('all');
const activeTab = ref<'home' | 'mine'>('home');
const redeemStatus = ref<'idle' | 'validating' | 'success' | 'error'>('idle');
const redeemCode = ref('QJ8FT-W2C6P-9MR4K-X7D3A');
const settingTarget = ref<'home' | 'lock' | 'both'>('both');
const settingSuccess = ref(false);
const tutorialVisible = ref(false);
const trialVisible = ref(false);
const qrCodeUrl = ref('');

const categories = [
  { label: '推荐', icon: Sparkles, tone: 'amber' as const },
  { label: '风景', icon: Mountain, tone: 'sage' as const },
  { label: '禅意', icon: Flower2, tone: 'blush' as const },
  { label: '角色', icon: Flame, tone: 'stone' as const },
  { label: '静态', icon: Image, tone: 'stone' as const }
];

const subcategories = [
  { id: 'all', label: '全部' },
  { id: 'nature', label: '自然风光' },
  { id: 'city', label: '城市夜景' },
  { id: 'zen', label: '国风禅意' },
  { id: 'character', label: '原创角色' }
];

const colors = [
  { name: '主文字', token: 'ink', value: '#191817' },
  { name: '品牌强调', token: 'accent', value: '#F4A91E' },
  { name: '暖粉辅助', token: 'blush', value: '#DE9C95' },
  { name: '成功', token: 'success', value: '#287A55' },
  { name: '页面背景', token: 'background', value: '#F4F3F0' },
  { name: '内容面', token: 'surface', value: '#FFFFFF' }
];

const redeemLabel = computed(() => {
  if (redeemStatus.value === 'success') return '下载壁纸';
  if (redeemStatus.value === 'validating') return '正在验证';
  return '验证并兑换';
});

const simulateRedeem = () => {
  redeemStatus.value = 'validating';
  window.setTimeout(() => {
    redeemStatus.value = 'success';
  }, 700);
};

const simulateSetting = () => {
  if (settingSuccess.value) {
    settingSuccess.value = false;
    return;
  }
  settingSuccess.value = true;
  showFeedback('壁纸设置成功');
};

const showFeedback = (message: string) => {
  showToast({ message, position: 'bottom' });
};

const previewQrCode = () => {
  if (!qrCodeUrl.value) return;
  showImagePreview({
    images: [qrCodeUrl.value],
    closeable: true
  });
};

const copyWechat = async () => {
  try {
    await navigator.clipboard.writeText('qingjing_service');
  } catch {
    const input = document.createElement('textarea');
    input.value = 'qingjing_service';
    document.body.append(input);
    input.select();
    document.execCommand('copy');
    input.remove();
  }
  showFeedback('客服微信已复制');
};

onMounted(async () => {
  qrCodeUrl.value = await QRCode.toDataURL('倾境壁纸客服微信：qingjing_service', {
    width: 420,
    margin: 2,
    color: {
      dark: '#191817',
      light: '#FFFFFF'
    }
  });
});
</script>

<template>
  <main class="design-page qj-design-system">
    <header class="document-header">
      <div>
        <span class="document-kicker">倾境壁纸</span>
        <h1>H5 设计系统</h1>
        <p>移动端视觉 Token、页面骨架与核心组件规范</p>
      </div>
      <div class="document-meta">
        <span>v0.1</span>
        <span>390 基准宽度</span>
        <span>H5 / Flutter</span>
      </div>
    </header>

    <section class="spec-section">
      <div class="section-heading">
        <span>01</span>
        <div>
          <h2>视觉原则</h2>
          <p>以壁纸内容为主体，界面保持安静、克制和高对比。</p>
        </div>
      </div>
      <div class="principle-grid">
        <article>
          <strong>大图优先</strong>
          <p>卡片把空间留给壁纸，文字只提供名称、类型和状态。</p>
        </article>
        <article>
          <strong>黑白骨架</strong>
          <p>白色内容面搭配深黑导航与按钮，保证不同壁纸都能融入。</p>
        </article>
        <article>
          <strong>暖色点睛</strong>
          <p>暖黄色只用于选中、关键状态和少量图标，不大面积铺色。</p>
        </article>
        <article>
          <strong>圆润克制</strong>
          <p>媒体 22px、卡片 24px、胶囊按钮，不使用强玻璃和炫光。</p>
        </article>
      </div>
    </section>

    <section class="spec-section">
      <div class="section-heading">
        <span>02</span>
        <div>
          <h2>基础 Token</h2>
          <p>唯一数据源位于 packages/design-tokens，并生成 CSS、TypeScript 和 Dart。</p>
        </div>
      </div>
      <div class="token-layout">
        <div class="color-grid">
          <article v-for="color in colors" :key="color.token">
            <span class="color-swatch" :style="{ background: color.value }"></span>
            <strong>{{ color.name }}</strong>
            <code>{{ color.value }}</code>
          </article>
        </div>
        <div class="type-card">
          <span class="type-label">Page title · 28 / 800</span>
          <strong class="type-title">倾境壁纸</strong>
          <span class="type-label">Section · 20 / 700</span>
          <strong class="type-section">不同风格，随心切换</strong>
          <span class="type-label">Body · 14 / 400</span>
          <p>中文使用系统字体，避免外部字体加载影响首屏和隐私。</p>
        </div>
      </div>
    </section>

    <section class="spec-section">
      <div class="section-heading">
        <span>03</span>
        <div>
          <h2>页面骨架</h2>
          <p>首页与详情沿用参考图的大圆角卡片、留白和悬浮黑色导航。</p>
        </div>
      </div>

      <div class="phone-grid">
        <article class="phone-frame">
          <div class="phone-label">
            <strong>首页</strong>
            <span>金刚区 + 二级分类 + 列表</span>
          </div>
          <div class="phone-screen">
            <QjBrandHeader @service="showFeedback('打开微信客服')" />
            <section class="phone-section">
              <div class="phone-section__title">
                <h3>壁纸分类</h3>
              </div>
              <div class="category-grid">
                <QjCategoryTile
                  v-for="category in categories"
                  :key="category.label"
                  :label="category.label"
                  :icon="category.icon"
                  :tone="category.tone"
                  :active="activeCategory === category.label"
                  @select="activeCategory = category.label"
                />
              </div>
            </section>
            <section class="phone-section">
              <div class="phone-section__title">
                <h3>精选壁纸</h3>
                <span>查看全部</span>
              </div>
              <QjSubcategoryRail v-model="activeSubcategory" :items="subcategories" />
              <div class="wallpaper-grid">
                <QjWallpaperCard :src="mountainImage" title="暮色山峦" type="4D动态" @select="showFeedback('打开暮色山峦')" />
                <QjWallpaperCard :src="zenImage" title="静观" type="动态" />
                <QjWallpaperCard :src="cityImage" title="城市余晖" type="4D动态" />
                <QjWallpaperCard :src="coastImage" title="深海呼吸" type="静态" />
              </div>
            </section>
            <div class="phone-nav">
              <QjBottomNav v-model="activeTab" />
            </div>
          </div>
        </article>

        <article class="phone-frame">
          <div class="phone-label">
            <strong>壁纸详情</strong>
            <span>标题 + 1:2 预览 + 悬浮主操作</span>
          </div>
          <div class="phone-screen phone-screen--detail">
            <QjWallpaperHero
              :src="mountainImage"
              title="暮色山峦"
              @tutorial="tutorialVisible = true"
              @trial="trialVisible = true"
              @action="redeemStatus = 'idle'"
            />
          </div>
        </article>
      </div>
    </section>

    <section class="spec-section">
      <div class="section-heading">
        <span>04</span>
        <div>
          <h2>业务组件</h2>
          <p>组件覆盖兑换、教程、客服和通用状态，正式页面只组合组件。</p>
        </div>
      </div>

      <div class="component-grid">
        <article class="component-card component-card--wide">
          <div class="component-title">
            <strong>金刚区与二级分类</strong>
            <span>一级分类固定可见；二级分类横向滚动。</span>
          </div>
          <div class="category-grid category-grid--spec">
            <QjCategoryTile
              v-for="category in categories"
              :key="'spec-' + category.label"
              :label="category.label"
              :icon="category.icon"
              :tone="category.tone"
              :active="activeCategory === category.label"
              @select="activeCategory = category.label"
            />
          </div>
          <QjSubcategoryRail v-model="activeSubcategory" :items="subcategories" />
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>壁纸卡片</strong>
            <span>列表只显示封面、类型和名称。</span>
          </div>
          <div class="component-card__pair">
            <QjWallpaperCard :src="cityImage" title="城市余晖" type="4D动态" />
            <QjWallpaperCard :src="coastImage" title="深海呼吸" type="静态" />
          </div>
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>标签与主按钮</strong>
            <span>黑色为默认主操作，黄色只表示当前选中。</span>
          </div>
          <div class="badge-row">
            <QjTypeBadge label="4D动态" />
            <QjTypeBadge label="动态" tone="amber" />
            <QjTypeBadge label="已获得" tone="success" />
            <QjTypeBadge label="Android" tone="light" />
          </div>
          <div class="button-stack">
            <QjPrimaryAction label="下载壁纸" />
            <QjPrimaryAction label="设置壁纸" tone="accent" />
            <QjPrimaryAction label="正在下载" loading />
            <QjPrimaryAction label="当前设备不支持" disabled />
          </div>
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>兑换码面板</strong>
            <span>底部弹出，验证成功后切换为下载。</span>
          </div>
          <QjRedeemPanel v-model="redeemCode" :status="redeemStatus" @redeem="simulateRedeem" />
          <small class="interactive-note">当前演示状态：{{ redeemLabel }}</small>
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>Android · 设置位置</strong>
            <span>只展示系统返回的可用目标；确认后显示成功态。</span>
          </div>
          <QjWallpaperTargetSheet
            v-model="settingTarget"
            :success="settingSuccess"
            @confirm="simulateSetting"
          />
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>我的 · 教程入口</strong>
            <span>放在已购买壁纸列表之前。</span>
          </div>
          <QjTutorialCard @open="showFeedback('打开壁纸设置教程')" />
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>我的 · 微信客服</strong>
            <span>参考考试 H5：二维码预览、微信号复制、服务时间。</span>
          </div>
          <QjCustomerServiceCard
            wechat-id="qingjing_service"
            :qr-code-url="qrCodeUrl"
            @copy="copyWechat"
            @preview="previewQrCode"
            @save="showFeedback('二维码已保存')"
          />
          <QjSettingTutorialCard class="customer-tutorial-entry" @open="tutorialVisible = true" />
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>空状态</strong>
            <span>不使用装饰插画抢占壁纸内容视觉。</span>
          </div>
          <QjStatePanel description="兑换后会显示在这里" @action="showFeedback('返回首页')" />
        </article>

        <article class="component-card">
          <div class="component-title">
            <strong>底部导航</strong>
            <span>全站固定两项：首页、我的。</span>
          </div>
          <QjBottomNav v-model="activeTab" />
        </article>
      </div>
    </section>

    <section class="spec-section rules-section">
      <div class="section-heading">
        <span>05</span>
        <div>
          <h2>组件规则</h2>
          <p>开发 H5 与 Flutter 页面时共同遵守。</p>
        </div>
      </div>
      <div class="rules-grid">
        <article>
          <Layers3 :size="22" aria-hidden="true" />
          <strong>层级</strong>
          <p>一个页面只有一个主按钮；壁纸图片始终高于装饰视觉。</p>
        </article>
        <article>
          <Image :size="22" aria-hidden="true" />
          <strong>图片</strong>
          <p>列表使用 3:4.15，详情接近满宽；加载失败必须有占位状态。</p>
        </article>
        <article>
          <Sparkles :size="22" aria-hidden="true" />
          <strong>动效</strong>
          <p>界面反馈 140–220ms；壁纸预览动效与界面过渡分离。</p>
        </article>
        <article>
          <Mountain :size="22" aria-hidden="true" />
          <strong>适配</strong>
          <p>375px 起无横向溢出；触控区域不小于 44px；支持减少动画。</p>
        </article>
      </div>
    </section>
  </main>
  <QjSettingTutorialPlayer v-model="tutorialVisible" />
  <QjWallpaperTrial v-model="trialVisible" :src="mountainImage" title="暮色山峦" @expired="showFeedback('2 分钟试用已结束')" />
</template>

<style scoped>
.design-page {
  width: min(1180px, calc(100% - 40px));
  margin: 0 auto;
  padding: 76px 0 110px;
}

.document-header {
  display: flex;
  min-height: 236px;
  padding: 40px 44px;
  align-items: flex-end;
  justify-content: space-between;
  gap: 30px;
  border-radius: 36px;
  background:
    radial-gradient(circle at 84% 16%, rgba(222, 156, 149, 0.42), transparent 27%),
    radial-gradient(circle at 73% 84%, rgba(244, 169, 30, 0.28), transparent 24%),
    var(--qj-color-surface);
  box-shadow: var(--qj-shadow-soft);
}

.document-kicker {
  display: inline-flex;
  min-height: 30px;
  padding: 0 13px;
  align-items: center;
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-bold);
  background: var(--qj-color-accent);
}

.document-header h1 {
  margin: 18px 0 0;
  font-size: clamp(38px, 6vw, 68px);
  line-height: 1;
  letter-spacing: -0.065em;
}

.document-header p {
  margin: 16px 0 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-body-large);
}

.document-meta {
  display: flex;
  flex-wrap: wrap;
  justify-content: flex-end;
  gap: 8px;
}

.document-meta span {
  display: inline-flex;
  min-height: 34px;
  padding: 0 13px;
  align-items: center;
  border: 1px solid var(--qj-color-outline);
  border-radius: var(--qj-radius-pill);
  color: var(--qj-color-ink-soft);
  font-size: var(--qj-font-size-caption);
  background: rgba(255, 255, 255, 0.82);
}

.spec-section {
  margin-top: 84px;
}

.section-heading {
  display: grid;
  margin-bottom: 28px;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 16px;
}

.section-heading > span {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 14px;
  color: var(--qj-color-ink);
  font-size: var(--qj-font-size-caption);
  font-weight: var(--qj-font-weight-heavy);
  background: var(--qj-color-accent);
}

.section-heading h2 {
  margin: 0;
  font-size: var(--qj-font-size-page-title);
  letter-spacing: -0.035em;
}

.section-heading p {
  margin: 6px 0 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-body);
}

.principle-grid,
.rules-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 14px;
}

.principle-grid article,
.rules-grid article,
.type-card {
  padding: 22px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: var(--qj-radius-card);
  background: rgba(255, 255, 255, 0.74);
}

.principle-grid strong,
.rules-grid strong {
  display: block;
  font-size: var(--qj-font-size-card-title);
}

.principle-grid p,
.rules-grid p {
  margin: 9px 0 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption-large);
  line-height: var(--qj-line-height-body);
}

.token-layout {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.7fr);
  gap: 18px;
}

.color-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}

.color-grid article {
  display: grid;
  padding: 12px;
  grid-template-columns: 52px minmax(0, 1fr);
  align-items: center;
  gap: 3px 12px;
  border-radius: 18px;
  background: var(--qj-color-surface);
}

.color-swatch {
  width: 52px;
  height: 52px;
  grid-row: 1 / 3;
  border: 1px solid rgba(25, 24, 23, 0.08);
  border-radius: 16px;
}

.color-grid strong {
  align-self: end;
  font-size: var(--qj-font-size-caption-large);
}

.color-grid code {
  align-self: start;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-micro);
}

.type-card {
  display: grid;
  align-content: center;
  gap: 8px;
  background: var(--qj-color-surface);
}

.type-label {
  color: var(--qj-color-subtle-ink);
  font-size: var(--qj-font-size-micro);
  text-transform: uppercase;
}

.type-title {
  margin-bottom: 12px;
  font-size: var(--qj-font-size-page-title);
  font-weight: var(--qj-font-weight-heavy);
  letter-spacing: -0.04em;
}

.type-section {
  font-size: var(--qj-font-size-section-title);
}

.type-card p {
  margin: 0;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-body);
  line-height: var(--qj-line-height-body);
}

.phone-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(320px, 430px));
  justify-content: center;
  gap: 44px;
}

.phone-frame {
  margin: 0;
}

.phone-label {
  display: flex;
  margin-bottom: 12px;
  justify-content: space-between;
  align-items: baseline;
  gap: 14px;
}

.phone-label strong {
  font-size: var(--qj-font-size-body);
}

.phone-label span {
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}

.phone-screen {
  position: relative;
  height: 850px;
  padding: 20px var(--qj-size-page-gutter) 104px;
  overflow: hidden;
  border: 8px solid var(--qj-color-surface);
  border-radius: 42px;
  background: var(--qj-color-surface);
  box-shadow: var(--qj-shadow-card);
}

.phone-screen--detail {
  height: 880px;
  padding-bottom: var(--qj-space-5);
}

.phone-section {
  margin-top: 24px;
}

.phone-section__title {
  display: flex;
  margin-bottom: 15px;
  align-items: center;
  justify-content: space-between;
}

.phone-section__title h3 {
  margin: 0;
  font-size: var(--qj-font-size-section-title);
}

.phone-section__title span {
  color: var(--qj-color-subtle-ink);
  font-size: var(--qj-font-size-caption);
}

.category-grid {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 8px;
}

.wallpaper-grid {
  display: grid;
  margin-top: 18px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 24px 13px;
}

.phone-nav {
  position: absolute;
  right: 20px;
  bottom: 20px;
  left: 20px;
}

.component-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 18px;
}

.component-card {
  min-width: 0;
  padding: 24px;
  border-radius: 30px;
  background: rgba(255, 255, 255, 0.78);
}

.component-card--wide {
  grid-column: 1 / -1;
}

.component-title {
  margin-bottom: 20px;
}

.component-title strong {
  display: block;
  font-size: var(--qj-font-size-card-title);
}

.component-title span {
  display: block;
  margin-top: 5px;
  color: var(--qj-color-muted-ink);
  font-size: var(--qj-font-size-caption);
}

.category-grid--spec {
  max-width: 480px;
  margin-bottom: 22px;
}

.component-card__pair {
  display: grid;
  max-width: 350px;
  margin: 0 auto;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.badge-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.button-stack {
  display: grid;
  margin-top: 20px;
  gap: 10px;
}

.interactive-note {
  display: block;
  margin-top: 12px;
  color: var(--qj-color-muted-ink);
  text-align: center;
}

.customer-tutorial-entry {
  margin-top: var(--qj-space-4);
}

.rules-grid article {
  background: var(--qj-color-surface);
}

.rules-grid svg {
  margin-bottom: 18px;
  color: var(--qj-color-accent-strong);
}

@media (max-width: 900px) {
  .principle-grid,
  .rules-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .token-layout,
  .component-grid {
    grid-template-columns: 1fr;
  }

  .phone-grid {
    grid-template-columns: minmax(0, 430px);
  }

  .component-card--wide {
    grid-column: auto;
  }
}

@media (max-width: 560px) {
  .design-page {
    width: 100%;
    padding: 0 0 72px;
  }

  .document-header {
    min-height: 300px;
    padding: 34px 22px;
    align-items: flex-start;
    flex-direction: column;
    justify-content: flex-end;
    border-radius: 0 0 32px 32px;
  }

  .document-meta {
    justify-content: flex-start;
  }

  .spec-section {
    margin-top: 64px;
    padding-inline: 16px;
  }

  .principle-grid,
  .rules-grid,
  .color-grid {
    grid-template-columns: 1fr 1fr;
  }

  .principle-grid article,
  .rules-grid article {
    padding: 18px;
  }

  .phone-label {
    padding-inline: 6px;
  }

  .phone-screen {
    height: 820px;
    border-width: 4px;
    border-radius: 34px;
  }

  .component-card {
    padding: 18px;
    border-radius: 24px;
  }
}

@media (max-width: 380px) {
  .principle-grid,
  .rules-grid,
  .color-grid {
    grid-template-columns: 1fr;
  }

  .document-header h1 {
    font-size: 42px;
  }

  .category-grid {
    gap: 3px;
  }
}
</style>
