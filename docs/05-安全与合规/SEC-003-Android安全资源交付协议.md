# SEC-003 Android 安全资源交付协议

版本：1.2.0；状态：WP-A05 服务端实现与格式冻结，端侧安装由 WP-A06 验收。

## 身份与密钥

保留 A03 RSA 签名密钥及已有 credentialKeyId。该 Keystore 密钥仅授权 SIGN/VERIFY，不能用作 DECRYPT。增加独立 RSA-2048 解密密钥，由现有安装身份签署绑定请求，服务端按 credentialKeyId 固定绑定公钥；同公钥重试幂等，不允许无证据替换。升级不重置身份或权益。卸载/清数据沿用 A03 边界。

内容密钥包装固定 RSA-OAEP-SHA256-MGF1-SHA1，空 PSource label。主摘要 SHA-256、MGF1 SHA-1 均显式指定，不能依赖 JCA provider 的默认值。该组合依据 [Android 官方加密说明](https://developer.android.com/privacy-and-security/cryptography?hl=en)。每个资源版本独立随机 AES-256 内容密钥；DB 仅保存由服务端 master key 加密的内容密钥。资源签名使用独立服务端 RSA-2048 / SHA256withRSA，客户端信任构建中固定的公钥，禁止信任包内自带公钥。

## 安全包格式 2

文件为 AES-256-GCM 加密 ZIP，头部是 8 字节 ASCII `QJWP0002`、12 字节随机 nonce，随后密文和 16 字节 GCM tag。AAD 为 UTF-8 `QJ-PACKAGE-V2\n{wallpaperId}\n{variantId}\n{versionNo}\n{resourceType}`，无末尾换行。

ZIP 包含 `manifest.json` 原始 UTF-8 字节、`manifest.sig` 原始 RSA 签名字节和 `payload/` 内资源。签名对象仅为 manifest 原字节；不得先解析再序列化后验证。manifest 包含 formatVersion=2、作品/变体 ID、版本号、资源类型、文件名/角色/类型/长度/SHA-256 列表。摘要覆盖全部 payload；不允许清单外文件。manifest 摘要、明文 ZIP 摘要和密文摘要进入下载元数据。外层 AAD 与 manifest 身份必须一致。

只支持 STATIC_IMAGE、VIDEO、LAYER_PARALLAX。静态需要一个 STATIC_IMAGE，视频一个 VIDEO；分层需要 BACKGROUND、FOREGROUND、PARALLAX_CONFIG，图层元数据继续校验画布和视差参数。允许 JPEG/PNG/WebP、MP4、JSON。拒绝脚本、动态库、路径越界、目录/符号链接、重复文件名、畸形清单与未知格式。单包原始 payload 总量最多 64 MiB、最多 16 个文件，manifest 最多 64 KiB。客户端先验外层长度/摘要，再 GCM 和 ZIP/manifest 签名/全部文件校验；全部通过后才原子安装。

## 服务端交付与发布

后台页面发布 Android/UNIVERSAL 兼容 READY 版本前，使用既有资源绑定创建不可变安全包，制作失败停止发布；旧 H5_PLACEHOLDER 保留。历史公开版本可能无安全包，Android 新兑换不授予这种资源的权益；已有权益申请不到兼容包时返回 SECURE_PACKAGE_NOT_READY。正式安全包独立记录版本、存储 Adapter key、密文和明文摘要、大小、签名 key ID、受保护内容密钥。V1 迁移不变，新增 V2。原始资产及安全包不提供永久公开读取路径。

票据有效期 90 秒，以随机不可预测 token 引用 Redis 状态，绑定设备/凭据、加密公钥、资源版本和平台。创建时验证权益、发布状态、平台/能力和限流；读取时重新核对凭据/权益/资源状态，不能让撤销后的旧票据继续读取。只通过 Authorization 传票据，无 URL 密钥。Redis 丢失时旧票据失效，可重新申请，不影响 MySQL 权益。

## 实施状态

服务端三类真实媒体制作、原身份签名绑定、设备/版本绑定票据与受控读取已通过隔离 MySQL/Redis HTTP 集成。1.2.0 快照冻结本协议、Schema、契约、领域/数据库设计和 V2；1.1.0 历史快照保留。Android 已增加独立 Keystore 解密公钥生成及签名绑定接口；固定签名信任根、实际解密、安全解包和原子安装由 A06 实施，尚无真机安装通过结论。已开始的流可完成，已下载内容不能远程收回。

## 媒体制作补充约束

制作接口 `POST /admin/resource-versions/{resourceVersionId}/secure-package` 只为未制作的 READY 版本生成包；已有包幂等返回，不重加密、不重签名。使用 FFprobe 探测、FFmpeg 全量解码，子进程限制为 file/pipe 协议、单线程、探测 15 秒/解码 45 秒截止。单资源尺寸最多 4096×4096；视频为 H.264/yuv420p、单视频流无音频、0～30 秒（不含 0）、最多 60 fps。不兼容内容需先转码，不能只凭文件头发布。缺工具时拒绝制作。

format 2 分层配置见 parallax-config-v2.schema.json：通过 role + ordinal 引用 manifest 的资源，无用户文件路径；拒绝重复键、额外字段和尾随 JSON。画布需与每层实际尺寸一致，前景需可确认 alpha 像素格式，depth 按远到近排列。已有 format 1 的 file 路径配置需转换后再制作。

macOS 本地验证使用 [FFmpeg 官方下载页](https://ffmpeg.org/download.html) 列出的 [Evermeet 9.0.1 构建](https://evermeet.cx/ffmpeg/)，位于忽略的 .runtime/media-tools；镜像和 API CI 通过系统包安装 ffmpeg。格式与媒体约束纳入 1.2.0 冻结；本地工具不进入 Git，实际环境需独立配置签名私钥与工具路径。
