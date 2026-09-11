import { createApp } from 'vue';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import { createPinia } from 'pinia';

import App from './App.vue';
import router from './router';
import './styles/index.css';

window.addEventListener('qingjing:session-expired', () => {
  if (router.currentRoute.value.name !== 'login') {
    void router.replace({ name: 'login', query: { redirect: router.currentRoute.value.fullPath } });
  }
});

createApp(App).use(createPinia()).use(router).use(ElementPlus).mount('#app');
