# OPS-002 Android 真机连接本地 API

**日期：** 2026-09-14

**范围：** 本地开发，USB 或已授权的无线 ADB；无需生产部署

**验证：** 红米 M2012K11AC / Android 13，经无线 ADB reverse 请求分类 API 返回 HTTP 200

第 5 节隔离环境的固定 ID 为 `LOCAL_DEVICE`。跨端连接、数据清理和验收记录统一遵守 [OPS-005 环境与联调管理规范](OPS-005-环境与联调管理规范.md)。

第 1～4 节保留首次 8081/localDebug 网络验证；当前 A10 隔离 API 和 HTTPS 内测配置见第 5 节，不用旧 API 的分类 200 代替新链路验收。

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

## 5. A10 当前 HTTPS 内测环境

当前独立 API 使用 8083、MySQL 3311、Redis 6391 和自己的资源/凭据。正式 Android 身份及 format 2/3 交付使用当前 1.3.x 契约；不会将手机直接连接数据库。HTTPS 代理只监听电脑 loopback 8443，转到该隔离 API 8083：

```sh
python3 scripts/internal-api-https.py --certificate 公共证书路径 --private-key 私钥路径 --api-port 8083
adb -s 手机序列号 reverse tcp:8443 tcp:8443
adb -s 手机序列号 reverse --list
curl --fail --cacert 公共证书路径 https://127.0.0.1:8443/api/v1/public/categories
```

`com.qingjing.bizhi.internal` 使用独立测试签名和安装身份，构建地址为 `https://127.0.0.1:8443/api/v1`；Lab 对应 `com.qingjing.bizhi.lab`。公共 TLS 证书同时配置到 Android 内测资源和 Dart 信任上下文；仅 internal/lab flavor 使用，保留链/有效期/主机名检查。普通浏览器没有这个内测信任时会拒绝自签证书，这不能单独证明 API 不可达。原生下载及 Flutter 真实目录均已在红米验证，详细证据见 [A10](../06-测试与验收/WP-A10-Android全链路真机验收-2026-09-14.md)。

内测构建参数见 [App README](../../apps/mobile/README.md)。TLS 私钥、APK 签名 Keystore/密码、API 凭据和真实兑换码只保存到忽略的运行时目录；仅公共证书/资源验签公钥进入内测 APK。此环境依赖电脑和 ADB 通道持续运行，不是已部署的生产服务。

手机重启后先检查 `adb devices -l`，已有授权正常时无需重复配对；连接端口改变时使用无线调试主页面的新连接地址。确实需要重新配对时才按第 2 节使用新的短时配对码。连接恢复后重新检查/创建 8443 reverse，再验证内测 App 的目录；保留现有 `.local` 与 `.internal` 数据以验证同一安装身份恢复。
