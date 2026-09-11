# OpenAPI 契约

客户端、管理后台与 Java API 共用的 OpenAPI 定义目录。接口字段、错误码和版本在这里冻结，再生成或实现各端调用层。

## 文件

- `openapi.yaml`：V1 唯一机器契约。
- `scripts/check-contract.mjs`：项目边界与敏感字段专项检查。
- `package.json` / `package-lock.json`：固定契约校验工具版本。

## 验证

~~~bash
npm ci
npm test
~~~

生成单文件 bundle 时执行 `npm run bundle`，产物写入已被 Git 忽略的 `dist/`。
