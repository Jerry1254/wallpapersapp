# ARC-002 Android 正式客户端实施增补

版本 1.0.0，日期 2026-09-14。依据 PM-005 的已确认决策，补充 ARC-001 历史基线；本增补不修改 OpenAPI 1.0.1、领域/数据库字段、Flyway V1 或历史九文件摘要。后续身份/资源契约修改在 A03/A05 独立版本化。

当前范围 Android → HarmonyOS → iOS → 统一首发。Android 使用 Flutter 页面与业务，Kotlin 平台适配；后两端保留类型边界，未创建可用客户端，不以 Android 构建结果证明支持。首版包含静态、视频、4D，效果与 HOME/LOCK/BOTH 能力分开检测，设置结果保留取消/未知/不支持。

正式目录 apps/mobile。共享 Token 从现有生成物导出，纯 Dart 平台接口独立于 Flutter 和 Android 类。初始运行首页/我的基础壳；A01/A02 接入真实目录/详情；A03/A04 实现正式身份和服务端权益；A05/A06 安全交付/安装；A07/A08 原生能力；A09 试用；A10/A11 真机验收和内部候选。阶段状态只在 PM-002。

开发与生产使用 local/prod flavor 和不同 applicationId。仅 localDebug 开放明文 API；其余包要求 HTTPS。localRelease 用测试签名验证构建，不是提审包；生产签名待候选阶段配置。API、MySQL、Redis 和存储保持本地环境隔离，不复制生产数据。禁用备份，正式凭据丢失后的权益恢复不得依据本地 ID 自行关联。

原 PoC 的传感器、渲染、预览和 WallpaperService 按 apps/mobile/README.md 清单迁移。平台插件必须隔离预览和壁纸服务生命周期，只有验证安装成功才保存本地已下载，只有真实可验证的系统结果才显示设置完成。
