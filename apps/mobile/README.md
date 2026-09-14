# 倾境壁纸 Flutter 客户端

正式入口；执行状态只在 PM-002。Android 优先，鸿蒙/iOS 的类型化接口显式不支持，未声称构建或效果通过。A00 只提供首页/我的基础壳，后续工作包接入真实业务。

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

开发 applicationId 为 com.qingjing.qingjing_wallpaper.local，正式保留 com.qingjing.qingjing_wallpaper；尚未作商店账号注册确认。Android 最低 API 26 是工程基线，兼容性以真机矩阵为准。应用禁用自动备份，避免安装凭据恢复产生错误绑定。

## 分层与后续迁移

- packages/design-tokens 以原生成文件导出 Flutter Token，未复制或修改冻结生成物。
- packages/wallpaper-platform-interface 为纯 Dart 类型边界，分别提供身份、能力、预览、安装、设置和购买接口。
- apps/mobile/lib/config 从实际 flavor 读取环境，页面不自行授予权益。
- 后续 Kotlin 插件承接 PoC：TiltSensor.java → 姿态订阅；ParallaxScene.java → 图层/裁切；ParallaxPreviewView.java → 独立预览；ParallaxWallpaperService.java → 生命周期和系统效果；MainActivity.java → 仅迁移系统交互经验，不复用 PoC 页面。
- 未实现平台统一返回 unsupported。正式 Provider、安全包和原子安装完成前，不把封面或占位操作计为正式能力。

不涉及数据库迁移。H5/API 1.0.1 的历史冻结字节保留；Android 架构增补见 ARC-002，身份与交付契约在所属工作包显式版本化。
