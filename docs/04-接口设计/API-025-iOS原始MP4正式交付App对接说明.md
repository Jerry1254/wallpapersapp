# API-025 iOS 原始 MP4 正式交付 App 对接说明

**状态：** 已完成、已部署并通过 iOS 真机端到端联调

**日期：** 2026-09-30

**OpenAPI 版本：** `2.13.0`

**Java 提交：** `99e9524 完成：iOS正式交付原始视频`

**iOS 提交：** `e7ad5b3 优化：iOS使用原始视频单次生成动态壁纸`

## 1. 本次对接结论

iOS 动态壁纸分为两条独立链路：

1. **免费预览**：Java 继续返回前 60 帧生成的 `1 秒 / 60 fps` MOV，仅用于详情页长按预览。
2. **正式下载**：免费商品或已获得权益后，Java 返回运营上传的原始 MP4。iOS 使用原始视频的前 60 个显示帧，在本地只编码一次并生成最终 Live Photo。

这样可以避免“Java 先生成 HEVC，iOS 再编码一次”带来的二次画质损失。

## 2. 申请正式下载票据

```http
POST /api/v1/device/wallpapers/{wallpaperId}/download-tickets
Authorization: Bearer {deviceAccessToken}
Content-Type: application/json
X-Request-Timestamp: {timestamp}
X-Request-Nonce: {nonce}
X-Request-Signature: {signature}
```

请求体：

```json
{
  "deliveryPlatform": "IOS",
  "resourceType": "LIVE_PHOTO"
}
```

该 POST 请求继续使用现有 `QJ-SIGNED-REQUEST-V1` 规则签名。会话必须属于 iOS 设备，Android 和 HarmonyOS 会话不能申请该资源。

### 2.1 成功响应

HTTP 状态码：`201 Created`

```json
{
  "deliveryMode": "LIVE_PHOTO",
  "wallpaperId": "123",
  "ticket": "43位Base64URL无填充票据",
  "downloadUrl": null,
  "expiresAt": "2026-09-29T12:00:00Z",
  "resourceVersion": {
    "id": "456",
    "variantId": "789",
    "versionNo": 1,
    "platform": "IOS",
    "resourceType": "LIVE_PHOTO",
    "manifestSha256": "64位小写十六进制"
  },
  "package": null,
  "poster": null,
  "photo": null,
  "video": null,
  "sourceVideo": {
    "url": "/api/v1/delivery/live-photo/source",
    "sha256": "64位小写十六进制",
    "sizeBytes": 12345678,
    "mimeType": "video/mp4"
  },
  "image": null
}
```

iOS 必须严格校验：

- `deliveryMode=LIVE_PHOTO`
- `resourceVersion.platform=IOS`
- `resourceVersion.resourceType=LIVE_PHOTO`
- `sourceVideo.url=/api/v1/delivery/live-photo/source`
- `sourceVideo.mimeType=video/mp4`
- `sourceVideo.sizeBytes > 0`
- `sourceVideo.sha256` 为 64 位小写十六进制
- `photo` 和 `video` 为 `null` 或不存在

## 3. 下载原始 MP4

```http
GET /api/v1/delivery/live-photo/source
Authorization: Bearer {downloadTicket}
Accept: video/mp4
```

这里使用上一步响应中的 `ticket`，不使用设备 Access Token，也不再附加敏感请求签名。

### 3.1 成功响应

```http
HTTP/1.1 200 OK
Content-Type: video/mp4
Content-Length: {sourceVideo.sizeBytes}
Cache-Control: no-store
Digest: sha-256=:{SHA-256原始32字节的Base64}:
```

响应体是管理后台上传的原始 MP4 完整字节。接口不会跳转，不返回存储路径，也不会生成长期公开 URL。

iOS 下载完成后必须同时校验：

1. HTTP 状态码为 `200`。
2. 没有发生 `3xx` 重定向。
3. MIME 为 `video/mp4`。
4. 实际字节数等于 `sourceVideo.sizeBytes`。
5. 实际 SHA-256 等于 `sourceVideo.sha256`。

任一项不一致时必须删除临时文件，不得继续生成或保存 Live Photo。

## 4. iOS 本地生成规则

iOS 获得原始 MP4 后：

1. 按显示顺序解码第一个视频轨。
2. 仅使用前 60 个显示帧，不抽帧、不补帧、不交换顺序。
3. 按当前设备屏幕比例生成 `aspectFit + 模糊背景` 画布。
4. 将 60 帧重新标记为 `60 fps`，生成精确 1 秒视频。
5. 使用 40～100 Mbps 自适应高码率只编码一次。
6. 不保留源音频、字幕、数据轨和输入元数据。
7. 生成使用同一 UUID 的 HEIC 和 paired MOV。
8. 先通过 `PHLivePhoto.request` 验证，再使用 `.photo + .pairedVideo` 保存。
9. 无论成功、失败或取消，都删除原始 MP4 和中间临时文件。

当前客户端的实际实现位置：

- `packages/wallpaper-ios/ios/Classes/WallpaperIosPlugin.swift`：描述校验、短票据下载、禁止重定向、大小与 SHA-256 校验、系统相册权限、`PHLivePhoto.request` 验证、相册保存和临时文件清理。
- `packages/wallpaper-ios/ios/Classes/NativeLivePhotoComposer.swift`：前 60 个显示帧、设备比例画布、HEVC 单次编码、HEIC 封面、统一 UUID 和 paired MOV 生成。
- `packages/wallpaper-ios/ios/Resources/wallpaper-metadata-template.mov`：提供两条 Apple Live Photo metadata 轨，最终 MOV 保证视频轨排在前面。

