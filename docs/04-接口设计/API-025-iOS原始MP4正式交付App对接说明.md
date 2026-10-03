# API-025 iOS 原始 MP4 正式交付 App 对接说明

**原始 MP4 接口状态：** 已完成、已部署并通过 iOS 真机端到端联调；封面修复的验证记录见第 12 节。

**日期：** 2026-09-30

**iOS 导出规则与排障更新：** 2026-10-03

**OpenAPI 版本（本接口首次交付时）：** `2.13.0`；本次封面修复不改变接口契约。

**Java 提交：** `99e9524 完成：iOS正式交付原始视频`

**iOS 提交：** `e7ad5b3 优化：iOS使用原始视频单次生成动态壁纸`

**iOS 封面对齐修复提交：** `4d8ad95 fix(ios): align Live Photo cover with its still-image marker`

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
7. 从随 App 打包的 metadata 模板读取 `com.apple.quicktime.still-image-time` 所在组的时间，按该时间从最终画布视频提取 HEIC 封面。
8. HEIC 与 paired MOV 使用同一新 UUID，并将模板中的两条 metadata 轨复制到最终 MOV。
9. 先通过 `PHLivePhoto.request` 验证，再使用 `.photo + .pairedVideo` 保存；动态锁屏是否可用还需单独在系统设置中验收。
10. 无论成功、失败或取消，都删除原始 MP4 和中间临时文件。

当前客户端的实际实现位置：

- `packages/wallpaper-ios/ios/Classes/WallpaperIosPlugin.swift`：描述校验、短票据下载、禁止重定向、大小与 SHA-256 校验、系统相册权限、`PHLivePhoto.request` 验证、相册保存和临时文件清理。
- `packages/wallpaper-ios/ios/Classes/NativeLivePhotoComposer.swift`：前 60 个显示帧、设备比例画布、HEVC 单次编码、HEIC 封面、统一 UUID 和 paired MOV 生成。
- `packages/wallpaper-ios/ios/Resources/wallpaper-metadata-template.mov`：提供两条 Apple Live Photo metadata 轨，最终 MOV 保证视频轨排在前面。

当前客户端导出参数（2026-10-03）：

- 从 `UIScreen.main.nativeBounds` 取得当前设备竖屏比例。
- 先按源视频显示尺寸和设备比例计算画布，只扩展比例不足的一边。
- iPhone 将画布长边统一到 1920 像素，包括较低分辨率的源视频；iPad 保留不放大的规则，只有画布长边超过 4096 时才整体缩小。宽高最终取偶数，取整后的尺寸不得超过对应上限。
- 例如本次 iPhone 测试画布为 `888×1920`，这是按测试设备比例计算的结果，不是所有设备共用的固定宽高。同一 iPhone 的画布由设备比例和 1920 长边决定；iPad 的画布还取决于源视频尺寸和 4096 上限。源视频不要求固定为 `1080×1920`。
- 前景使用 `aspectFit` 完整居中，背景使用同帧 `aspectFill + CIGaussianBlur(radius: 36)`。
- HEVC Main、60 fps、60 帧、1 秒；平均码率为 `宽 × 高 × 60 × 0.30`，限制在 40～100 Mbps。
- HEIC 的选帧时间来自 metadata 模板，而不是独立写死为首帧或某个帧号。当前模板标记为 `0.5 秒`，在 60 fps 输出中对应零起始索引 30，即通常所说的第 31 帧；HEIC 使用质量 `1.0` 写出。
- HEIC MakerApple `17` 与 MOV Content Identifier 使用同一个新 UUID。选取 HEIC 封面不会移动、删除或重排视频帧，也不会把视频播放起点改成 0.5 秒。
- 编码后的画布视频以压缩样本直通方式写入 paired MOV；封面提取和 metadata 配对均不再次编码视频。
- 输入中的音频、字幕、数据轨和第 61 帧以后的内容都不会进入最终成品。

### 4.1 实况照片封面与业务封面的区别

列表、详情页展示用的业务封面，与最终保存到相册的 Live Photo 静态封面是两个用途。业务封面即使要求使用源视频首帧，也不能据此将 Live Photo 的 HEIC 封面强制改成首帧。

Live Photo 需要同时保持两种一致性：

| 项目 | 当前规则 |
| --- | --- |
| 资源配对 | HEIC MakerApple `17` 与 MOV `com.apple.quicktime.content.identifier` 相同 |
| 封面时间 | HEIC 的画面与 MOV `com.apple.quicktime.still-image-time` 标记的时间对应 |

仅满足 UUID 相同、文件能保存和相册中能播放，不足以完成动态锁屏验收。不得通过删掉开头黑帧、循环挪帧、改变播放顺序或压缩动画时间来修正封面不一致。

### 4.2 封面时间的实现约束

`NativeLivePhotoComposer.stillImageTime(from:)` 遍历模板 metadata 轨，通过 `AVTimedMetadataGroup` 查找标识为 `mdta/com.apple.quicktime.still-image-time` 的项目，并读取所在组的 `timeRange.start`。

该时间必须是有效数值，且在当前 1 秒输出的 `[0, 1)` 范围内。缺失或无效时抛出 `missingStillImageTime`，不得静默回退到首帧。`createImage(from:at:identifier:outputURL:)` 使用这个时间选帧，前后时间容差均为零；`createPairedVideo` 继续复制同一个模板的 metadata 轨。

