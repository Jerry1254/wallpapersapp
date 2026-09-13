# OpenAPI 契约

客户端、管理后台与 Java API 共用的 OpenAPI 定义目录。接口字段、错误码和版本在这里冻结，再生成或实现各端调用层。

## 文件

- `openapi.yaml`：V1 唯一机器契约。
- `scripts/check-contract.mjs`：项目边界与敏感字段专项检查。
- `package.json` / `package-lock.json`：固定契约校验工具版本。
- `baseline-v1.json`：WP-P12 App 开工快照，OpenAPI 1.0.1、47 个操作、72 个 Schema 和 9 个领域/契约/结构/Token 文件摘要。
- `scripts/check-baseline.mjs`：只读核对快照 SHA-256、版本和数量；字段变更须同步语义、实现、测试并显式更新版本/快照。

## 验证

~~~bash
npm ci
npm test
npm run baseline:check
~~~

生成单文件 bundle 时执行 `npm run bundle`，产物写入已被 Git 忽略的 `dist/`。

`npm test` 同时检查直接 Java ApiException 字面量错误码是否在 ErrorCode 中；它不证明动态错误或所有运行时响应均满足契约。正式平台设备验证与 SECURE_PACKAGE 当前未实现，App 接入顺序与前置条件见 [PM-003](../../docs/10-项目管理/PM-003-App开工门禁与接入清单.md)。