画质参数已经定版：

- 从 `UIScreen.main.nativeBounds` 取得当前设备竖屏比例。
- 目标画布保留源视频显示尺寸，只扩展比例不足的一边；最大边超过 4096 时才整体缩小，宽高最终取偶数。
- 前景使用 `aspectFit` 完整居中，背景使用同帧 `aspectFill + CIGaussianBlur(radius: 36)`。
- HEVC Main、60 fps、60 帧、1 秒；平均码率为 `宽 × 高 × 60 × 0.30`，限制在 40～100 Mbps。
- HEIC 取最终画布第 31 帧并使用质量 `1.0` 写出；HEIC MakerApple `17` 与 MOV Content Identifier 使用同一个新 UUID。
- 输入中的音频、字幕、数据轨和第 61 帧以后的内容都不会进入最终成品。

## 5. 预览接口保持不变

详情长按预览仍使用：

```http
POST /api/v1/device/wallpapers/{wallpaperId}/preview-tickets
GET  /api/v1/preview/live-photo/video
```

预览响应仍为：

- `deliveryMode=LIVE_PHOTO_PREVIEW`
- `purpose=APP_PREVIEW`
- `video.url=/api/v1/preview/live-photo/video`
- `video.mimeType=video/quicktime`
- `durationSeconds=1`

预览票据不能访问原始 MP4 端点；正式下载票据也不能访问预览端点。

## 6. 权益和票据规则

- 免费商品可直接申请正式下载票据。
- 需要兑换的商品必须先有当前设备的 `ACTIVE` 权益。
- 票据 TTL 为 90 秒。
- 票据绑定设备、凭据、壁纸、资源版本、iOS 平台和原始 MP4 指纹。
- 签发时校验一次原文件的字节数和 SHA-256。
- HTTP 延迟流真正开始时再校验一次设备、凭据、权益、发布状态和文件完整性。
- 票据签发后如果绑定、大小、SHA-256 或存储对象发生变化，旧票据立即失效。

## 7. 主要错误码

| HTTP | 错误码 | iOS 处理建议 |
| --- | --- | --- |
| 400 | `RESOURCE_PLATFORM_MISMATCH` | 不重试，检查当前会话和请求平台 |
| 401 | `DOWNLOAD_TICKET_INVALID` | 删除本次临时文件，重新申请票据；连续失败时重建设备会话 |
| 403 | `ENTITLEMENT_REQUIRED` | 引导用户先完成兑换 |
| 404 | `RESOURCE_NOT_AVAILABLE` | 当前商品没有可用的原始 MP4，禁用下载按钮并支持刷新 |
| 429 | `RATE_LIMITED` | 等待后重试，不要并发重复下载 |

## 8. 旧接口边界

下列接口仅为旧客户端过渡保留，新版 iOS 不得调用：

```http
GET /api/v1/delivery/live-photo/image
GET /api/v1/delivery/live-photo/video
```

新版正式交付不使用 `live_photo_package.photo_*` 或 `live_photo_package.video_*`。它们仍由 Java 保留，其中标准化 MOV 仅用于免费预览。

## 9. 管理后台和数据库

- 管理后台上传方式不变，仍上传一个 MP4。
- 原文件继续使用 `LIVE_PHOTO_SOURCE` 资产和资源绑定。
- 本次没有新增 Flyway 迁移，不需要修改数据库表结构。
- 已发布资源只要仍有有效的 `LIVE_PHOTO_SOURCE` 绑定，正式下载无需重新转码。

## 10. iOS 联调验收清单

1. 未兑换的收费壁纸申请正式票据，返回 `403 ENTITLEMENT_REQUIRED`。
2. 免费商品或已兑换商品返回 `sourceVideo`，且 `photo/video` 为空。
3. `sourceVideo` 的 MIME、大小和 SHA-256 与实际下载文件完全一致。
4. 预览票据访问 `/delivery/live-photo/source` 返回 `401`。
5. 过期票据、Android/HarmonyOS 会话、被撤销凭据和已下线资源均不能下载。
6. 替换数据库中的源文件大小或 SHA-256 后，已签发票据返回 `401 DOWNLOAD_TICKET_INVALID`。
7. iOS 只使用前 60 帧生成一次编码的 Live Photo。
8. `PHLivePhoto.request` 校验通过，能保存到相册，锁屏编辑页不再显示“动态效果不可用”。
9. 长按预览仍使用后端 MOV，不会提前下载原始 MP4，也不会创建权益。

## 11. 当前发布状态

Java 代码、OpenAPI `2.13.0` 和相关测试已经推送并部署到生产环境。线上活动发布为 `20260930-010553-757fbd43062b`，Readiness 为 `UP`；未带票据读取原始 MP4 端点会返回 `401 DOWNLOAD_TICKET_INVALID`。

iOS Release `1.0.0 (10021)` 已使用线上 API 构建，签名校验通过，并覆盖安装到 iPad。正式兑换下载链路已经完成真机验证：原始 MP4 下载校验通过，本地生成结果可保存为 Live Photo，锁屏动态效果可用；用户已确认整体效果和画质符合当前预期。
