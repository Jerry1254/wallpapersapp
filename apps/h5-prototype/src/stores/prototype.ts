import { defineStore } from 'pinia';

type WallpaperTarget = 'home' | 'lock' | 'both';

interface PrototypeSnapshot {
  ownedIds: string[];
  downloadedIds: string[];
  appliedTargets: Record<string, WallpaperTarget>;
}

const storageKey = 'qingjing-wallpaper-prototype-v1';
const fallbackState: PrototypeSnapshot = {
  ownedIds: ['quiet-zen'],
  downloadedIds: ['quiet-zen'],
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
    ...loadSnapshot(),
    deviceSupportId: 'QJ-A7K9-2M4X'
  }),
  getters: {
    isOwned: (state) => (wallpaperId: string) => state.ownedIds.includes(wallpaperId),
    isDownloaded: (state) => (wallpaperId: string) => state.downloadedIds.includes(wallpaperId),
    isApplied: (state) => (wallpaperId: string) => Boolean(state.appliedTargets[wallpaperId])
  },
  actions: {
    persist() {
      const snapshot: PrototypeSnapshot = {
        ownedIds: this.ownedIds,
        downloadedIds: this.downloadedIds,
        appliedTargets: this.appliedTargets
      };
      window.localStorage.setItem(storageKey, JSON.stringify(snapshot));
    },
    grantWallpaper(wallpaperId: string) {
      if (!this.ownedIds.includes(wallpaperId)) this.ownedIds.push(wallpaperId);
      this.persist();
    },
    markDownloaded(wallpaperId: string) {
      this.grantWallpaper(wallpaperId);
      if (!this.downloadedIds.includes(wallpaperId)) this.downloadedIds.push(wallpaperId);
      this.persist();
    },
    markApplied(wallpaperId: string, target: WallpaperTarget) {
      this.appliedTargets[wallpaperId] = target;
      this.persist();
    }
  }
});
