# API-001 OpenAPI V1 契约说明

**状态：** 已确认

**版本：** V1.0.0

**日期：** 2026-09-11

**机器契约：** [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml)

**语义基线：** DM-001、DB-001、ARC-001、SEC-001

## 1. 契约结论

V1 使用同一份 OpenAPI 3.0.3 契约服务 H5、正式 App 和管理后台，但按调用方分成三个边界：

| 边界 | 路径前缀 | 身份 | 负责内容 |
|---|---|---|---|
| 公开目录 | `/public` | 无 | 分类、列表、搜索、详情、分类图标和壁纸封面 |
| 匿名设备 | `/device`、`/delivery` | 设备短期 Bearer 会话；敏感写入另带请求签名 | 设备注册、会话、兑换、权益和下载描述 |
| 管理端 | `/admin` | HttpOnly Cookie；写操作另带 CSRF token | 管理登录、资源、分类、壁纸、版本、批次、事件和设备查询 |

数据库实体不直接作为响应模型。公开模型、设备模型和管理模型独立裁剪，任何响应都不得包含 `storageKey`、绝对路径、兑换码摘要、设备证据摘要、密码摘要、私钥或明文内容密钥。

## 2. 端点范围

### 2.1 公开目录

- `GET /public/categories` 返回最多两级的分类树与已发布壁纸计数。
- `GET /public/wallpapers` 统一承担分类筛选、推荐视图、静态视图、平台筛选和搜索。
- `GET /public/wallpapers/{wallpaperId}` 只返回已发布作品、封面和交付能力描述。
- `GET /public/assets/{assetId}/content` 只允许读取已发布目录引用的分类图标和封面。

`FEATURED` 和 `STATIC` 是计算得到的系统视图，不是可写分类。`platforms` 和兼容性也从已发布变体计算，客户端不能写回。

### 2.2 设备、兑换和权益

- `POST /device/registrations` 创建或重新关联设备凭据。正式 App 使用安装公钥；H5 联调环境使用一次性返回的测试 secret。
- `POST /device/session-challenges` 与 `POST /device/sessions` 使用一次性挑战建立短期会话。
- `GET /device/me/entitlements` 是“我的壁纸”的服务端事实来源。
- `POST /device/redemptions` 执行幂等兑换事务，`GET /device/redemptions/{idempotencyKey}` 用于超时后的结果确认。
- `POST /device/wallpapers/{wallpaperId}/download-tickets` 在同一契约中返回 H5 占位描述或 App 安全资源包描述。
- `GET /delivery/files` 只接受放在 `Authorization` header 中的短时、限定设备与资源版本的 Bearer 票据。

H5 的已下载、已设置和试用倒计时继续保存在客户端本地，不通过 API 伪造成权益。服务端权益只由兑换事务创建。

### 2.3 管理端

管理端覆盖单管理员会话、首页汇总、文件上传、两级分类、壁纸作品、平台变体、资源版本、发布/下架/归档、兑换码批次、掩码兑换码、兑换事件和设备权益查询。

完整兑换码只存在于创建批次后签发的短时 CSV 交付材料。批次历史和码列表只返回掩码、尾号和额度；下载 CSV 时通过 `X-Delivery-Ticket` header 传递票据，避免进入 URL 日志。确认交付或票据过期后，服务端不能从数据库恢复明文。

资源写入按以下顺序执行：

1. 上传单个文件并等待 Asset 校验为 `READY`。
2. 创建平台变体。
3. 使用 Asset ID 和角色绑定创建候选资源版本。
4. 资源版本校验为 `READY` 后，通过壁纸发布操作选中版本。
5. 发布后的资源版本和文件绑定不可修改；替换文件必须创建新版本。

## 3. 通用传输规则

### 3.1 ID、时间和命名

- MySQL `BIGINT`/Java `Long` 在 JSON 中一律返回十进制字符串，避免 JavaScript 精度丢失。
- 时间使用带时区的 ISO 8601 字符串；服务端按 UTC 存储和比较。
- JSON 字段使用 `camelCase`，枚举使用大写下划线形式。
- 客户端只能持久化契约明确允许的公开 ID、凭据 ID 和会话材料，不能依赖数据库列名。

### 3.2 分页和排序

列表统一使用从 1 开始的 `page` 和 `pageSize`，`pageSize` 默认 20、最大 100。响应统一返回 `page`、`pageSize`、`totalItems` 和 `totalPages`。

稳定排序规则如下：

| 列表 | 主排序 | 最终稳定排序 |
|---|---|---|
| 公开普通列表 | `sortOrder ASC` | `id DESC` |
| 公开推荐 | `featuredRank ASC` | `id DESC` |
| 公开最新 | `publishedAt DESC` | `id DESC` |
| 管理壁纸 | `updatedAt DESC` | `id DESC` |
| 批次、兑换、设备 | 业务时间 `DESC` | `id DESC` |

每次查询的 `totalItems` 与该次查询条件一致，不承诺跨多次翻页形成数据库快照。数据变化时客户端刷新第一页。

### 3.3 幂等与并发

