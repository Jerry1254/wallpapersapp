# API-016 设备目录按设置能力筛选 Java 增量交接说明

**版本：** 1.1.0
**日期：** 2026-09-17
**对应任务：** WP-UI13
**状态：** 已实施 Java API 与 OpenAPI 2.1.0；App 确认说明见 [API-017](API-017-设备目录精确能力筛选App交接确认说明.md)

本文只补充 [API-014](API-014-单商品多设置能力与设备能力协商全链路整改方案.md) 和 [API-015](API-015-设备能力目录与多形式交付App对接说明.md) 中遗漏的“目录按精确设置能力筛选”。设备能力上报、设备个性化目录、单商品多能力、权益复用、预览票据、下载票据和管理后台不在本次重复修改。

## 1. 为什么需要补充

App 首页保留以下入口：

| 首页入口 | 精确设置能力 |
|---|---|
| 4D动态 | `ANDROID + LAYER_PARALLAX` |
| 动态壁纸 | `ANDROID + VIDEO` |
| 静态壁纸 | `UNIVERSAL + STATIC_IMAGE` |
| 免费壁纸 | `accessType=FREE` |
| 精选推荐 | `view=FEATURED` |

OpenAPI 2.0.0 已删除互斥的 `kind` 参数，这是正确的；一个商品可以同时拥有 4D、动态和静态能力，不能恢复 `kind`。

但当前 `GET /api/v1/public/wallpapers` 只能用 `view=STATIC` 筛选静态能力，没有筛选 Android 4D、Android 动态、iOS 动态或鸿蒙动态的精确参数。如果 App 在取得一页结果后本地过滤，会出现以下错误：

- 当前页可能被过滤成空页，但下一页仍有匹配商品；
- `totalItems`、`totalPages` 和加载更多状态不准确；
- 分类、搜索、免费和能力筛选组合时结果不稳定。

因此筛选必须在 API 计算总数和分页之前完成。

## 2. 接口增量

在现有接口增加两个可选查询参数：

```http
GET /api/v1/public/wallpapers
    ?deliveryPlatform=ANDROID
    &resourceType=LAYER_PARALLAX
```

### 2.1 参数定义

| 参数 | 类型 | 规则 |
|---|---|---|
| `deliveryPlatform` | `DeliveryPlatform` | 必须与 `resourceType` 同时提供或同时省略 |
| `resourceType` | `ResourceType` | 必须与 `deliveryPlatform` 同时提供或同时省略 |

允许的组合继续复用 API-014 的稳定组合：

```text
ANDROID    + LAYER_PARALLAX
ANDROID    + VIDEO
IOS        + LIVE_PHOTO
HARMONYOS  + THEME_PACKAGE
UNIVERSAL  + STATIC_IMAGE
```

只提供一个参数或组合不合法时返回：

```http
HTTP 400
code: VALIDATION_FAILED
```

不新增错误码。

### 2.2 组合规则

新增参数与现有参数使用 AND 关系，可以和以下条件同时使用：

- `rootCategoryId`
- `childCategoryId`
- `view`
- `accessType`
- `q`
- `sort`
- `page` / `pageSize`

项目尚未上线，本次同步删除重复的 `view=STATIC` 语义。静态入口统一使用 `UNIVERSAL + STATIC_IMAGE`；`view` 只保留 `FEATURED`，并可与精确能力组合叠加。

## 3. 服务端过滤语义

筛选对象必须是 `DeviceCatalogVisibility.resolve(deviceId)` 已经算出的当前设备可见能力，不能只查询数据库中已发布的变体。

正确顺序：

```text
设备上报能力
∩ 服务端宿主/版本/运行环境规则
∩ 商品已启用且已发布能力
∩ minimumOsVersion / capabilityRequirements
∩ 请求指定的 deliveryPlatform + resourceType
→ 分类、搜索、获取方式等普通条件
→ COUNT
→ 排序和分页
```

Java 可以先从 `visible.capabilitiesByWallpaper()` 计算匹配商品 ID：

```java
List<Long> capabilityIds = visible.capabilitiesByWallpaper().entrySet().stream()
        .filter(entry -> entry.getValue().stream().anyMatch(capability ->
                capability.deliveryPlatform() == deliveryPlatform
                        && capability.resourceType() == resourceType))
        .map(Map.Entry::getKey)
        .toList();
```

若没有匹配 ID，直接返回当前请求页对应的空结果：

```json
{
  "items": [],
  "page": {
    "page": 1,
    "pageSize": 20,
    "totalItems": 0,
    "totalPages": 0
  }
}
```

