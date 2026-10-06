# 创作台壁纸上传与上架 API 对接说明

日期：2026 年 10 月 6 日。契约版本：2.21.0。

本次按最新确认的范围，交付壁纸资源上传、保存草稿、上架任务和发布检查。创作台上传已生成的成品，API 复用现有壁纸处理与发布服务；剪辑项目、原始素材库、渲染、作品、模板和收藏由创作台负责。

代码已完成，尚未执行生产部署或生产迁移。前端以本说明和 `contracts/openapi/openapi.yaml` 为本次接入依据。

## 一 接口范围

所有路径以下列 `/api/v1` 为前缀。

| 方法和路径 | 用途 | 成功返回 |
| --- | --- | --- |
| `GET /admin/creator/capabilities` | 当前环境、上传限制和平台规则 | 200 |
| `POST /admin/assets` | 上传封面、静态原图和平台源视频 | 首次 201，幂等重放 200 |
| `GET /admin/assets/{assetId}` | 读取已上传资产状态和哈希 | 200 |
| `POST /admin/parallax-packages` | 导入 4D 原始 ZIP | 首次 201，已有同哈希源包 200 |
| `POST /admin/creator/wallpaper-publications` | 保存草稿或上架 | 202 和任务查询 `Location` |
| `GET /admin/creator/wallpaper-publications` | 查询并恢复上架任务 | 200，分页列表 |
| `GET /admin/creator/wallpaper-publications/{taskId}` | 查询任务阶段、已有 ID 和结果 | 200 |
| `POST /admin/creator/wallpaper-publications/{taskId}/retry` | 恢复可重试的失败任务 | 202 |
| `POST /admin/wallpapers/{wallpaperId}/publication-check` | 只读发布前检查 | 200 |
| `GET /admin/wallpapers/{wallpaperId}` | 回读商品、变体、发布状态和版本 | 200 和 ETag |

原有管理后台的草稿、变体、资源版本、构建、发布和下架接口继续保留。本次不修改管理后台页面。

## 二 登录和环境

沿用管理 API 的登录会话：`POST /admin/sessions`。请求携带 `QJ_ADMIN_SESSION` Cookie；所有 POST 请求还必须发送 `X-CSRF-Token`，包括只读的 publication-check。建议发送 `X-Request-Id` 方便查错。

本机页面可以通过现有同源 API 代理接入。直接跨域访问时，应配置管理 API 已有的允许来源配置，不能用任意目标地址代理替代环境选择。

先读取 capabilities，取得 `environment.id` 和 `rulesVersion`，再原样提交。服务器的稳定环境 ID 来自 `QJ_ENVIRONMENT_ID`。未配置环境 ID，或提交的 ID 与当前 API 不一致，不能创建上架任务。连接失败时不得自动切换到另一套 API。

当前规则版本是 `creator-wallpaper-rules-1`。capabilities 的 `supportedOperations` 仅将壁纸上传、上架、发布检查声明为可用，其他创作台模块均为 false。

## 三 上传成品资源

### 上传用途

| 内容 | 上传接口和 purpose | 上架平台和类型 | 单文件上限 |
| --- | --- | --- | --- |
| 商品列表封面 | assets，`WALLPAPER_COVER` | 商品的 `coverAssetId` | 20 MiB |
| 高清静态原图 | assets，`STATIC_IMAGE` | `UNIVERSAL / STATIC_IMAGE` | 50 MiB |
| Android 动态视频 | assets，`VIDEO` | `ANDROID / VIDEO` | 200 MiB |
| iOS 源视频 | assets，`LIVE_PHOTO_SOURCE` | `IOS / LIVE_PHOTO` | 200 MiB |
| 鸿蒙源视频 | assets，`MOVING_PHOTO_SOURCE` | `HARMONYOS / MOVING_PHOTO` | 200 MiB |
| Android 4D ZIP | parallax-packages，无 purpose 字段 | `ANDROID / LAYER_PARALLAX` | 100 MiB |

