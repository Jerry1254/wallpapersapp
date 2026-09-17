# API-015 设备能力目录与多形式交付 App 对接说明

**版本：** 2.1.0
**日期：** 2026-09-17
**对应任务：** WP-UI13
**状态：** Java API、V9、OpenAPI 和管理后台已实施；App 待按本文联调

本文是 App 的实施和验收依据。机器契约以 [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml) 为准，产品与全链路原则见 [API-014](API-014-单商品多设置能力与设备能力协商全链路整改方案.md)。

## 1. 对接结论

1. 一款壁纸是一个商品，可同时提供多种设置形式。
2. App 上报当前设备实测能力，API 只返回“商品已发布能力 ∩ 设备有效能力”。
3. 分类数量、首页、列表、搜索、详情、已获得列表和下载都使用同一可见性规则。
4. 预览成功不代表系统允许设置；只有系统探测或真实设置成功才能上报能力。
5. 下载必须精确指定一个 `deliveryPlatform + resourceType`，API 不回退到其他形式。

## 2. 能力枚举

App 只能上报以下五组能力：

| 设置形式 | `deliveryPlatform` | `resourceType` |
|---|---|---|
| Android 4D | `ANDROID` | `LAYER_PARALLAX` |
| Android 动态 | `ANDROID` | `VIDEO` |
| iOS 动态 | `IOS` | `LIVE_PHOTO` |
| 鸿蒙原生动态 | `HARMONYOS` | `THEME_PACKAGE` |
| 通用静态 | `UNIVERSAL` | `STATIC_IMAGE` |

设置位置为 `HOME` 或 `LOCK`。有任意一个位置即可展示商品，App 只启用对应的设置按钮。

## 3. 设备初始化顺序

```text
注册安装凭据
  → 建立设备会话
  → 本机能力探测
  → 签名 PUT /device/me/capabilities
  → 使用设备 Bearer 请求目录
  → 按 availableCapabilities 展示和下载
```

设备尚未上报能力时，目录返回 HTTP `428` + `DEVICE_CAPABILITY_PROFILE_REQUIRED`。App 收到后应完成检测与上报，然后重试原请求一次。

## 4. 能力上报

### 4.1 接口

```http
PUT /api/v1/device/me/capabilities
Authorization: Bearer <device-session>
Content-Type: application/json
X-Request-Timestamp: <ISO-8601>
X-Request-Nonce: <UUID>
X-Request-Signature: <Base64URL signature>
```

正文必须参与现有 `QJ-SIGNED-REQUEST-V1` 签名，必须按发出的原始 UTF-8 字节计算 SHA-256，不能在签名后重新序列化 JSON。

### 4.2 Android 示例

```json
{
  "hostOsFamily": "ANDROID",
  "hostOsVersion": "15",
  "sdkInt": 35,
  "manufacturer": "Google",
  "model": "Pixel 8",
  "executionMode": "NATIVE",
  "probeVersion": 1,
  "probedAt": "2026-09-17T08:00:00Z",
  "featureFlags": [],
  "capabilities": [
    {
      "deliveryPlatform": "ANDROID",
      "resourceType": "LAYER_PARALLAX",
      "runtimeOsVersion": "35",
      "placements": ["HOME"],
      "evidence": "SYSTEM_PROBE"
    },
    {
      "deliveryPlatform": "ANDROID",
      "resourceType": "VIDEO",
      "runtimeOsVersion": "35",
      "placements": ["HOME", "LOCK"],
      "evidence": "SUCCESSFUL_SET"
    },
    {
      "deliveryPlatform": "UNIVERSAL",
      "resourceType": "STATIC_IMAGE",
      "runtimeOsVersion": "35",
      "placements": ["HOME", "LOCK"],
      "evidence": "SYSTEM_PROBE"
    }
  ]
}
```

### 4.3 响应

```json
{
  "version": 2,
  "profileHash": "64位小写 SHA-256",
  "hostOsFamily": "ANDROID",
  "hostOsVersion": "15",
  "sdkInt": 35,
  "manufacturer": "Google",
  "model": "Pixel 8",
  "executionMode": "NATIVE",
  "probeVersion": 1,
  "probedAt": "2026-09-17T08:00:00Z",
  "featureFlags": [],
  "effectiveCapabilities": [],
  "recheckRequired": false
}
```

App 只能用响应中的 `effectiveCapabilities`，不得继续使用被服务端过滤的上报项。`GET /api/v1/device/me/capabilities` 可读取当前有效档案。

当设备确实没有任何可设置能力时，`capabilities` 可以是空数组；服务端保存有效的空档案，目录正常返回空结果，不要伪造一项能力来绕过初始化。

## 5. 鸿蒙和 Android 容器规则

| 环境 | `hostOsFamily` | `executionMode` | 可上报能力 |
|---|---|---|---|
| 普通 Android | `ANDROID` | `NATIVE` | 实测 Android 4D/动态及通用静态 |
| 早期华为 Android / EMUI | `EMUI` | `NATIVE` | 实测 Android 能力及通用静态 |
| 早期鸿蒙 | `HARMONY_CLASSIC` | `ANDROID_COMPATIBLE` | 实测 Android 能力及通用静态，不能上报鸿蒙原生包 |
| 新鸿蒙原生 App | `HARMONY_NATIVE` | `NATIVE` | 鸿蒙原生动态及通用静态 |
| 新鸿蒙 Android 容器 | `HARMONY_NATIVE` | `ANDROID_CONTAINER` | 不授予 Android 4D/动态；静态也必须真实探测成功 |
| iOS | `IOS` | `NATIVE` | iOS 动态及通用静态 |

能运行 Android APK 不代表宿主系统提供 Android 壁纸设置能力。Java 会再次过滤这类上报。

