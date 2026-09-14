# Android 原生适配包

Flutter 页面通过本包的类型化类调用插件，不直接使用 MethodChannel。当前提供安装 RSA 签名身份、独立 Keystore 解密公钥、format 2 下载与安全安装，以及 WP-A07 的静态/视频预览和设置入口；4D 原生能力留待 WP-A08。

`AndroidDeviceIdentity` 保存安装签名身份和 credentialKeyId，只返回公钥/指纹/签名；`encryptionPublicKey()` 返回独立解密公钥。服务端按原凭据固定绑定公钥，私钥始终留在 AndroidKeyStore。

`AndroidPackageInstaller` 实现共享 PackageInstaller 接口：先 prepare(SecurePackageDownload)，再 install(requestId)。下载描述来自已签名的正式设备 API；调用方必须固定同源下载路径和所选作品/资源类型。插件再次检查平台、元数据、密钥指纹及全部包内容。返回 completed/installedId 只表示安全安装完成，取消或失败不会表示系统设置成功。current(wallpaperId, resourceType) 返回私有库中的已安装 ID，不暴露资源路径；progress 回传当前请求的进度与阶段，cancel(requestId) 取消传输。

clearUnused() 拒绝与下载同时运行，保留 active-home/active-lock、视频服务提交版本、系统设置待确认版本及所有预览/播放租约。单进程共用原子资源库，创建预览或服务不会重新清理下载暂存目录；原子版本更新保留旧资源直到显式清理。

`AndroidWallpaperPlayback` 实现共享 Preview/WallpaperApply。capabilities() 读取系统壁纸策略、H.264 解码器及动态壁纸入口；open(installedId,effect) 复验原始 manifest 签名、身份和所有文件哈希后进入 App 内全屏预览；apply(installedId,effect,target) 只使用已安装私有资源。静态 home/lock/both 使用 WallpaperManager.setStream，不允许备份，返回 ID 与请求位置一致才报告 completed；BOTH 桌面 ID 匹配、锁屏无独立 ID 时按系统共用画面规则确认。预览 completed 仅表示预览已展示并结束。

视频 apply 只接受 home 作为打开系统选择页的入口，`systemChoosesLiveTarget` 为 true 时 UI 必须说明位置由系统选择。不能把它解释为保证独立动态桌面或锁屏；lock/both 不作为可直接请求的能力。系统取消保留原提交版本。RESULT_OK、待设置视频已渲染且系统查询到倾境服务时提交并描述实际位置。Android 13 及更早系统无法读取组件时，RESULT_OK 与已渲染只允许保留新视频供已启用服务播放，结果仍为 unknown，不声称某个位置成功；Android 14+ 可以查询却未找到倾境服务时保留旧视频。两处使用同一倾境服务时共用视频，不支持独立配置两段视频；未申请 QUERY_ALL_PACKAGES。

红米 Android 13 的系统选择器另检查 MIUI AppOp 10045。`LiveWallpaperPolicy` 只读查询本应用 UID，在确认拒绝时禁用视频设置目标并返回 setupMessage，引导用户从系统“动态壁纸服务”权限开启后重新检测；不自动授予权限或修改 AppOps。非 SDK 查询在其他 ROM 不可用时回到系统选择页，不假定拒绝。当前只在 M2012K11AC 验证，不据此宣称所有小米 ROM 兼容。

VideoWallpaperService 的每个 Engine 和 App 预览各自创建播放器。视频静音循环且居中裁切；不可见或 Surface 销毁释放播放器；可见/进程重建时从持久提交指针复验重载。预览使用待选指针，已有桌面使用提交指针，取消不替换桌面内容。debugPlaybackState 仅调试包可调用，输出系统 ID、位置查询能力和 Engine 状态，不返回文件路径或密钥；QJPlayback 生命周期日志仅 debuggable 包输出状态变化。

构建 local/prod 时分别设置 QJ_LOCAL_PACKAGE_SIGNING_KEY_ID / QJ_LOCAL_PACKAGE_PUBLIC_KEY_DER 或 QJ_PROD_PACKAGE_SIGNING_KEY_ID / QJ_PROD_PACKAGE_PUBLIC_KEY_DER。公钥为 RSA-2048 SPKI DER 的标准 Base64，服务端 QJ_PACKAGE_SIGNING_KEY_ID 必须匹配，私钥只配置到 API。空信任根禁用安装；local 配置不会作为 production 的默认值。正式契约和尺寸/媒体限制见 [SEC-003](../../docs/05-安全与合规/SEC-003-Android安全资源交付协议.md)。

验证见 [原生互通夹具](../../scripts/native-package-fixture/README.md)、[WP-A06 记录](../../docs/06-测试与验收/WP-A06-下载与安全安装实施记录-2026-09-14.md) 和 [WP-A07 记录](../../docs/06-测试与验收/WP-A07-静态与视频原生实施记录-2026-09-14.md)。真机全链路、升级与目标机型设置继续待验收；Flutter integration test 会卸载测试 APK，不能拿这一方式检验已有权益安装的升级保留。
