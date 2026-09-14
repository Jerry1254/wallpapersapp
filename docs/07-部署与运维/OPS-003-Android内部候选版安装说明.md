# Android 内部候选版安装说明

状态：Android 1.0 本轮功能范围已完成，最终 10015 已安装到红米，最后实际体验由用户验收。当前为连接本地 API 的内部安装包。

## 包与适用范围

最终交付为 internal release `1.0.0+10015`，ARM64，安装标识 `com.qingjing.qingjing_wallpaper.internal`，显示名称“倾境壁纸·候选内测”。最低 API 26、compile/target API 36；目前只验收 Redmi M2012K11AC、Android 13、1080×2400，不以最低系统版本宣称其他机型已通过。

APK 来源提交 `38be91019cea1624ead14a99e94859f23049ca57`，SHA-256：`95616520ac59e5af74ad8733d63c0ad6cc8ad016836a1c627195c36803f60ff5`。独立内测签名证书 SHA-256：`f46b13574dc73b8bba32d008d4967306edf6d06181b174569ba94b0b7709bfa8`。debuggable=false，查看器不导出，动态服务受 BIND_WALLPAPER 保护；生产包采用独立标识/签名策略，不携带内测信任或诊断。

## 连接本地 API

手机仅访问 HTTPS API，不连接 MySQL/Redis。当前隔离 API 为 8083、MySQL 3311、Redis 6391；HTTPS loopback 代理为 8443→8083。电脑和红米连同一局域网，使用已配对无线 ADB；重启后先解锁并开启无线调试，再自动发现原设备的新端口或按手机页面给出的连接端口重连。

```sh
adb devices -l
adb mdns services
export QJ_PHONE_SERIAL='替换为上面实际红米的序列号'
adb -s "$QJ_PHONE_SERIAL" reverse tcp:8443 tcp:8443
adb -s "$QJ_PHONE_SERIAL" reverse --list
```

配对端口与连接端口不同；已经配对时优先连接，不重复索取配对码。不要重启共享 ADB server 来替代单设备重连。HTTPS 代理配置、证书和私有运行时说明见 [OPS-002](OPS-002-Android真机连接本地API.md)。当前内测证书有效至 2026-12-13，仅覆盖 localhost/127.0.0.1；超期需重新构建配置证书的内测包。原生与 Dart 均验证证书/主机名，不关闭 TLS 检查。

## 安装与升级

在得到最终交付 APK 后，将 `QJ_INTERNAL_APK` 替换为该文件的实际路径；先验证 SHA-256 与上述清单一致。

```sh
export QJ_INTERNAL_APK='替换为内部候选 APK 的实际路径'
shasum -a 256 "$QJ_INTERNAL_APK"
adb -s "$QJ_PHONE_SERIAL" install -r --user 0 "$QJ_INTERNAL_APK"
adb -s "$QJ_PHONE_SERIAL" shell am start --user 0 -n com.qingjing.qingjing_wallpaper.internal/com.qingjing.qingjing_wallpaper.MainActivity
```

同标识、同签名的覆盖升级保留 Keystore/资源/权益。既有 `.local` 安装独立保留，不清数据、不卸载。卸载或清数据会丢失该安装身份的密钥，旧权益留在后端，新的安装不能自动继承；10013 已实际验证 user 0 清数据/无保留卸载/真正新装，三次安装凭据不同、新装权益为 0；既有 .local 后台权益保留。最终 10015 同签名覆盖安装及两类预览确认通过；用户已取消额外完整视频固定测量。若 MIUI streamed 安装被拒绝，使用同一 APK 的 `adb install --no-streaming -r --user 0` 并在显示本包名称的正常提示中选择“继续安装”；不关闭系统安全检查。

## 红米正常设置流程

首次视频/4D 设置如果系统页立即返回，在手机设置→应用管理→倾境壁纸·候选内测→权限管理→其他权限中允许动态壁纸服务，然后回详情重新检测。静态支持桌面、锁屏、两者；视频/4D 的位置由系统选择页提供，Android 13 的 App 不能读取全部位置时显示待检查提示。

本红米的视频选择页实际提供主屏幕及主屏幕和锁定屏幕，共用同一服务和资源；没有独立动态锁屏入口，不承诺独立动态锁屏。普通原生预览仅在 App 内播放，完成预览不表示系统设置成功。1.0 暂时隐藏试用入口与自动恢复界面。4D 详情可倾斜/拖动，MP4 详情循环；未下载作品只使用独立受限预览，不进入系统壁纸设置或产生权益。

MIUI 静态壁纸颜色切换可能重建 Main 页面并返回首页，本次未发现应用崩溃，权益/安全安装保留；回“我的”重新查看即可。兑换出现未确认结果时，使用“确认上次兑换结果”查询原请求，不换作品/码重建意图。下载失败或取消后旧安装保留；正常缓存清理保护动态引用及活动预览，不等同内存或磁盘压力测试。

验收、已知限制和未测范围见 [A10 报告](../06-测试与验收/WP-A10-Android全链路真机验收-2026-09-14.md)。内存/存储不足专项已按用户要求排除，不记为实测通过。最终候选清单及鸿蒙交接待 A10/A11 完成。

最终 APK 本机位置：`.runtime/android-candidate/qingjing-android-internal-1.0.0-10015-arm64.apk`，阶段标签 `android-internal-1.0.0-10015`。华为/荣耀 2.0、苹果 3.0 未开始；最后实际体验由用户验收。
