# API-018 按安装包平台过滤目录与交付 Java 交接说明

**版本：** 1.0.0

**日期：** 2026-09-27

**状态：** 当前唯一有效的平台可见性与交付方案

**替代：** API-014、API-015、API-016、API-017 的设备能力上报、有效能力交集和个性化目录方案

## 1. 最终产品结论

目录可见性只由**安装包平台**决定，不再由手机型号、系统版本、权限状态、系统开关或运行时能力探测决定。

安装包完成设备注册后，API 已能从 `DevicePrincipal.platform` 获得可信平台：

- Android 安装包注册为 `ANDROID`；
- iOS 安装包注册为 `IOS`；
- HarmonyOS 安装包注册为 `HARMONYOS`。

App 不再调用 `PUT /device/me/capabilities`，API 不再读取设备能力档案决定目录、详情、权益、预览或下载。

系统是否真的支持某种设置方式，只在用户点击设置时由客户端调用系统能力验证。调用失败时，App 提示：

> 您的手机不支持此壁纸，请尝试切换其他类型。

## 2. 安装包可见资源矩阵

| 安装包 | 可见的专属资源 | 同时可见的通用资源 |
|---|---|---|
| Android | `ANDROID / LAYER_PARALLAX`、`ANDROID / VIDEO` | `UNIVERSAL / STATIC_IMAGE` |
| iOS | `IOS / LIVE_PHOTO` | `UNIVERSAL / STATIC_IMAGE` |
| HarmonyOS | `HARMONYOS / MOVING_PHOTO` | `UNIVERSAL / STATIC_IMAGE` |

规则：

1. 商品至少有一个符合当前安装包的已发布资源，才在该安装包中出现。
2. 同一商品可同时拥有多个平台资源，但响应只返回当前安装包可见的 `availableCapabilities`。
3. 通用静态资源在三个正式安装包中都可见。
4. Android 不返回 iOS、HarmonyOS 标签；iOS 不返回 Android、HarmonyOS 标签；HarmonyOS 不返回 Android、iOS 标签。
5. HarmonyOS 动态资源统一使用 `HARMONYOS / MOVING_PHOTO`；旧 `HARMONYOS / THEME_PACKAGE` 不再作为正式方案。

## 3. 服务端唯一判断方式

服务端必须使用已认证会话中的 `DevicePrincipal.platform`，不得信任客户端额外提交的“当前平台”字段。

建议集中实现一个平台范围函数，所有读取链路复用：

```java
boolean visibleTo(DevicePlatform appPlatform, DeliveryCapability capability) {
    if (capability.deliveryPlatform() == DeliveryPlatform.UNIVERSAL
            && capability.resourceType() == ResourceType.STATIC_IMAGE) {
        return true;
    }
    return switch (appPlatform) {
        case ANDROID -> capability.deliveryPlatform() == DeliveryPlatform.ANDROID;
        case IOS -> capability.deliveryPlatform() == DeliveryPlatform.IOS;
        case HARMONYOS -> capability.deliveryPlatform() == DeliveryPlatform.HARMONYOS;
        default -> false;
    };
}
```

安装包平台来自设备注册和签名会话，注册后不可由普通业务请求修改。各平台正式包使用自己的注册证明，API 继续校验 `platform`、应用标识和签名凭据之间的关系。

## 4. 公开目录接口

### 4.1 分类

`GET /api/v1/public/categories`

- 分类数量只统计当前安装包可见的商品；
- 没有可见商品的根分类和子分类不返回；
- 统计逻辑必须使用与列表相同的平台范围。

### 4.2 列表、搜索、精选和免费

`GET /api/v1/public/wallpapers`

处理顺序固定为：

1. 从 `DevicePrincipal.platform` 得到安装包平台；
2. 筛出至少包含一个当前安装包可见资源的商品；
3. 再叠加分类、精选、免费、搜索和排序条件；
4. 计算 `totalItems`、`totalPages`；
5. 最后分页；
6. 每项的 `availableCapabilities` 只保留当前安装包可见资源。

现有 `deliveryPlatform + resourceType` 参数可以保留，供安装包内的具体标签筛选，但必须同时满足当前安装包范围。例如 Android 会话可以筛选 `ANDROID / VIDEO`，不能筛选 `IOS / LIVE_PHOTO`。

列表接口不得先分页再由 App 删除其他平台商品，否则页数和分类数量会不准确。

### 4.3 详情

`GET /api/v1/public/wallpapers/{wallpaperId}`

- 商品存在但没有当前安装包可见资源时，返回 `404 WALLPAPER_NOT_AVAILABLE_FOR_PLATFORM`；
- `availableCapabilities` 只返回当前安装包平台资源和通用静态资源；
- 详情默认展示顺序由各客户端固定，不由 API 探测设备能力。

