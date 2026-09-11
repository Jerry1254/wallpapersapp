import city from '@/assets/wallpapers/city.svg';
import coast from '@/assets/wallpapers/coast.svg';
import mountain from '@/assets/wallpapers/mountain.svg';
import zen from '@/assets/wallpapers/zen.svg';
import type {
  Category,
  CodeBatch,
  DeviceEntitlement,
  RedemptionRecord,
  Wallpaper
} from '@/domain/admin';

const STORAGE_KEY = 'qingjing-admin-data-v2';

interface AdminData {
  categories: Category[];
  wallpapers: Wallpaper[];
  batches: CodeBatch[];
  redemptions: RedemptionRecord[];
  devices: DeviceEntitlement[];
}

const file = (name: string, size: number, mime: string) => ({ name, size, mime });

const fixtures: AdminData = {
  categories: [
    { id: 'recommend', name: '推荐', parentId: null, iconUrl: mountain, sort: 10, wallpaperCount: 4 },
    { id: 'popular', name: '热门精选', parentId: 'recommend', iconUrl: '', sort: 10, wallpaperCount: 2 },
    { id: 'new', name: '最近上新', parentId: 'recommend', iconUrl: '', sort: 20, wallpaperCount: 2 },
    { id: 'depth', name: '4D 景深', parentId: 'recommend', iconUrl: '', sort: 30, wallpaperCount: 2 },
    { id: 'scenery', name: '风景', parentId: null, iconUrl: coast, sort: 20, wallpaperCount: 3 },
    { id: 'nature', name: '自然风光', parentId: 'scenery', iconUrl: '', sort: 10, wallpaperCount: 2 },
    { id: 'city', name: '城市夜景', parentId: 'scenery', iconUrl: '', sort: 20, wallpaperCount: 1 },
    { id: 'sea', name: '海洋天空', parentId: 'scenery', iconUrl: '', sort: 30, wallpaperCount: 2 },
    { id: 'zen', name: '禅意', parentId: null, iconUrl: zen, sort: 30, wallpaperCount: 2 },
    { id: 'buddha', name: '佛系禅境', parentId: 'zen', iconUrl: '', sort: 10, wallpaperCount: 1 },
    { id: 'ink', name: '水墨国风', parentId: 'zen', iconUrl: '', sort: 20, wallpaperCount: 0 },
    { id: 'lotus', name: '莲花静心', parentId: 'zen', iconUrl: '', sort: 30, wallpaperCount: 1 },
    { id: 'character', name: '角色', parentId: null, iconUrl: city, sort: 40, wallpaperCount: 2 },
    { id: 'armor', name: '未来机甲', parentId: 'character', iconUrl: '', sort: 10, wallpaperCount: 2 },
    { id: 'fantasy', name: '幻想角色', parentId: 'character', iconUrl: '', sort: 20, wallpaperCount: 1 },
    { id: 'anime', name: '动漫插画', parentId: 'character', iconUrl: '', sort: 30, wallpaperCount: 0 },
    { id: 'static', name: '静态', parentId: null, iconUrl: coast, sort: 50, wallpaperCount: 2 },
    { id: 'minimal', name: '极简色彩', parentId: 'static', iconUrl: '', sort: 10, wallpaperCount: 1 },
    { id: 'illustration', name: '艺术插画', parentId: 'static', iconUrl: '', sort: 20, wallpaperCount: 0 },
    { id: 'photo', name: '摄影作品', parentId: 'static', iconUrl: '', sort: 30, wallpaperCount: 1 }
  ],
  wallpapers: [
    {
      id: 'wp-1001', title: '暮色山峦', categoryId: 'scenery', subcategoryId: 'nature', kind: 'four_d',
      platforms: ['android'], status: 'published', sort: 10, coverUrl: mountain, downloads: 328,
      updatedAt: '2026-09-10 14:20',
      resources: {
        cover: file('mountain-cover.webp', 438272, 'image/webp'),
        backgroundLayer: file('mountain-background.webp', 1845120, 'image/webp'),
        foregroundLayer: file('mountain-foreground.png', 1218560, 'image/png'),
        depthConfig: file('mountain-depth.json', 2860, 'application/json')
      }
    },
    {
      id: 'wp-1002', title: '静观', categoryId: 'zen', subcategoryId: 'buddha', kind: 'dynamic',
      platforms: ['android', 'ios'], status: 'published', sort: 20, coverUrl: zen, downloads: 246,
      updatedAt: '2026-09-10 13:48',
      resources: {
        cover: file('zen-cover.webp', 396288, 'image/webp'),
        androidVideo: file('zen-android.mp4', 6815744, 'video/mp4'),
        iosMov: file('zen-live.mov', 7340032, 'video/quicktime'),
        iosPhoto: file('zen-live.heic', 1048576, 'image/heic')
      }
    },
    {
      id: 'wp-1003', title: '城市余晖', categoryId: 'character', subcategoryId: 'armor', kind: 'four_d',
      platforms: ['android'], status: 'draft', sort: 30, coverUrl: city, downloads: 0,
      updatedAt: '2026-09-10 11:06',
      resources: {
        cover: file('city-cover.webp', 476160, 'image/webp'),
        backgroundLayer: file('city-background.webp', 1945600, 'image/webp'),
        foregroundLayer: file('city-foreground.png', 1572864, 'image/png')
      }
    },
    {
      id: 'wp-1004', title: '深海呼吸', categoryId: 'static', subcategoryId: 'photo', kind: 'static',
      platforms: ['android', 'ios', 'harmony'], status: 'offline', sort: 40, coverUrl: coast, downloads: 186,
      updatedAt: '2026-09-09 18:32',
      resources: {
        cover: file('coast-cover.webp', 412672, 'image/webp'),
        staticImage: file('coast-original.webp', 3145728, 'image/webp')
      }
    }
  ],
  batches: [
    { id: 'batch-001', name: '9月直播首批', codeCount: 100, quotaPerCode: 3, redeemed: 76, createdAt: '2026-09-09 20:18' },
    { id: 'batch-002', name: '客服补发批次', codeCount: 30, quotaPerCode: 1, redeemed: 12, createdAt: '2026-09-10 09:42' },
    { id: 'batch-003', name: '内部测试', codeCount: 10, quotaPerCode: 2, redeemed: 8, createdAt: '2026-09-08 16:30' }
  ],
  redemptions: [
    { id: 'rd-1', codeMask: 'QJ8F****X7D3', wallpaperTitle: '暮色山峦', deviceMask: 'AND-****-2M4X', result: 'success', createdAt: '2026-09-10 15:42' },
    { id: 'rd-2', codeMask: 'QJ3A****P8K2', wallpaperTitle: '静观', deviceMask: 'AND-****-8K1Q', result: 'success', createdAt: '2026-09-10 14:26' },
    { id: 'rd-3', codeMask: 'QJ7K****N2H6', wallpaperTitle: '城市余晖', deviceMask: 'AND-****-4A9M', result: 'failed', createdAt: '2026-09-10 13:07' }
  ],
  devices: [
    { id: 'dev-1', deviceMask: 'AND-****-2M4X', platform: 'android', wallpaperCount: 3, lastActiveAt: '2026-09-10 15:46', status: 'active' },
    { id: 'dev-2', deviceMask: 'AND-****-8K1Q', platform: 'android', wallpaperCount: 1, lastActiveAt: '2026-09-10 14:29', status: 'active' },
    { id: 'dev-3', deviceMask: 'IOS-****-9X7P', platform: 'ios', wallpaperCount: 2, lastActiveAt: '2026-09-09 21:18', status: 'active' }
  ]
};

