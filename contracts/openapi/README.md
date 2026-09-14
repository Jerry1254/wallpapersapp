# OpenAPI 契约

客户端、管理后台与 Java API 共用的 OpenAPI 定义目录。接口字段、错误码和版本在这里冻结，再生成或实现各端调用层。

## 文件

- `openapi.yaml`：V1 唯一机器契约。
- `scripts/check-contract.mjs`：项目边界与敏感字段专项检查。
- `package.json` / `package-lock.json`：固定契约校验工具版本。
- `baseline-v1.json`：当前 App 契约冻结快照；历史版本逐字节归档在 baseline-v1.0.1.json、baseline-v1.1.0.json、baseline-v1.2.0.json。
- `scripts/check-baseline.mjs`：只读核对快照 SHA-256、版本和数量；字段变更须同步语义、实现、测试并显式更新版本/快照。

## 验证

~~~bash
npm ci
npm test
npm run baseline:check
~~~

生成单文件 bundle 时执行 `npm run bundle`，产物写入已被 Git 忽略的 `dist/`。

`npm test` 同时检查直接 Java ApiException 字面量错误码是否在 ErrorCode 中；它不证明动态错误或所有运行时响应均满足契约。Android 安装持钥证明、SECURE_PACKAGE 和 APP_PREVIEW 已实现；鸿蒙/iOS Provider 与平台交付须按 [PM-005](../../docs/10-项目管理/PM-005-App分平台实施开发计划.md) 独立接入验收。

WP-A03 当前兼容扩展为 1.1.0：Android 安装 RSA 持钥证明、RSA_SHA256 挑战算法与注册 401 响应；仍为 47 操作/72 Schema。当前 baseline-v1.json 包含原九文件和 SEC-002 共十项，六个原文件的计划变更已审阅；V1 SQL/Token 字节未改。baseline-v1.0.1.json 为原快照逐字节归档，可在 78cb4c0 历史树验证，不能用当前树假装旧快照通过。检查脚本不生成或重写摘要。

后续版本记录：A05 1.2.0 增加安全交付与 V2，49 操作/74 Schema/14 冻结文件，其字节快照归档在 baseline-v1.2.0.json，对应接受输入可在 95a168e 历史树核验。A09 1.3.0 增加独立试用票据/流、三个 Schema、V3 和 SEC-004；当前为 51 操作/77 Schema，正式 format 2 与 H5 行为保留。当前树通过新冻结不代表旧冻结通过；升级检查包括 V1/V2 原字节保持、MySQL 事实保留和票据/包用途隔离。端侧真机验收状态以 A09 记录为准。
