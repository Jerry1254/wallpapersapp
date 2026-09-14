# Android 原生适配包

Flutter 页面通过本包的类型化类调用插件，不直接使用 MethodChannel。当前提供安装 RSA 签名身份、独立 Keystore 解密公钥、format 2 下载与安全安装；原生预览和系统壁纸设置由 WP-A07/A08 接入。

`AndroidDeviceIdentity` 保存安装签名身份和 credentialKeyId，只返回公钥/指纹/签名；`encryptionPublicKey()` 返回独立解密公钥。服务端按原凭据固定绑定公钥，私钥始终留在 AndroidKeyStore。

`AndroidPackageInstaller` 实现共享 PackageInstaller 接口：先 prepare(SecurePackageDownload)，再 install(requestId)。下载描述来自已签名的正式设备 API；调用方必须固定同源下载路径和所选作品/资源类型。插件再次检查平台、元数据、密钥指纹及全部包内容。返回 completed/installedId 只表示安全安装完成，取消或失败不会表示系统设置成功。current(wallpaperId, resourceType) 返回私有库中的已安装 ID，不暴露资源路径；progress 回传当前请求的进度与阶段，cancel(requestId) 取消传输。

clearUnused() 拒绝与下载同时运行，并保留 active-home/active-lock 的所有使用版本。原生设置流程成功后需要写入对应使用记录；原子版本更新保留旧资源直到显式清理。

构建 local/prod 时分别设置 QJ_LOCAL_PACKAGE_SIGNING_KEY_ID / QJ_LOCAL_PACKAGE_PUBLIC_KEY_DER 或 QJ_PROD_PACKAGE_SIGNING_KEY_ID / QJ_PROD_PACKAGE_PUBLIC_KEY_DER。公钥为 RSA-2048 SPKI DER 的标准 Base64，服务端 QJ_PACKAGE_SIGNING_KEY_ID 必须匹配，私钥只配置到 API。空信任根禁用安装；local 配置不会作为 production 的默认值。正式契约和尺寸/媒体限制见 [SEC-003](../../docs/05-安全与合规/SEC-003-Android安全资源交付协议.md)。

验证见 [原生互通夹具](../../scripts/native-package-fixture/README.md) 和 [WP-A06 记录](../../docs/06-测试与验收/WP-A06-下载与安全安装实施记录-2026-09-14.md)。真机全链路、升级与系统设置继续待验收；Flutter integration test 会卸载测试 APK，不能拿这一方式检验已有权益安装的升级保留。
