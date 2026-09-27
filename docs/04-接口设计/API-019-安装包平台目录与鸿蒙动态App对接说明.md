# API-019 安装包平台目录与鸿蒙动态 App 对接说明

**版本：** 1.1.0

**日期：** 2026-09-27

**对应 OpenAPI：** 2.6.0

**对接对象：** Android App、HarmonyOS App、iOS App

## 1. 对接结论

App 不再上报手机的设置能力，API 只根据已认证会话中的安装包平台返回内容。

App **不要再调用**：

- `GET /api/v1/device/me/capabilities`
- `PUT /api/v1/device/me/capabilities`

目录、分类数量、搜索、精选、详情和我的可用都由 API 按安装包平台统一过滤。App 只渲染服务端返回的 `availableCapabilities`，不在本地再删除其他平台商品。

## 2. 当前完成状态

| 能力 | Android | HarmonyOS | iOS |
|---|---:|---:|---:|
| 按安装包平台过滤目录、分类、详情和权益 | 已完成 | API 业务逻辑已完成 | API 业务逻辑已完成 |
| 设备注册、挑战和签名会话 | 已完成 | 已完成 | 待实现 |
| Android 4D/动态安全包预览与下载 | 已完成 | 不适用 | 不适用 |
| HarmonyOS Moving Photo 双文件下载 | 不适用 | API 已完成 | 不适用 |
| 鸿蒙通用静态原图正式下载 | — | 待实现 | — |
| iOS Live Photo/通用静态正式交付 | — | — | 待实现 |

> 鸿蒙身份、目录和 Moving Photo 交付代码已就绪。部署包含 OpenAPI 2.6.0 的新 API 后，鸿蒙 App 可进行真实端到端联调。

## 3. 平台可见矩阵

| 会话平台 | API 返回的专属资源 | 同时可见的通用资源 |
|---|---|---|
| `ANDROID` | `ANDROID / LAYER_PARALLAX`<br>`ANDROID / VIDEO` | `UNIVERSAL / STATIC_IMAGE` |
| `HARMONYOS` | `HARMONYOS / MOVING_PHOTO` | `UNIVERSAL / STATIC_IMAGE` |
| `IOS` | `IOS / LIVE_PHOTO` | `UNIVERSAL / STATIC_IMAGE` |

规则：

1. 商品至少有一个已发布且当前平台可见的资源，才会出现在目录中。
2. `availableCapabilities` 只包含当前平台资源和真正的通用静态资源。
3. Moving Photo 配套 JPEG 只是鸿蒙动态照片的一部分，不算 `UNIVERSAL / STATIC_IMAGE`。
4. 平台过滤在数据库查询、计数和分页前完成，App 不需要修正 `totalItems` 或 `totalPages`。

## 4. 通用请求约定

- API 路径前缀：`/api/v1`
- Long ID 在 JSON 中都是字符串，例如 `"42"`
- 时间为带时区的 ISO 8601 字符串
- 目录、详情、权益和签发票据需要设备会话：

```http
Authorization: Bearer <deviceAccessToken>
```

- 签名写请求还需要：

```http
X-Request-Timestamp: 2026-09-27T08:30:00Z
X-Request-Nonce: <每次新生成的 UUID>
X-Request-Signature: <Base64URL 无填充签名>
```

需要签名的路由：

- `PUT /api/v1/device/encryption-key`
- `POST /api/v1/device/redemptions`
- `POST /api/v1/device/wallpapers/{wallpaperId}/download-tickets`
- `POST /api/v1/device/wallpapers/{wallpaperId}/preview-tickets`

签名原文是：

```text
QJ-SIGNED-REQUEST-V1
<HTTP_METHOD>
<PATH>
<X-Request-Timestamp 原值>
<X-Request-Nonce 原值>
<请求体原始字节的 SHA-256 小写十六进制>
```

`PATH` 是以 `/api/v1` 开头的实际请求路径，不包含域名。签名和发送必须使用完全相同的 JSON 字节，不要在签名后重新序列化。

## 5. 设备身份和会话

设备首次启动的通用流程为：