assets 请求使用 multipart：`purpose` 和 `file`。封面和静态图支持 JPEG、PNG、WebP。封面仍会自动生成不超过 720×1280 的 WebP；静态正式原图不会使用封面压缩后的文件替代。

Android 可以上传 MP4 或 MOV，源视频不超过 30 秒；正式交付沿用现有 H.264 MP4 处理。iOS 正式上传统一使用 MP4，至少 60 个实际显示帧，H.264 或 HEVC、帧率不超过 60、宽高各不超过 4096。iOS 派生预览仍是 60 帧、60 fps、1 秒，正式下载保留源 MP4。

鸿蒙上传 MP4，至少 2 秒，H.264 或 HEVC、帧率不超过 60、宽高各不超过 4096。API 生成不超过 2 秒的 MP4 和一张 JPEG，二者属于同一份鸿蒙动态资源。配套 JPEG 不会自动变成全平台静态能力。

4D ZIP 包含原始 `config.json` 与连续编号的 2–12 层，不要求 `cover.jpg`。沿用现有专用导入与校验，配置文件和源 ZIP 不由新接口重新序列化。商品列表封面必须独立上传。

### 上传重试

创作台调用 assets 时建议发送 `Idempotency-Key: <UUID>`。同一个上传请求的文件字节、文件名、Content-Type 和 purpose 必须保持一致；重试返回原 assetId。换文件或用途时使用新的请求键，否则返回 409 `IDEMPOTENCY_CONFLICT`。

保存每个上传结果的真实 `id`、`sha256` 和 `validationStatus`。只有 READY 资产可以进入上架请求。4D 导入结果保存的是 `sourcePackageId`，不能当作 assetId 使用。

## 四 保存草稿和上架

### 请求

`POST /admin/creator/wallpaper-publications` 必须发送新的 UUID `Idempotency-Key`。示例中的 ID 必须替换为当前环境中真实上传、分类查询返回的 ID。

```json
{
  "environmentId": "LOCAL_DEVICE",
  "clientProjectKey": "studio-project-8d613abc",
  "action": "PUBLISH",
  "wallpaperId": null,
  "expectedWallpaperVersion": null,
  "metadata": {
    "title": "城市光影",
    "slug": "wallpaper-8d613abc",
    "accessType": "FREE",
    "rootCategoryId": "12",
    "childCategoryId": null,
    "coverAssetId": "204",
    "featuredRank": null,
    "sortOrder": 10,
    "copyrightNote": "自有原创素材",
    "previewWatermarkEnabled": true,
    "offlinePromotionOnly": false
  },
  "resources": [
    {"platform": "UNIVERSAL", "resourceType": "STATIC_IMAGE", "assetId": "205"},
    {"platform": "ANDROID", "resourceType": "VIDEO", "assetId": "206"},
    {"platform": "ANDROID", "resourceType": "LAYER_PARALLAX", "sourcePackageId": "207"}
  ],
  "retainResourceVersionIds": [],
  "iosAcquisition": null,
  "rulesVersion": "creator-wallpaper-rules-1"
}
```

本次与原始交接方案的区别：不使用服务端 `projectId`、`projectRevisionNo`、`coverMediaId`、`artifactId` 或 `sourceMediaId`。直接提交正式上传得到的 assetId 和 sourcePackageId。

`clientProjectKey` 是创作台自己生成并持久保存的项目标识，允许 1–128 个字母、数字、点、下划线、冒号、短横线。它只用于当前环境中的商品关联和任务恢复，不代表 API 托管了剪辑项目。项目绑定商品后，后续更新必须明确提交对应 wallpaperId，不能再次创建另一款商品。

`metadata` 沿用现有 WallpaperWriteRequest 的字段校验。名称最多 40 字符；slug 是 2–64 个小写字母、数字和短横线；分类必须真实存在；coverAssetId 必填；排序为 0–999999。版权字段仍是现有 API 必填契约，前端不要提交空字符串。未知字段会被拒绝，不能假装已保存。

