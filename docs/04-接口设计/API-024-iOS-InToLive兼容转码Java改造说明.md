# API-024 iOS 动态壁纸端到端实现与发布手册

**状态：** Java API、iOS 客户端和 iPad 真机验证均已完成

**日期：** 2026-09-29

**OpenAPI 版本：** `2.12.0`

## 1. 最终结论

倾境 iOS 动态壁纸采用“后台标准化视频，客户端生成最终 Live Photo”的两段式方案。后台不能只把 HEIC 与 MOV 直接交给相册，客户端也不能直接保存普通视频；两端必须各自完成自己的部分。

最终实现按 IntoLive 的实际行为处理：

1. 解码源视频，按显示顺序取前 `60` 个独立视频帧。
2. 不改变这 60 帧的内容、顺序和编号。
3. 将这 60 帧重新标记为 `60 fps`，得到精确 `1.000 秒`的视频。
4. 丢弃第 61 帧及其后的画面。
5. 不保留音频、字幕或源文件元数据。
6. Java 后端交付标准化视频，并保留服务端 HEIC/MOV 记录与接口兼容。
7. iOS 客户端按当前设备屏幕比例重新生成视频画布。
8. iOS 客户端使用 Apple 原生框架生成具有同一标识的 HEIC/MOV 配对，先校验再保存到相册。

这不是“截取源视频前 1 秒”。对一段 `30 fps / 2 秒`素材，必须保留原来的前 60 帧，再把播放时间从约 2 秒压缩为 1 秒。动画设计依赖帧顺序时，任何抽帧、补帧或插帧都会改变效果。

本次还确认了两个与 Live Photo 文件无关、但会造成真机不可用的打包规则：

1. 给用户或测试设备安装时必须构建 `Release`。iOS 14 以后，Flutter `Debug` 包从桌面独立启动会白屏或被系统终止。
2. Release 构建必须显式传入 `API_BASE_URL=https://wallpaper.biguo66.top/api/v1`。省略后应用会使用保护地址 `https://api.invalid/api/v1`，页面可以打开但不会加载任何线上商品。

## 2. 已验证的 IntoLive 基准

本次使用同一段素材分别由 IntoLive 和倾境测试程序处理，并在 iPad 真机锁屏中验证。

| 项目 | 基准结果 |
| --- | --- |
| 输入 | H.264 MP4，1080×2338，30 fps，约 2.033 秒，61 个视频帧，含 AAC 音频 |
| IntoLive 输出 | HEVC/hvc1 MOV，60 fps，1.000 秒，60 个视频帧 |
| 音频 | IntoLive 输出包含音频，但倾境无音频版本同样通过，因此倾境必须移除音频 |
| 封面帧 | 输出时间 0.5 秒，对应第 31 帧（从 1 开始计数） |
| 真机结果 | 可保存为 Live Photo，锁屏动态效果可用 |

逐帧比对结果：

| 输出帧 | 对应输入帧 |
| --- | --- |
| 1 | 1 |
| 16 | 16 |
| 31 | 31 |
| 46 | 46 |
| 60 | 60 |

因此禁止使用以下处理：

- 截取输入时间轴的前 1 秒；
- 只取中间一段；
- 插帧、补帧、混帧或复制帧；
- 调整第 16～30 帧在帧序列中的位置；
- 用 `-t 1` 配合原始 30 fps 直接输出 30 帧。

## 3. 上传素材规则

Java 仍读取 `LIVE_PHOTO_SOURCE` 绑定的 MP4 文件。

必须满足：

- 只有一个有效视频轨；
- 视频编码为 H.264 或 HEVC；
- 解码后至少有 60 个可显示帧；
- 宽高均为正数且不超过现有安全上限；
- YUV 4:2:0 素材宽高必须为偶数；
- 文件大小继续使用现有限制。

推荐运营上传：

- `30 fps`；
- `2.0～2.1 秒`；
- 至少 `60` 个视频帧；
- 动画设计以帧编号为准，尤其保留第 `16～30` 帧的既定内容。

输入时长和声明帧率可以不同，但后端选择范围只由“解码后的前 60 个显示帧”决定。少于 60 帧时不得静默复制帧，必须拒绝生成。

## 4. Java 媒体处理规则

修改位置：

- `DynamicPhotoMediaProcessor.livePhoto`
- `DynamicPhotoMediaProcessor` 中 iOS 专用的视频生成与校验方法
- `LivePhotoPublisher` 的错误码映射和生成结果记录