1. `POST /api/v1/device/registrations` 提交平台、安装包标识、安装级公钥和注册证明，获得 `credentialKeyId`。
2. `POST /api/v1/device/session-challenges` 申请一次性 challenge。
3. App 使用安装级私钥对 challenge 规范原文签名。
4. `POST /api/v1/device/sessions` 提交签名，获得 `deviceAccessToken`。
5. 会话过期后重复第 2‑4 步，不需要每次重新注册。

注册请求结构：

```json
{
  "platform": "HARMONYOS",
  "appInstallScope": "com.qingjing.bizhi",
  "credentialType": "PLATFORM_PUBLIC_KEY",
  "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
  "evidenceToken": "<Base64URL 无填充字符串>"
}
```

鸿蒙安装级密钥固定使用 HUKS RSA-2048、SHA-256 和 PKCS#1 v1.5。`publicKeyPem` 的主体是 `huks.exportKeyItem` 返回的 X.509 SubjectPublicKeyInfo 原始字节，用 PEM 头尾包装。

公钥指纹：

```text
lowercaseHex(SHA-256(huks.exportKeyItem 原始字节))
```

鸿蒙注册签名原文：

```text
QJ-HARMONYOS-REGISTER-V1
com.qingjing.bizhi
<公钥指纹>
<timestamp>
<nonce>
```

`evidenceToken` 是以下 UTF-8 JSON 的 Base64URL 无填充编码：

```json
{
  "timestamp": "<ISO 8601 UTC>",
  "nonce": "<UUID>",
  "proof": "<注册原文的 RSA SHA-256 PKCS#1 v1.5 签名，Base64URL 无填充>"
}
```

申请 challenge：

```json
{
  "credentialKeyId": "<UUID>"
}
```

会话签名规范原文：

```text
QJ-DEVICE-SESSION-V1
<credentialKeyId>
<challengeId>
<challenge.nonce>
<clientTimestamp>
```

创建会话：

```json
{
  "credentialKeyId": "<UUID>",
  "challengeId": "<UUID>",
  "clientTimestamp": "2026-09-27T08:30:00Z",
  "proof": "<Base64URL 无填充签名>"
}
```

HarmonyOS 的 `challenge.algorithm` 固定为 `RSA_SHA256`，会话 `proof` 使用同一把 HUKS RSA-2048 私钥按 SHA-256 / PKCS#1 v1.5 签名，输出 Base64URL 无填充字符串。

会话响应中的 `platform` 是服务端后续过滤目录和交付资源的依据。Android 线上联调包已允许以下 `appInstallScope`：

- `com.qingjing.bizhi.internal`
- `com.qingjing.bizhi.lab`

上述 Android 和 HarmonyOS 的 RSA-2048/SHA-256 设备注册、会话及 `QJ-SIGNED-REQUEST-V1` 业务请求验签都已在 Java API 实现。

## 6. 目录与详情

### 6.1 分类

```http
GET /api/v1/public/categories
Authorization: Bearer <deviceAccessToken>
```

API 只返回当前平台存在可见商品的分类，分类数量与当前平台目录一致。

### 6.2 列表、搜索、精选和免费

```http
GET /api/v1/public/wallpapers?page=1&pageSize=20&sort=DEFAULT
GET /api/v1/public/wallpapers?view=FEATURED&page=1&pageSize=20
GET /api/v1/public/wallpapers?accessType=FREE&page=1&pageSize=20
GET /api/v1/public/wallpapers?q=佛&page=1&pageSize=20
```

可在当前安装包范围内继续按精确资源筛选，`deliveryPlatform` 和 `resourceType` 必须同时传入：

```http
GET /api/v1/public/wallpapers?deliveryPlatform=HARMONYOS&resourceType=MOVING_PHOTO&page=1&pageSize=20
```

如果 HarmonyOS 会话传入 `ANDROID / VIDEO`，API 返回 `400 RESOURCE_PLATFORM_MISMATCH`。

列表项关键字段示例：

