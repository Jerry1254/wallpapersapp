# 壁纸格式共享定义

理解 4D 效果可先阅读 [4D 分层视差壁纸原理](../../docs/02-系统架构/ARC-003-4D分层视差壁纸原理.md)，制作素材请查看 [4D 源包制作说明](4D源包制作说明.md)。当前固定 ZIP 支持 2～12 层，源配置使用 `parallax-source-v1.schema.json`。

正式交付采用冻结的安全包 formatVersion=2，见 `manifest-v2.schema.json`、`parallax-config-v2.schema.json` 和 [SEC-003](../../docs/05-安全与合规/SEC-003-Android安全资源交付协议.md)。服务端制作、签名、加密和交付已由 WP-A05 的真实媒体集成验证；端侧安全安装属于 WP-A06。

[格式 v0.1](../../docs/03-数据设计/壁纸资源包规范-v0.1.md) 和格式 1 保留为 PoC 输入，不自动作为正式包。Schema 只检查结构；制作器和安装器还须校验角色/文件一一对应、唯一性、类型组合、累计大小和实际媒体内容。签名必须验证 manifest 原始字节，信任根来自客户端构建，禁止信任包内公钥。

App 内受限试用使用独立 formatVersion=3、purpose=APP_PREVIEW、QJPV0001 与 QJ-PREVIEW-V1 AAD，见 manifest-preview-v3.schema.json 和 [SEC-004](../../docs/05-安全与合规/SEC-004-Android受限试用交付协议.md)。配置仍按 parallax-config-v2.schema.json 校验，受限画布最长边 1280；独立票据和临时库不进入正式下载或系统壁纸入口。不能把修改描述的 format 字段当成正式授权。