### 4.1 视频标准化

Live Photo 分支必须固定走转码，不再尝试 REMUX/PASSTHROUGH：

1. 按解码显示顺序读取视频帧。
2. 只保留帧索引 `0...59`。
3. 输出时间戳使用 `PTS = N / 60`。
4. 输出固定为：
   - 容器：QuickTime MOV；
   - 编码：HEVC Main；
   - codec tag：`hvc1`；
   - 像素格式：YUV 4:2:0；
   - 帧率：`60/1`；
   - 视频帧数：`60`；
   - 时长：`1.000 秒`；
   - video time base：建议 `1/600`；
   - 音频、字幕和数据轨：全部移除；
   - faststart：开启。
5. 后端标准化阶段保持上传视频的原始宽高，不裁切主体，也不写死 1080×1920、1080×1546 或 1344×1926。

iPhone/iPad 的最终画布比例由 iOS 客户端保存前处理：主体使用 `aspectFit`，空白区域使用同一画面的模糊 `aspectFill` 背景。Java 发布阶段不知道最终设备尺寸，不负责设备画布适配。

等价的 FFmpeg 处理逻辑示例：

```bash
ffmpeg -v error -xerror -y -nostdin \
  -i source.mp4 \
  -map 0:v:0 -an -sn -dn \
  -vf "select='lt(n,60)',setpts=N/(60*TB),format=yuv420p" \
  -frames:v 60 -r 60 \
  -c:v libx265 -preset medium -crf 12 \
  -tag:v hvc1 -video_track_timescale 600 \
  -map_metadata -1 -movflags +faststart \
  normalized.mov
```

命令只是实现参考。最终必须以第 7 节的逐帧验收为准，不能只检查 FFmpeg 是否返回成功。

### 4.2 封面帧

封面使用标准化视频的第 31 帧（零基索引 `30`，时间 `0.500 秒`）。

不得重新从原视频按 `-ss 0.5` 取帧，因为原视频的 0.5 秒不一定对应标准化结果的第 31 帧。

### 4.3 Apple 配对元数据

现有两组元数据轨可以继续使用：

- `com.apple.quicktime.live-photo-info`
- `com.apple.quicktime.still-image-time`
- `com.apple.quicktime.live-photo-still-image-transform`

HEIC MakerApple `17` 与 MOV `com.apple.quicktime.content.identifier` 仍需使用同一个 UUID。

但是 Java 生成的 HEIC/MOV 只作为服务端标准化交付资源。iOS 客户端下载后必须：

1. 读取标准化 MOV 的视频轨；
2. 按当前设备画布完成 `aspectFit + 模糊背景`；
3. 使用 Apple ImageIO 重新生成带相同新 UUID 的 HEIC；
4. 使用 AVFoundation 将视频轨作为第一轨，并写入模板中的两个 metadata 轨；
5. 先通过 `PHLivePhoto.request` 校验，再以 `.photo + .pairedVideo` 保存。

不能继续把 Java/MP4Box 生成的文件直接写入相册作为最终成品。现有实现中 metadata 轨排在视频轨前，虽然相册能播放，锁屏仍可能拒绝动态效果。

## 5. 接口与数据兼容

第一阶段保持以下接口不变：

- `POST /api/v1/admin/resource-versions/{resourceVersionId}/live-photo/build`
- `GET /api/v1/delivery/live-photo/image`
- `GET /api/v1/delivery/live-photo/video`
- `GET /api/v1/preview/live-photo/video`

下载描述仍保持：

- `deliveryMode=LIVE_PHOTO`
- `photo.mimeType=image/heic`
- `video.mimeType=video/quicktime`

`live_photo_package` 暂不迁移，继续保存 HEIC、MOV、SHA-256、尺寸、时长和处理信息。生成结果必须记录：

- `duration_ms=1000`
- `frame_rate=60.000`
- `output_video_codec=hevc`
- `processing_mode=TRANSCODE`

OpenAPI `2.12.0` 已明确：`video` 是符合 IntoLive 帧序规则的标准化 MOV，iOS 客户端会用它进行原生最终封装；不再承诺 Java 返回的双文件可直接写入相册后用于锁屏。

## 6. 错误码

当前使用以下错误码：

