# APP-UI-001 App UI 规范

版本：0.2；日期：2026-09-15；任务：WP-UI01 / WP-UI02；当前状态：规范及业务页已实施，10017 构建与红米页面短核对通过，待用户最终视觉验收。进度以 [PM-002 第 14 节](../../docs/10-项目管理/PM-002-数据库API与H5联调开发计划.md#14-app-样式与内容调整) 为准。

## 1. 依据与入口

以现有 H5 为依据，不另选配色、字体或添加业务文案。参考顺序：实际 H5 组件和页面 → 共用 Token → [MASTER](MASTER.md)。组件明确指定的比例和尺寸优先于泛用尺寸 Token。

- 规范参考页：`apps/h5-prototype/src/views/DesignSystemView.vue`。
- 页面与组件：`apps/h5-prototype/src/views`、`src/design-system/components`、`src/components/QjMobileShell.vue`。
- 唯一 Token 数据源：`packages/design-tokens/qingjing-wallpaper.tokens.json`，Flutter 读取已有生成物，不直接编辑生成物。
- App 入口：底部「首页 / 我的 / UI 规范」→「UI 规范」。H5 原有两个入口；第三个入口是用户明确要求的 App 规范页。
- Flutter 实现：`apps/mobile/lib/design_system/qj_theme.dart`、`qj_components.dart`、`ui_spec_screen.dart`。

规范页离线显示颜色、字体、间距、圆角、页面骨架和组件状态；点选分类、导航、状态以及面板示例，只改变本页演示状态。不会发起兑换、创建权益、下载正式资源或调用系统设置。演示面板顶部始终标明「组件演示」。图片沿用 H5 的城市与海岸 SVG 示例，不混入真实目录。

WP-UI01 提供共用主题、基础组件与对照页；WP-UI02 已把首页、分类、搜索、详情、面板、我的、客服和教程接入这些组件。Android 真实能力替代 H5 模拟流程，差异与检查结果见 [WP-UI02 验收记录](../../docs/06-测试与验收/WP-UI02-App全站H5同步验收-2026-09-15.md)。

## 2. 视觉原则

大图优先，白色内容面与黑色文字、导航和默认主按钮构成骨架。橙色用于选中及重点操作，暖粉只作辅助；绿色表示成功，红色表示错误。圆角和柔和阴影沿用 H5，不添加玻璃效果、外部字体或新主题。

## 3. 颜色

| Token | HEX | 用途 |
|---|---|---|
| ink / navigation / scrim | #191817 | 标题、默认主按钮、导航、遮罩 |
| inkSoft | #3F3B37 | 次级标题 |
| mutedInk | #6F6A64 | 说明 |
| subtleInk | #98928B | 辅助与禁用文字 |
| inverseInk / surface / navigationInactive | #FFFFFF | 白字、白色内容面、未选中导航 |
| accent | #F4A91E | 选中导航、重点主操作 |
| accentStrong | #DB8E00 | 强调描边和图标 |
| accentSoft | #FFF3D8 | 暖色图标底、标签 |
| blush / blushSoft | #DE9C95 / #F8E8E5 | 暖粉辅助与浅底 |
| success / successSoft | #287A55 / #E7F4EC | 成功文字、图标与浅底 |
| danger / dangerSoft | #C94943 / #FBE9E7 | 错误文字、图标与浅底 |
| background | #F4F3F0 | 规范页画布、外层暖灰 |
| surfaceMuted | #F8F7F4 | 表单、教程和未选中位置浅底 |
| surfaceStrong | #E8E5E0 | 禁用按钮、媒体占位、进度轨道 |
| outline / outlineStrong | #E7E3DD / #D8D2CA | 细边框、输入框边框 |

实际 H5 `QjMobileShell` 内容面为白色；App Scaffold 使用 `surface`，规范页画布使用 `background`。Flutter 固定 ColorScheme，不再用 `fromSeed` 自动派生不属于 H5 的颜色。H5 组件局部色保留：暖黄标签文字 #855700、禅意图标 #A5524B，不新增全局 Token。

## 4. 排版

中文使用系统字体，Flutter 沿用系统 fallback；不下载远程字体。默认字体不禁用系统缩放。CSS px 对应 Flutter 逻辑像素，字号按逻辑字号结合系统字体缩放显示，不按手机截图物理像素放大。

| 角色 | 字号 | 字重 | 行高 |
|---|---:|---:|---:|
| Display | 34 | 800 | 1.12 |
| 页面标题 | 28 | 800 | 1.25 |
| 分区标题 | 20 | 700 | 1.35 |
| 卡片标题 | 16 | 700 | 1.35 |
| 大正文 | 16 | 400 | 1.6 |
| 正文 | 14 | 400 | 1.6 |
| 大辅助 | 13 | 500 | 1.45 |
| 辅助 / 导航 | 12 | 500 / 600 | 1.45 |
| 微型标签 | 10 | 700 | 1.45 |

## 5. 尺寸、布局与动效

- 间距：4 / 8 / 12 / 16 / 20 / 24 / 28 / 32 / 40 / 48；页面水平边距 20，内容最大宽度 430，设计基准 390。
- 圆角：小块 10、输入和位置选项 14、金刚区图标 19、媒体 22、内容卡片 24、面板 28、导航 30、胶囊 999。
- 主按钮最小高度 54；图标按钮和实际点击区域至少 44；一级分类图标 56。二级分类可见胶囊最小高度 38，外层点击区域至少 44。
- 品牌栏 64；导航最小高度 66、内边距 7、选项最小高度 52。App 导航距两侧及底部至少 20，底部结合 SafeArea，正文由 Scaffold 预留导航高度。
- 双列媒体比例 3:4.15；详情媒体比例 1:2。H5 组件实际比例优先于通用 `detailMedia=470` Token。
- 壁纸阴影：0 / 16 / 42，rgba(49,40,31,0.10)；普通卡：0 / 8 / 24，rgba(49,40,31,0.07)；导航：0 / 12 / 34，rgba(25,24,23,0.18)。
- 动效：140 / 220 / 360ms；曲线沿用共用 Token。加载状态尊重系统减少动画设置，离开的 Tab 暂停界面动画；规范页静态加载示例不持续刷新。

375、390、430 宽度及放大字体需要可完整滚动，无横向溢出。分类保持左对齐、横向滚动；大屏居中限制内容宽度；键盘出现后面板可滚动避让。

## 6. 组件对照

| H5 | Flutter / 规范页 | 本步骤结果 |
|---|---|---|
| Token / 页面基础样式 | QjTheme.light | 全 App 引入固定颜色、排版、按钮、输入、卡片和面板主题 |
| QjPrimaryAction | QjPrimaryAction | 黑底白字默认、橙底黑字强调、胶囊 54、加载和禁用示例 |
| QjTypeBadge | QjTypeBadge | dark / amber / success / light 四种样式 |
| QjCategoryTile | QjCategoryTile | 56 图标、局部色、19 圆角、选中橙边与加粗 |
| QjSubcategoryRail | QjFilterChip | 左对齐横向胶囊、白底细边 / 黑底白字 |
| QjWallpaperCard | QjWallpaperCard | 双列、3:4.15、22 圆角、左上角类型、图片下只有名称 |
| QjWallpaperHero | 页面骨架示例 | 返回 / 标题 / 观看教程、1:2 主图、底部内嵌橙色下载按钮 |
| QjStatePanel | QjStatePanel | 空内容 / 加载失败 / 断网三态及操作 |
| QjTutorialCard | QjTutorialCard | 使用指南、壁纸设计教程、H5 原说明文案 |
| QjBottomNav | QjBottomNav | 黑底、橙色选中、白色未选中；实际 App 三个 Tab，规范样例保留 H5 两个 |
| 各底部面板 | QjSheet + 本页局部演示 | 兑换四态、下载四态、设置单选 / 成功；仅本地演示 |
| Lucide SVG | QjIcon | 复用 H5 锁定 Lucide 1.39.0 几何和许可，不改用 Emoji 或另一套图标 |
| 品牌栏 / 微信客服 / 设置教程播放器 | QjBrandHeader / QjCustomerServiceCard / SettingTutorialScreen | 业务页已同步；二维码支持预览、保存和复制，教程使用 H5 同一视频素材 |

按钮具体用法：默认主要按钮黑底白字；详情下载、下载完成后的设置等强调主操作橙底黑字。单屏业务主操作只有一个，不把所有动作同时铺成多个大按钮。规范页为组件对照可同时展示多种按钮。

资源同步脚本：`node scripts/sync-mobile-ui-reference.mjs`；检查：追加 `--check`。4 张 H5 示例 SVG 逐字节复制；Lucide 从 H5 锁定包导出 SVG，保留完整 LICENSE。Flutter 使用锁定 `flutter_svg 2.3.0` 本地渲染。

## 7. 业务页同步结果

WP-UI02 按以下规则完成 H5 页面同步，未经用户要求不自行增加内容：

- 首页品牌栏、分类、搜索和列表；我的教程、客服和权益入口按实际 H5 顺序及文案。
- 所有业务壁纸列表只显示名称和类型，不加平台、价格、下载数、已获得 / 已下载 / 已设置标签。
- 详情只保留返回、标题、观看教程、真实主预览和主操作；不再增加平台、文件大小、适配标签、描述或独立全屏预览按钮。
- 设置使用单选底部面板，业务选项来自能力层；规范页三项目只是 H5 样例，不能作为当前手机支持锁屏的证据。
- App 真实 4D 交互和 MP4 循环继续使用已完成的原生能力；H5 占位能力不覆盖 App 实现。
- 用户最新决定优先于旧 H5 规范：试用暂时隐藏；华为/荣耀 2.0、苹果 3.0 尚未开始。

规范建立记录见 [WP-UI01](../../docs/06-测试与验收/WP-UI01-AppUI规范验收-2026-09-14.md)，业务页实现与构建见 [WP-UI02](../../docs/06-测试与验收/WP-UI02-App全站H5同步验收-2026-09-15.md)。最终视觉和真实体验由用户验收。
