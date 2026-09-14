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