| HTTP | 错误码 | 场景 |
| --- | --- | --- |
| 422 | `DYNAMIC_SOURCE_FORMAT_INVALID` | 视频轨、编码、尺寸或像素格式不支持 |
| 422 | `IOS_LIVE_PHOTO_FRAME_COUNT_INVALID` | 解码后少于 60 个可显示帧 |
| 422 | `LIVE_PHOTO_PROCESSING_FAILED` | 转码、封面、元数据写入或生成后校验失败 |

`LivePhotoPublisher.errorCode` 必须保留 `IOS_LIVE_PHOTO_FRAME_COUNT_INVALID`，不能统一覆盖成 `LIVE_PHOTO_PROCESSING_FAILED`。

## 7. 后端自动验收

### 7.1 媒体属性

对生成的标准化视频必须同时验证：

- 只有一个视频轨；
- 没有音频、字幕轨；
- 视频编码为 `hevc`；
- codec tag 为 `hvc1`；
- 帧率精确为 `60/1`；
- 解码帧数精确为 `60`；
- 时长在 `0.998～1.002 秒`；
- 宽高等于输入视频显示宽高；
- HEIC 尺寸等于视频尺寸；
- HEIC 和 MOV 的 Content Identifier 一致；
- MOV 仍包含两组 Live Photo metadata 轨。

仅校验 `duration=1 秒` 和 `frameRate=60` 不够，必须实际解码计数为 60 帧。

### 7.2 逐帧一致性

测试夹具至少包含一段 `30 fps / 2.033 秒 / 61 帧`视频。

分别解码输入前 60 帧和输出 60 帧，在统一色彩空间与尺寸后逐帧比较。允许有 HEVC 有损编码误差，但必须满足：

- 输出第 N 帧只对应输入第 N 帧；
- 不能出现重复帧、跳帧或顺序变化；
- 至少显式断言第 `1、16、31、46、60` 帧的对应关系；
- 输出第 61 帧不存在。

### 7.3 接口回归

- 管理后台构建成功后状态为 `READY`；
- 预览接口返回新的 60 帧 MOV；
- 正式下载票据、SHA-256、大小和 MIME 校验保持有效；
- iOS 以外的平台不能请求该资源；
- 已发布旧资源需重新执行 live-photo build，不能继续复用旧成品。

## 8. 标准发布与联调顺序

1. Java 完成前 60 帧标准化算法、校验、错误码和 OpenAPI `2.12.0`。
2. Java 使用基准素材生成 MOV，提供 `ffprobe` 结果和逐帧映射测试结果。
3. 部署 Java API。
4. 对现有 iOS Live Photo 资源重新构建。
5. iOS 客户端接入本地 Apple 原生重封装。
6. 真机验证相册播放、锁屏动态效果和重复下载。

验收通过的最终标准不是“相册里能动”，而是 iOS 锁屏编辑页不再显示“动态效果不可用”，并且设置后按压可播放完整动画。

## 9. 前后端职责边界

### 9.1 Java API 必须负责

1. 接收运营上传的 MP4，并读取 `LIVE_PHOTO_SOURCE` 资源。
2. 校验输入视频轨、编码、尺寸、像素格式和可解码帧数。
3. 按显示顺序选择前 60 帧，将这 60 帧重定时为 60 fps、1.000 秒。
4. 转码为 HEVC Main、`hvc1`、YUV 4:2:0 的 QuickTime MOV，删除音频、字幕、数据轨和输入元数据。
5. 用标准化视频第 31 帧生成服务端 HEIC，并生成服务端配对 MOV，记录资源状态、尺寸、时长、SHA-256 和存储键。
6. 签发预览票据和正式下载票据，并在读取文件时再次校验设备、凭据、平台、资源发布状态和文件元数据。
7. 只向 iOS 安装身份下发 iOS 商品，且正式下载必须通过权益校验。
8. 已发布旧资源在算法更新后必须重新构建；修改代码不会自动改变历史成品。

### 9.2 iOS 客户端必须负责

1. 使用安装级 P-256 身份完成注册、challenge、会话和敏感请求签名。
2. 未兑换预览只下载 1 秒标准化 MOV；长按时播放，持续按住时本地循环，松开时恢复封面。
3. 正式下载前校验服务端描述中的平台、资源类型、路径、MIME、大小和 SHA-256。
4. 下载标准化 MOV 后，按当前 iPhone/iPad 的原生屏幕比例生成最终画布。
5. 使用 Apple 原生框架重新生成 HEIC 与 paired MOV，不把服务器文件直接写入相册。
6. 调用 `PHLivePhoto.request` 验证配对文件，验证成功后才以 `.photo + .pairedVideo` 保存。
7. 保存成功后记录相册 `localIdentifier`，用于“已保存到相册，请设置”和重复下载状态。

