# API-001 OpenAPI V1 契约说明

**状态：** 已确认

**版本：** V1.6.0

**日期：** 2026-09-15

**机器契约：** [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml)

**语义基线：** DM-001、DB-001、ARC-001、SEC-001

## 1. 契约结论

当前 V1.6.0 共 58 个操作、86 个 Schema。4D 源包响应增加只读 `configFormatVersion=2`，图层响应只保留文件结构字段；配置算法字段仅存在于原始 `config.json`，不通过管理 DTO 展开。固定 ZIP 格式、原样透传、正式发布门禁和错误边界见 [API-007](API-007-4D固定资源包导入接口.md) 与 [API-008](API-008-4D配置透传与管理后台改造方案.md)。公开目录和设备端点数量不变。

V1 使用同一份 OpenAPI 3.0.3 契约服务 H5、正式 App 和管理后台，但按调用方分成三个边界：

| 边界 | 路径前缀 | 身份 | 负责内容 |
|---|---|---|---|
| 公开目录 | `/public` | 无 | 分类、列表、搜索、详情、分类图标、壁纸封面和设置教程视频 |
| 匿名设备 | `/device`、`/delivery`、`/preview` | 设备短期 Bearer 会话；敏感写入另带请求签名；文件使用独立短时票据 | 设备注册、会话、兑换、权益、正式下载和受限试用描述 |
| 管理端 | `/admin` | HttpOnly Cookie；写操作另带 CSRF token | 管理登录、资源、分类、壁纸、设置教程、版本、批次、事件和设备查询 |

数据库实体不直接作为响应模型。公开模型、设备模型和管理模型独立裁剪，任何响应都不得包含 `storageKey`、绝对路径、兑换码摘要、设备证据摘要、密码摘要、私钥或明文内容密钥。

WP-P12 冻结版本为 1.0.1，47 个操作和 72 个 Schema 保持不变。相对 1.0.0 补齐已实现的 20 个设备/交付错误码，以及处理中的 202、设备挑战/会话、缺失壁纸和 nonce 冲突响应；不存在的 wallpaperId 兑换改为 404 WALLPAPER_NOT_FOUND，事务回滚，不形成权益或额度扣减。业务拒绝仍为 422 RedemptionResult。冻结文件摘要和变更门禁见 [App 开工门禁](../10-项目管理/PM-003-App开工门禁与接入清单.md)。

## 2. 端点范围

### 2.1 公开目录

- `GET /public/categories` 返回最多两级的分类树与已发布壁纸计数。
- `GET /public/wallpapers` 统一承担分类筛选、推荐视图、静态视图、平台筛选和搜索。
- `GET /public/wallpapers/{wallpaperId}` 只返回已发布作品、封面和交付能力描述。
- `GET /public/assets/{assetId}/content` 只允许读取已发布目录引用的分类图标和封面。
- `GET /public/wallpaper-tutorials` 只返回已启用且 MP4 已就绪的固定教程槽位。
- `GET/HEAD /public/wallpaper-tutorials/{tutorialKey}/video` 返回公开 MP4，GET 支持单段 bytes Range。

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

