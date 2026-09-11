import { defineStore } from 'pinia';
import { ref } from 'vue';

const SESSION_KEY = 'qingjing-admin-session';
const demoUsername = import.meta.env.VITE_DEMO_ADMIN_USERNAME || '';
const demoPassword = import.meta.env.VITE_DEMO_ADMIN_PASSWORD || '';

export const useAuthStore = defineStore('auth', () => {
  const username = ref(window.sessionStorage.getItem(SESSION_KEY) || '');
  const authenticated = ref(Boolean(username.value));

  const login = async (account: string, password: string) => {
    await new Promise((resolve) => window.setTimeout(resolve, 450));
    if (!demoUsername || !demoPassword) throw new Error('本地演示账号尚未配置');
    if (account !== demoUsername || password !== demoPassword) throw new Error('账号或密码错误');
    username.value = account;
    authenticated.value = true;
    window.sessionStorage.setItem(SESSION_KEY, account);
  };

  const logout = () => {
    username.value = '';
    authenticated.value = false;
    window.sessionStorage.removeItem(SESSION_KEY);
  };

  return { username, authenticated, login, logout };
});
