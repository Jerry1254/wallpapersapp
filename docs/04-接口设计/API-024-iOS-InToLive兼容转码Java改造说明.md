# API-024 iOS IntoLive 兼容转码 Java 改造说明

**状态：** 待 Java API 实现，iOS 客户端随后接入

**日期：** 2026-09-29

**OpenAPI 目标版本：** `2.12.0`

## 1. 结论

Java 后端需要修改 iOS 动态壁纸的转码算法，但第一阶段不需要新增接口、数据表或管理后台字段。

当前实现按时间截取源视频的前 1 秒，再直接生成 HEIC 与 MOV。真机验证表明，这种文件可以在相册中播放，但可能被 iOS 锁屏判定为“动态效果不可用”。

目标实现按 IntoLive 的实际行为处理：

1. 解码源视频，按显示顺序取前 `60` 个独立视频帧。
2. 不改变这 60 帧的内容、顺序和编号。
3. 将这 60 帧重新标记为 `60 fps`，得到精确 `1.000 秒`的视频。
4. 丢弃第 61 帧及其后的画面。
5. 不保留音频、字幕或源文件元数据。
6. 后端交付标准化视频；iOS 客户端使用 Apple 原生框架生成最终 HEIC/MOV 配对并保存到相册。

这不是“截取源视频前 1 秒”。对一段 `30 fps / 2 秒`素材，必须保留原来的前 60 帧，再把播放时间从约 2 秒压缩为 1 秒。

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

OpenAPI `2.12.0` 只需修正语义说明：`video` 是符合 IntoLive 帧序规则的标准化 MOV，iOS 客户端会用它进行原生最终封装；不再承诺 Java 返回的双文件可直接写入相册后用于锁屏。

## 6. 错误码

保留现有错误码，并新增一个可识别错误：

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

## 8. 联调顺序

1. Java 完成前 60 帧标准化算法、校验、错误码和 OpenAPI `2.12.0`。
2. Java 使用基准素材生成 MOV，提供 `ffprobe` 结果和逐帧映射测试结果。
3. 部署 Java API。
4. 对现有 iOS Live Photo 资源重新构建。
5. iOS 客户端接入本地 Apple 原生重封装。
6. 真机验证相册播放、锁屏动态效果和重复下载。

验收通过的最终标准不是“相册里能动”，而是 iOS 锁屏编辑页不再显示“动态效果不可用”，并且设置后按压可播放完整动画。
