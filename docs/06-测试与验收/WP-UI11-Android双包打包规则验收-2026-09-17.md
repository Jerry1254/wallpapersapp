# WP-UI11 Android 双包打包规则验收记录

**日期：** 2026-09-17
**范围：** Android applicationId、显示名称、双包图标、安装 scope、构建与交付规范
**结论：** 实现和自动验证完成；未生成对外正式产物，未执行生产发布或生产配置变更

## 1. 固定产品身份

| 应用 | flavor | 显示名称 | applicationId | 图标 |
|---|---|---|---|---|
| 正式 App | `prod` | 倾境动态壁纸 | `com.qingjing.bizhi` | 紫蓝山景图 |
| 4D 本地测试 App | `lab` | 4D壁纸·本地测试 | `com.qingjing.bizhi.lab` | 蓝色“4D TEST”图 |

两包继续共用同一套业务源码，通过 flavor 切换 4D 调试详情和 Lab 图标。applicationId 不同，因此可以同时安装；本地数据、设备身份、下载资源和安装密钥分别隔离。

工程调试标识同步为 `com.qingjing.bizhi.local` 和 `com.qingjing.bizhi.internal`。其中 internal 桌面名称固定为“倾境动态壁纸·本地测试”，与 prod 正式名称明确区分。Kotlin/Java namespace 保持 `com.qingjing.qingjing_wallpaper`，只作为源码类路径使用。

## 2. 图标与资源结果

- 正式高清母版保存为 `apps/mobile/branding/android/qingjing-dynamic-wallpaper-1024.png`，并保留用户提供的 512 像素版本。
- Lab 母版保存为 `apps/mobile/branding/android/4d-wallpaper-lab-512.png`。
- 两套图标均生成 `mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi` 五档 launcher 资源；Lab 在 `src/lab/res` 覆盖同名图标。
- prod 合并资源中的 xxxhdpi 图标 SHA-256 与正式源资源一致：`0312633a638d18c155554fdf0a8180d551d47a697902e4aba0356505372cd053`。
- lab 合并资源中的 xxxhdpi 图标 SHA-256 与 Lab 源资源一致：`fb6a2a1f2a3045179a4fc454c5f004855bd8b09259cdbdef6bccf975a6bc9d60`。

## 3. 构建与服务端边界

- Gradle 已固定 prod/lab 名称和 applicationId；local/internal 只用于工程调试。
- 本地 API 默认 Android scope 已切换为 `com.qingjing.bizhi.local`。
- 诊断开关只允许 internal 和 lab，prod 不包含内部诊断能力。
- [OPS-004](../07-部署与运维/OPS-004-Android双包打包与命名规范.md) 已记录版本号、签名、scope、构建命令、产物命名和交付检查规则。
- 旧测试标识 `com.qingjing.qingjing_wallpaper*` 仅保留在历史验收记录；项目未正式上线，不做旧包升级迁移。

## 4. 自动验证

| 范围 | 结果 |
|---|---|
| Android 合并 Manifest | localDebug、localRelease、prodRelease、internalRelease、labRelease 全部通过；身份、网络、备份、release 调试标记、权限和壁纸组件符合预期 |
| Android 合并资源 | prod/lab 显示名称正确；两套合并图标分别与各自源资源哈希一致 |
| Flutter | `flutter analyze` 无问题；51 项测试通过，1 项仅在显式本地 API 环境运行而跳过 |
| Android 原生 | `:wallpaper_android:testDebugUnitTest` 通过 |
| Java API | 53 项测试通过 |
| OpenAPI | Redocly、59 个操作、89 个 Schema、144 处 Java 错误码覆盖及 29 个冻结文件校验通过 |

## 5. 交付边界

本轮完成打包规则和工程身份落地，不冒充已签名正式发布。后续交付 prod 时必须配置专用生产签名并输出 AAB；Lab 使用测试签名输出 ARM64 APK。每次交付都要记录 Git commit、版本号、versionCode、文件 SHA-256 和真机安装结果。
