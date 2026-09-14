# 混合移动端

Flutter 共享客户端正式工程目录。从首版开始保持 Flutter 业务层与 Android、iOS、HarmonyOS 平台适配层分离。

按 [PM-005 App 分平台实施开发计划](../../docs/10-项目管理/PM-005-App分平台实施开发计划.md) 依次实现 Android、HarmonyOS、iOS。安卓阶段保留后两端接口与显式能力占位，三端正式功能分别验收后统一首发。鸿蒙允许仅动态锁屏；当前下一工作包为 WP-A00，尚未开始实施。

WP-P00 至 WP-P12 的数据/API/H5 阶段已完成。当前目录仅为工程入口，未创建 Flutter SDK 工程或正式功能。下一阶段按 [PM-003 App 开工门禁与接入清单](../../docs/10-项目管理/PM-003-App开工门禁与接入清单.md) 开工，契约使用 [OpenAPI 1.0.1](../../contracts/openapi/openapi.yaml) 和 [冻结快照](../../contracts/openapi/baseline-v1.json)。

先建立 Flutter 共享业务层、真实公开目录和平台接口，再配套实现 Android 安全凭据验证、加密下载与原子安装。当前 API 只支持 local/test H5 Provider 和下载占位，不可将 H5 secret/演示设置当作正式 Android 交付。正式下载、预览和系统设置退出条件在接入清单中逐项定义。

视觉源为 packages/design-tokens/qingjing-wallpaper.tokens.json；Dart 常量位于 packages/design-tokens/generated/qingjing_wallpaper_tokens.dart，通过 H5 Token 生成脚本维护，不手工修改生成物。API、本地环境与各工作包验收记录均在 PM-003 中关联。
