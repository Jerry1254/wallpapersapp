# API-001 OpenAPI 契约说明

**状态：** 已确认
**版本：** 2.1.0
**日期：** 2026-09-17
**机器契约：** [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml)
**语义基线：** DM-001、DB-001、API-014、SEC-001 至 SEC-004

## 1. 当前契约结论

OpenAPI 2.1.0 使用 OpenAPI 3.0.3 描述，共 61 个操作、97 个 Schema。2.0.0 删除商品单一 `kind`、新增设备能力档案并将公开目录改为设备个性化目录；2.1.0 新增分页前精确能力筛选，并统一移除重复的 `view=STATIC`。

| 边界 | 路径前缀 | 身份 | 负责内容 |
|---|---|---|---|
| 设备目录 | `/public` | 设备短期 Bearer；除公开资产和教程媒体外 | 分类、计数、列表、搜索、详情和当前设备可用能力 |
| 设备交付 | `/device`、`/delivery`、`/preview` | 设备短期 Bearer；敏感写入另带请求签名；文件使用独立短时票据 | 身份、能力上报、兑换、权益、预览和正式下载 |
| 管理端 | `/admin` | HttpOnly Cookie；写操作另带 CSRF token | 管理登录、资源、分类、单商品多能力、教程、版本、批次、事件和设备查询 |

数据库实体不直接作为响应模型。任何响应都不得包含 `storageKey`、绝对路径、兑换码摘要、设备证据摘要、密码摘要、私钥或明文内容密钥。

## 2. 2.0.0 主要变化

### 2.1 商品能力

商品不再拥有互斥类型。管理 API 只接受以下五种变体组合：

- `ANDROID / LAYER_PARALLAX`
- `ANDROID / VIDEO`
- `IOS / LIVE_PHOTO`
- `HARMONYOS / THEME_PACKAGE`
- `UNIVERSAL / STATIC_IMAGE`

`WallpaperWriteRequest` 不再包含 `kind`。`AdminWallpaperVariant.enabled` 支持独立停用能力而保留资源版本历史。展示封面独立存在，不自动生成静态设置能力。

### 2.2 设备能力

新增：

- `PUT /device/me/capabilities`：签名上报宿主系统、运行环境、系统版本、探测版本、特征和实测能力；
- `GET /device/me/capabilities`：读取服务端过滤后的当前档案。

档案使用 `HostOsFamily`、`ExecutionMode`、`Placement` 和 `CapabilityEvidence`。服务端把上报能力过滤为 `effectiveCapabilities`，目录和交付只能使用该集合。没有档案时返回 HTTP 428 `DEVICE_CAPABILITY_PROFILE_REQUIRED`。

### 2.3 设备个性化目录

以下接口要求设备 Bearer，并使用同一可见性谓词：

- `GET /public/categories`
- `GET /public/wallpapers`
- `GET /public/wallpapers/{wallpaperId}`
- `GET /device/me/entitlements`

可见性条件为：存在启用且已发布的变体，其精确平台/资源组合命中设备有效能力，并满足最低系统版本、能力要求和至少一个 `HOME/LOCK` 设置位置。

公开壁纸使用 `availableCapabilities` 返回当前设备交集，不再返回单一 `kind`。列表不接受 `kind` 或单独 `platform` 作为设备兼容性替代条件；可以使用成对的 `deliveryPlatform + resourceType` 按当前设备已生效能力精确筛选。直接访问当前设备不可用的商品返回 404 `WALLPAPER_NOT_AVAILABLE_FOR_DEVICE`。

### 2.4 目录精确能力筛选

`GET /public/wallpapers` 的 `deliveryPlatform` 和 `resourceType` 必须同时提供或同时省略。精确能力与分类、`view=FEATURED`、获取方式、搜索和排序按 AND 关系叠加，服务端在 `COUNT` 和分页前完成筛选。详细对接见 [API-017](API-017-设备目录精确能力筛选App交接确认说明.md)。

### 2.5 精确预览和下载

以下请求正文统一为：

```json
{
  "deliveryPlatform": "ANDROID",
  "resourceType": "LAYER_PARALLAX"
}
```

适用端点：

- `POST /device/wallpapers/{wallpaperId}/preview-tickets`
- `POST /device/wallpapers/{wallpaperId}/download-tickets`

服务端不按客户端传入的优先级列表选择资源，也不跨平台或资源类型回退。正式下载响应使用 `SECURE_PACKAGE`，短时票据只通过 `Authorization: Bearer` 传递。

## 3. 通用传输规则

### 3.1 ID、时间与枚举

- MySQL `BIGINT` / Java `Long` 在 JSON 中返回十进制字符串。
- 时间使用带时区 ISO 8601，服务端按 UTC 存储和比较。
- JSON 字段使用 `camelCase`，枚举使用大写下划线。
- App 不能依赖数据库列名或服务端文件路径。

### 3.2 分页和稳定排序