const wait = () => new Promise((resolve) => window.setTimeout(resolve, 180));

const read = (): AdminData => {
  const raw = window.localStorage.getItem(STORAGE_KEY);
  if (!raw) return structuredClone(fixtures);
  try {
    return JSON.parse(raw) as AdminData;
  } catch {
    return structuredClone(fixtures);
  }
};

const write = (data: AdminData) => window.localStorage.setItem(STORAGE_KEY, JSON.stringify(data));

const timestamp = () => new Intl.DateTimeFormat('zh-CN', {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false
}).format(new Date()).replaceAll('/', '-');

const randomCode = () => {
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const segment = () => Array.from(crypto.getRandomValues(new Uint8Array(4)), (value) => chars[value % chars.length]).join('');
  return `QJ${segment()}-${segment()}-${segment()}-${segment()}`;
};

export const adminRepository = {
  async dashboard() {
    await wait();
    return read();
  },
  async categories() {
    await wait();
    return read().categories;
  },
  async saveCategory(input: Omit<Category, 'wallpaperCount'> & { wallpaperCount?: number }) {
    await wait();
    const data = read();
    const index = data.categories.findIndex((item) => item.id === input.id);
    const value: Category = { ...input, wallpaperCount: input.wallpaperCount ?? 0 };
    if (index >= 0) data.categories[index] = value;
    else data.categories.push(value);
    write(data);
    return value;
  },
  async wallpapers() {
    await wait();
    return read().wallpapers;
  },
  async saveWallpaper(input: Wallpaper) {
    await wait();
    const data = read();
    const index = data.wallpapers.findIndex((item) => item.id === input.id);
    const value = { ...input, id: input.id || `wp-${Date.now()}`, updatedAt: timestamp() };
    if (index >= 0) data.wallpapers[index] = value;
    else {
      data.wallpapers.unshift(value);
      const category = data.categories.find((item) => item.id === value.categoryId);
      if (category) category.wallpaperCount += 1;
    }
    write(data);
    return value;
  },
  async deleteWallpaper(id: string) {
    await wait();
    const data = read();
    const wallpaper = data.wallpapers.find((item) => item.id === id);
    if (!wallpaper) return false;
    data.wallpapers = data.wallpapers.filter((item) => item.id !== id);
    for (const categoryId of [wallpaper.categoryId, wallpaper.subcategoryId]) {
      const category = data.categories.find((item) => item.id === categoryId);
      if (category) category.wallpaperCount = Math.max(0, category.wallpaperCount - 1);
    }
    write(data);
    return true;
  },
  async batches() {
    await wait();
    return read().batches;
  },
  async generateBatch(name: string, codeCount: number, quotaPerCode: number) {
    await wait();
    const data = read();
    const batch: CodeBatch = {
      id: `batch-${Date.now()}`, name, codeCount, quotaPerCode, redeemed: 0,
      createdAt: timestamp()
    };
    data.batches.unshift(batch);
    write(data);
    return { batch, codes: Array.from({ length: codeCount }, randomCode) };
  },
  async redemptions() {
    await wait();
    return read().redemptions;
  },
  async devices() {
    await wait();
    return read().devices;
  }
};
