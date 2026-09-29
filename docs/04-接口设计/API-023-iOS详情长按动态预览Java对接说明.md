# API-023 iOS 详情长按动态预览 Java 对接说明

**状态：** iOS 客户端与 Java API 已完成，待部署后真机联调

**日期：** 2026-09-29

**OpenAPI 目标版本：** `2.11.0`

## 1. 目标效果

iOS 用户在壁纸详情页、尚未兑换前，可以预览 Live Photo 的动态效果：

1. 默认显示商品封面。
2. 用户长按图片时播放 1 秒动态片段。
3. 用户一直按住时，App 在本地循环播放。
4. 用户松手时立即停止，恢复展示封面。
5. 预览不产生兑换、权益或正式下载记录。

后端只需交付一份可校验的 1 秒视频文件。循环播放、长按和松手交互全部由 iOS 客户端完成。

## 2. 数据来源

不新增数据库表或字段，直接使用已有 `live_photo_package` 的正式成品视频：

| 用途 | 字段 |
| --- | --- |
| 存储键 | `video_storage_key` |
| 文件大小 | `video_size_bytes` |
| SHA-256 | `video_sha256` |
| 时长 | `duration_ms`，必须为 `1000` |
| 可用状态 | `status='READY'` |

选取资源时必须同时满足：

- `wallpaper.status='PUBLISHED'`
- `resource_version.status='PUBLISHED'`
- `wallpaper_variant.enabled=TRUE`
- `wallpaper_variant.platform='IOS'`
- `wallpaper_variant.resource_type='LIVE_PHOTO'`
- `live_photo_package.status='READY'`

预览必须读取 Live Photo 生成后的成品 MOV，不读取用户上传的源 MP4。成品视频已经截取为 1 秒并移除音轨。

## 3. 签发预览票据

复用已有接口：

```http
POST /api/v1/device/wallpapers/{wallpaperId}/preview-tickets
```

请求仍需要：

- iOS 设备会话 `Authorization: Bearer {deviceAccessToken}`
- `X-Request-Timestamp`
- `X-Request-Nonce`
- `X-Request-Signature`
- `QJ-SIGNED-REQUEST-V1` 规则的 ECDSA P-256 SHA-256 签名

请求体必须精确为：

```json
{
  "deliveryPlatform": "IOS",
  "resourceType": "LIVE_PHOTO"
}
```

请求的设备会话必须属于 `IOS`，且 App Bundle ID 必须在 iOS 白名单中。Android 或 HarmonyOS 会话不得申请该资源。

### 3.1 成功响应

HTTP 状态码：`201 Created`

```json
{
  "deliveryMode": "LIVE_PHOTO_PREVIEW",
  "purpose": "APP_PREVIEW",
  "durationSeconds": 1,
  "wallpaperId": "123",
  "ticket": "43位Base64URL无填充票据",
  "downloadUrl": null,
  "expiresAt": "2026-09-29T10:01:30Z",
  "resourceVersion": {
    "id": "456",
    "variantId": "78",
    "versionNo": 1,
    "platform": "IOS",
    "resourceType": "LIVE_PHOTO",
    "manifestSha256": "64位小写十六进制"
  },
  "package": null,
  "video": {
    "url": "/api/v1/preview/live-photo/video",
    "sha256": "64位小写十六进制",
    "sizeBytes": 1234567,
    "mimeType": "video/quicktime"
  }
}
```

iOS 客户端会对以下字段做严格匹配，不能改名或替换路径：

- `deliveryMode=LIVE_PHOTO_PREVIEW`
- `purpose=APP_PREVIEW`
- `durationSeconds=1`
- `resourceVersion.platform=IOS`
- `resourceVersion.resourceType=LIVE_PHOTO`
- `video.url=/api/v1/preview/live-photo/video`
- `video.mimeType=video/quicktime`
- `video.sizeBytes > 0`
- `video.sha256` 为 64 位小写十六进制

## 4. 票据保存与校验

预览票据使用 Redis 短时保存：

- Redis key 可沿用：`preview-ticket-v2:{HMAC(ticket)}`
- TTL：`90 秒`
- 票据用途：`APP_PREVIEW`
- 票据模式：`LIVE_PHOTO`
- 绑定：`deviceId`、`credentialKeyId`、`wallpaperId`、`resourceVersionId`、`IOS`、`LIVE_PHOTO`、到期时间

签发和读取文件时均必须重新校验：

1. 设备状态为 `ACTIVE`。
2. 凭据状态为 `ACTIVE`。
3. 设备平台为 `IOS`。
4. `app_install_scope` 在 iOS Bundle ID 白名单中。
5. 票据的壁纸、变体、资源版本与当前数据库记录一致。
6. 壁纸和资源版本仍然已发布，变体仍然启用。
7. `video_storage_key`、`video_size_bytes`、`video_sha256` 没有发生变化。