列表使用从 1 开始的 `page` 和 `pageSize`，`pageSize` 默认 20、最大 100。响应返回 `page`、`pageSize`、`totalItems`、`totalPages`。

| 列表 | 主排序 | 最终稳定排序 |
|---|---|---|
| 公开普通列表 | `sortOrder ASC` | `id DESC` |
| 公开推荐 | `featuredRank ASC` | `id DESC` |
| 公开最新 | `publishedAt DESC` | `id DESC` |
| 管理壁纸 | `updatedAt DESC` | `id DESC` |
| 批次、兑换、设备 | 业务时间 `DESC` | `id DESC` |

分类计数、分页总数与列表内容使用同一设备可见性条件。

### 3.3 幂等与乐观锁

兑换和批次生成要求 UUID 格式 `Idempotency-Key`。同一主体、同一键和相同规范请求返回原结果；同键异参返回 409 `IDEMPOTENCY_KEY_REUSED`。

可编辑内容通过强 `ETag` 和写请求 `If-Match` 实现乐观锁。版本不一致返回 412 `VERSION_CONFLICT`。

### 3.4 设备请求签名

设备会话 proof 的规范载荷：

```text
QJ-DEVICE-SESSION-V1
{credentialKeyId}
{challengeId}
{nonce}
{clientTimestamp}
```

能力上报、兑换、预览和下载票据等敏感请求还必须携带 `X-Request-Timestamp`、`X-Request-Nonce`、`X-Request-Signature`。签名载荷：

```text
QJ-SIGNED-REQUEST-V1
{UPPERCASE_METHOD}
{PATH_WITH_SORTED_QUERY}
{X-Request-Timestamp}
{X-Request-Nonce}
{LOWERCASE_HEX_SHA256_OF_EXACT_BODY_BYTES}
```

客户端必须对实际发送的 UTF-8 正文字节计算摘要；签名完成后不得重新序列化正文。

## 4. 权益、能力和资源的关系

- 权益绑定 `deviceId + wallpaperId`，不绑定平台、资源类型或设置位置。
- `REDEEM` 首次获得创建权益并消耗一次额度；`FREE` 免权益。
- 同一商品切换 4D、动态或静态不重复兑换。
- 兑换前仍检查当前设备至少有一个可交付能力，避免先扣额度后发现无法设置。
- 每次只下载用户选择的一种精确形式。
- `HOME` 或 `LOCK` 任意一个位置存在即允许商品显示；位置不改变下载包。

## 5. 错误处理重点

| HTTP | 稳定 code | 客户端处理 |
|---|---|---|
| 400 | `VALIDATION_FAILED`、`MALFORMED_REQUEST`、`SIGNED_REQUEST_INVALID` | 修正请求；不得猜测字段 |
| 401 | `UNAUTHORIZED`、`SESSION_EXPIRED`、`REQUEST_SIGNATURE_INVALID`、票据失效码 | 重建会话或重新申请票据 |
| 403 | `ENTITLEMENT_REQUIRED`、`PLATFORM_MISMATCH`、`DEVICE_DISABLED` | 进入获取流程或停止操作 |
| 404 | `WALLPAPER_NOT_AVAILABLE_FOR_DEVICE`、资源/对象不存在码 | 刷新当前设备目录 |
| 409 | 幂等键、nonce、状态和交付冲突码 | 按原键查结果或刷新状态 |
| 412 | `VERSION_CONFLICT` | 重新读取后再提交 |
| 422 | `WALLPAPER_UNAVAILABLE`、`SECURE_PACKAGE_NOT_READY` 等业务拒绝 | 不回退其他资源形式 |
| 428 | `DEVICE_CAPABILITY_PROFILE_REQUIRED` | 上报能力后重试一次 |
| 429 | `RATE_LIMITED` | 按 `Retry-After` 退避 |

完整响应集合以对应 operation 的 OpenAPI responses 和 `ErrorCode` Schema 为准。

## 6. 管理端发布顺序

1. 上传资源并等待 Asset 为 `READY`。
2. 为选中的设置能力创建或复用精确变体。
3. 创建不可变资源版本和角色绑定。
4. Android/通用资源制作正式及受限安全包。
5. 发布操作原子选择各能力的新版本，并退休被替换或停用能力的旧发布版本。
6. 任一能力校验失败时，不能部分切换发布状态。

管理端可以为同一商品逐步增加、替换或停用能力；至少保留一项完整能力才能发布商品。

## 7. 契约验证

在 `contracts/openapi` 执行：

```bash
npm ci
npm test
npm run bundle
npm run baseline:check
```

`npm test` 使用 Redocly 校验结构和引用，再检查关键端点、operationId、字符串 Long ID、幂等/乐观锁/设备签名头、敏感字段和 Java 直接错误码覆盖。当前基线为 61 个操作、97 个 Schema。

App 完整对接步骤和跨系统验收矩阵见 [API-015](API-015-设备能力目录与多形式交付App对接说明.md)，目录精确能力筛选增量见 [API-017](API-017-设备目录精确能力筛选App交接确认说明.md)。
