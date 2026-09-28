# API-020 MP4 生成鸿蒙动态照片与 iOS Live Photo 后端开发对接

**版本：** 1.2.0

**日期：** 2026-09-28

**状态：** 待后端实现

**对接对象：** 管理后台、Java API、HarmonyOS App、iOS App

## 1. 目标和边界

管理员在后台为每个平台上传一个已经剪好的 MP4，后端生成该平台系统相册要求的成对资源。App 兑换后下载成对资源，再保存成一张系统动态照片。

- HarmonyOS：单 MP4 源文件 → JPEG 封面＋平台可用的 MP4，最终保存为 Moving Photo。
- iOS：单 MP4 源文件 → HEIC 封面＋带 Live Photo 元数据的 MOV，最终保存为 Live Photo。
- Android 动态壁纸交付不在本文档范围内。
- 安装包平台过滤继续以 `API-019` 为准。本文档取代 `API-019` 中有关 iOS/HarmonyOS 媒体生成和下载产物的未实现描述。

当前代码与目标的差异：

- `PackageMediaInspector.movingPhoto` 当前会把所有鸿蒙源视频以 CRF 20 重新编码并缩放到固定边界；需改为“识别平台可直接使用的视频流并保留原分辨率、帧率和样本数据”。
- `IOS / LIVE_PHOTO` 当前要求人工绑定 `LIVE_PHOTO_IMAGE` 和 `LIVE_PHOTO_VIDEO`，没有正式产物表和下载接口；需改为单 MP4 生成。
- `DownloadDescriptor` 当前没有 `LIVE_PHOTO` 交付模式，必须与 OpenAPI 、Java DTO 和 App 一起增加。

## 2. 产品交付规则

| 项目 | HarmonyOS 动态壁纸 | iOS 动态壁纸 |
|---|---|---|
| 安装包平台 | `HARMONYOS` | `IOS` |
| 资源类型 | `MOVING_PHOTO` | `LIVE_PHOTO` |
| 后台上传 | 1 个 MP4 | 1 个 MP4 |
| 源视频时长 | 不少于 2 秒 | 不少于 1 秒 |
| 固定截取区间 | 从 0 秒开始取前 2 秒 | 从 0 秒开始取前 1 秒 |
| 最终视频时长 | 2 秒 | 1 秒 |
| 分辨率和比例 | 由上传 MP4 决定，原尺寸保留 | 由上传 MP4 决定，原尺寸保留 |
| 帧率 | 由上传 MP4 决定，当前最高 60fps | 由上传 MP4 决定，当前最高 60fps |
| 可接受源编码 | H.264 或 HEVC | H.264 或 HEVC |
| 当前真机验证配置 | H.264 High、30fps、`yuv420p` | HEVC Main、`hvc1`、60fps、`yuv420p` |
| 音频 | 不作为动态照片产物，生成时移除 | 不作为 Live Photo 产物，生成时移除 |
| 方向 | 像素已转正，不依赖 rotation metadata | 像素已转正，不依赖 rotation metadata |
| 最终产物 | JPEG＋MP4 | HEIC＋MOV |
| App 完成文案 | `已保存到相册，请设置` | `已保存到相册，请设置` |

最终时长是平台固定产物规格，与上传文件的总时长无关。后端始终从源视频 0 秒开始截取：HarmonyOS 取前 2 秒，iOS 取前 1 秒。后端不加速、减速、补帧、循环或冻结画面。分辨率、长宽比和帧率由每个上传文件决定，后端不把它们强制改成单一模板。

### 2.1 固定截取规则

