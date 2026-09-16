import cityImage from '@/assets/demo/city.svg';
import coastImage from '@/assets/demo/coast.svg';
import mountainImage from '@/assets/demo/mountain.svg';
import zenImage from '@/assets/demo/zen.svg';

export type WallpaperType = '4D动态' | '动态' | '静态';

export interface WallpaperCategory {
  id: string;
  label: string;
  tone: 'amber' | 'blush' | 'sage' | 'stone';
  subcategories: Array<{ id: string; label: string }>;
}

export interface WallpaperItem {
  id: string;
  title: string;
  type: WallpaperType;
  image: string;
  categories: string[];
  subcategories: string[];
  compatible: boolean;
}

export const categories: WallpaperCategory[] = [
  {
    id: 'recommend',
    label: '推荐',
    tone: 'amber',
    subcategories: [
      { id: 'all', label: '全部' },
      { id: 'popular', label: '热门精选' },
      { id: 'depth', label: '4D动态' },
      { id: 'dynamic', label: '动态壁纸' }
    ]
  },
  {
    id: 'scenery',
    label: '风景',
    tone: 'sage',
    subcategories: [
      { id: 'all', label: '全部' },
      { id: 'nature', label: '自然风光' },
      { id: 'city', label: '城市夜景' },
      { id: 'sea', label: '海洋天空' }
    ]
  },
  {
    id: 'zen',
    label: '禅意',
    tone: 'blush',
    subcategories: [
      { id: 'all', label: '全部' },
      { id: 'buddha', label: '佛系禅境' },
      { id: 'ink', label: '水墨国风' },
      { id: 'lotus', label: '莲花静心' }
    ]
  },
  {
    id: 'character',
    label: '角色',
    tone: 'stone',
    subcategories: [
      { id: 'all', label: '全部' },
      { id: 'armor', label: '未来机甲' },
      { id: 'fantasy', label: '幻想角色' },
      { id: 'anime', label: '动漫插画' }
    ]
  },
  {
    id: 'static',
    label: '静态',
    tone: 'stone',
    subcategories: [
      { id: 'all', label: '全部' },
      { id: 'minimal', label: '极简色彩' },
      { id: 'illustration', label: '艺术插画' },
      { id: 'photo', label: '摄影作品' }
    ]
  }
];

export const wallpapers: WallpaperItem[] = [
  { id: 'mountain-dusk', title: '暮色山峦', type: '4D动态', image: mountainImage, categories: ['recommend', 'scenery'], subcategories: ['popular', 'depth', 'nature'], compatible: true },
  { id: 'quiet-zen', title: '静观', type: '动态', image: zenImage, categories: ['recommend', 'zen'], subcategories: ['popular', 'buddha', 'dynamic'], compatible: true },
  { id: 'city-afterglow', title: '城市余晖', type: '4D动态', image: cityImage, categories: ['recommend', 'scenery', 'character'], subcategories: ['depth', 'city', 'armor'], compatible: true },
  { id: 'deep-breath', title: '深海呼吸', type: '静态', image: coastImage, categories: ['recommend', 'scenery', 'static'], subcategories: ['sea', 'minimal'], compatible: true },
  { id: 'cloud-ridge', title: '云岭微光', type: '动态', image: mountainImage, categories: ['scenery'], subcategories: ['nature', 'dynamic'], compatible: true },
  { id: 'night-grid', title: '霓虹矩阵', type: '4D动态', image: cityImage, categories: ['character'], subcategories: ['armor', 'depth'], compatible: true },
  { id: 'lotus-dream', title: '莲境', type: '动态', image: zenImage, categories: ['zen'], subcategories: ['lotus', 'buddha'], compatible: true },
  { id: 'silent-coast', title: '静默海岸', type: '静态', image: coastImage, categories: ['static', 'scenery'], subcategories: ['photo', 'sea'], compatible: true },
  { id: 'old-device-demo', title: '星际装甲', type: '4D动态', image: cityImage, categories: ['character'], subcategories: ['armor', 'fantasy'], compatible: false }
];

export const getWallpaper = (id: string) => wallpapers.find((item) => item.id === id);

export const getCategory = (id: string) => categories.find((item) => item.id === id) ?? categories[0]!;

export const getWallpapersByCategory = (categoryId: string, subcategoryId = 'all') => wallpapers.filter((item) => {
  if (!item.categories.includes(categoryId)) return false;
  return subcategoryId === 'all' || item.subcategories.includes(subcategoryId);
});

export const searchWallpapers = (input: string) => {
  const keyword = input.trim().toLocaleLowerCase('zh-CN');
  if (!keyword) return [];
  return wallpapers.filter((item) => {
    const categoryLabels = categories
      .filter((category) => item.categories.includes(category.id))
      .flatMap((category) => [category.label, ...category.subcategories.filter((child) => item.subcategories.includes(child.id)).map((child) => child.label)]);
    return [item.title, item.type, ...categoryLabels].some((value) => value.toLocaleLowerCase('zh-CN').includes(keyword));
  });
};
