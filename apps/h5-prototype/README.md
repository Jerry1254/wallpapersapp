# 倾境壁纸 H5 原型

当前已完成设计 Token、组件 UI 规范和可点击的 H5 Mock 主流程。

## 技术栈

- Vue 3
- TypeScript
- Vite
- Pinia
- Vue Router
- Vant
- Lucide SVG Icons

## 运行

    npm install
    npm run dev

访问：

    http://127.0.0.1:5175/home

设计系统：

    http://127.0.0.1:5175/design-system

## Token

权威来源：

    ../../packages/design-tokens/qingjing-wallpaper.tokens.json

生成：

    npm run tokens:build

检查：

    npm run tokens:check

生成结果覆盖 H5 CSS、TypeScript 和未来 Flutter 使用的 Dart 常量。

## 当前组件

- QjBrandHeader
- QjCategoryTile
- QjSubcategoryRail
- QjWallpaperCard
- QjWallpaperHero
- QjWallpaperTargetSheet
- QjTypeBadge
- QjPrimaryAction
- QjRedeemPanel
- QjDownloadPanel
- QjTutorialCard
- QjCustomerServiceCard
- QjStatePanel
- QjBottomNav

## 原型页面

- `/home` 首页
- `/categories/:id` 分类列表
- `/wallpapers/:id` 壁纸详情、兑换、下载和设置
- `/mine` 已购买壁纸、教程入口和微信客服
- `/tutorial` 壁纸设计教程
- `/customer-service` 微信客服
- `/device-help` 设备恢复说明