- HarmonyOS：源视频必须至少 2 秒，输出固定为区间 `[0ms, 2000ms)`。
- iOS：源视频必须至少 1 秒，输出固定为区间 `[0ms, 1000ms)`。
- 超出截取区间的后续画面全部丢弃。
- 源视频短于对应截取时长时直接拒绝，返回 `DYNAMIC_SOURCE_DURATION_INVALID`。
- 源视频可以明显长于 1 秒或 2 秒，不因总时长较长而拒绝；只受上传文件大小、媒体解析和处理超时等安全边界限制。
- 截取后时间轴从 0 开始；包含所有显示时间戳小于截取终点的视频帧。
- 源视频起始位置必须可独立解码。如果无法在不损坏首帧的前提下做精确截取，可对截取区间使用高质量转码，并记录为 `TRANSCODE`。

### 2.2 分辨率、帧率和编码

1. 宽高以视频实际像素尺寸为准，封面必须与最终视频宽高一致。
2. 不固定 9:16 或某一组分辨率；建议上传竖屏内容，但后端不裁剪、拉伸、扩图或加留白。
3. 当前服务安全边界为宽高均不超过 4096 像素；`yuv420p` 宽高必须为偶数。
4. 帧率不固定为 30fps 或 60fps；当前接受大于 0 且不超过 60fps 的有效时间戳。
5. MP4 源视频接受 H.264 或 HEVC。后端必须先判断目标平台是否可直接使用该视频流，不能仅因为它与“参考配置”不同就直接降质。

### 2.3 无损的定义

本文档中的“无损”指动画视频流不二次编码：

1. 上传视频流可被目标平台直接使用且可在目标终点做样本级截取时，后端必须使用 stream copy/remux，保留截取区间内的原分辨率、帧率和视频样本数据。
2. iOS 的 MP4 转 MOV 优先只更换容器并添加 Live Photo 元数据，使原 H.264 或 HEVC 图像数据保持不变。
3. HEIC/JPEG 封面是从视频解码帧生成的新文件，使用最高质量保存；“视频流无损”不等于封面文件字节级无损。
4. 只有视频流确实无法被目标平台使用时才允许转码；转码必须使用高质量参数，并将处理模式标记为 `TRANSCODE`，不能对外称为无损。
5. 生成记录必须保存 `PASSTHROUGH | REMUX | TRANSCODE` 之一，后台可查看实际处理方式。

## 3. HarmonyOS 生成规则

### 3.1 输入

- 容器：MP4。
- 编码：H.264 或 HEVC；当前已验证的安全参考配置是 H.264 High、30fps、`yuv420p`。
- 宽高、长宽比和帧率由源视频决定。
- 时长：至少 2 秒；后端只取前 2 秒。
- 必须存在视频轨；音频轨在产物中移除，不接受字幕、脚本或外部网络引用。

### 3.2 输出

1. `poster.jpg`
   - 取截取后视频的第 1 帧，与视频尺寸一致。
   - JPEG 最高质量，sRGB，无额外旋转元数据。
2. `video.mp4`
   - 固定为源视频前 2 秒。
   - 视频流可被鸿蒙目标设备使用时，直接保留截取区间内的视频样本。
   - 可重写 MP4 容器并开启 `faststart`；如确需转码，必须保留源分辨率并记录 `TRANSCODE`。

HarmonyOS App 已有的保存逻辑保持不变：

```text
PhotoType.IMAGE
PhotoSubtype.MOVING_PHOTO
IMAGE_RESOURCE  = poster.jpg
VIDEO_RESOURCE  = video.mp4
```

## 4. iOS Live Photo 生成规则

### 4.1 输入

- 容器：MP4。
- 编码：H.264 或 HEVC；当前已验证的安全参考配置是 HEVC Main、`hvc1`、60fps、`yuv420p`。
- 宽高、长宽比和帧率由源视频决定。
- 时长：至少 1 秒；后端只取前 1 秒。
- 必须存在视频轨；音频轨在产物中移除。
- 1344×1926、60fps HEVC 是已通过当前 iPad 动态锁屏验证的一个样例，不是全部 iOS 商品的强制分辨率或帧率。

### 4.2 输出