## 5. 权益、预览与正式下载

### 5.1 我的可用

`GET /api/v1/device/me/entitlements`

- 权益仍然属于商品，不拆成多个平台权益；
- 当前安装包只返回至少有一种本平台可用资源的权益商品；
- 商品中的 `availableCapabilities` 同样按安装包平台裁剪；
- 同一设备已经获得商品权益后，在本安装包内切换该商品的可用资源形式不重复兑换。

### 5.2 预览和下载票据

客户端继续精确选择一个 `deliveryPlatform + resourceType`。API 校验：

1. 请求资源已发布；
2. 请求资源属于 `DevicePrincipal.platform`，或为 `UNIVERSAL / STATIC_IMAGE`；
3. 正式下载满足免费或已获得权益；
4. 不读取 `device_capability_profile`；
5. 不跨平台、不跨资源类型回退。

平台不匹配返回 `400 RESOURCE_PLATFORM_MISMATCH`；资源不存在或未发布返回 `404 RESOURCE_NOT_AVAILABLE`。

## 6. 管理后台规则

管理后台无需增加“发布到 Android/iOS/HarmonyOS”的人工勾选项。

平台由商品下已发布资源变体自动决定：

- 上传 Android 4D 或视频资源，Android 包可见；
- 上传 iOS Live Photo，iOS 包可见；
- 上传 HarmonyOS Moving Photo，HarmonyOS 包可见；
- 上传通用静态原图，三个安装包都可见；
- 同一商品上传多种资源后，可在多个安装包出现，但每个安装包只看到自己的资源标签。

封面仍是商品展示素材，不参与平台判断。

## 7. 设备能力旧方案处理

以下内容从正式业务链路移除：

- `PUT /api/v1/device/me/capabilities`；
- `GET /api/v1/device/me/capabilities`；
- `hostOsFamily`、`executionMode`、`reportedCapabilities`、`effectiveCapabilities`；
- 缺少设备能力档案时的 `428 DEVICE_CAPABILITY_PROFILE_REQUIRED`；
- 根据权限是否开启、用户是否允许操作、是否已获得临时授权来决定目录可见性。

项目尚未正式上线，不保留旧客户端兼容逻辑。已经提交的 Flyway 迁移不要修改；如需删除 `device_capability_profile`，新增后续增量迁移。若本轮暂不删除表，也必须保证任何正式接口都不再读取它。

## 8. Java 改造范围

至少检查并统一以下位置：

1. `PublicCatalogController`：把完整 `DevicePrincipal` 或其 `platform` 传给目录服务。
2. `PublicCatalogService`：分类、列表、详情全部先按安装包平台过滤。
3. `PublishedResourceCatalog`：提供按 `DevicePlatform` 裁剪后的商品与能力集合。
4. `PublicWallpaperViewReader`：响应中的 `availableCapabilities` 使用裁剪结果。
5. `DeviceEntitlementService`：我的可用列表使用同一平台范围。
6. `PreviewTicketService`、`DownloadTicketService`：校验会话平台与请求资源平台一致，允许通用静态。
7. HarmonyOS 资源枚举：正式使用 `MOVING_PHOTO`，移除 `THEME_PACKAGE` 业务路径。
8. OpenAPI：删除设备能力上报作为前置条件，更新目录、详情、权益及票据错误码。
9. 契约测试：删除对 `428` 和能力档案前置条件的断言，增加安装包平台隔离断言。

## 9. 验收清单

- [ ] Android 会话的精选、免费、搜索、分类和详情不出现 iOS、HarmonyOS 专属商品或标签。
- [ ] iOS 会话不出现 Android、HarmonyOS 专属商品或标签。
- [ ] HarmonyOS 会话不出现 Android、iOS 专属商品或标签。
- [ ] 三个平台都能看到带 `UNIVERSAL / STATIC_IMAGE` 的商品和静态标签。
- [ ] 分类计数、`totalItems`、`totalPages` 与当前安装包实际可见商品一致。
- [ ] 同一商品拥有多平台资源时，每个平台只返回本平台资源及通用静态资源。
- [ ] 其他平台资源不能通过修改查询参数、预览请求或下载请求越权获取。
- [ ] 未上报设备能力不会返回 `428`，目录和下载不读取设备能力档案。
- [ ] 客户端系统设置失败不会改变后台目录，只由 App 提示用户切换类型。

## 10. 当前 App 对接状态

- Android 已按安装包固定过滤 Android 资源和通用静态资源；
- HarmonyOS 已按安装包固定过滤 HarmonyOS 资源和通用静态资源；
- App 不再需要设备能力档案参与内容展示；
- API 完成本文件改造后，服务端分页、分类数量和客户端展示将完全一致。
