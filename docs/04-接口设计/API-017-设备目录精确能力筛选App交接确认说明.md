# API-017 设备目录精确能力筛选 App 交接确认说明

**版本：** 1.0.1
**日期：** 2026-09-17
**对应任务：** WP-UI13
**状态：** Java API、OpenAPI 2.1.0、真实 MySQL/Redis 集成验证和 App 接入已完成，等待本地 API 与红米真机验收

本文是 API-016 实施后的 App 增量交接依据。设备能力上报、目录可见性、详情、权益、预览和下载的完整规则继续以 [API-015](API-015-设备能力目录与多形式交付App对接说明.md) 为准；机器契约以 [`contracts/openapi/openapi.yaml`](../../contracts/openapi/openapi.yaml) 为准。

## 1. 最终结论

`GET /api/v1/public/wallpapers` 新增两个成对出现的可选参数：

- `deliveryPlatform`
- `resourceType`

API 先计算当前设备真正可见的商品能力，再按请求指定的精确能力筛选，最后计算 `totalItems`、`totalPages` 和分页内容。App 不再对一页结果做二次能力过滤。

筛选只决定商品是否进入列表。响应中的 `availableCapabilities` 仍返回该商品对当前设备有效的完整能力集合，不能只保留用于筛选的能力。

## 2. 首页入口调用方式

| App 入口 | 请求参数 |
|---|---|
| 4D动态 | `deliveryPlatform=ANDROID&resourceType=LAYER_PARALLAX` |
| 动态壁纸 | `deliveryPlatform=ANDROID&resourceType=VIDEO` |
| 静态壁纸 | `deliveryPlatform=UNIVERSAL&resourceType=STATIC_IMAGE` |
| 免费壁纸 | `accessType=FREE` |
| 精选推荐 | `view=FEATURED` |

示例：

```http
GET /api/v1/public/wallpapers?page=1&pageSize=20&deliveryPlatform=ANDROID&resourceType=VIDEO
Authorization: Bearer <device-session>
```

精选中的 Android 动态：

```http
GET /api/v1/public/wallpapers?view=FEATURED&deliveryPlatform=ANDROID&resourceType=VIDEO
Authorization: Bearer <device-session>
```

## 3. 参数规则

两个参数必须同时提供或同时省略，只允许以下五种组合：

```text
ANDROID    + LAYER_PARALLAX
ANDROID    + VIDEO
IOS        + LIVE_PHOTO
HARMONYOS  + THEME_PACKAGE
UNIVERSAL  + STATIC_IMAGE
```

以下请求返回 HTTP `400`，错误码 `VALIDATION_FAILED`：

- 只传 `deliveryPlatform`；
- 只传 `resourceType`；
- 传入 `ANDROID + STATIC_IMAGE` 等非法组合；
- 继续使用已经删除的 `view=STATIC`。

项目尚未上线，因此不保留 `view=STATIC`。静态入口统一使用 `UNIVERSAL + STATIC_IMAGE`。`view` 当前只接受 `FEATURED`。

## 4. 与其他筛选条件组合

精确能力参数可以与以下参数同时使用，全部按 AND 关系计算：

- `rootCategoryId`
- `childCategoryId`
- `view=FEATURED`
- `accessType=FREE|REDEEM`
- `q`
- `sort`
- `page` / `pageSize`

服务端处理顺序为：

```text
设备有效能力与商品已发布能力交集
→ 精确 deliveryPlatform + resourceType
→ 分类、精选、获取方式和搜索条件
→ COUNT
→ 稳定排序
→ 分页
```

因此 App 可以直接信任分页元数据，不需要扫描后续页面补齐当前入口。

## 5. 响应语义

假设一个商品同时具有当前设备可用的 Android 动态和通用静态能力，请求 Android 动态入口时仍返回完整能力：

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

App 从动态入口进入详情后，仍可让用户选择该商品的静态形式；权益仍绑定商品，不重复兑换。

没有匹配商品时返回正常空分页：

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

## 6. App 修改清单

1. 首页 4D、动态和静态入口改用第 2 节的精确参数。
2. 删除所有 `view=STATIC` 调用。
3. 不在收到分页后按 `availableCapabilities` 删除商品卡片。
4. 卡片和详情仍使用完整 `availableCapabilities` 渲染能力标签及设置形式。
5. 目录缓存键加入 `deliveryPlatform`、`resourceType`、其他查询参数及当前 `profileHash`。
6. 收到 `428 DEVICE_CAPABILITY_PROFILE_REQUIRED` 时先上报能力，再重试一次原目录请求。
7. 收到 `400 VALIDATION_FAILED` 时修正参数，不尝试本地回退为其他能力。

## 7. 不变部分

本次没有修改：

- 设备能力上报请求和响应；
- 分类接口及商品详情 DTO；
- `availableCapabilities` 结构；
- 权益和兑换规则；
- 预览、下载票据请求体；
- 管理后台和数据库结构。

## 8. App 确认清单

- [x] 三个首页能力入口已改为精确参数。
- [x] 已删除 `view=STATIC`。
- [x] 不再进行分页后的能力过滤。
- [x] 多能力商品在每个列表中只显示一张卡片。
- [x] 多能力商品响应保留完整 `availableCapabilities`。
- [x] 能力筛选可以与分类、免费、精选和搜索组合。
- [x] 空结果按 `totalItems=0`、`totalPages=0` 正常结束加载更多。
- [x] 当前 App 不持久化目录和详情响应，无旧 `profileHash` 缓存串用；未来新增持久化缓存时必须将全部查询参数和 `profileHash` 纳入缓存键。

App 实施、自动回归、双包构建和待真机验收项见 [WP-UI13 App 验收记录](../06-测试与验收/WP-UI13-App设备能力与多形式交付实施验收-2026-09-17.md)。

## 9. Java 交付确认

- OpenAPI：`2.1.0`，61 个操作、97 个 Schema；
- Java 快速测试：53 项通过；
- Testcontainers 集成测试：13 项通过，其中 12 项为真实 MySQL/Redis 业务场景；
- 契约校验：61 个操作、97 个 Schema、144 个 Java 直接错误码覆盖通过；
- 契约基线：2.1.0，34 个 SHA-256 文件通过。

本次未修改数据库迁移、管理后台、权益或票据结构，也未执行生产发布或生产数据操作。