1. `photo.heic`
   - 从截取后视频 0.5 秒附近取中间帧。
   - 输出宽高必须与最终 MOV 视频宽高一致，使用最高质量 HEIC。
   - 写入本次资源的 `assetIdentifier`（Apple MakerNote 键 17）。
2. `video.mov`
   - 固定为源视频前 1 秒，优先将截取区间内的原 H.264 或 HEVC 视频样本 stream copy 到 QuickTime MOV 容器。
   - 写入与 HEIC 完全相同的 content identifier。
   - 带有经真机验证的 Live Photo 元数据轨：
     - `com.apple.quicktime.live-photo-info`
     - `com.apple.quicktime.still-image-time`
     - `com.apple.quicktime.live-photo-still-image-transform`
   - still-image-time 设为 0.5 秒附近，并与 HEIC 取帧位置一致。
   - 不包含音频轨。

### 4.3 元数据模板

已验证可设置动态锁屏的参考模板已作为后端测试资源入库：

```text
services/api-server/src/test/resources/live-photo/wallpaper-metadata-template.mov
SHA-256: 39b7239d8cb0a442b659948ac2e47eaba05e5922771f12614355042ae073a1f0
```

该文件只用于解析、对照和自动测试，不得将其视频画面作为正式商品产物。后端可复用其元数据轨结构，但要按 iOS 最终固定 1 秒的时间轴和当前视频帧时间戳重建样本时序。每份产物必须生成新的 `assetIdentifier`，不能把模板的标识符或时间戳原样复制给不同商品。不能只使用 FFmpeg 生成普通 MOV 就视为完成；必须验证 HEIC/MOV 配对标识和上述元数据轨。

后端可在 Java 进程中调用经固定版本、受控参数和超时限制的本地媒体 worker。具体库或工具不作为 API 契约，但输出必须通过本文档的自动检查和真机验收。

## 5. 后端存储与生成状态

### 5.1 资源角色

HarmonyOS 继续使用：

```text
AssetPurpose = MOVING_PHOTO_SOURCE
AssetRole    = MOVING_PHOTO_SOURCE
```

iOS 新增单源文件角色：

```text
AssetPurpose = LIVE_PHOTO_SOURCE
AssetRole    = LIVE_PHOTO_SOURCE
```

为了兼容旧数据，`LIVE_PHOTO_IMAGE` 和 `LIVE_PHOTO_VIDEO` 枚举可暂时保留，但新建 iOS 版本只允许绑定一个 `LIVE_PHOTO_SOURCE`。

### 5.2 数据库迁移

新增 Flyway 迁移，预计为 `V14__ios_live_photo_delivery.sql`：

1. 扩展 `asset.purpose` 和 `resource_binding.role` 检查约束，增加 `LIVE_PHOTO_SOURCE`。
2. 新增 `live_photo_package`，至少包含：
   - `resource_version_id`
   - `status`: `PROCESSING | READY | REJECTED`
   - HEIC 的 storage key / size / sha256
   - MOV 的 storage key / size / sha256
   - `asset_identifier`
   - `duration_ms`
   - `width_px` / `height_px`
   - `video_codec` / `frame_rate`
   - `processing_mode`: `PASSTHROUGH | REMUX | TRANSCODE`
   - `error_code`
   - `created_at` / `updated_at`
3. `READY` 约束必须要求两个产物都存在、SHA-256 合法，HarmonyOS 产物固定 2000ms、iOS 产物固定 1000ms，且封面和视频宽高一致。

HarmonyOS 现有 `moving_photo_package` 保留。建议增加原始/输出编码、帧率和 `processing_mode`，同时保留每个源文件的实际分辨率。

### 5.3 生成时机