票据不得写入 `device_entitlement`、`redemption_event` 或任何正式下载记录。

## 5. 新增预览文件接口

```http
GET /api/v1/preview/live-photo/video
Authorization: Bearer {previewTicket}
Accept: video/quicktime
```

这里使用上一步返回的预览票据，不使用设备 Access Token，也不使用正式下载票据。

### 5.1 成功响应

```http
HTTP/1.1 200 OK
Content-Type: video/quicktime
Content-Length: {video_size_bytes}
Cache-Control: no-store
Digest: sha-256=:{video_sha256对应原始32字节的Base64}:
```

响应体为 `video_storage_key` 对应的完整 MOV 文件。发送前必须确认存储层实际文件大小等于 `video_size_bytes`。

iOS 客户端下载后会再次校验：

- HTTP 状态码必须为 `200`
- MIME 必须为 `video/quicktime`
- 文件大小必须等于 `sizeBytes`
- 文件 SHA-256 必须等于 `sha256`
- 不接受 HTTP 重定向

## 6. 限流建议

沿用现有预览限流边界：

- 签发预览票据：每设备每分钟最多 `6` 次
- 读取 iOS 预览视频：每设备每分钟最多 `12` 次
- 服务端同时预览文件传输：最多 `4` 个

## 7. 主要错误码

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 400 | `RESOURCE_PLATFORM_MISMATCH` | 设备平台与请求资源平台不匹配 |
| 401 | `PREVIEW_TICKET_INVALID` | 票据格式错误、过期、设备/凭据失效或资源已变更 |
| 404 | `RESOURCE_NOT_AVAILABLE` | 没有已发布且 `READY` 的 iOS Live Photo 资源 |
| 429 | `RATE_LIMITED` | 票据签发或文件读取超限 |

## 8. Java 实现结果

本次 Java API 已按上述契约完成：

1. `PreviewTicketService`
   - 已增加 `IOS + LIVE_PHOTO` 精确分支。
   - 只选择已发布、已启用、`READY` 且 `duration_ms=1000` 的 `live_photo_package`。
   - 票据已绑定设备、凭据、壁纸、资源版本以及 MOV 的存储键、大小和 SHA-256。
   - 签发和读取时都会重新校验设备、凭据、Bundle ID 白名单、发布状态和文件元数据。
   - 已增加 `readLivePhotoVideo` 流式读取，限流为每设备每分钟 12 次，并与其他预览共用最多 4 个并发传输槽位。
2. `DevicePreviewController`
   - 已增加 `GET /api/v1/preview/live-photo/video`。
   - 返回 `video/quicktime`、`Content-Length`、`Cache-Control: no-store` 和 `Digest`。
3. iOS 发布链路
   - 不再为 iOS Live Photo 生成 Android MP4 预览包或加密预览包。
   - 详情预览直接读取正式生成的 1 秒 MOV；正式 Live Photo 图片和视频下载接口保持不变。
4. OpenAPI
   - 已升级到 `2.11.0`。
   - `PreviewDescriptor.deliveryMode` 已增加 `LIVE_PHOTO_PREVIEW`。
   - 已补充 iOS 预览文件路径、票据安全模型和 QuickTime 响应定义。
5. 数据与后台
   - 未新增数据库表或字段。
   - 管理后台上传和发布流程无需修改。

## 9. 验收结果与真机检查

Java 自动化检查已覆盖：

1. 没有权益的 iOS 设备可获取 `LIVE_PHOTO_PREVIEW` 票据。
2. 签发票据前后，`device_entitlement` 和 `redemption_event` 数量不变。
3. Android 和 HarmonyOS 会话请求 `IOS/LIVE_PHOTO` 被拒绝。
4. 预览票据不能访问正式 `/delivery/*` 接口。
5. 正式下载票据不能访问 `/preview/live-photo/video`。
6. 返回视频的大小和 SHA-256 与描述一致。
7. 设备停用、凭据撤销、资源下线、变体禁用或票据过期后，预览读取失败。
8. OpenAPI 契约校验、Java 快速测试和生产构建均通过。

部署后由 iOS 真机确认最后一项交互：进入详情页显示封面；长按播放；持续按住循环；松手恢复封面。

## 10. 发布顺序

1. 部署 OpenAPI `2.11.0` 对应的 Java API。
2. 验证已发布 iOS 商品在 `live_photo_package` 中为 `READY`。
3. 使用 iOS 正式包真机联调长按预览。

如果只更新 iOS App、但未部署本次 Java API，详情页仍只能显示封面，并提示动态预览暂不可用；正式兑换和下载流程不受影响。
