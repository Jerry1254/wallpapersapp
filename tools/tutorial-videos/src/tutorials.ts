export type Screen = 'app' | 'home' | 'support' | 'redeem' | 'gallery' | 'photo' | 'permission' | 'preview' | 'success' | 'save' | 'android-set' | 'placement';
export type Step = {
  seconds: number;
  title: string;
  description: string;
  screen: Screen;
  action: string;
  hint: string;
  tag?: string;
  device?: 'android' | 'harmony' | 'ios';
};
export type Tutorial = {
  id: string;
  title: string;
  platform: string;
  effect: 'static' | 'video' | 'parallax';
  format: string;
  steps: Step[];
};

export const tutorials: Tutorial[] = [
  {
    id: '01-static', title: '静态壁纸设置', platform: '安卓 · 鸿蒙 · iOS', effect: 'static', format: '静态壁纸',
    steps: [
      { seconds: 5, title: '选择喜欢的静态壁纸', description: '在壁纸详情里选择「静态壁纸」', screen: 'app', action: '下载壁纸', hint: '安卓看第 2 步 · 鸿蒙看第 3～6 步\n苹果看第 7～10 步' },
      { seconds: 8, title: '安卓：下载后直接设置', description: '点击「设置壁纸」，按系统提示确认', screen: 'android-set', action: '设置壁纸', hint: '选择桌面、锁屏或同时设置，即可完成。', tag: '安卓手机', device: 'android' },
      { seconds: 6, title: '鸿蒙：下载后保存到相册', description: '下载完成，点击保存按钮存入手机图库', screen: 'save', action: '保存图片', hint: '看到「已保存到相册」后，再前往手机图库。', tag: '华为鸿蒙 · 1 / 4', device: 'harmony' },
      { seconds: 5, title: '打开图库，找到这张壁纸', description: '在最近保存的图片里，点击打开壁纸', screen: 'gallery', action: '打开壁纸', hint: '打开的是刚保存的静态图片。', tag: '华为鸿蒙 · 2 / 4', device: 'harmony' },
      { seconds: 6, title: '右上角菜单 → 设置为壁纸', description: '打开照片右上角「⋯」，选择壁纸选项', screen: 'photo', action: '设置为壁纸', hint: '部分版本显示为「设置为 → 壁纸」。', tag: '华为鸿蒙 · 3 / 4', device: 'harmony' },
      { seconds: 8, title: '应用到锁屏或桌面', description: '调整图片位置，点击「应用」并选择位置', screen: 'placement', action: '应用', hint: '可选择锁屏、桌面，或同时设置。', tag: '华为鸿蒙 · 4 / 4', device: 'harmony' },
      { seconds: 6, title: '苹果：下载并保存到相册', description: '下载静态壁纸，确认已保存到手机照片', screen: 'save', action: '下载壁纸', hint: '首次保存时，按提示允许保存到照片。', tag: '苹果 iPhone · 1 / 4', device: 'ios' },
      { seconds: 5, title: '打开照片，找到这张壁纸', description: '在最近保存的图片里，点击打开壁纸', screen: 'gallery', action: '打开壁纸', hint: '找到刚保存的静态图片，点击查看大图。', tag: '苹果 iPhone · 2 / 4', device: 'ios' },
      { seconds: 7, title: '分享 → 用作墙纸', description: '点击分享图标，在菜单中找到「用作墙纸」', screen: 'photo', action: '用作墙纸', hint: '分享图标是方框加向上箭头。\n向上滑动分享菜单，可找到墙纸选项。', tag: '苹果 iPhone · 3 / 4', device: 'ios' },
      { seconds: 8, title: '添加 → 设为墙纸组合', description: '调整图片位置，点击右上角「添加」', screen: 'preview', action: '添加', hint: '「设为墙纸组合」同时应用到锁屏和主屏幕。\n也可选择「自定义主屏幕」单独调整。', tag: '苹果 iPhone · 4 / 4', device: 'ios' },
      { seconds: 4, title: '静态壁纸，设置好了', description: '按照你的手机平台，完成对应操作即可', screen: 'success', action: '设置完成', hint: '安卓直接设置 · 鸿蒙和苹果从手机相册设置' },
    ],
  },
  {
    id: '02-android-video', title: '安卓动态壁纸', platform: 'ANDROID', effect: 'video', format: '动态壁纸',
    steps: [
      { seconds: 5, title: '下载喜欢的动态壁纸', description: '在详情里选择「动态壁纸」并下载', screen: 'app', action: '下载壁纸', hint: '等待下载完成，即可开始设置。' },
      { seconds: 5, title: '点击「设置壁纸」', description: '按系统提示确认应用到桌面或锁屏', screen: 'preview', action: '设置壁纸', hint: '正常情况下，直接点击设置就可以。' },
      { seconds: 9, title: '提示权限？这样开启', description: '手机设置 → 应用管理 → 当前倾境 App', screen: 'permission', action: '动态壁纸服务', hint: '权限管理 → 其他权限 → 允许动态壁纸服务\n开启后回到 App，重新检测并设置。', tag: '仅出现权限提示时' },
      { seconds: 4, title: '让壁纸动起来', description: '返回桌面，查看动态效果', screen: 'success', action: '设置完成', hint: '权限名称和设置位置可能因手机品牌而不同。' },
    ],
  },
  {
    id: '03-android-4d', title: '安卓 4D 壁纸', platform: 'ANDROID · 4D', effect: 'parallax', format: '4D壁纸',
    steps: [
      { seconds: 7, title: '先选择喜欢的 4D 壁纸', description: '需要兑换的壁纸，使用客服发来的兑换码', screen: 'app', action: '兑换并下载', hint: '免费或已兑换的壁纸，可直接下载。\n已有兑换码，可直接看第 4 步。', tag: '需要兑换时' },
      { seconds: 6, title: '没有码？点首页右上角客服', description: '回到首页，点击右上角「客服」', screen: 'home', action: '客服', hint: '没有兑换码时，先从这里联系获取。\n已有兑换码，可以跳过第 2、3 步。', tag: '仅没有兑换码时' },
      { seconds: 7, title: '添加客服微信，获取兑换码', description: '复制微信号，在微信中搜索并添加客服', screen: 'support', action: '复制', hint: '添加客服微信，联系获取兑换码。\n收到后，返回这张壁纸继续兑换。', tag: '仅没有兑换码时' },
      { seconds: 8, title: '返回详情，输入兑换码', description: '点击「兑换并下载」，再「验证并兑换」', screen: 'redeem', action: '验证并兑换', hint: '请输入客服发来的兑换码。\n验证成功后，即可下载这张壁纸。' },
      { seconds: 4, title: '兑换成功，等待下载完成', description: '下载完成后，按钮变为「设置壁纸」', screen: 'app', action: '下载中', hint: '免费或已兑换的壁纸，点击「下载壁纸」即可。' },
      { seconds: 5, title: '点击「设置壁纸」', description: '按系统提示确认，完成壁纸设置', screen: 'preview', action: '设置壁纸', hint: '正常情况下，直接点击设置就可以。' },
      { seconds: 9, title: '提示权限？先允许服务', description: '手机设置 → 应用管理 → 当前倾境 App', screen: 'permission', action: '动态壁纸服务', hint: '权限管理 → 其他权限 → 允许动态壁纸服务\n开启后回到 App，重新检测并设置。', tag: '仅出现权限提示时' },
      { seconds: 5, title: '轻轻转动手机', description: '前景和背景，随角度产生层次变化', screen: 'success', action: '设置完成', hint: '实际效果以当前手机和壁纸资源为准。' },
    ],
  },
  {
    id: '04-harmony-video', title: '鸿蒙动态壁纸', platform: 'HarmonyOS', effect: 'video', format: '动态壁纸',
    steps: [
      { seconds: 5, title: '先保存到相册', description: '在 App 下载壁纸，按提示保存到图库', screen: 'app', action: '保存到图库', hint: '首次保存时，按系统提示允许保存。' },
      { seconds: 5, title: '打开「图库」', description: '找到刚保存的动态照片，点击打开', screen: 'gallery', action: '动态照片', hint: '请选择动态照片，而不是单独的视频。' },
      { seconds: 6, title: '更多 → 设置为壁纸', description: '在动态照片页面，打开「更多」', screen: 'photo', action: '设置为壁纸', hint: '点击「设置为壁纸」，进入壁纸预览。' },
      { seconds: 6, title: '保留动态，应用到锁屏', description: '保持动态效果开启，点击「应用」', screen: 'preview', action: '应用', hint: '根据系统提示，完成锁屏壁纸设置。' },
      { seconds: 4, title: '锁屏也有动态效果', description: '设置完成，查看锁屏壁纸', screen: 'success', action: '设置完成', hint: '需要支持动态照片壁纸的鸿蒙版本和机型。' },
    ],
  },
  {
    id: '05-ios-video', title: 'iOS 动态壁纸', platform: 'iPhone · iOS 17 或更新版本', effect: 'video', format: '动态壁纸',
    steps: [
      { seconds: 5, title: '下载并保存实况照片', description: '在 App 下载动态壁纸，保存到相册', screen: 'app', action: '下载壁纸', hint: '首次下载时，按提示允许保存到照片。' },
      { seconds: 5, title: '打开「照片」', description: '找到刚保存的实况照片，点击打开', screen: 'gallery', action: '实况照片', hint: '请选择带有 LIVE 标识的照片。' },
      { seconds: 6, title: '分享 → 用作墙纸', description: '点击分享，在菜单中选择「用作墙纸」', screen: 'photo', action: '用作墙纸', hint: '也可从 设置 → 墙纸 → 添加新墙纸 进入。' },
      { seconds: 7, title: '打开实况播放，点击添加', description: '保持播放开启，再确认墙纸组合', screen: 'preview', action: '添加', hint: '选择「设为墙纸组合」完成设置。' },
      { seconds: 5, title: '唤醒锁屏，欣赏动态', description: '实况照片会在唤醒锁屏时播放', screen: 'success', action: '设置完成', hint: '动态效果显示在锁屏；主屏幕显示静态画面。' },
    ],
  },
];

export const duration = (tutorial: Tutorial) => tutorial.steps.reduce((total, step) => total + step.seconds, 0);