1. 管理员上传 MP4，创建资源版本。
2. 管理员点击“生成平台动态照片”，或发布前由后端自动调用 publisher。
3. publisher 先用 ffprobe 读取实际尺寸、时长、时间戳、帧率、编码和轨道，选择 `PASSTHROUGH`、`REMUX` 或 `TRANSCODE` 后生成成对资源。
4. 生成完成后校验实际输出，将状态置为 `READY`。
5. 只有产物为 `READY` 的资源版本可发布。
6. 同一资源版本重试生成必须幂等；新产物完整成功前不覆盖已有 READY 产物。

## 6. OpenAPI 改造

### 6.1 管理端

1. `AssetPurpose` 和 `AssetRole` 增加 `LIVE_PHOTO_SOURCE`。
2. `IOS / LIVE_PHOTO` 的新资源版本只绑定一个 `LIVE_PHOTO_SOURCE` MP4。
3. `AdminResourceVersion` 增加 `livePhoto`，结构与 `movingPhoto` 对齐，包含生成状态、HEIC/MOV 摘要、时长、实际尺寸、帧率、原始/输出编码、`processingMode` 和错误码。
4. 增加显式生成或重试接口：

```http
POST /api/v1/admin/resource-versions/{resourceVersionId}/live-photo/build
```

### 6.2 App 下载描述

`DownloadDescriptor.deliveryMode` 增加 `LIVE_PHOTO`。

iOS 成功响应示例：

```json
{
  "deliveryMode": "LIVE_PHOTO",
  "wallpaperId": "42",
  "ticket": "<90秒有效下载票据>",
  "expiresAt": "2026-09-28T13:30:00Z",
  "cover": {
    "assetId": "301",
    "contentUrl": "/api/v1/public/assets/301/content",
    "mimeType": "image/webp",
    "widthPx": 720,
    "heightPx": 1280
  },
  "photo": {
    "url": "/api/v1/delivery/live-photo/image",
    "sha256": "<64位小写十六进制>",
    "sizeBytes": 1234567,
    "mimeType": "image/heic"
  },
  "video": {
    "url": "/api/v1/delivery/live-photo/video",
    "sha256": "<64位小写十六进制>",
    "sizeBytes": 4567890,
    "mimeType": "video/quicktime"
  }
}
```

`DeliveryFile.mimeType` 增加：

```text
image/heic
video/quicktime
```

新增下载接口：

```http
GET /api/v1/delivery/live-photo/image
Authorization: Bearer <download-ticket>

GET /api/v1/delivery/live-photo/video
Authorization: Bearer <download-ticket>
```

两个文件共用同一票据。票据必须绑定 iOS 设备、商品、资源版本和两份产物，保持现有 90 秒有效期、限流、文件大小和 SHA-256 校验。

HarmonyOS 继续返回已有结构：

```text
deliveryMode = MOVING_PHOTO
poster       = image/jpeg
video        = video/mp4
```

## 7. iOS App 对接规则

iOS App 对 `LIVE_PHOTO` 执行：

1. 申请下载票据：`IOS / LIVE_PHOTO`。
2. 并发下载 HEIC 和 MOV 到 App 私有临时目录。
3. 分别校验文件大小和 SHA-256。
4. 使用 `PHAssetCreationRequest` 在一次 Photos 事务中添加：

```text
resourceType = .photo       -> photo.heic
resourceType = .pairedVideo -> video.mov
```

5. 保存成功后删除临时文件，页面显示“已保存到相册，请设置”。
6. 下载或保存失败时不把单独 HEIC 或 MOV 留在系统相册。

注意：`API-019` 中 iOS 设备注册和签名会话尚未实现。正式 iOS App 端到端联调前，后端还必须完成 iOS 安装级身份、会话和签名下载请求。

## 8. 错误码

| 错误码 | 含义 |
|---|---|
| `DYNAMIC_SOURCE_FORMAT_INVALID` | 容器无法解析，或编码、帧率、像素格式、尺寸、时间戳或轨道既不能直接使用也不能安全转换 |
| `DYNAMIC_SOURCE_DURATION_INVALID` | 源视频短于平台固定截取时长，或时长无法识别 |
| `MOVING_PHOTO_PROCESSING_FAILED` | 鸿蒙成对资源生成或交叉校验失败 |
| `LIVE_PHOTO_PROCESSING_FAILED` | iOS 成对资源生成、标识绑定或元数据校验失败 |
| `LIVE_PHOTO_NOT_READY` | iOS 资源产物尚未 READY，禁止发布或下载 |

