import { defineStore } from 'pinia';
import { computed, ref } from 'vue';
import type { Entitlement, RedemptionResult } from '@/domain/device';
import type { PageMetadata } from '@/domain/catalog';
import { deviceRepository } from '@/repositories/http/deviceRepository';
import { deviceErrorMessage } from '@/repositories/http/h5DeviceProvider';
import { RedemptionCoordinator } from '@/services/redemptionCoordinator';

export const useDeviceStore = defineStore('device', () => {
  const items = ref<Entitlement[]>([]);
  const page = ref<PageMetadata>();
  const loaded = ref(false);
  const loading = ref(false);
  const errorMessage = ref('');
  const coordinator = new RedemptionCoordinator(window.localStorage);
  const pending = ref(coordinator.pending);
  let flight: Promise<void> | undefined;
  const hasMore = computed(() => Boolean(page.value && page.value.page < page.value.totalPages));
  const isOwned = (id: string) => items.value.some((item) => item.wallpaper.id === id);
  const syncPending = () => { pending.value = coordinator.pending; };
  const load = async (reset: boolean) => {
    if (flight) return flight;
    loading.value = true;
    errorMessage.value = '';
    flight = (async () => {
      try {
        const response = await deviceRepository.entitlements(reset ? 1 : (page.value?.page ?? 0) + 1);
        const combined = reset ? response.items : [...items.value, ...response.items];
        items.value = [...new Map(combined.map((item) => [item.wallpaper.id, item])).values()];
        page.value = response.page;
        loaded.value = true;
      } catch (error) {
        errorMessage.value = deviceErrorMessage(error);
        throw error;
      } finally { loading.value = false; flight = undefined; }
    })();
    return flight;
  };
  const refresh = () => load(true);
  const loadMore = () => load(false);
  const ensureOwned = async (id: string) => {
    if (!loaded.value) await refresh();
    while (!isOwned(id) && hasMore.value) await loadMore();
    return items.value.find((item) => item.wallpaper.id === id);
  };
  const applyResult = (result: RedemptionResult) => {
    if (result.entitlement && ['GRANTED', 'ALREADY_OWNED'].includes(result.result)) {
      items.value = [result.entitlement, ...items.value.filter((item) => item.wallpaper.id !== result.entitlement!.wallpaper.id)];
    }
    return result;
  };
  const redeem = async (id: string, code: string) => {
    try { return applyResult(await coordinator.redeem(id, code)); }
    finally { syncPending(); }
  };
  const confirmRedemption = async () => {
    try { return applyResult(await coordinator.confirm()); }
    finally { syncPending(); }
  };
  return { items, page, loaded, loading, errorMessage, pending, hasMore, isOwned, refresh, loadMore, ensureOwned, redeem, confirmRedemption, syncPending };
});