管理端覆盖单管理员会话、首页汇总、文件上传、两级分类、壁纸作品、平台变体、资源版本、发布/下架/归档、五个固定设置教程槽位、兑换码批次、掩码兑换码、兑换事件和设备权益查询。教程先以 `purpose=TUTORIAL_VIDEO` 上传真实 MP4，再使用 `If-Match` 修改视频、启停和排序；不提供新增或删除槽位的端点。

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
| 400 | `VALIDATION_FAILED`、`MALFORMED_REQUEST`、`SIGNED_REQUEST_INVALID` |
| 401 | `UNAUTHORIZED`、`SESSION_EXPIRED`、`CHALLENGE_INVALID`、`CREDENTIAL_INVALID`、`CREDENTIAL_REVOKED`、`PROOF_INVALID`、`TIMESTAMP_INVALID`、`DOWNLOAD_TICKET_INVALID` |
| 403 | `INVALID_DEVICE_PROOF`、`DEVICE_DISABLED`、`CSRF_INVALID`、`FORBIDDEN`、`DEVICE_PROVIDER_NOT_ALLOWED`、`DEVICE_PROVIDER_UNAVAILABLE`、`REQUEST_SIGNATURE_INVALID`、`PLATFORM_MISMATCH`、`ENTITLEMENT_REQUIRED`、`DELIVERY_TICKET_INVALID` |
| 404 | `RESOURCE_NOT_FOUND`、`CATEGORY_NOT_FOUND`、`WALLPAPER_NOT_FOUND`、`ASSET_NOT_FOUND`、`CREDENTIAL_NOT_FOUND`、`REDEMPTION_REQUEST_NOT_FOUND`、`CODE_BATCH_NOT_FOUND`、`DEVICE_NOT_FOUND`、`REDEMPTION_NOT_FOUND` |
| 409 | `DUPLICATE_SLUG`、`DUPLICATE_CATEGORY_NAME`、`DUPLICATE_VARIANT`、`IDEMPOTENCY_KEY_REUSED`、`STATE_CONFLICT`、`RESOURCE_IN_USE`、`CODE_GENERATION_CONFLICT`、`REDEMPTION_PROCESSING`、`REQUEST_NONCE_REUSED`、`DELIVERY_EXPIRED` |
| 412 | `VERSION_CONFLICT` |
| 413/415 | `PAYLOAD_TOO_LARGE`、`UNSUPPORTED_MEDIA_TYPE` |
| 422 | `DOMAIN_RULE_VIOLATION`、`ASSET_NOT_READY`、`ASSET_VALIDATION_FAILED`、`RESOURCE_VERSION_NOT_READY`、`WALLPAPER_UNAVAILABLE`、`CODE_NOT_FOUND`、`CODE_EXHAUSTED`、`DELIVERY_UNAVAILABLE`、`UNSUPPORTED_DEVICE`、`SECURE_PACKAGE_NOT_READY` |
| 410 | `TICKET_EXPIRED`、`DELIVERY_EXPIRED` |
| 429 | `RATE_LIMITED`，并返回 `Retry-After` |
| 500/503 | `INTERNAL_ERROR`、`SERVICE_UNAVAILABLE` |

兑换业务拒绝使用 `422`，响应体为已持久化的 `RedemptionResult`，其中 `result` 精确区分码不存在、额度耗尽和壁纸不可用。这样客户端重试和结果查询能得到同一最终事实。

POST 兑换和 GET 结果的 202 均返回 `{status: "PROCESSING", idempotencyKey}`，保留原意图等待确认。不存在的 wallpaperId 用 404 ErrorEnvelope 区分于已存在但下线作品的 422 最终业务结果。`DELIVERY_EXPIRED` 在重新生成已确认批次交付时为 409，在读取已过期/已确认 CSV 时为 410。表中保留的通用码不代表所有端点都会返回，具体响应以机器契约为准；客户端保留未知 code 的安全回退。

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

专项检查还比对 Java 中直接写出的 ApiException 字面量错误码与 ErrorCode 枚举；它不能证明动态错误码或每个响应实例都满足 Schema。`npm run baseline:check` 另外核验冻结文件 SHA-256 与版本，不代替运行时集成测试。

## 1.1.0 Android 安装身份增补

WP-A03 按 [SEC-002](../05-安全与合规/SEC-002-Android安装身份协议.md) 使用 Keystore RSA 2048 安装公钥持钥证明，挑战新增 RSA_SHA256。注册证据为签名时间/nonce/proof，同密钥新证明恢复同一 ACTIVE credential，禁用/撤销拒绝；不证明物理设备唯一性或 APK 来源。清数据/卸载丢失密钥后无自动权益迁移。Android evidence_hash 为 HMAC(scope + 公钥 DER 指纹)，公钥写入已有 public_key_pem，secret_hash 为空。

数据库结构无变化，Flyway V1 字节保持；不创建无必要 V2。H5 HMAC 流程兼容，消费者枚举兼容新增算法但 H5 仍拒绝非 HMAC。历史 1.0.1 快照保留，当前 1.1.0 审核快照记录本次字段语义与 DTO 变更，验收结果见 WP-A03。

## 1.2.0 安全资源交付

机器契约新增 2 操作，合计 49 操作、74 Schema。`PUT /device/encryption-key` 使用原安装 RSA 签名正文，绑定独立解密公钥；同值 200，异值 409。`POST /admin/resource-versions/{id}/secure-package` 使用管理会话及 CSRF，制作 READY 版本的不可变包，返回 AdminResourceVersion 的真实 manifest 摘要。

Android 下载票据返回 SECURE_PACKAGE，增加 variantId、formatVersion=2、plaintextSizeBytes、plaintextSha256、signingKeyId、encryptionKeySha256；keyAlgorithm 固定 RSA-OAEP-SHA256-MGF1-SHA1，wrappedContentKey 为 Base64URL。客户端必须使用已固定的服务端签名公钥，不能信任包内自带公钥。完整算法见 SEC-003。H5_TEST 仍返回 H5_PLACEHOLDER，安全包字段为 null。