以后替换模板或调整输出时长、帧率时，必须重新验证封面时间、视频时间轴和最终锁屏效果，不能只修改其中一处。

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
8. 核对 HEIC 与 MOV 的配对 UUID 相同、尺寸一致；提取最终 MOV 的 still-image-time，确认 HEIC 来自对应时间的画面。
9. 分别验证 `PHLivePhoto.request`、相册播放和系统锁屏设置。前两项通过后，仍须在锁屏编辑页确认可启用动态效果，并实际锁屏、唤醒检查播放。
10. 长按预览仍使用后端 MOV，不会提前下载原始 MP4，也不会创建权益。

## 11. 首次交付的发布验证记录（2026-09-30）

Java 代码、OpenAPI `2.13.0` 和相关测试已经推送并部署到生产环境。线上活动发布为 `20260930-010553-757fbd43062b`，Readiness 为 `UP`；未带票据读取原始 MP4 端点会返回 `401 DOWNLOAD_TICKET_INVALID`。

iOS Release `1.0.0 (10021)` 已使用线上 API 构建，签名校验通过，并覆盖安装到 iPad。正式兑换下载链路已经完成真机验证：原始 MP4 下载校验通过，本地生成结果可保存为 Live Photo，锁屏动态效果可用；用户已确认整体效果和画质符合当前预期。

## 12. 封面不一致导致锁屏不可用的排障记录（2026-10-03）

### 12.1 现象与原因

新导出的实况照片在相册中可以播放，但在设置动态锁屏时不可用。该视频的首帧全黑，旧客户端在 `createImage` 内使用 `AVAssetImageGenerator` 从 `.zero` 提取画面，将全黑首帧写入 HEIC；paired MOV 复制的模板仍把静态照片时间标记在 `0.5 秒`，此时视频已显示主体画面。

因此，两个资源虽然有相同 UUID、可以配对播放，但静态封面与视频声明的封面时间不一致。之前代码调整为首帧时没有同步维护这一关联规则，文档仍保留旧的第 31 帧说明，造成了代码与文档脱节。

### 12.2 单变量验证结果

| 对照步骤 | 修改内容 | 结果 |
| --- | --- | --- |
| 原失败样本 | `720×1560`，HEIC 为全黑首帧，MOV 封面标记在 0.5 秒 | 相册能播放，动态锁屏不可用 |
| 仅调整尺寸 | 统一为 `888×1920`，仍使用全黑首帧封面 | 用户反馈仍不可用，说明尺寸调整不足以解决本次问题 |
| 仅替换 HEIC 封面 | 使用上一行的同一 MOV，仅将封面改为该 MOV 的 0.5 秒画面 | 用户确认可用于动态锁屏 |

封面对照样本的视频文件通过 SHA-256 比对确认字节完全一致，时长、帧数、尺寸、音频状态和 metadata 都未修改。本次结论针对这个已验证样本，其他锁屏失败仍需独立排查，不能归因于所有黑首帧或所有尺寸。

### 12.3 正式修复与检查结果

正式 App 已改为读取模板中的 still-image-time，再从最终画布视频的同一时间提取 HEIC。当前读取和实际提取时间均验证为 `0.5 秒`，不再独立指定首帧。

本地原生导出检查通过：

- HEIC 与 paired MOV 均为 `888×1920`，配对 UUID 一致。
- 视频保持 `60 帧 / 60 fps / 1 秒`，视频首帧仍为全黑，帧顺序未调整。
- 最终 MOV 保留两条 metadata 轨，still-image-time 仍为 `0.5 秒`。
- 画布 MOV 与 paired MOV 的压缩视频流哈希一致，配对阶段没有再次编码视频。
- 使用缺失 still-image-time 的模板时，生成流程按预期拒绝。

iOS Release `1.0.0 (10022)` 已构建，通过签名校验，并覆盖安装、启动到测试 iPhone；本次记录的测试设备为 iPhone 12、iOS 18.7.8，仅用于说明验证环境，不构成机型或系统版本白名单。

用户已确认封面对照样本可设置；正式 App 的最终验收应重新下载同一壁纸，检查新导出的那张。覆盖安装不会自动改写相册中的旧文件。本次修复仅涉及 iOS 客户端，不需要 Java、管理后台或数据库修改，也不需要后端部署。

### 12.4 后续回归检查

1. 从相册导出实际失败样本的 HEIC 和 paired MOV，先核对真实文件，避免仅凭 App 版本或显示效果推测。
2. 检查尺寸、时长、帧率、帧数、轨道和配对 UUID，再确认 still-image-time 与 HEIC 画面是否对应。
3. 每次只修改一个变量，保留原样本；对照时用文件或压缩视频流哈希确认其余内容没有变化。
4. 至少保留一份以黑帧开头、后续显现主体的源视频作为回归素材，确认选帧对齐后视频开头仍被保留。
5. 使用新版正式 App 重新导出，分别检查相册播放、系统锁屏设置和实际唤醒播放。不得把 `PHLivePhoto.request` 成功直接等同于动态锁屏可用。
