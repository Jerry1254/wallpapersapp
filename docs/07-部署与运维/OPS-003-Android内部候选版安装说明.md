# Android 内部候选版安装说明

状态：材料准备中。A10 尚未收口，新装/最终候选验收完成后更新本说明；不是公开首发或生产发布。

## 包与适用范围

当前待定候选输入为 internal release `1.0.0+10013`，ARM64，安装标识 `com.qingjing.qingjing_wallpaper.internal`，显示名称“倾境壁纸·候选内测”。最低 API 26、compile/target API 36；目前只验收 Redmi M2012K11AC、Android 13、1080×2400，不以最低系统版本宣称其他机型已通过。

APK 来源提交 `03538121eab835e2d1cc2296a65001298c983fbd`，SHA-256：`11aa3c2f6906065d454e7540467a5e566aef3e13cc163f22864fe63bf8a9e5cf`。独立内测签名证书 SHA-256：`f46b13574dc73b8bba32d008d4967306edf6d06181b174569ba94b0b7709bfa8`。debuggable=false，查看器不导出，动态服务受 BIND_WALLPAPER 保护；生产包采用独立标识/签名策略，不携带内测信任或诊断。

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

同标识、同签名的覆盖升级保留 Keystore/资源/权益。既有 `.local` 安装独立保留，不清数据、不卸载。卸载或清数据会丢失该安装身份的密钥，旧权益留在后端，新的安装不能自动继承；候选新装/边界实际验收仍待补齐，不把覆盖安装成功记为新装成功。

## 红米正常设置流程

首次视频/4D 设置如果系统页立即返回，在手机设置→应用管理→倾境壁纸·候选内测→权限管理→其他权限中允许动态壁纸服务，然后回详情重新检测。静态支持桌面、锁屏、两者；视频/4D 的位置由系统选择页提供，Android 13 的 App 不能读取全部位置时显示待检查提示。

本红米的视频选择页实际提供主屏幕及主屏幕和锁定屏幕，共用同一服务和资源；没有独立动态锁屏入口，不承诺独立动态锁屏。普通原生预览仅在 App 内播放，完成预览不表示系统设置成功。试用带标记及两分钟倒计时，后台继续计时，不能设置系统壁纸或产生正式权益。

MIUI 静态壁纸颜色切换可能重建 Main 页面并返回首页，本次未发现应用崩溃，权益/安全安装保留；回“我的”重新查看即可。兑换出现未确认结果时，使用“确认上次兑换结果”查询原请求，不换作品/码重建意图。下载失败或取消后旧安装保留；正常缓存清理保护动态引用及活动预览，不等同内存或磁盘压力测试。

验收、已知限制和未测范围见 [A10 报告](../06-测试与验收/WP-A10-Android全链路真机验收-2026-09-14.md)。内存/存储不足专项已按用户要求排除，不记为实测通过。最终候选清单及鸿蒙交接待 A10/A11 完成。