下载 URL 固定为 /api/v1/delivery/files，90 秒 token 只放 Authorization，响应为流式 application/octet-stream，含长度、Digest 和 Cache-Control:no-store。请求必须匹配会话平台和已有权益，版本需已发布且有安全包；按 supportedResourceTypes 顺序选择，minimumOsVersion 按最多四段数字比较，未满足能力要求的变体不交付。失效票据或撤销后的读取返回 401 DOWNLOAD_TICKET_INVALID；读取限流 429。原先已开始的流不在传输途中反复查权益。

1.0.1、1.1.0 历史快照保留；WP-A05 已显式冻结 1.2.0，旧快照不回写。端侧安全安装与真机验证继续由 A06 验收。

## 1.3.0 受限试用交付

新增 2 操作、3 Schema，当前合计 51 操作、77 Schema。POST /device/wallpapers/{wallpaperId}/preview-tickets 要求 Android 会话及 timestamp/nonce/精确正文签名，无需作品权益；请求平台 ANDROID、resourceType 三类及 osVersion。201 PreviewDescriptor 的 deliveryMode/purpose 固定 APP_PREVIEW、durationSeconds=120、package.formatVersion=3；清单摘要指向派生清单。GET /preview/files 只接受 previewTicketBearer，固定同源下载路径、90 秒票据、no-store/长度/Digest，与正式 /delivery/files 双向拒绝。无兼容受限包为 422 PREVIEW_RESOURCE_NOT_READY，失效票据为 401 PREVIEW_TICKET_INVALID，频繁操作为 429 RATE_LIMITED。

正式 DownloadDescriptor 和 SecurePackageMetadata 保持原模式与 format 2，H5 占位字段不变。既有管理 secure-package 操作扩展为制作正式/受限包对，允许已有正式包的 PUBLISHED 版本补建派生包；正式字节、正式清单与权益不变。详细规格、用途签名、临时库和计时边界见 [SEC-004](../05-安全与合规/SEC-004-Android受限试用交付协议.md)。1.2.0 历史冻结输入单独保留；1.3.0 在契约、API 实现与格式/权限回归检查后显式冻结，端侧 120 秒与三类显示的真机验收继续属于 A09，不以接口冻结代替。

## 1.3.1 下载错误响应与下架说明修正

A10 的实际 HTTPS 下架测试发现：客户端使用 `Accept: application/octet-stream` 时，失效下载票据的既有 401 被错误响应的媒体协商转换为 500。统一错误处理显式设置 `Content-Type: application/json`，保留原状态、ErrorEnvelope 和 Retry-After；正常正式/试用资源仍为 octet-stream。缺少 Authorization 的正式/试用文件读取也按已冻结契约返回 401 及各自票据失效码，避免落入通用缺少 Header 的 400。没有更改票据授权条件、DTO、资源格式或数据库结构。

下架响应中遗留的“默认仍可交付”说明与 A05 已冻结的实际授权规则不一致，现明确：已有 ACTIVE 权益及摘要保留，公开目录和新增兑换关闭，正式 App 不再创建新下载票据，旧正式/试用票据不能开始新的读取；已开始的流允许完成，已安装资源可继续使用。领域模型、安全协议和 Java 状态门禁保持原行为，H5 不增加正式交付能力。

1.3.0 原快照逐字节归档为 baseline-v1.3.0.json；1.3.1 只修订 OpenAPI 版本/该说明和本文，重新审阅这两项摘要。DM/DB 仍为 1.3.0，V1/V2/V3 和其余冻结文件保持原字节。不通过修改历史快照或重写迁移掩盖变更；具体验收结果见 A10 记录。

## 1.4.0 壁纸设置教程

新增 5 个操作、6 个 Schema，当前合计 56 操作、83 Schema。管理端读取五个固定槽位，通过 `TUTORIAL_VIDEO` 上传不超过 200 MB/15 分钟的真实 MP4，再用 `PUT /admin/wallpaper-tutorials/{tutorialKey}` 和强 ETag 替换视频、启停或排序。固定标题、平台和壁纸类型不接受客户端写入。

公开列表只包含已启用且 READY 的教程，`contentUrl` 带教程乐观锁版本。视频流支持 GET、HEAD、完整 200 与单段 Range 206，未配置或已停用统一返回 `TUTORIAL_NOT_FOUND`。详细字段与前端匹配规则见 [API-006](API-006-壁纸设置教程接口.md)。
