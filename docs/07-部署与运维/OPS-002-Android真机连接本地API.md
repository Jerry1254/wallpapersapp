# OPS-002 Android 真机连接本地 API

**日期：** 2026-09-14

**范围：** 本地开发，USB 或已授权的无线 ADB；无需生产部署

**验证：** 红米 M2012K11AC / Android 13，经无线 ADB reverse 请求分类 API 返回 HTTP 200

## 1. 连接原理

手机 App 请求 `http://127.0.0.1:8081/api/v1`，ADB 将手机的 8081 端口转到电脑的 8081 API。手机只访问 API，不连接 MySQL 或 Redis。手机与电脑保持在可通信的本地网络，电脑 API 和 ADB 连接需要持续运行。

无线调试的配对端口、连接端口和 API 8081 是不同用途，不能把配对地址填写为 API 地址。手机端无线调试显示的连接端口可能改变；重新连接时以当前页面为准。

## 2. 电脑操作

首次无线连接，在手机开启开发者选项和无线调试，打开“使用配对码配对设备”。电脑执行以下命令，按提示输入一次性配对码，不将码写入脚本或文档：

```sh
adb pair 手机IP:配对端口
adb mdns services
adb devices -l
```

若配对后没有自动连接，用无线调试主页面显示的地址执行：

```sh
adb connect 手机IP:连接端口
```

设备列表状态为 `device` 后，指定其序列号创建转发：

```sh
adb -s 手机序列号 reverse tcp:8081 tcp:8081
adb -s 手机序列号 reverse --list
curl --fail http://127.0.0.1:8081/api/v1/public/categories
```

ADB 不在 PATH 时使用 SDK 下 `platform-tools/adb` 的绝对路径。本机为 `/Users/kele/Library/Android/sdk/platform-tools/adb`。USB 连接时跳过无线配对，授权后同样执行 reverse。ADB 重连或电脑重启后检查转发，必要时重新创建。

## 3. 手机验证与 App 配置

手机浏览器打开 `http://127.0.0.1:8081/api/v1/public/categories`，出现包含 `items` 的 JSON 即证明 API 可达。也可从电脑对手机执行只读验证：

```sh
adb -s 手机序列号 shell curl --fail --silent --max-time 10 -o /dev/null -w '%{http_code}' http://127.0.0.1:8081/api/v1/public/categories
```

本机红米内置 curl，该命令已返回 200；其他机型可能没有 curl，使用浏览器验证即可。

正常 App 入口在 `apps/mobile`，使用 localDebug 构建：

```sh
export LC_ALL=en_US.UTF-8 LANG=en_US.UTF-8
cd apps/mobile
/Users/kele/development/flutter/bin/flutter run -d 手机序列号 --flavor local --dart-define=API_BASE_URL=http://127.0.0.1:8081/api/v1
```

API 地址是构建配置，不是 App 内可随意修改的设置项；地址改变需要重新构建。仅 localDebug 允许此 HTTP 地址，release 使用 HTTPS。真机使用正常 App 安装更新，不执行会在结束时卸载 APK 的模拟器 integration test 剧本。

## 4. 本次验证边界

当前 8081 仍是 A03 启动的旧本地 API，分类接口从手机访问已通过。新版 1.2.0 API、安全包签名与正式资源发布尚需配套联调，不能把网络 200 当作兑换/安全下载/系统设置全链路通过。8082 是资源安装测试夹具，不是正式业务 API。未更换已有本地数据库、资源或生产配置。

本地服务启动说明见 [OPS-001](OPS-001-本地API开发环境.md)；A07 实施状态见 [验收记录](../06-测试与验收/WP-A07-静态与视频原生实施记录-2026-09-14.md)。