`SAVE_DRAFT` 可以提交空 resources，仅保存有效商品信息和封面；上传的资源版本会保留，但不执行正式上架。返回商品的真实状态：新建时为 DRAFT，更新已下架商品时可以为 OFFLINE。已上架商品不能通过 SAVE_DRAFT 伪装成草稿，请明确上架更新或先下架。

`PUBLISH` 至少需要一个合法资源。API 创建或复用对应变体、创建资源版本、准备现有交付资源、执行发布检查，然后提交发布状态和任务成功结果。

### 更新已有壁纸

同时提交 `wallpaperId` 和 `expectedWallpaperVersion`。后者来自最近一次商品 GET 的 version，不是 ETag 字符串。其他原有管理修改接口仍按原契约使用带双引号的 If-Match。

`resources` 表示本次新增或替换的能力；`retainResourceVersionIds` 明确列出本次要保留的已发布或 READY 版本。每个变体最终只能选择一个版本。

例如商品已有 Android、iOS 和静态版本，本次只替换 Android 视频：resources 提交新的 Android assetId，retainResourceVersionIds 填入原 iOS 和静态版本 ID。遗漏没有替换的已发布能力会返回 `PUBLICATION_SELECTION_INCOMPLETE`，不会悄悄减少其他平台的资源。

不自动增加静态能力，不新增人工发布平台选择。各 App 继续按现有安装包平台和已发布完整资源查询。

### iOS 获取配置

`iosAcquisition` 可空。非空时复用当前主分支已有配置，支持 NON_CONSUMABLE 与 CREDITS；这不是新的支付实现。

```json
{
  "acquisitionMode": "CREDITS",
  "credits": 3,
  "firstFreeEligible": true,
  "enabled": true
}
```

credits 支持 1–10、12、14、15、16、18、20、21、24、27、30。NON_CONSUMABLE 使用既有 productId、firstFreeEligible、enabled 字段。价格展示继续来自 Apple 同步；创作台不应写入 chinaReferencePrice。capabilities 会返回当前实际支持的字段。

## 五 任务状态和恢复

首次返回 202，例如：

```json
{
  "taskId": "6101",
  "state": "QUEUED",
  "stage": "QUEUED",
  "environmentId": "LOCAL_DEVICE",
  "clientProjectKey": "studio-project-8d613abc",
  "action": "PUBLISH",
  "title": "城市光影",
  "wallpaperId": null,
  "wallpaperVersion": null,
  "attempt": 0,
  "completedSteps": [],
  "result": null,
  "errorCode": null,
  "errorMessage": null,
  "retryable": false,
  "createdAt": "2026-10-06T12:00:00Z",
  "updatedAt": "2026-10-06T12:00:00Z"
}
```

轮询 Location 或任务详情，建议间隔 1–2 秒。页面恢复时，可以用 `GET /admin/creator/wallpaper-publications?clientProjectKey=...&page=1&pageSize=20` 找到持久任务。pageSize 最大 100。

| state | 前端处理 |
| --- | --- |
| QUEUED | 等待处理，不显示已上架 |
| RUNNING | 展示 stage 和 completedSteps；不虚构百分比 |
| SUCCEEDED | 检查 result 的实际商品状态，并 GET 商品回读确认 |
| FAILED 且 retryable=true | 显示保留的商品和资源 ID；允许调用 retry |
| NEEDS_INPUT | 用户处理字段、文件或版本冲突后，用新请求键重新提交 |

stage 包括 WALLPAPER、IOS_CONFIGURATION、VARIANT_0、RESOURCE_0、PREPARE_资源版本ID 和 COMPLETED。completedSteps 保存已完成步骤的真实 ID；不会因为重试重新创建已经记录的商品或资源版本。

成功 result 示例：

```json
{
  "wallpaperId": "7101",
  "status": "PUBLISHED",
  "version": 4,
  "resourceVersionIds": ["8101", "8102"],
  "previewGenerationStatus": "PENDING"
}
```

只有 action=PUBLISH、state=SUCCEEDED、result.status=PUBLISHED，并且商品回读仍为 PUBLISHED，才显示“已上架”。上架和任务结果在同一个 MySQL 事务中提交，响应丢失时先查询或使用原请求键重放，不重复创建商品。

