# Android 资源安装互通夹具

该夹具使用 API 的 `SecurePackageCodec` 制作真实 PNG、H.264 MP4、透明分层 PNG/config，再将随机内容密钥包装给模拟器实际 Android Keystore 公钥。仅监听本机 127.0.0.1，模拟器通过 adb reverse 读取；不替代正式 API 的 MySQL 权益/Redis 票据测试，不写原业务数据库。

Flutter 测试框架结束时会卸载测试 APK，清除该模拟器安装身份与资源；请在可清理的模拟器运行。已有权益的升级验收使用正常 APK 安装剧本。准备时需要 JDK 17、Maven 和 FFmpeg，所有数据/密钥放在忽略的 `.runtime/android-native-fixture`。本地私钥及资产保持，重复准备不会替换已有资产或签名密钥；夹具不打印私钥、内容密钥、票据或请求正文。

在工程根目录执行：

```sh
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
python3 scripts/native-package-fixture/prepare.py
python3 scripts/native-package-fixture/serve.py .runtime/android-native-fixture
```

另一个终端执行，路径根据 SDK 实际位置调整：

```sh
source .runtime/android-native-fixture/build.env
adb reverse tcp:8082 tcp:8082
cd apps/mobile
flutter test integration_test/native_package_delivery_test.dart -d emulator-5554 --flavor local --reporter expanded
```

build.env 只包含公开签名 ID/SPKI 公钥，通过 local flavor 编入 Android string 资源。生产使用独立 `QJ_PROD_PACKAGE_SIGNING_KEY_ID` / `QJ_PROD_PACKAGE_PUBLIC_KEY_DER`，空配置时安装器拒绝资源；客户端不从包或下载描述接收信任根。

测试覆盖三类实际安装、错版本和 GCM 篡改拒绝后保留旧版、慢速下载取消、测试实例内签名身份/credentialKeyId 值保持。只证明模拟器安装互通；完整设备证明→真实 API 兑换→下载→系统设置链及真机矩阵另行验收。

## A07 原生预览与设置组合测试（当前排障中）

使用可清理的 Android 35、英文系统模拟器。先按上面准备签名配置和 reverse，再在 `apps/mobile` 执行：

```sh
flutter test integration_test/native_wallpaper_playback_test.dart -d emulator-5554 --flavor local --reporter expanded > ../../.runtime/android-evidence/a07-emulator-playback.log 2>&1
```

在输出文件已重新创建后，从工程根目录另开终端执行：

```sh
python3 scripts/native-package-fixture/playback_host.py .runtime/android-evidence/a07-emulator-playback.log
```

主机脚本只接受模拟器序列号，通过实际 UI 文本/边界操作原生预览和系统选择页；不接受物理手机。找不到系统控件或测试标记会失败，不猜坐标。`adb` 不在 PATH 时传 `--adb /实际路径/adb`。脚本会设置模拟器壁纸；系统的目标选择项因版本/语言而异，本脚本不是 OEM 兼容性证明。

组合测试检查静态三目标、视频取消与桌面确认、桌面播放/切回 App 停止、独立视频预览、缓存保留。当前完整组合尚未通过，ANR 和环境排障记录见 `docs/06-测试与验收/WP-A07-静态与视频原生实施记录-2026-09-14.md`；不能把测试脚本存在或部分步骤成功视为验收通过。

## A07 真机独立测试入口

真机使用 `apps/mobile/tool/native_playback_probe.dart`，不依赖 Flutter 测试框架，不在退出时卸载应用。它仅接受 localDebug，通过独立构建 target 进入，正常产品及 release 入口仍为 `lib/main.dart`。测试会更换手机壁纸，使用经授权的开发设备。先完成签名配置、设备连接和 8082 reverse，再从 `apps/mobile` 执行：

```sh
flutter build apk --debug --flavor local --target tool/native_playback_probe.dart --target-platform android-arm64
adb -s 手机序列号 install -r build/app/outputs/flutter-apk/app-local-debug.apk
adb -s 手机序列号 shell am start -n com.qingjing.bizhi.local/com.qingjing.qingjing_wallpaper.MainActivity
```

先点击“安装三类夹具”，依次执行静态预览、桌面/锁屏/两者；视频系统设置分别退出和确认，记录系统实际目标项。返回桌面时通过调试 QJPlayback 状态变化检查非预览 Engine 播放，返回 App 时检查已停止；独立视频预览结束后再次返回桌面确认同一已提交资源恢复，清理缓存后也必须保留视频。Android 13 的位置接口可能不可查询，unknown 属于预期的诚实结果，另记录系统实际选择及服务状态，不用打开页面冒充成功。

真机测试后重新构建正常 `lib/main.dart` 入口，以 `adb install -r` 恢复同一内测应用，保留安装身份和资源。红米已通过该方式执行 A07 主要功能复测；长时性能及新版正式业务 API 下载链另行验收。

WP-A08 的 4D 夹具改用网格背景和透明圆形/十字前景，便于检查真实视差。prepare.py 按来源摘要保留不可变包；来源变化才递增版本，重复执行不会把不同清单重发为同一版本。夹具提供授权隔离外的纯原生互通测试，不产生正式设备权益；业务闭环另走独立本地 API。