### 9.3 服务端 HEIC 的当前用途

正式下载描述继续返回 `photo` 与 `video`，以保持接口、存储记录和完整性元数据兼容。iOS 客户端会严格校验 `photo` 描述，但最终只下载标准化 `video`，再在本机生成新的 HEIC 与 MOV。

这是有意设计：服务端 HEIC 不能代表最终设备比例，且服务端 MP4Box 生成的轨道顺序曾出现 metadata 轨在视频轨之前的情况。文件在相册里可以播放，并不表示 iOS 锁屏会接受它。

## 10. 完整业务流程

```mermaid
flowchart TD
    A[管理后台上传 MP4] --> B[Java 校验视频]
    B --> C[选择前 60 个显示帧]
    C --> D[重定时为 60 fps / 1 秒]
    D --> E[生成 HEVC hvc1 标准化 MOV]
    E --> F[生成服务端 HEIC/MOV并记录 SHA-256]
    F --> G[发布 iOS 商品]
    G --> H[iOS 设备注册并建立会话]
    H --> I[首页只请求 iOS 商品]
    I --> J{用户操作}
    J -->|长按预览| K[签发预览票据]
    K --> L[下载 1 秒 MOV并本地循环]
    J -->|兑换并下载| M[校验权益并签发下载票据]
    M --> N[下载并校验标准化 MOV]
    N --> O[按设备比例生成 60 帧画布]
    O --> P[生成同 UUID 的 HEIC + paired MOV]
    P --> Q[PHLivePhoto.request 校验]
    Q --> R[保存到系统相册]
    R --> S[用户在相册或锁屏中设置]
```

### 10.1 首次进入首页

1. App 读取编译期 `API_BASE_URL`。
2. iOS 插件生成或读取 Secure Enclave P-256 安装密钥。
3. 无服务端凭据时调用 `POST /api/v1/device/registrations`。
4. 调用 `POST /api/v1/device/session-challenges` 获取 challenge。
5. 使用 `ECDSA_P256_SHA256` 签名 challenge，并调用 `POST /api/v1/device/sessions`。
6. 使用 Bearer 会话请求分类和商品目录。
7. 服务端根据会话平台与 App 安装范围返回 iOS 商品。

如果首页 UI 正常但所有内容为空，先检查构建是否写入真实 `API_BASE_URL`，再检查设备注册和 Bundle ID 白名单。不要先修改首页状态管理。

### 10.2 详情长按预览

1. App 请求 `POST /api/v1/device/wallpapers/{wallpaperId}/preview-tickets`。
2. 请求体为 `deliveryPlatform=IOS`、`resourceType=LIVE_PHOTO`。
3. 服务端返回 `deliveryMode=LIVE_PHOTO_PREVIEW`、短期票据和 MOV 的大小、SHA-256、MIME。
4. App 使用预览票据读取 `GET /api/v1/preview/live-photo/video`。
5. App 禁止重定向，并校验 HTTP 200、`video/quicktime`、文件大小与 SHA-256。
6. 资源加载期间显示“资源加载中，请稍后”；加载成功后显示“长按查看动态效果”。
7. 用户持续按住时本地循环，松手立即停止并恢复封面。

预览不会创建权益、兑换记录或正式下载记录。

### 10.3 兑换与正式下载

1. 免费商品直接进入下载；收费商品先完成兑换并取得权益。
2. App 使用设备会话和敏感请求签名申请正式下载票据。
3. 服务端返回 `deliveryMode=LIVE_PHOTO`，以及 `photo`、`video` 两个文件描述。
4. App 校验两个描述，但只下载 `/api/v1/delivery/live-photo/video`。
5. 下载时禁止重定向，并校验 MIME、字节数和 SHA-256。
6. App 在临时目录生成最终 HEIC 和 paired MOV，成功保存后删除临时文件。
7. UI 显示“已保存到相册，请设置”。

## 11. iOS 安装身份与请求签名

### 11.1 固定身份参数