错误响应不返回 ffmpeg/ffprobe 原始命令、服务器路径或堆栈；详细诊断只写服务端日志并携带 request ID。

## 9. 验收标准

### 9.1 自动验收

- 上传符合规格的 MP4 后，资源可生成并进入 `READY`。
- 短于平台固定截取时长、无法解码、尺寸越界、帧率越界或含不允许轨道的文件被明确拒绝；音频轨被移除。
- 产物的实际尺寸、时长、轨道、编码、帧率、处理模式、文件大小和 SHA-256 与数据库一致。
- 可直接使用的输入在输出中保持同一视频样本数据；iOS 只 remux 容器并增加元数据轨。
- 使用带时间码的长视频验证：HarmonyOS 输出只包含前 2 秒，iOS 输出只包含前 1 秒，截取点之后的画面不得出现。
- 至少使用两组不同分辨率和两组不同帧率的样本验证后端没有强制缩放或改帧率。
- 票据过期、篡改、跨设备、跨平台或跨资源使用均失败。
- OpenAPI 契约检查和 API 相关测试全部通过。

### 9.2 真机验收

HarmonyOS：

1. 下载后相册显示为动态照片。
2. 完整播放固定 2 秒，内容为源视频前 2 秒。
3. 可由相册设置为动态锁屏。

iOS：

1. 下载后 Photos 显示 Live Photo 标识并可播放。
2. 锁屏设置界面不显示“动态效果不可用”。
3. 可设置为动态锁屏，完整播放固定 1 秒，内容为源视频前 1 秒。
4. HEIC 和 MOV 宽高一致，且与上传 MP4 的实际分辨率一致；可直接复用的视频流没有二次压缩。

## 10. 后端完成后如何同步给 App

后端交付时一次性提供以下内容，App 不通过聊天描述猜测接口：

1. 后端 Git commit SHA 或 PR 链接。
2. 更新后的 `contracts/openapi/openapi.yaml` 和 OpenAPI 版本号。
3. Flyway 迁移版本及执行结果。
4. 测试环境 API 地址和已部署版本。
5. 一个可免费下载的 HarmonyOS 测试商品 ID，以及一个 iOS 测试商品 ID。
6. 两个平台实际的票据响应示例（票据打码）。
7. 每份生成产物的 MIME、大小、SHA-256、时长、尺寸、帧率和编码检查结果。
8. 新增错误码清单和已通过的测试命令。

如果后端和 App 在同一个仓库开发，后端只需要完成代码、迁移和 OpenAPI 并提供 commit/PR；App 端直接从变更后的 OpenAPI 对接。如果在另一个开发会话中实现，将本文档连同上述 8 项交付物一起回传即可。

## 11. 后端开发任务清单

- [ ] 调整 HarmonyOS 源文件严格校验与视频流无损保留。
- [ ] 新增 `LIVE_PHOTO_SOURCE` 资产用途和绑定角色。
- [ ] 新增 `live_photo_package` 迁移、publisher 和存储清理。
- [ ] 实现 HEIC/MOV 配对标识和完整 Live Photo 元数据轨。
- [ ] 新增 iOS 管理端生成状态和 build/retry 接口。
- [ ] 新增 `LIVE_PHOTO` 下载描述和 HEIC/MOV 下载接口。
- [ ] 完成 iOS 安装级身份、会话和签名请求。
- [ ] 同步 OpenAPI、契约检查和 Java 集成测试。
- [ ] 使用 HarmonyOS 真机和 iOS/iPad 真机完成最终验收。