## 6. 目录与详情

以下请求都必须携带设备 Bearer：

- `GET /api/v1/public/categories`
- `GET /api/v1/public/wallpapers`
- `GET /api/v1/public/wallpapers/{wallpaperId}`
- `GET /api/v1/device/me/entitlements`

列表不再接受 `kind` 或单独的 `platform` 来决定设备可见性。首页设置能力入口使用成对的 `deliveryPlatform + resourceType` 查询参数，API 在计数和分页前按当前设备可见交集进行精确筛选：

```text
4D动态  → ANDROID / LAYER_PARALLAX
动态壁纸 → ANDROID / VIDEO
静态壁纸 → UNIVERSAL / STATIC_IMAGE
```

`view` 只保留 `FEATURED`；项目尚未上线，不保留 `view=STATIC`。完整增量调用说明见 [API-017](API-017-设备目录精确能力筛选App交接确认说明.md)。每个商品返回当前设备的完整可用交集：

```json
{
  "id": "1001",
  "title": "山谷晨光",
  "availableCapabilities": [
    {
      "deliveryPlatform": "ANDROID",
      "resourceType": "LAYER_PARALLAX",
      "placements": ["HOME"]
    },
    {
      "deliveryPlatform": "UNIVERSAL",
      "resourceType": "STATIC_IMAGE",
      "placements": ["HOME", "LOCK"]
    }
  ]
}
```

App 渲染规则：

- 商品只显示一次；
- 一项能力时直接显示对应设置入口；
- 多项能力时先选设置形式，再按 `placements` 选桌面/锁屏；
- 数组为空属于协议错误，不展示设置入口；
- 详情直连返回 `404 WALLPAPER_NOT_AVAILABLE_FOR_DEVICE` 时，返回列表并刷新目录。

## 7. 预览和下载

预览与正式下载都传精确组合：

```json
{
  "deliveryPlatform": "ANDROID",
  "resourceType": "LAYER_PARALLAX"
}
```

对应端点：

- `POST /api/v1/device/wallpapers/{wallpaperId}/preview-tickets`
- `POST /api/v1/device/wallpapers/{wallpaperId}/download-tickets`

两个请求都使用设备会话和 `QJ-SIGNED-REQUEST-V1` 签名。正式下载还要求：

- 组合仍在当前 `availableCapabilities` 中；
- `REDEEM` 商品已有设备权益，`FREE` 商品免权益；
- 已发布资源版本和安全包已就绪；
- 会话、签名、限流和短时票据有效。

请求的形式不可用时，App 回到详情重新获取 `availableCapabilities`，不能自动改成静态或其他动态资源。

## 8. 本地状态键

已下载/已安装状态至少使用以下组合键：

```text
wallpaperId + deliveryPlatform + resourceType + resourceVersionId
```

商品权益仍只是 `deviceId + wallpaperId`。同一商品切换 4D、动态或静态不重复兑换，也不应预下载其他形式。

## 9. 重新探测和缓存

以下情况必须重新探测并 `PUT` 能力档案：

- 宿主系统升级；
- App 升级或 `probeVersion` 升级；
- 真实设置成功；
- 系统明确拒绝或返回不可恢复的设置错误；
- API 要求重新上报。

上报后如果 `profileHash` 改变，App 必须清理分类、列表、详情和待用下载描述缓存。用户主动取消设置不能记为设备不支持。

## 10. 异常处理

| HTTP / code | App 处理 |
|---|---|
| `428 DEVICE_CAPABILITY_PROFILE_REQUIRED` | 检测、上报、清缓存，重试一次 |
| `400 VALIDATION_FAILED` | 记录档案与客户端版本，不用未被服务端确认的能力 |
| `404 WALLPAPER_NOT_AVAILABLE_FOR_DEVICE` | 移除本地该目录项并刷新 |
| `403 ENTITLEMENT_REQUIRED` | 引导免费获取/兑换流程 |
| `422 SECURE_PACKAGE_NOT_READY` | 提示资源准备中，不回退其他形式 |
| `401 SESSION_EXPIRED` | 重建会话，复用当前能力档案或按要求重新上报 |

## 11. 当前交付边界

- Android `LAYER_PARALLAX` / `VIDEO` / `STATIC_IMAGE` 已具备安全包、预览票据和正式下载链路。
- iOS `LIVE_PHOTO` 和鸿蒙 `THEME_PACKAGE` 已完成数据、后台发布、能力匹配和目录契约；对应原生 App 身份与安装交付器要在对应平台开发阶段联调。
- 管理后台可为同一商品独立新增、替换、启停五种能力；展示封面不会自动授予通用静态能力。

## 12. App 联调验收清单

1. 首次会话未上报能力时收到 428，上报后目录可读。
2. 普通 Android 仅看到 Android/通用形式。
3. 早期鸿蒙可按实测继续使用 Android 资源，且不获得鸿蒙原生包。
4. 新鸿蒙 Android 容器不因 APK 可运行而看到只有 Android 4D/动态的商品。
5. 只支持 `HOME` 的设备可看到商品，详情只显示“设为桌面”。
6. 同一商品有多项当前设备能力时只显示一张卡片，可精确选择形式。
7. 直接请求不可用详情或下载返回错误，不交付其他形式。
8. 同一商品在不同形式间切换不重复兑换。
9. 能力档案变化后目录、分类数量和已获得列表同步变化。
10. 预览可用但系统设置接口不可用时，App 不上报对应设置能力。
11. 4D、动态和静态入口在分页前按精确能力过滤，总数、页数与列表一致。
12. 从任一能力入口返回的多能力商品仍保留完整 `availableCapabilities`。