| 参数 | 当前值 |
| --- | --- |
| 正式 Bundle ID | `com.qingjing.bizhi` |
| 测试 Bundle ID | `com.qingjing.livephotolab` |
| 私钥 | Secure Enclave P-256，安装级、不可导出 |
| 公钥上传格式 | X9.63 65 字节公钥包装为 X.509 SPKI PEM |
| 指纹 | SPKI DER 的 SHA-256，小写十六进制 |
| challenge 算法 | `ECDSA_P256_SHA256` |
| 签名格式 | X9.62 ECDSA ASN.1 DER |
| 传输编码 | Base64URL，无 `=` 填充 |
| 本地凭据 | Keychain，`ThisDeviceOnly` |

Java 白名单与 iOS 插件必须同时包含上述两个 Bundle ID。新增 Bundle ID 时必须同步修改两端，不能只改 Xcode。

### 11.2 会话签名原文

```text
QJ-DEVICE-SESSION-V1
{credentialKeyId}
{challengeId}
{nonce}
{clientTimestamp}
```

### 11.3 敏感请求签名原文

```text
QJ-SIGNED-REQUEST-V1
{HTTP_METHOD}
{/api/v1开头且不含查询参数的路径}
{timestamp}
{nonce}
{SHA-256请求体小写十六进制}
```

JSON 请求体必须先确定最终字节，再计算 body hash 和发送；不能签名后重新编码 JSON。

## 12. iOS 最终 Live Photo 生成细节

实现位置：

- `packages/wallpaper-ios/ios/Classes/NativeLivePhotoComposer.swift`
- `packages/wallpaper-ios/ios/Classes/WallpaperIosPlugin.swift`
- `packages/wallpaper-ios/ios/Resources/wallpaper-metadata-template.mov`
- `packages/wallpaper-ios/ios/wallpaper_ios.podspec`

### 12.1 设备画布

客户端读取 `UIScreen.main.nativeBounds`，统一使用竖屏短边和长边计算比例：

```text
目标宽度 = 1080
目标高度 = round(1080 × 原生长边 / 原生短边)
目标高度必须是偶数，且不超过 4096
```

本次 iPad 原生像素为 `1668×2388`，最终画布为 `1080×1546`。这里的 1080 是客户端输出画布宽度，后台仍保留上传视频的原始宽高，不写死分辨率。

每帧合成规则：

1. 背景使用同一帧 `aspectFill` 铺满画布。
2. 背景应用高斯模糊，当前半径为 `36`。
3. 前景使用 `aspectFit` 完整居中，不裁切主体。
4. 前景覆盖在模糊背景之上。

该方案同时解决“保持原图比例”和“不同屏幕比例不出现纯色留白”。

### 12.2 视频输出

客户端再次要求输入精确为 60 帧，并输出：

| 项目 | 值 |
| --- | --- |
| 编码 | HEVC Main |
| 容器 | QuickTime MOV |
| 帧率 | 60 fps |
| 帧数 | 60 |
| 时长 | 1.000 秒 |
| 平均码率 | 20 Mbps |
| 最大关键帧间隔 | 60 |
| 音频 | 无 |
| 每帧时间戳 | `N/60` |

客户端读完第 60 帧后还会确认不存在第 61 帧，防止后台错误资源被静默保存。

### 12.3 HEIC 与 paired MOV

1. 为每次生成创建一个新的 UUID。
2. 从最终画布视频的 `0.5 秒`取封面，即零基第 30 帧、从 1 开始的第 31 帧。
3. HEIC 使用最高质量写出，并把 UUID 写入 MakerApple 字典的 `17`。
4. paired MOV 第一轨必须是视频轨。
5. 从内置模板复制两个 Apple metadata 轨。
6. MOV 的 `com.apple.quicktime.content.identifier` 写入同一个 UUID。
7. 使用 `PHLivePhoto.request` 验证 `[HEIC, MOV]` 能组成 Live Photo。
8. 使用 `PHAssetCreationRequest` 分别添加 `.photo` 和 `.pairedVideo`。

`wallpaper-metadata-template.mov` 必须随 `wallpaper_ios_assets.bundle` 打入 App。缺失时客户端应返回 `LIVE_PHOTO_INVALID`，不能退化成普通照片或普通视频。

## 13. 正式构建、安装与上架

### 13.1 测试设备安装包

在 `apps/mobile` 执行：

```bash
DEVELOPER_DIR=/Applications/Xcode-26.0.1.app/Contents/Developer \
  flutter build ios --release \
  --dart-define=API_BASE_URL=https://wallpaper.biguo66.top/api/v1
```