若存在匹配 ID，应在 `COUNT` 和分页 SQL 之前追加 `w.id IN (:capabilityIds)`。

## 4. 响应规则

筛选只决定商品是否进入列表，不能裁剪商品响应中的 `availableCapabilities`。

例如一个商品同时支持当前设备的 Android 动态和通用静态：

```json
{
  "id": "1001",
  "title": "山谷晨光",
  "availableCapabilities": [
    {
      "deliveryPlatform": "ANDROID",
      "resourceType": "VIDEO",
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

请求 `ANDROID + VIDEO` 时该商品进入列表，但响应仍返回上面两项完整能力。用户从动态标签进入详情后，仍可以选择将同一商品设置为静态壁纸，而且不重复兑换。

同一商品在一个分页结果中只能出现一次。

## 5. OpenAPI 修改

在 `GET /public/wallpapers` 增加：

```yaml
- name: deliveryPlatform
  in: query
  description: 与 resourceType 同时提供，按当前设备可见的精确设置能力筛选商品。
  schema:
    $ref: '#/components/schemas/DeliveryPlatform'
- name: resourceType
  in: query
  description: 与 deliveryPlatform 同时提供，按当前设备可见的精确设置能力筛选商品。
  schema:
    $ref: '#/components/schemas/ResourceType'
```

OpenAPI 版本已从 `2.0.0` 升为 `2.1.0`，契约基线及覆盖测试同步更新。因项目尚未上线，旧的 `view=STATIC` 不保留兼容。

## 6. Java 修改位置

主要修改：

1. `PublicCatalogController.wallpapers(...)`
   - 接收可选 `DeliveryPlatform deliveryPlatform`；
   - 接收可选 `ResourceType resourceType`；
   - 原样传给服务层。
2. `PublicCatalogService.wallpapers(...)`
   - 校验两个参数成对出现及组合合法；
   - 从 `VisibleCatalog` 计算 `capabilityIds`；
   - 在 `COUNT` 和分页前追加筛选。
3. `contracts/openapi/openapi.yaml`
   - 增加两个查询参数；
   - 升级契约版本和基线。
4. 集成测试与 OpenAPI 覆盖测试
   - 覆盖第 7 节场景。

不需要修改：

- Flyway 和数据库表；
- `device_capability_profile`；
- 设备能力上报接口；
- 权益或兑换表；
- 预览与下载票据 DTO；
- 管理后台页面和管理 API；
- 资源包生成和加密逻辑。

## 7. 验收用例

1. 只传 `deliveryPlatform` 返回 `400 VALIDATION_FAILED`。
2. 只传 `resourceType` 返回 `400 VALIDATION_FAILED`。
3. `ANDROID + STATIC_IMAGE` 等非法组合返回 `400 VALIDATION_FAILED`。
4. 未上报设备能力时仍返回 `428 DEVICE_CAPABILITY_PROFILE_REQUIRED`。
5. 设备只有静态能力时，请求 `ANDROID + VIDEO` 返回正确空分页。
6. 商品已发布视频变体，但当前设备没有视频能力时，请求 `ANDROID + VIDEO` 不返回该商品。
7. 同一商品同时有视频和静态能力时，分别请求两种组合都只返回一张商品卡片。
8. 上述商品在任一筛选结果中的 `availableCapabilities` 都保持完整，不只保留用于筛选的那一项。
9. 能力筛选与 `accessType=FREE`、分类、搜索组合后，`totalItems` 和 `totalPages` 与实际匹配数量一致。
10. 翻页时不出现空页、漏项或同一商品重复。
11. `view=FEATURED` 与精确能力同时提供时，只返回同时满足精选和能力条件的商品。
12. 不提供两个新增参数时，现有目录行为和排序保持不变。
13. `view=STATIC` 返回 `400 VALIDATION_FAILED`，静态入口必须使用精确能力组合。

## 8. App 对接约定

Java 完成后，App 使用：

```text
精选推荐 → view=FEATURED
免费壁纸 → accessType=FREE
4D动态   → deliveryPlatform=ANDROID&resourceType=LAYER_PARALLAX
动态壁纸 → deliveryPlatform=ANDROID&resourceType=VIDEO
静态壁纸 → deliveryPlatform=UNIVERSAL&resourceType=STATIC_IMAGE
```

API 返回的 `availableCapabilities` 仍是当前设备完整能力交集。App 以它渲染多个标签、选择设置形式和选择桌面/锁屏位置，并在预览或下载票据请求中提交用户最终选择的精确组合。
