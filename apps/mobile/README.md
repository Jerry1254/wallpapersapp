# 倾境壁纸 Flutter 客户端

正式入口；执行状态只在 PM-002。Android 优先，鸿蒙/iOS 的类型化接口显式不支持，未声称构建或效果通过。A01 已接入真实首页、分类、筛选、中文搜索及分页；A02 已加入真实详情、能力状态、教程与客服；身份/权益/下载在后续工作包实现，未实现操作不报成功。

## 工具链与运行

精确版本见 toolchain.json，应用依赖提交 pubspec.lock。本机 Flutter 位于非标准 channel，但完整 revision c6f67dede3d4aa1aa7a69dd56a3494a5cde6cc80 已与官方 3.38.10 标签读回核对一致。macOS 命令行建议 LC_ALL=en_US.UTF-8；Gradle 固定 UTF-8，避免中文工作树下子进程启动失败。构建与 CI 证据另行记录。

```sh
cd apps/mobile
flutter pub get --enforce-lockfile
flutter analyze
flutter test
adb reverse tcp:8080 tcp:8080
flutter run --flavor local --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1
flutter build apk --debug --flavor local --dart-define=API_BASE_URL=http://127.0.0.1:8080/api/v1
flutter build apk --release --flavor local
```

宿主机 API 由仓库 scripts/local-api.sh 启动；手机 loopback 依赖 adb reverse，不是电脑地址。只有 localDebug 开放明文；localRelease 验证优化编译，使用测试签名，联调需指定可信 HTTPS API。未指定地址时使用保留的 api.invalid 域名，避免意外访问真实服务。production flavor 为 prod，禁止明文，无生产签名配置，不是可提审包。

internal flavor 使用独立 `.internal` 安装标识，供 A10 新装/卸载与候选版测试。仅允许 HTTPS localhost/127.0.0.1；构建时通过 `QJ_INTERNAL_TLS_CERT_FILE` 配置公共 PEM 证书，Dart 同一证书以 base64 写入 `INTERNAL_TLS_CERTIFICATE` dart-define。原生仅对 loopback 添加此信任，Dart 保留正常 TLS 校验；生产包不添加内测证书。内测签名使用 `QJ_INTERNAL_KEYSTORE_FILE`、`QJ_INTERNAL_KEYSTORE_PASSWORD`、`QJ_INTERNAL_KEY_PASSWORD`、`QJ_INTERNAL_KEY_ALIAS`；未配置时不会自动使用生产签名。资源签名根另用 `QJ_INTERNAL_PACKAGE_SIGNING_KEY_ID` / `QJ_INTERNAL_PACKAGE_PUBLIC_KEY_DER`，它与 APK/TLS 签名用途不同。

本机 HTTPS 代理使用 `scripts/internal-api-https.py --certificate 公共证书路径 --private-key 私钥路径 --api-port 本地API端口`，仅监听 127.0.0.1:8443，随后创建 `adb reverse tcp:8443 tcp:8443`。私钥、Keystore、密码、dart-define 配置和 API 运行时目录不得提交。internal release 可经已授权 ADB 的 `dumpsys activity service` 读取动态服务的帧数/绘制/传感器/解码字节状态，输出不含票据、安装 ID、密钥或文件路径；prod 不开启此内测诊断。

为此独立本地 API 配置 `qingjing.device.allowed-android-scopes` 同时包含 `.local` 和 `.internal` 的完整安装标识；默认 local profile 只允许 `.local`，不要修改其他环境以同步测试身份。内测测量脚本 `scripts/measure-android-playback.py` 读取自己的系统壁纸服务和 PSS/CPU/电量状态，按固定 5+30+10 分钟执行，临时调整屏幕超时并在退出时恢复；不采集桌面或个人通知截图。

开发 applicationId 为 com.qingjing.qingjing_wallpaper.local，正式保留 com.qingjing.qingjing_wallpaper；尚未作商店账号注册确认。Android 最低 API 26 是工程基线，兼容性以真机矩阵为准。应用禁用自动备份，避免安装凭据恢复产生错误绑定。

## 分层与后续迁移

- packages/design-tokens 以原生成文件导出 Flutter Token，未复制或修改冻结生成物。
- packages/wallpaper-platform-interface 为纯 Dart 类型边界，分别提供身份、能力、预览、安装、设置和购买接口。
- apps/mobile/lib/config 从实际 flavor 读取环境，页面不自行授予权益。
- 后续 Kotlin 插件承接 PoC：TiltSensor.java → 姿态订阅；ParallaxScene.java → 图层/裁切；ParallaxPreviewView.java → 独立预览；ParallaxWallpaperService.java → 生命周期和系统效果；MainActivity.java → 仅迁移系统交互经验，不复用 PoC 页面。
- 未实现平台统一返回 unsupported。正式 Provider、安全包和原子安装完成前，不把封面或占位操作计为正式能力。

不涉及数据库迁移。H5/API 1.0.1 的历史冻结字节保留；Android 架构增补见 ARC-002，身份与交付契约在所属工作包显式版本化。

只读本地目录联调测试：`flutter test test/catalog_local_test.dart --dart-define=LOCAL_API=http://127.0.0.1:8080/api/v1`。普通 CI 不依赖本机夹具；不指定 LOCAL_API 时该用例明确跳过。

WP-A03 工作树 API 可用 `scripts/android-local-api.sh` 启动：显式设置 QJ_LOCAL_ENV_FILE 为已有本地 compose.env、QJ_STORAGE_ROOT 为同一本地存储。默认使用 8081，不停止现有 8080；APK 构建传入 http://127.0.0.1:8081/api/v1，并执行 adb reverse tcp:8081 tcp:8081。脚本固定 UTF-8，避免 macOS 中文目录被错误解析。不得指向生产环境。
