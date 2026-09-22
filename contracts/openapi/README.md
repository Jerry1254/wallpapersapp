# OpenAPI 契约

正式 App、管理后台与 Java API 共用的机器契约。接口字段、错误码和版本先在这里冻结，再由各端实现。

## 当前版本

**2.2.0，61 个操作 / 97 个 Schema。**

本版完成单商品多设置能力与设备能力协商：

- 删除商品单一 `kind`；
- 管理端支持 Android 4D、Android 动态、iOS 动态、鸿蒙原生动态和通用静态五项独立能力；
- 新增签名设备能力上报与有效档案读取；
- 分类、列表、详情和已获得列表按当前设备能力交集返回；
- 预览和正式下载按精确 `deliveryPlatform + resourceType` 选择，不跨形式回退；
- 公开列表支持按当前设备的精确 `deliveryPlatform + resourceType` 在计数和分页前筛选；
- 静态入口统一使用 `UNIVERSAL + STATIC_IMAGE`，`view` 只保留 `FEATURED`；
- V9 新增设备能力档案、变体启停，并移除 `wallpaper.kind`。
- 4D ZIP 改为只包含 `config.json` 与 2～12 层图片；列表封面作为独立 `WALLPAPER_COVER` 资产上传，V10 放宽源包 READY 状态对旧封面的依赖。

App 对接流程见 [API-015](../../docs/04-接口设计/API-015-设备能力目录与多形式交付App对接说明.md)，精确能力筛选增量见 [API-017](../../docs/04-接口设计/API-017-设备目录精确能力筛选App交接确认说明.md)，完整产品和服务端规则见 [API-014](../../docs/04-接口设计/API-014-单商品多设置能力与设备能力协商全链路整改方案.md)。

## 文件

- `openapi.yaml`：当前唯一机器契约。
- `scripts/check-contract.mjs`：项目边界、关键操作和敏感字段专项检查。
- `package.json` / `package-lock.json`：固定校验工具版本。
- `baseline-v1.json`：当前契约冻结入口。
- `baseline-v2.1.0.json`：2.1.0 逐版本归档。
- `baseline-v2.2.0.json`：2.2.0 逐版本归档。
- `baseline-v2.0.0.json`：2.0.0 逐版本归档。
- `baseline-v1.*.json`：历史版本归档，只用于历史核验。
- `scripts/check-baseline.mjs`：只读核对 SHA-256、版本和操作/Schema 数量。

## 验证

```bash
npm ci
npm test
npm run bundle
npm run baseline:check
```

`npm test` 使用 Redocly 校验 OpenAPI，并检查 operationId、字符串 Long ID、幂等头、乐观锁、设备签名头、敏感字段和 Java 直接 `ApiException` 错误码。它不代替 Java MySQL/Redis 集成测试或 App 真机设置验收。

`npm run bundle` 生成 `dist/openapi.yaml`；`dist/` 不提交 Git。升级契约时必须同步语义文档、Java、管理后台、测试和冻结基线。