```json
{
  "id": "42",
  "title": "佛光菩萨",
  "slug": "fo-guang-pu-sa",
  "accessType": "REDEEM",
  "cover": {
    "assetId": "301",
    "contentUrl": "/api/v1/public/assets/301/content",
    "mimeType": "image/webp",
    "widthPx": 720,
    "heightPx": 1280
  },
  "featured": true,
  "sortOrder": 1,
  "availableCapabilities": [
    {
      "deliveryPlatform": "HARMONYOS",
      "resourceType": "MOVING_PHOTO",
      "placements": ["HOME", "LOCK"]
    }
  ]
}
```

### 6.3 详情

```http
GET /api/v1/public/wallpapers/{wallpaperId}
Authorization: Bearer <deviceAccessToken>
```

- 当前平台有可见资源：返回 `200`。
- 商品存在但当前平台无可见资源：返回 `404 WALLPAPER_NOT_AVAILABLE_FOR_PLATFORM`。
- App 的设置方式入口以 `availableCapabilities` 为准。

## 7. 权益与兑换

### 7.1 我的可用

```http
GET /api/v1/device/me/entitlements?page=1&pageSize=20
Authorization: Bearer <deviceAccessToken>
```

权益属于整个商品，不按平台或资源类型拆分。API 只返回当前安装包有可用资源的权益商品。

### 7.2 兑换收费壁纸

```http
POST /api/v1/device/redemptions
Authorization: Bearer <deviceAccessToken>
Idempotency-Key: <这次兑换意图的 UUID>
X-Request-Timestamp: <ISO 8601 UTC>
X-Request-Nonce: <UUID>
X-Request-Signature: <signature>
Content-Type: application/json

{
  "wallpaperId": "42",
  "code": "<20 位兑换码>"
}
```

- 超时重试必须复用原 `Idempotency-Key` 和原请求体。
- `201` 表示新权益已创建。
- `200` 可能表示已拥有或返回既有幂等结果。
- `202` 表示仍在处理，使用原 `Idempotency-Key` 查询：

```http
GET /api/v1/device/redemptions/{idempotencyKey}
```

- `accessType=FREE` 的商品不需要兑换，可直接申请正式下载票据。

## 8. 鸿蒙 Moving Photo 下载

### 8.1 申请下载票据

当详情的 `availableCapabilities` 包含 `HARMONYOS / MOVING_PHOTO` 时，App 可请求：

```http
POST /api/v1/device/wallpapers/42/download-tickets
Authorization: Bearer <deviceAccessToken>
X-Request-Timestamp: <ISO 8601 UTC>
X-Request-Nonce: <UUID>
X-Request-Signature: <signature>
Content-Type: application/json

{
  "deliveryPlatform": "HARMONYOS",
  "resourceType": "MOVING_PHOTO"
}
```

`REDEEM` 商品需要当前设备已获得商品权益；`FREE` 商品可直接申请。

### 8.2 票据响应

```json
{
  "deliveryMode": "MOVING_PHOTO",
  "wallpaperId": "42",
  "cover": {
    "assetId": "301",
    "contentUrl": "/api/v1/public/assets/301/content",
    "mimeType": "image/webp",
    "widthPx": 720,
    "heightPx": 1280
  },
  "ticket": "<90 秒有效票据>",
  "downloadUrl": null,
  "expiresAt": "2026-09-27T08:31:30Z",
  "resourceVersion": {
    "id": "102",
    "variantId": "58",
    "versionNo": 1,
    "platform": "HARMONYOS",
    "resourceType": "MOVING_PHOTO",
    "manifestSha256": "<64 位小写十六进制>"
  },
  "package": null,
  "poster": {
    "url": "/api/v1/delivery/moving-photo/poster",
    "sha256": "<64 位小写十六进制>",
    "sizeBytes": 245678,
    "mimeType": "image/jpeg"
  },
  "video": {
    "url": "/api/v1/delivery/moving-photo/video",
    "sha256": "<64 位小写十六进制>",
    "sizeBytes": 1987654,
    "mimeType": "video/mp4"
  }
}
```

### 8.3 下载双文件

`poster.url` 和 `video.url` 都使用票据响应中的同一个 `ticket`：

```http
GET /api/v1/delivery/moving-photo/poster
Authorization: Bearer <downloadTicket>
```

```http
GET /api/v1/delivery/moving-photo/video
Authorization: Bearer <downloadTicket>
```

