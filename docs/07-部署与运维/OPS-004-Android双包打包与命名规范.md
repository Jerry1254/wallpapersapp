# OPS-004 Android 双包打包与命名规范

**状态：** 生效
**日期：** 2026-09-17
**对应任务：** PM-002 / WP-UI11

## 1. 固定应用身份

面向产品与真机调参只维护下面两个应用。应用名称、应用 ID 和图标一经固定，后续版本不得更换。

| 应用 | Android flavor | 显示名称 | applicationId | 图标源文件 | 用途 |
|---|---|---|---|---|---|
| 正式 App | `prod` | 倾境动态壁纸 | `com.qingjing.bizhi` | `apps/mobile/branding/android/qingjing-dynamic-wallpaper-1024.png` | 用户安装、商店发布 |
| 4D 本地测试 App | `lab` | 4D壁纸·本地测试 | `com.qingjing.bizhi.lab` | `apps/mobile/branding/android/4d-wallpaper-lab-512.png` | LOCAL_DEVICE 真机查看并整体保存 4D 参数 |

两包使用同一 Git 提交和同一套公共源码。Lab 只通过 flavor 打开 4D 调参详情，不复制首页、目录、登录、下载、播放或设置代码。两个 applicationId 不同，可以同时安装；两者的本地数据、安装密钥、下载资源和设备身份完全隔离。

工程内部还保留以下构建，不作为正式对外产物：

| flavor | applicationId | 显示名称 | 用途 |
|---|---|---|---|
| `local` | `com.qingjing.bizhi.local` | 倾境动态壁纸·本地 | ADB reverse 与本地明文 API 调试 |
| `internal` | `com.qingjing.bizhi.internal` | 倾境动态壁纸·本地测试 | LOCAL_DEVICE 的正式功能体验 |

Kotlin/Java namespace 继续使用 `com.qingjing.qingjing_wallpaper`。namespace 是源码类路径，不是安装包名，不能为了改 applicationId 批量移动原生类。

## 2. 图标规则

- 正式 App 使用用户提供的紫蓝山景图；1024 像素文件为唯一高清母版，512 像素文件用于商店资料和快速核对。
- Lab 使用带有“4D TEST”文字的蓝色图标，避免与正式 App 混淆。
- Android launcher 栅格固定生成 `mdpi 48`、`hdpi 72`、`xhdpi 96`、`xxhdpi 144`、`xxxhdpi 192` 五档，资源名统一为 `@mipmap/ic_launcher`。
- 正式图标位于 `src/main/res`，因此 prod、internal 和 local 共用；Lab 在 `src/lab/res` 以同名资源覆盖。
- 不从截图裁切图标，不在各密度目录手工调整色彩或构图。更换母版时必须重新生成五档资源并同时更新本规范。

## 3. 版本规则

- 正式 App 使用 `主版本.次版本.修订号 + versionCode`。`versionName` 面向用户，`versionCode` 每次交付严格递增，不能复用或回退。
- Lab 独立使用 `0.1.0+N`，每次向测试人员交付时递增 `N`。Lab 版本变化不要求正式 App 同步升级。
- 同一次双包交付必须记录同一 Git commit；文档分别记录两个版本号、SHA-256 和构建时间。
- 只修改 Lab 页面时可以只递增 Lab；修改公共目录、下载、播放、设置或安全代码时，两包都要重新构建和验证。

## 4. 签名与环境

- prod 必须使用专用生产签名；不得使用 debug、local 或 Lab/内测 Keystore。当前仓库不保存生产 Keystore，未配置生产签名时产物不能标记为可发布正式包。
- lab 与 internal 使用现有独立测试签名，可以互相复用 QA 签名，但不能复用生产签名。
- local 仅可使用 debug 签名。
- APK/AAB、Keystore、密码、TLS 私钥、环境变量文件和运行时证书不得进入 Git。
- 服务端按完整 applicationId 维护 `qingjing.device.allowed-android-scopes`：生产只启用 `com.qingjing.bizhi`；Lab 测试环境启用 `com.qingjing.bizhi.lab`；本地按需启用 `.local`、`.internal` 和 `.lab`，不能把测试 scope 加入生产允许列表。

应用 ID 会参与安装身份。旧测试包 `com.qingjing.qingjing_wallpaper*` 与新包不是同一个应用，升级时不会继承旧包数据或权益。本项目尚未正式上线，因此不做旧包迁移；开始使用新包后，旧标识只保留在历史验收记录中。

## 5. 标准构建命令

正式商店包默认输出 AAB：

```bash
cd apps/mobile
flutter build appbundle --release --flavor prod \
  --build-name=1.0.0 --build-number=正式递增编号
```

Lab 真机包默认输出 ARM64 APK，并显式传入测试 API 与内部证书配置：

```bash
cd apps/mobile
flutter build apk --release --flavor lab --target-platform android-arm64 \
  --build-name=0.1.0 --build-number=Lab递增编号 \
  --dart-define=API_BASE_URL=https://127.0.0.1:8443/api/v1 \
  --dart-define=INTERNAL_TLS_CERTIFICATE=测试证书Base64
```

构建 Lab 前还需要配置 `QJ_INTERNAL_TLS_CERT_FILE`、测试 APK 签名和资源签名公钥环境变量。命令中的编号和证书值是占位说明，禁止原样用于交付。

## 6. 产物命名

复制到交付目录时统一命名：

```text
倾境动态壁纸-android-{versionName}-{versionCode}-prod.aab
倾境动态壁纸-android-{versionName}-{versionCode}-arm64.apk
4D壁纸本地测试-android-{versionName}-{versionCode}-arm64.apk
```

正式 APK 只用于已授权的侧载验收；商店发布以 AAB 为准。Lab 不生成 AAB，不提交商店。

## 7. 交付前检查

每次打包必须完成：

1. 从 merged manifest 或 `apkanalyzer` 核对 applicationId、版本号、`debuggable=false`、`allowBackup=false` 和网络策略。
2. 在启动器中核对正式名称/山景图标与 Lab 名称/“4D TEST”图标，不能只看 APK 文件名。
3. 核对 prod 与 lab 能同时安装、分别注册设备身份，并且 Lab 不覆盖正式包数据。
4. 正式包不包含内部 TLS 信任或诊断开关；Lab 不使用生产签名和生产设备 scope。
5. 运行 Flutter 静态检查、单元测试和 Android 原生测试；公共代码变化时两包各做一次启动、目录、详情、下载和设置短验收。
6. 记录 Git commit、文件名、applicationId、versionName、versionCode、ABI、SHA-256 和真机结果。

文件名、桌面名称或图标正确都不能替代 manifest 与签名核对。只有以上信息全部一致，产物才可交付。