兑换和批次生成要求 `Idempotency-Key`，格式为 UUID。同一主体、同一键和相同规范化请求体返回原结果；同一键异参返回 `409 IDEMPOTENCY_KEY_REUSED`。客户端遇到超时必须复用原键，不能生成新键重试同一次用户意图。

可编辑内容通过响应 `ETag: "{lockVersion}"` 和写请求 `If-Match` 实现乐观锁。版本不一致返回 `412 VERSION_CONFLICT`，客户端重新读取后再决定是否提交。

### 3.4 设备证明规范

设备会话挑战的 proof 对以下 UTF-8 文本签名或计算 HMAC，换行符固定为 `\n`，字段不做额外空白处理：

~~~text
QJ-DEVICE-SESSION-V1
{credentialKeyId}
{challengeId}
{nonce}
{clientTimestamp}
~~~

正式 App 按挑战返回的算法使用安装私钥签名；H5 测试 Provider 使用服务端一次性返回的 secret 计算 HMAC-SHA-256。proof 使用无填充 Base64URL 编码。

兑换和下载票据请求还必须带 `X-Request-Timestamp`、`X-Request-Nonce` 和 `X-Request-Signature`。签名载荷为：

~~~text
QJ-SIGNED-REQUEST-V1
{UPPERCASE_METHOD}
{PATH_WITH_SORTED_QUERY}
{X-Request-Timestamp}
{X-Request-Nonce}
{LOWERCASE_HEX_SHA256_OF_EXACT_BODY_BYTES}
~~~

查询参数按名称、再按值进行 Unicode 码点升序排列，使用 RFC 3986 百分号编码；无查询参数时第三行只包含路径。空请求体的 SHA-256 按零字节计算。服务端限制时间偏差并在短时窗口内拒绝 nonce 重放。

## 4. 状态码和错误模型

成功响应使用 `200`、`201`、`202` 或 `204`。通用错误体为：

~~~json
{
  "error": {
    "code": "VALIDATION_FAILED",
    "message": "请求字段校验失败",
    "requestId": "da6833e9-2c14-41ec-b933-1d672a516abc",
    "details": [
      { "field": "slug", "reason": "格式不正确" }
    ]
  }
}
~~~

客户端流程只依赖稳定 `code`，不匹配 `message`。错误码分组如下：

| HTTP | 稳定错误码 |
|---|---|
| 400 | `VALIDATION_FAILED`、`MALFORMED_REQUEST` |
| 401 | `UNAUTHORIZED`、`SESSION_EXPIRED` |
| 403 | `INVALID_DEVICE_PROOF`、`DEVICE_DISABLED`、`CSRF_INVALID`、`FORBIDDEN` |
| 404 | `RESOURCE_NOT_FOUND`、`CATEGORY_NOT_FOUND`、`WALLPAPER_NOT_FOUND`、`ASSET_NOT_FOUND`、`CREDENTIAL_NOT_FOUND`、`REDEMPTION_REQUEST_NOT_FOUND` |
| 409 | `DUPLICATE_SLUG`、`DUPLICATE_CATEGORY_NAME`、`DUPLICATE_VARIANT`、`IDEMPOTENCY_KEY_REUSED`、`STATE_CONFLICT`、`RESOURCE_IN_USE` |
| 412 | `VERSION_CONFLICT` |
| 413/415 | `PAYLOAD_TOO_LARGE`、`UNSUPPORTED_MEDIA_TYPE` |
| 422 | `DOMAIN_RULE_VIOLATION`、`ASSET_NOT_READY`、`ASSET_VALIDATION_FAILED`、`RESOURCE_VERSION_NOT_READY`、`WALLPAPER_UNAVAILABLE`、`CODE_NOT_FOUND`、`CODE_EXHAUSTED`、`ENTITLEMENT_REQUIRED`、`DELIVERY_UNAVAILABLE`、`UNSUPPORTED_DEVICE` |
| 410 | `TICKET_EXPIRED` |
| 429 | `RATE_LIMITED`，并返回 `Retry-After` |
| 500/503 | `INTERNAL_ERROR`、`SERVICE_UNAVAILABLE` |

兑换业务拒绝使用 `422`，响应体为已持久化的 `RedemptionResult`，其中 `result` 精确区分码不存在、额度耗尽和壁纸不可用。这样客户端重试和结果查询能得到同一最终事实。

## 5. 下载模式兼容

下载响应通过 `deliveryMode` 区分实现：

| 模式 | 使用方 | 返回内容 |
|---|---|---|
| `H5_PLACEHOLDER` | 当前 H5 联调 | 封面和占位交互；ticket、资源版本和安全包字段为空 |
| `SECURE_PACKAGE` | 后续正式 App | 短时 ticket、资源版本、密文摘要、大小和按设备公钥包装的内容密钥 |

两种模式使用同一端点和作品权益判断。后续接入加密包、对象存储或 CDN 时，可以替换交付 Adapter，不改变作品、兑换和权益接口。

## 6. 契约验证

在 `contracts/openapi` 目录执行：

~~~bash
npm ci
npm test
~~~

`npm test` 先使用 Redocly 校验 OpenAPI 结构和引用，再执行项目专项检查，确认关键端点、稳定 operationId、Long ID 字符串、兑换结果枚举、幂等头、乐观锁头、敏感设备签名头和禁止暴露字段没有漂移。