下载后，App 必须：

1. 分别核对响应长度与 `sizeBytes`。
2. 分别计算 SHA-256，与 `poster.sha256` 和 `video.sha256` 比对。
3. 只有两个文件都完整通过校验后，才注册为鸿蒙动态照片并保存到图库。
4. 票据过期或校验失败时，删除临时文件并重新申请票据。
5. JPEG 不可单独当作通用静态壁纸保存。

## 9. 错误码处理

| HTTP | `error.code` | App 处理 |
|---:|---|---|
| 400 | `RESOURCE_PLATFORM_MISMATCH` | 不要回退到其他平台；刷新详情并按新能力列表展示 |
| 401 | `SESSION_EXPIRED` / `UNAUTHORIZED` | 重新申请设备会话后重试 |
| 401 | `DOWNLOAD_TICKET_INVALID` | 重新申请下载票据 |
| 403 | `ENTITLEMENT_REQUIRED` | 引导先兑换该商品 |
| 403 | `REQUEST_SIGNATURE_INVALID` | 检查签名原文、JSON 字节、时间和密钥 |
| 404 | `WALLPAPER_NOT_AVAILABLE_FOR_PLATFORM` | 返回上一页并刷新目录 |
| 404 | `RESOURCE_NOT_AVAILABLE` | 刷新详情，停止使用已删除的资源类型 |
| 409 | `REQUEST_NONCE_REUSED` | 对新的请求生成新 nonce；不复用失败请求的 nonce |
| 429 | `RATE_LIMITED` | 按 `Retry-After` 退避 |

错误响应结构：

```json
{
  "error": {
    "code": "RESOURCE_NOT_AVAILABLE",
    "message": "The requested resource is not available",
    "requestId": "c59ea4c3-e893-4cec-82c8-80d7a93852d6",
    "details": []
  }
}
```

App 分支只依赖 `error.code`，不依赖可变的 `message`。

## 10. App 联调验收清单

### Android

- [ ] 不再调用设备能力上报接口。
- [ ] 只展示 API 返回的 Android 4D、Android 动态和通用静态标签。
- [ ] 分类数量、列表总数和分页数直接使用 API 返回值。
- [ ] 现有设备注册、签名、预览包和正式包逻辑保持不变。

### HarmonyOS

- [ ] 目录只接收 `HARMONYOS / MOVING_PHOTO` 和 `UNIVERSAL / STATIC_IMAGE`。
- [ ] 使用 `com.qingjing.bizhi` 和 HUKS RSA-2048 完成设备注册、challenge 会话和敏感请求签名。
- [ ] 使用详情返回的精确类型申请下载，不请求 Android 或 iOS 资源。
- [ ] 下载并校验 Moving Photo 的 JPEG 和 MP4，两者成功后才交给系统。
- [ ] 系统注册或保存失败时在 App 提示，不修改服务端目录。

### iOS

- [ ] 目录只接收 `IOS / LIVE_PHOTO` 和 `UNIVERSAL / STATIC_IMAGE`。
- [ ] 设备身份和 Live Photo 正式交付完成前，不把目录可见误判为已可正式下载。

## 11. 鸿蒙身份固定契约

| 项目 | 固定值 |
|---|---|
| `platform` | `HARMONYOS` |
| `appInstallScope` | `com.qingjing.bizhi` |
| 密钥 | HUKS RSA-2048 |
| 摘要 | SHA-256 |
| 填充 | PKCS#1 v1.5 |
| 公钥容器 | X.509 SubjectPublicKeyInfo PEM |
| 注册签名域 | `QJ-HARMONYOS-REGISTER-V1` |
| challenge 算法 | `RSA_SHA256` |
| 签名输出 | Base64URL 无填充 |

新版 API 部署后，服务端会拒绝其他鸿蒙 `appInstallScope`、非 RSA-2048 公钥、过期时间、重放 nonce、非 PKCS#1 v1.5 签名和签名后被改动的请求体。

## 12. 契约依据

最终字段、枚举和错误响应以：

- `contracts/openapi/openapi.yaml`
- OpenAPI 版本 `2.6.0`

为准。本文档只说明 App 对接流程和当前实现边界。
