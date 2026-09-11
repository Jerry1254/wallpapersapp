import { defineStore } from 'pinia';
import { computed, ref } from 'vue';

import { ApiError, setCsrfToken, setUnauthorizedHandler } from '@/repositories/http/apiClient';
import { adminRepository, type AdminSession } from '@/repositories/http/adminRepository';

export const useAuthStore = defineStore('auth', () => {
  const session = ref<AdminSession>();
  const initialized = ref(false);
  let restoring: Promise<void> | undefined;

  const username = computed(() => session.value?.admin.username || '');
  const authenticated = computed(() => Boolean(session.value));
  const clear = () => {
    session.value = undefined;
    setCsrfToken('');
  };
  setUnauthorizedHandler(clear);

  const apply = (value: AdminSession) => {
    session.value = value;
    setCsrfToken(value.csrfToken);
  };

  const restore = async () => {
    if (initialized.value) return;
    if (restoring) return restoring;
    restoring = (async () => {
      try {
        apply(await adminRepository.currentSession());
      } catch (cause) {
        if (!(cause instanceof ApiError) || cause.status !== 401) throw cause;
        clear();
      } finally {
        initialized.value = true;
        restoring = undefined;
      }
    })();
    return restoring;
  };

  const login = async (account: string, password: string) => {
    apply(await adminRepository.login(account, password));
    initialized.value = true;
  };

  const logout = async () => {
    try {
      if (session.value) await adminRepository.logout();
    } finally {
      clear();
      initialized.value = true;
    }
  };

  return { username, authenticated, initialized, restore, login, logout };
});