预览与水印继续走现有发布后生成队列。PENDING 或 PROCESSING 时显示“已上架，预览处理中”，并查询商品的最新 previewGenerationStatus；READY 后使用可用预览。FAILED 时提示管理端重试预览生成。正式资源不会被预览文件替换。

本次不提供任务取消接口；已提交的上架不能通过取消自动下架。需要下架时调用现有明确的 offline 操作。

## 六 发布检查和错误

publication-check 请求：

```json
{"resourceVersionIds":["8101","8102"]}
```

返回 wallpaperId、wallpaperVersion、canPrepare、canPublish、checks。每条检查含 scope、code、level、message、suggestedAction、preventsPreparation。它只读取文件并校验，不转码、不上架、不改状态。

canPrepare=true、canPublish=false 通常表示交付派生资源待构建。封面、分类、元数据、文件大小与哈希、资源归属、变体启用、版本状态、平台格式或绑定有问题时，会阻止进入准备。最终 publish 在版本锁内执行同一检查，预检查不能替代最终校验。

尚未生成的预览包按当前发布后队列机制返回 WARNING；已损坏的文件、正式包和动态照片配对文件会阻止发布。iOS MOV 即使能通过通用上传，也不能作为该流程的正式源视频。

| 错误码 | 处理 |
| --- | --- |
| CREATOR_ENVIRONMENT_MISMATCH | 核对已选 API 和 capabilities，不切换环境重试 |
| CREATOR_RULES_CHANGED | 重新读取规则，确认请求后使用新键 |
| IDEMPOTENCY_CONFLICT | 同一个请求键的输入变化了；恢复原输入或使用新键 |
| CREATOR_WALLPAPER_LINK_CONFLICT | 核对项目关联的真实商品 ID |
| VERSION_CONFLICT | 回读商品，展示他人改动，确认后重新提交 |
| ASSET_NOT_READY | 核对资产状态、上传用途和所在环境 |
| IOS_SOURCE_MP4_REQUIRED | 上传正确用途的 iOS 源 MP4 |
| PUBLICATION_SELECTION_INCOMPLETE | 明确保留未替换的其他平台版本 |
| PUBLICATION_CHECK_FAILED | 按检查详情修复资源或发布选择 |
| RATE_LIMITED 或可恢复处理失败 | 保留已有 ID，等待后 retry 同一任务 |

## 七 前端联调验收

1. 读取环境和真实分类；独立上传封面与静态图，保存草稿并回读 DRAFT。
2. 重放同一个上传键与上架键，确认得到原资产、原任务和原商品；不同输入重用同一个键应报 409。
3. 上架静态、Android 视频、4D、iOS 和鸿蒙资源；回读已发布版本，并沿用现有 App 下载验证。
4. 只更新一个平台时，明确保留其余平台版本；故意遗漏应报错。
5. 构建失败保留草稿和资源 ID；恢复后重试不能重复创建版本。
6. 两个页面同时修改商品，旧版本提交或失败任务恢复应停止并要求确认，不覆盖新版本。
7. 区分“已接受任务”“草稿已保存”“已上架”“预览处理中”，不能用 202 或本地 UI 动画替代真实状态。

## 八 工程和验证

新增增量迁移 `V23__creator_wallpaper_publications.sql`，只保存上传请求去重记录、上架任务和项目到商品的关联；没有项目、素材库或渲染任务表。

Java 构建、相关单元测试和 OpenAPI 校验通过。数据库回归用例已覆盖幂等、草稿、真实发布、失败恢复、外部版本冲突和上传去重；当前机器没有可运行的隔离 MySQL 测试环境，这些数据库用例尚未执行通过。发布前应在隔离 MySQL 8 环境运行该回归并进行以上联调验收。

部署时只需沿用现有 API 的数据库、存储和媒体处理配置，并确保环境 ID 正确、后台调度开启。本次不申请新的 Apple 商品 ID、不配置生产凭据、不执行生产迁移或部署。
