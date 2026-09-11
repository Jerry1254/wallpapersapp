import { createPinia } from 'pinia';
import Vant from 'vant';
import 'vant/lib/index.css';
import { createApp } from 'vue';

import App from './App.vue';
import router from './router';
import './design-system/generated/tokens.css';
import './styles/base.css';
import './styles/prototype.css';

createApp(App).use(createPinia()).use(router).use(Vant).mount('#app');