构建后必须校验：

```bash
codesign --verify --deep --strict --verbose=2 \
  build/ios/iphoneos/Runner.app

/usr/libexec/PlistBuddy -c 'Print :CFBundleIdentifier' \
  build/ios/iphoneos/Runner.app/Info.plist

rg -a -m 1 'https://wallpaper\.biguo66\.top/api/v1' \
  build/ios/iphoneos/Runner.app/Frameworks/App.framework/App
```

三项必须分别确认：签名有效、Bundle ID 正确、AOT 二进制确实包含线上 API 地址。

安装和启动使用实际连接设备标识：

```bash
xcrun devicectl device install app \
  --device {deviceId} build/ios/iphoneos/Runner.app

xcrun devicectl device process launch \
  --device {deviceId} --terminate-existing com.qingjing.bizhi
```

设备安装使用的是“Release 配置 + 开发描述文件”，可以从桌面独立启动，但它不是 App Store 上传包。

### 13.2 App Store 上架包

上架时仍必须带同一个线上 API 参数：

```bash
flutter build ipa --release \
  --dart-define=API_BASE_URL=https://wallpaper.biguo66.top/api/v1
```

随后通过 Xcode Organizer 或受支持的上传工具提交 Archive/IPA，并检查：

- Bundle ID 为 `com.qingjing.bizhi`；
- Version 与 Build 号符合 App Store Connect 当前版本；
- 使用 App Store 分发签名和描述文件；
- 包含 `wallpaper_ios_assets.bundle/wallpaper-metadata-template.mov`；
- 上传工具返回成功并在 App Store Connect 中完成处理。

“Release 构建成功”不等于“App Store 已提交”。

### 13.3 本机 Skill

iOS 固定打包流程已写入：

```text
/Users/kele/.codex/skills/qingjing-ios-package/SKILL.md
```

以后“打包 iOS”默认使用 Release 和线上 API；“打包”默认还包含签名校验、覆盖安装和启动检查。

## 14. 本次实际问题与根因

### 14.1 相册能播放，锁屏提示“动态效果不可用”

根因不是单一的“缺少 UUID”。已验证的影响因素包括：

- 只按时间截取 1 秒，导致原动画只有前 30 帧；
- 服务器生成的 MOV 轨道顺序不符合最终真机兼容结果；
- 视频画布比例与当前设备差异过大；
- 把服务端 HEIC/MOV 直接保存，缺少 Apple 原生重新封装与本机校验。

最终通过组合方案解决：前 60 帧重定时、设备比例画布、视频轨优先、同 UUID 元数据、`PHLivePhoto.request` 校验、`.photo + .pairedVideo` 保存。

### 14.2 信任后打开仍是白屏

设备日志为：

```text
Cannot create a FlutterEngine instance in debug mode without Flutter tooling or Xcode.
```

根因是安装了 Flutter Debug 包。Debug 包只能由 `flutter run`、Xcode 或 Flutter IDE 调试器启动。解决方法是安装 Release 包，业务代码无需修改。

### 14.3 Release 包打开但没有商品

根因是构建时漏传 `API_BASE_URL`。工程有意把默认值设为：

```text
https://api.invalid/api/v1
```

这是防止错误构建误连生产的保护措施。解决方法是重新构建并显式传入线上地址，同时用 `rg -a` 检查产物内是否包含真实地址。

### 14.4 安装后仍运行旧行为

依次确认：

1. 当前打开的应用 Bundle ID 是否为 `com.qingjing.bizhi`；测试 App `com.qingjing.livephotolab` 可以同时存在。
2. 安装命令是否使用了刚生成的 `build/ios/iphoneos/Runner.app`。
3. 安装后是否用正式 Bundle ID 执行 `--terminate-existing` 并重新启动。
4. 后台历史 iOS 资源是否在新算法上线后重新构建。

## 15. 发布前验收清单

### 15.1 Java API

- [ ] OpenAPI 版本为 `2.12.0`。
- [ ] 正式和测试 Bundle ID 均已放行。
- [ ] 输入至少能解码 60 个显示帧。
- [ ] 输出为 60 帧、60 fps、1.000 秒、HEVC/hvc1、无音频。
- [ ] 输出宽高保持输入显示尺寸。
- [ ] 封面来自输出第 31 帧。
- [ ] `live_photo_package.status=READY`。
- [ ] 大小、SHA-256、MIME 和存储键与实际文件一致。
- [ ] 预览票据不能读取正式下载接口，正式票据不能读取预览接口。
- [ ] 旧资源已重新执行 Live Photo build。

