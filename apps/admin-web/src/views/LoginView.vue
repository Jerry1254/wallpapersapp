<script setup lang="ts">
import { Lock, User } from '@element-plus/icons-vue';
import { reactive, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAuthStore } from '@/stores/auth';

const auth = useAuthStore();
const route = useRoute();
const router = useRouter();
const demoUsername = import.meta.env.VITE_DEMO_ADMIN_USERNAME || '';
const form = reactive({ username: demoUsername, password: '' });
const busy = ref(false);
const error = ref('');

const submit = async () => {
  if (busy.value) return;
  if (!form.username || !form.password) {
    error.value = '请输入管理员账号和密码';
    return;
  }
  busy.value = true;
  error.value = '';
  try {
    await auth.login(form.username.trim(), form.password);
    const target = typeof route.query.redirect === 'string' ? route.query.redirect : '/dashboard';
    await router.replace(target);
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '登录失败';
  } finally {
    busy.value = false;
  }
};
</script>

<template>
  <main class="login-page">
    <section class="login-visual">
      <div class="login-orbit login-orbit--one"></div>
      <div class="login-orbit login-orbit--two"></div>
      <div class="login-brand-mark">倾</div>
      <div>
        <p>QINGJING WALLPAPER</p>
        <h1>让每一张壁纸<br />都有清晰的交付路径</h1>
        <span>分类、资源、兑换码与设备权益统一管理</span>
      </div>
    </section>
    <section class="login-panel">
      <div class="login-card">
        <div class="login-mobile-brand"><span>倾</span><strong>倾境壁纸</strong></div>
        <h2>管理员登录</h2>
        <p>进入内容与兑换管理后台</p>
        <ElAlert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <ElForm label-position="top" @submit.prevent="submit">
          <ElFormItem label="管理员账号">
            <ElInput v-model="form.username" :prefix-icon="User" autocomplete="username" aria-label="管理员账号" />
          </ElFormItem>
          <ElFormItem label="密码">
            <ElInput v-model="form.password" :prefix-icon="Lock" type="password" show-password autocomplete="current-password" aria-label="密码" />
          </ElFormItem>
          <ElButton class="login-submit" type="primary" native-type="submit" :loading="busy">登录</ElButton>
        </ElForm>
        <div class="login-demo-note">
          <span>本地原型账号</span>
          <code>{{ demoUsername || '请配置 .env.local' }} / 本地环境变量中的密码</code>
        </div>
      </div>
    </section>
  </main>
</template>
