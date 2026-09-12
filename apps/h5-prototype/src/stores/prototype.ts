import { defineStore } from 'pinia';

type WallpaperTarget = 'home' | 'lock' | 'both';

interface PrototypeSnapshot {
  downloadedIds: string[];
  appliedTargets: Record<string, WallpaperTarget>;
}

const storageKey = 'qingjing-wallpaper-h5-demo-v2';
const fallbackState: PrototypeSnapshot = {
  downloadedIds: [],
  appliedTargets: {}
};

const loadSnapshot = (): PrototypeSnapshot => {
  try {
    const saved = window.localStorage.getItem(storageKey);
    return saved ? { ...fallbackState, ...JSON.parse(saved) as PrototypeSnapshot } : fallbackState;
  } catch {
    return fallbackState;
  }
};

export const usePrototypeStore = defineStore('prototype', {
  state: () => ({
    ...loadSnapshot()
  }),
  getters: {
    isDownloaded: (state) => (wallpaperId: string) => state.downloadedIds.includes(wallpaperId),
    isApplied: (state) => (wallpaperId: string) => Boolean(state.appliedTargets[wallpaperId])
  },
  actions: {
    persist() {
      const snapshot: PrototypeSnapshot = {
        downloadedIds: this.downloadedIds,
        appliedTargets: this.appliedTargets
      };
      window.localStorage.setItem(storageKey, JSON.stringify(snapshot));
    },
    markDownloaded(wallpaperId: string) {
      if (!this.downloadedIds.includes(wallpaperId)) this.downloadedIds.push(wallpaperId);
      this.persist();
    },
    markApplied(wallpaperId: string, target: WallpaperTarget) {
      this.appliedTargets[wallpaperId] = target;
      this.persist();
    }
  }
});