### 15.2 iOS 客户端

- [ ] Secure Enclave 注册、challenge 和会话成功。
- [ ] 首页只显示 iOS 商品。
- [ ] 详情页加载时提示正确，长按可循环预览，松开恢复封面。
- [ ] 下载时校验 HTTP 状态、MIME、大小和 SHA-256。
- [ ] 最终画布使用当前设备比例，前景完整、背景模糊填充。
- [ ] HEIC 与 MOV 使用同一个新 UUID。
- [ ] MOV 第一轨为视频轨，并包含两个 metadata 轨。
- [ ] `PHLivePhoto.request` 验证成功后才写相册。
- [ ] 相册中显示为 Live Photo。
- [ ] 锁屏编辑页不显示“动态效果不可用”。
- [ ] 锁屏按压可以播放完整动画。

### 15.3 构建与安装

- [ ] 构建模式为 Release。
- [ ] 显式传入线上 `API_BASE_URL`。
- [ ] `codesign --verify --deep --strict` 通过。
- [ ] Bundle ID 为 `com.qingjing.bizhi`。
- [ ] 产物二进制包含正确线上地址。
- [ ] 元数据模板已打入资源 bundle。
- [ ] 覆盖安装成功，并能从桌面独立启动。
- [ ] 首页分类和商品能够加载。
- [ ] 真机完成一次预览、兑换、下载、保存和锁屏设置全流程。

## 16. 排障顺序

| 现象 | 第一检查项 | 常见根因 |
| --- | --- | --- |
| 点击同意后接口报错 | 设备注册与 Bundle ID 白名单 | 正式/测试 Bundle ID 未同步放行 |
| 页面能开但没有内容 | 产物内 `API_BASE_URL` | Release 构建漏传 dart-define |
| 信任后启动白屏 | 设备控制台 | 误装 Flutter Debug 包 |
| 长按没有动态效果 | 预览票据与 MOV 下载校验 | 后端未部署或资源未重新构建 |
| 相册里是静态图 | `.pairedVideo` 保存流程 | HEIC/MOV 未按 Live Photo 资源写入 |
| 相册会动但锁屏不可用 | 60 帧、画布、轨道和元数据 | 直接保存服务端文件或配对不兼容 |
| 图片被裁切 | 客户端画布合成 | 前景误用 aspectFill |
| 图片两边纯色留白 | 客户端画布合成 | 缺少同帧模糊 aspectFill 背景 |
| 下载校验失败 | 描述与文件的大小/SHA-256 | 历史文件、缓存或存储记录不一致 |
| 新代码安装后仍是旧效果 | Bundle ID、安装路径、旧进程 | 同机存在测试包或没有终止旧进程 |

排障时先确认“构建配置和接口地址”，再检查“身份与票据”，最后检查“媒体文件”。这样可以避免把打包问题误判成视频算法问题。

## 17. 代码与资源索引

| 作用 | 文件 |
| --- | --- |
| Flutter 构建期 API 配置 | `apps/mobile/lib/config/app_config.dart` |
| iOS 安装身份、下载校验、相册保存 | `packages/wallpaper-ios/ios/Classes/WallpaperIosPlugin.swift` |
| iOS 设备画布和原生 Live Photo 合成 | `packages/wallpaper-ios/ios/Classes/NativeLivePhotoComposer.swift` |
| Apple metadata 模板 | `packages/wallpaper-ios/ios/Resources/wallpaper-metadata-template.mov` |
| iOS 插件资源打包 | `packages/wallpaper-ios/ios/wallpaper_ios.podspec` |
| Java 动态照片转码 | `services/api-server/src/main/java/com/qingjing/wallpaper/delivery/infrastructure/DynamicPhotoMediaProcessor.java` |
| Java Live Photo 发布与状态记录 | `services/api-server/src/main/java/com/qingjing/wallpaper/delivery/LivePhotoPublisher.java` |
| iOS ECDSA 凭据验证 | `services/api-server/src/main/java/com/qingjing/wallpaper/device/IosCredentialProof.java` |
| OpenAPI 契约 | `contracts/openapi/openapi.yaml` |
| iOS 固定打包流程 | `/Users/kele/.codex/skills/qingjing-ios-package/SKILL.md` |
