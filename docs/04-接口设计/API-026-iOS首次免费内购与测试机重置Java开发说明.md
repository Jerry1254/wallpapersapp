# API-026 iOS 首次免费、苹果内购与测试机重置 Java 开发说明

更新日期：2026-10-02。状态：**Java 基础业务和 Apple 账号准备已完成；真实 Apple 验签适配器、前端契约对齐及首免/购买真机验收仍待完成。本次未发布后端。**

本文件集中说明本次业务、后端接口、管理后台、前端待补部分及账号持有人需要办理的事项。当前入库主契约为 [openapi.yaml](../../contracts/openapi/openapi.yaml)，版本 `2.14.0`；早期增量方案见 [ios-acquisition.draft.yaml](../../contracts/openapi/ios-acquisition.draft.yaml)。入库版本不代表线上已部署。第 11 节记录 Java 实现结果；**本次给 Java 的账号参数、已下载密钥、安全配置和剩余开发任务请先看第 12 节**。前文示例与主契约不同时，以主契约及第 11、12 节为准。

## 1. 本次确定的业务

| 场景 | 规则 |
| --- | --- |
| Android、HarmonyOS | 继续使用现有兑换码流程，本次不增加首次免费或苹果支付 |
| iOS 新设备 | 经苹果设备验证后，可免费获取一张符合活动条件的壁纸 |
| 免费获取成功，App 未卸载 | 当前安装保留该壁纸权益，可重复下载，不重复消耗免费次数 |
| 免费获取后卸载重装 | 不要求恢复旧安装的免费壁纸列表；同一设备不能因为重装重新获得免费次数 |
| iOS 其他未拥有壁纸 | 通过 Apple In-App Purchase 购买；每张壁纸对应一个非消耗型商品 |
| 付费后卸载重装或更换设备 | 使用原购买 Apple 账号，可以恢复购买；不得要求对同一商品重新付费 |
| 测试机需要重新测试首免 | 管理员发起一次“重置测试机免费资格”，清理当前安装的活动免费权益，并将苹果免费标记重置；成功后只恢复一次机会 |

不引入平台账号、手机号登录或“通过 Apple 登录”。购买使用系统 App Store 账号，App 不获取账号密码。免费资格属于设备，付费购买属于苹果购买权益，两者不能共用一个“已使用”字段。

“免费一次”是获取一张壁纸的资格，不是每下载一次扣一次。相册授权拒绝、转换失败、网络中断后，已经取得的壁纸可以继续下载。切换静态/动态资源不能再领一次活动免费资格；同一商品具体包含的壁纸资源应在商品描述中明确。

后台重置对应用户所说的“删掉测试记录、变回新机”的业务效果；实现采用撤销免费来源并保留审计，不物理删除设备、苹果交易或操作证据。已保存到系统相册的图片无法由后台收回。

## 2. 现有代码与本次待开发范围

| 部分 | 当前事实 | 本次需要补齐 |
| --- | --- | --- |
| Flutter | `apps/mobile/lib/entitlements/ios_acquisition.dart` 已有首免、购买、恢复的流程骨架，尚未完全对齐 `2.14.0` | 商品数组与状态枚举解析、购买证明字段、测试机重置握手、代次更新、缓存失效和真实联调 |
| iOS 原生 | `packages/wallpaper-ios/ios/Classes/IosAcquisitionBridge.swift` 已接部分 StoreKit 2、App Attest、DeviceCheck 桥接 | 补充 AppTransaction JWS、Apple 设备验证 ID 和验证；真机、Sandbox、TestFlight 验证 |
| 本地演示 | 独立 Debug 演示及 `.storekit` 测试商品 | 仅验证交互，不能证明 Apple 服务端验签或真实防重领已完成 |
| 功能开关 | `IOS_ACQUISITION_ENABLED` 默认 `false` | 后端和真机验收通过再开启；正式包不得混入演示授权 |
| Java | V15、首免/购买/重置业务接口及 Apple 通知入口已提交，真实 Apple gateway 仍为不可用占位实现 | 真实验签、Apple 网络调用、环境隔离、补偿与并发故障验收；详见第 12 节 |
| 管理端 | iOS 商品配置、测试机标记和重置入口已提交 | 联调真实 Apple 重置进度、状态和审计 |

V15 已定义多来源权益迁移；实际部署和迁移执行情况需另行核验。验收必须覆盖旧兑换数据回填、新权益聚合以及敏感请求签名和原始 body 捕获，不能用一条删除 SQL 替代首免重置。

## 3. 设备识别与防重复领取

### 3.1 三层验证各自解决什么

| 层 | 用途 | 不能代替什么 |
| --- | --- | --- |
| 现有匿名安装会话 + P256 签名 | 确认请求属于哪个安装、请求未被修改 | 不能证明安装对应唯一物理设备 |
| App Attest | 核验可信 App 的密钥证明及业务请求断言，防止普通脚本伪造和重放 | 不是跨卸载的永久设备 ID，也不证明某个 Apple 账号拥有购买 |
| DeviceCheck | 在苹果维护的设备状态中记录首免已经使用 | 不返回可任意查询的永久硬件 ID，不是原子扣减接口 |

为本活动约定 `bit0=true` 表示已经免费领取；`bit1` 保留，不用于本功能。DeviceCheck 位处于开发者范围，接入前确认同一开发者其他 App 没有占用 bit0。不可把 Bundle ID 改一个就当作独立免费活动。

DeviceCheck token 是临时凭证，不能用 token 或其哈希充当永久设备编号，也不能从旧数据库设备 ID 推导新 token。App Attest 密钥随安装变化；正常重装后的防重领依赖苹果保留的标记，而不是本地文件、IDFV、Keychain 或客户端传来的设备 ID。[Apple DeviceCheck](https://developer.apple.com/documentation/devicecheck)、[App Attest 生命周期](https://developer.apple.com/documentation/devicecheck/establishing-your-app-s-integrity)

必须如实说明边界：这是较稳妥的官方防滥用方案，不能承诺“任何工具、越狱、代理设备、抹机和所有跨安装并发都绝对无法作弊”。DeviceCheck 没有按物理设备暴露 compare-and-set；本安装数据库锁不能消除所有跨安装竞态。未知状态不发免费资格，配合限流、异常密钥频率和领取行为监控。

### 3.2 App Attest 服务端验证要求

使用现有安装会话发起一次性随机 challenge，最长有效期 120 秒，绑定安装、App Attest keyId、动作、壁纸及当前免费代次。注册动作返回完整 `clientData`；其余动作断言覆盖最终提交的原始 UTF-8 JSON 字节。不可解析 JSON 后重新序列化再计算摘要。

注册核验苹果证书链、nonce、公钥与 keyId、凭据 ID、rpIdHash、环境和初始计数器。rpIdHash 使用配置中的 **App ID prefix + Bundle ID**；App ID prefix 通常等于 Team ID，但不能无条件假设。验证成功后保存公钥和 receipt，不保存任何客户端私钥。

业务断言核验签名、绑定关系、challenge、rpIdHash 和严格递增计数器；计数器不要求每次恰好加一。验证计数器更新与消费 challenge 必须有原子并发控制。客户端对同一 key 的证明请求排队，乱序失败重新取挑战。业务重试使用新 challenge、原业务幂等键。[Apple 验证规范](https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-server)

### 3.3 环境不能混为一个开关

分别配置业务环境、DeviceCheck 环境、App Attest 环境和 StoreKit 交易环境。TestFlight 的购买是 Sandbox，但 App Attest 使用生产证明，不能因为交易是 Sandbox 就切换所有苹果服务到 development。

Xcode 本地 StoreKit 交易不进入任何生产权益账本。真实 Sandbox 交易由验签后的环境决定，进入隔离测试账本；审核和 TestFlight 必须能走完整合法购买与下载流程，但不能把 Sandbox 标为正式付款，也不能允许客户端自报 `sandbox=true` 绕过验签。开发、测试、生产使用各自数据和凭据；同一 Apple team 的设备位还需单独确认实际隔离边界。[App Attest 环境说明](https://developer.apple.com/documentation/devicecheck/assessing-fraud-risk)、[Apple 内购配置](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/overview-for-configuring-in-app-purchases/)

## 4. 后端数据模型与迁移

以下是拟新增逻辑模型，Java 可按现有命名落表，但约束不能省略。MySQL 保存业务事实；Redis 只保存短期挑战、限流、短时票据及辅助协调。

| 表/对象 | 关键字段与约束 |
| --- | --- |
| `ios_installation_acquisition` | `device_id` 唯一、服务端生成 `account_token` UUID 唯一、`free_generation` 非负整数、`is_test_device` 默认 false、`active_reset_id`、状态、乐观锁版本；记录最近苹果查询结果和时间，但不能把缓存当领取依据 |
| `ios_app_attest_key` | 安装外键、keyId 唯一、验证后公钥、App ID prefix、Bundle ID、证明环境、receipt、最大 counter、状态、时间；大 counter 使用足够范围的类型 |
| `ios_free_claim` | requestId、安装、generation、壁纸、状态、苹果写入尝试及结果、权益来源 ID、失败码、时间；唯一 `(device_id, request_id)`，目标和代次不可改变；同一代次只允许一个非失败的领取占位 |
| `ios_product_mapping` | 壁纸、Bundle ID、productId、商品类型 NON_CONSUMABLE、启用状态；同 Bundle 下壁纸与商品一对一；发布商品不可换绑另一张壁纸 |
| `ios_store_transaction` | Apple 验证后的 environment、bundleId、transactionId、originalTransactionId、productId、appAccountToken、可用时的 appTransactionId、购买/撤销时间、最新验证时间；唯一 `(environment, bundle_id, transaction_id)` |
| `entitlement_grant` | 安装、壁纸、来源 `REDEMPTION / IOS_FIRST_FREE / IOS_IAP`、来源记录 ID、环境、generation（仅首免）、ACTIVE/REVOKED、撤销原因及时间；来源业务键唯一 |
| `ios_free_reset` | resetId、安装、expectedGeneration、resultingGeneration、幂等键、操作人、原因、状态、有效期、苹果执行阶段、重试次数、最后错误、时间；同安装最多一个未结束重置 |
| `apple_notification_inbox` | notificationUUID 唯一、已验签载荷、环境、处理状态、重试信息；先持久化后异步应用业务变化 |
| `admin_operation_audit` | 复用现有审计能力，记录测试标记、重置、重试、取消和结果；不写入 token、私钥、完整 JWS 或原始资源地址 |

迁移现有权益时，推荐保留 `device_entitlement` 作为 `(device_id, wallpaper_id)` 聚合索引，新增 `entitlement_grant` 保存来源。让 `source_code_id` 可以为空并保留其现有外键；存量兑换权益回填一个 REDEMPTION 来源，兑换流程后续同时写来源和聚合行。不可伪造一张兑换码承载所有苹果交易。

聚合状态为“至少有一个有效来源”。免费和购买可以同时指向同一壁纸：重置首免不能撤销购买，购买退款不能误删独立免费权益。来源变更、聚合重算和 outbox 同一事务提交，避免存在有效来源却被查询为无权益。下载和管理查询继续用同一业务权益判定。

Flyway 使用新的递增迁移文件，不改已发布迁移；具体编号由 Java 开发时取最新值。迁移必须验证存量兑换数据、外键、回填幂等性及旧版 Android/HarmonyOS 兼容性。

涉及证明、交易数据的存储、保留期和用途，要同步隐私政策及 App Store 隐私申报，不能继续声明“完全不处理设备信息”。

## 5. App 业务接口与调用顺序

所有路径以配置的 API base `/api/v1` 为前缀；本文件不虚构一个新生产域名。设备 POST 同时需要当前 Bearer 和现有 P256 敏感请求签名。后三类业务请求另有 `X-App-Attest-Key-Id`、`X-App-Attest-Assertion`。

| 接口 | 作用 |
| --- | --- |
| `POST /device/ios/attestation/challenges` | 获取 ENROLL、STATUS、FREE_CLAIM、PURCHASE_SYNC 或 FREE_RESET 挑战 |
| `POST /device/ios/attestation/registrations` | 验证并登记 App Attest 公钥 |
| `POST /device/ios/acquisition/status` | 查询免费资格、商品映射、当前安装权益及待执行测试重置 |
| `POST /device/ios/acquisition/free-claims` | 幂等领取首次免费壁纸 |
| `POST /device/ios/acquisition/purchases` | 验证 Apple 交易，购买或恢复当前安装权益 |
| `POST /device/ios/acquisition/free-resets/{resetId}/complete` | 被管理员授权的测试手机提交新设备证明，推进一次指定重置 |
| 现有 `POST /device/wallpapers/{wallpaperId}/download-tickets` | 校验实际权益后发原始 MP4 的短时下载资格 |

### 5.1 启动与状态

顺序为：原有安装注册/会话 → App Attest key 注册（首次）→ STATUS challenge → 生成 DeviceCheck token 与断言 → status → 获取 StoreKit 商品价格、同步本地有效/未完成交易。

状态返回示例（ID 为示意）：

```json
{
  "installationId": "d8b3096e-a4b5-43bb-a00b-0368e0bb3608",
  "accountToken": "26e7b6e8-e8dc-4b2a-b874-1163058c5ebd",
  "freeAllowance": "AVAILABLE",
  "freeGeneration": 0,
  "products": [{"wallpaperId": "123", "productId": "com.qingjing.bizhi.wallpaper.123"}],
  "freeWallpaperIds": [],
  "purchasedWallpaperIds": [],
  "pendingFreeReset": null
}
```

`installationId` 是现有 `anonymous_device.public_id`，供手机复制后在管理端精确定位，不是物理设备 ID。`accountToken` 由服务器签发，用于新购买 `appAccountToken`，不能自报。商品价格取 Apple `Product.displayPrice`，Java 不向客户端伪造支付金额。`AVAILABLE` 只表示刚通过资格查询；真正领取前仍重新验证。状态未知、Apple 不可用时不能默认 AVAILABLE，也不要误显示“已经用过”。

普通详情页读内存/本地权益缓存，不逐页调用苹果恢复。启动、主动刷新、领取/购买/退款通知后的同步才更新状态；已有权限每次申请下载仍由服务端判定。缓存不是下载通行证。

### 5.2 免费领取与异常补偿

请求示例：

```json
{
  "challengeId": "c0f380d4-32e6-4b1a-a733-3b111c749eee",
  "nonce": "server-issued-nonce",
  "deviceToken": "base64-devicecheck-token",
  "wallpaperId": "123",
  "requestId": "6c892c66-b8c6-44cd-a96d-eae47acb7f40"
}
```

客户端先持久化 requestId 与壁纸再发送，网络重试使用相同 requestId、新 challenge。Java 在合法请求身份校验后处理：

1. 若相同 requestId 已成功且来源仍有效，返回当前权益快照，不再次修改苹果标记；如果已被管理员重置撤销，返回 `IOS_FREE_CLAIM_RESET`，不能借旧幂等请求把旧权益复活。
2. 检查目标壁纸已发布、支持 iOS、符合活动；若已经拥有该壁纸，直接返回现有权益，不消耗资格。相同 requestId 换壁纸返回幂等冲突。
3. 当前安装存在重置则拒绝新领取；校验 challenge 绑定的 generation 仍为当前值。创建本代次唯一领取占位并落库后，才调用苹果。并发请求不能分别获得两张壁纸。
4. 查询 DeviceCheck。确定 bit0 已使用且不是本请求已成功记录时，返回已使用；未知/异常保持待查。确认未使用后，写 bit0=true，仅修改占用的位。
5. 苹果明确确认成功后，将领取状态、IOS_FIRST_FREE 来源、聚合权益及审计事务提交，再返回成功；随后客户端走已有 MP4 下载和本地导出流程。

建议状态：`RESERVED → APPLE_WRITE_STARTED → APPLE_CONFIRMED → GRANTED`，异常进入 `RECONCILING` 或确定的 `REJECTED`。进程重启后从 MySQL 恢复未完成操作，不能只依赖 Redis 锁。

苹果写入和 MySQL 无法做一个数据库事务。Apple 超时并不等于没写入；单看 bit0=true 也不能证明是哪个安装、哪次请求写入。结果有歧义时保持待核查，不重新扣、不自动发另一张；返回 `IOS_FREE_CLAIM_PENDING` 并让原请求恢复。补偿任务必须记录采用的证据及人工处理入口，不能把所有未知情况伪装成确定的“免费资格已使用”。

服务端必须设置请求限流、每安装单活动操作、幂等约束及 worker fencing，防止租约到期后的旧 worker 再次写苹果。以上可控制本安装并发，不能声称解决了苹果未提供稳定设备 ID/CAS 的所有跨安装竞争。

### 5.3 购买与恢复

交互顺序：获取商品 → 用户点击显示 Apple 价格的购买按钮 → 系统支付 → StoreKit verified transaction → PURCHASE_SYNC challenge/断言 → Java 验证和持久化 → App finish 交易 → 现有受保护下载。

Java 使用 [Apple App Store Server Library for Java](https://github.com/apple/app-store-server-library-java) 验证 JWS/证书链，核对预期环境、Bundle ID、生产 appAppleId、NON_CONSUMABLE 商品、交易状态及映射。按需通过 App Store Server API 获取最新交易，不能只 Base64 解码，也不接受客户端布尔“支付成功”。验证新的正常购买时核对服务端签发的 appAccountToken；异常绑定不能直接放行。

非消耗型恢复需要接受旧安装的合法交易并为新安装建立权益来源，不能要求历史 appAccountToken 等于新安装 token。交易主记录可以关联多个有效恢复安装，不是“先绑定过别的安装就永久拒绝”。退款需要撤销这笔交易对应的所有安装来源。

恢复入口放在“我的 → 已获得壁纸”区域。用户点击后调用 `AppStore.sync()`，逐笔上报经 StoreKit 验证的 currentEntitlements；成功后一次更新整份列表。普通启动可以被动读取本地 StoreKit 权益，不能每次开详情弹 Apple 登录框。没有购买时显示“未找到可恢复的购买”；已免费领取的历史不走 Apple 恢复。

**购买证明仍需安全验收**：App Attest 只能证明请求来自可信 App，不能独立证明该请求者拥有任意粘贴的交易 JWS。原生桥接只允许从 StoreKit verified 结果生成业务请求，并核对当前设备有效性；必须测试复制他人的 JWS 不能通过普通客户端路径获得授权。Apple 提供交易 `deviceVerification` 校验；可用时使用经验证的 AppTransaction/appTransactionId 增强关联，不能把客户端自报 ID 当证明。不要仅为兼容恢复取消所有归属检查。当前主契约 `2.14.0` 已要求 `signedAppTransaction` 和 `deviceVerificationId`；前端及真实 Java 验签适配器仍需完成相应接入，见第 11.2、12.5 节。[设备交易验证](https://developer.apple.com/documentation/storekit/transaction/deviceverification)、[AppTransaction 标识](https://developer.apple.com/documentation/storekit/apptransaction/apptransactionid)

用户取消购买不发权益；pending/Ask to Buy 显示处理中。付款后断网不要 finish 未交付交易；重启靠 unfinished/updates 重试服务器。服务器已确认但 finish 失败，不得撤销已经取得的权益。不得用“测试通过”把 Xcode 伪交易解锁生产 MP4。

### 5.4 Apple 回调与重新下载

新增 `POST /integrations/apple/app-store-notifications`，按 App Store Server Notifications V2 接收 `signedPayload`。该路由不使用设备 Bearer 或管理员 Cookie，以 Apple 签名验证为身份依据，其他内部路径不能一起豁免鉴权。

验签后以 notificationUUID 去重并持久化 inbox，再响应成功；异步处理退款、撤销及恢复通知。按最新 Apple 事实更新来源，处理乱序和重复事件，保留失败重试及定期对账。退款只撤销匹配购买来源；即使客户端未启动也能生效。现有下载票据签发和文件交付须核对权益有效性/版本，撤销未使用旧票据；已经下载的文件不能追回。

此回调列入本次待实现契约。Java 提交时同步主 OpenAPI、路由鉴权、通知验签测试及配置，不以文档代替运行代码。

## 6. 管理后台：重置测试机免费资格

### 6.1 页面与操作

复用“设备权益”列表及详情，不新增一套用户系统。增加 iOS 测试设备筛选/标记、设备 publicId 的精确查询、免费状态及上次确认时间、当前免费壁纸、generation、重置状态和记录。

手机“我的”提供可复制的安装编号，便于管理员选择当前测试安装。不要展示或复制 DeviceCheck token/App Attest 证明。后台已有设备 ID 指的是安装记录，不保证卸载前后是同一条记录。

管理员操作：

1. 在手机打开 App，核对当前安装编号和最近活跃时间。
2. 在设备详情将该 iOS 安装标记为测试设备，记录原因；只管理端可以修改。
3. 点击“重置测试机免费资格”。确认文案：**将撤销此安装本次活动的免费壁纸权益，并恢复一次免费获取机会；付费购买不受影响。需要测试手机联网完成设备验证。**
4. 输入原因后提交，显示“等待测试手机联网”，不能立即显示成功。
5. 测试手机打开/刷新 App，完成指定重置握手。后台显示完成后，手机按钮恢复为首次免费；再领一张后照常变成已使用。

重置按钮只对 IOS、ACTIVE、已标记测试安装开放；无新凭证时只能排队。默认只支持单台操作，不增加批量清空或“测试机永远免费”。测试标记不直接授予免费权益，不修改 App Attest 计数器、安装私钥、已购商品或 Apple 付款记录。

### 6.2 管理 API

继承 `QJ_ADMIN_SESSION`，所有写操作验证绑定会话的 `X-CSRF-Token`。沿用现有单管理主体，不为此新增 RBAC。

现有 `GET /admin/devices` 增加可选查询参数 `publicId`（UUID 精确匹配）与 `iosTestDevice`（boolean）；现有列表/详情补 `publicId`。详情增加 `iosAcquisition` 对象，含 `isTestDevice`、`freeGeneration`、`lastKnownFreeAllowance`、`lastCheckedAt`、`pendingFreeReset`；非 iOS 返回 null。管理页只展示上次确认状态，不能在手机离线时宣称刚向苹果确认。这些现有接口的增量字段也需要 Java 实现时合入主契约，不能只补 Controller。

| 接口 | 请求/返回 |
| --- | --- |
| `PUT /admin/devices/{deviceId}/ios-test-status` | `{isTestDevice, reason}`；返回安装标记与当前代次 |
| `POST /admin/devices/{deviceId}/ios-free-resets` | Header `Idempotency-Key: UUID`，body `{expectedGeneration, reason}`；创建返回 202，已完成幂等结果返回 200 |
| `GET /admin/devices/{deviceId}/ios-free-resets/{resetId}` | 查询等待、处理中、完成或错误及可重试状态 |
| `POST /admin/devices/{deviceId}/ios-free-resets/{resetId}/cancel` | 仅允许苹果写入尚未开始的等待操作取消；原子检查，已经有外部副作用时必须继续核查 |

标记测试设备、创建/取消操作和失败原因写审计。取消测试标记时，有未结束重置则拒绝并提示先处理原操作。幂等键绑定安装及原请求参数；同键不同参数返回冲突。

重置返回的 `resetId` 是随机 UUID，必须校验它属于当前管理路径的设备；手机也只能完成自身安装的待处理重置。客户端不得选择清零哪个 bit，不得自行申请免费重置，不接受客户端 `isTestDevice=true`。

### 6.3 新鲜证明与状态机

status 在普通有效设备验证后返回可选 `pendingFreeReset`。按当前 `2.14.0` 契约，有待重置时 `freeAllowance=PENDING_RESET`，前端暂停领取并显示“测试资格重置处理中”。新前端请求 FREE_RESET challenge，body 包含 resetId；服务端绑定该 resetId 与代次，手机再提交：

```json
{
  "challengeId": "62959706-70f0-43b9-aef4-c98b6fef37c4",
  "nonce": "server-issued-nonce",
  "deviceToken": "fresh-base64-devicecheck-token",
  "expectedGeneration": 0
}
```

POST 到 `/device/ios/acquisition/free-resets/{resetId}/complete`。resetId 由路径和 challenge 绑定，App Attest key 必须属于被指定安装。完成同步返回 200 AcquisitionState；仍在处理返回 202 ResetOperation，前端后续有退避地刷新状态，不重复创建重置。

状态为：

```text
WAITING_DEVICE → PROCESSING → APPLE_RESET_CONFIRMED → COMPLETED
       ├→ EXPIRED / CANCELLED（仅未开始苹果写入）
       └→ RETRYABLE_FAILURE → PROCESSING（保持同一个 resetId）
```

等待有效期建议 30 分钟，是本系统策略，不是 Apple token 的官方有效期。进入外部写入后不能因等待超时自动解冻为可领取；未确定结果必须继续核查。`RETRYABLE_FAILURE` 对 App 仍阻止新免费领取，保留原操作。需要新 token 时由同一安装重新提供。

执行顺序：

1. 持久化重置操作，锁住该安装的新免费领取。创建时如有未决领取，先返回冲突并处理原领取，不能并行重置。
2. 校验新 DeviceCheck token、会话、App Attest、challenge、测试标记和 expectedGeneration。所有 Apple 写入在持久化状态与 worker fencing 下执行；不得持有长数据库事务等待网络。
3. 调用苹果更新 bit0=false，**不发送 bit1**，保留其他用途；只有取得明确结果并完成必要核查，才推进 APPLE_RESET_CONFIRMED。HTTP 超时保留处理中，不把 SQL 删除当成功。
4. 在 MySQL 事务内撤销该安装本活动旧代次的 IOS_FIRST_FREE 来源及领取有效状态，按剩余来源重算聚合权益；generation 恰好加一，写 COMPLETED 和审计/outbox。
5. 使旧免费票据及缓存失效，前端收到新代次后清理旧首免记录、待领取 requestId 和免费资格缓存，再取新状态。保留付费权益，保留身份及证明密钥。

Apple 写入成功后数据库提交失败，由同一操作补偿到完成，不能新建重置。重复调用 COMPLETED 操作仅返回已记录结果和当前快照，**绝不能再次清 bit0**：否则用户已重新领取后，旧重试又会送一次免费机会。所有旧代次 challenge、旧领取请求、迟到 worker 都应被拒绝。

如果测试机在等待期间卸载，新的安装不是原操作授权对象。管理员先取消尚未执行的旧重置，再核对并标记新安装；已有苹果副作用的旧操作先完成核查，不能直接删除。DeviceCheck 不提供永久 ID，不能承诺后台自动认出重装后的这台手机并继承测试授权。

### 6.4 Apple DeviceCheck 调用

服务端使用 DeviceCheck 专用私钥签发 JWT。调用 `/v1/query_two_bits` 和 `/v1/update_two_bits`；生产 base 为 `https://api.devicecheck.apple.com`，开发 base 为 `https://api.development.devicecheck.apple.com`。环境由服务端配置决定。

更新请求包含 `device_token`、唯一 `transaction_id`、UTC Unix 毫秒 `timestamp` 和 `bit0:false`。不要把 transaction_id 误当 Apple 保证的业务幂等键；幂等由本系统状态机保证。`last_update_time` 只有年月，不是精确领取时间或设备标识。苹果返回明确“Bit State Not Found”才按无已存状态解释；401、403、429、5xx、超时或 token 无效不能当新机。[Apple 设备位接口](https://developer.apple.com/documentation/devicecheck/accessing-and-modifying-per-device-data)

只为必要的短期补偿加密保存 token，限制访问、到期清理，不记录到日志、前端管理响应或 Git。不能让管理员上传任意 token 来清零别人设备。

## 7. 错误码与前端处理

沿用现有 `{code,message}` 格式、traceId 和脱敏日志；下表为本次增量语义，具体响应见草案。

| HTTP / code | 前端/管理端行为 |
| --- | --- |
| 403 `IOS_ATTESTATION_INVALID` / `IOS_ASSERTION_REPLAY` | 不发权益；提示验证失败，合法重试必须新挑战 |
| 409 `IOS_CHALLENGE_EXPIRED` | 重新获取挑战，不复用旧证明 |
| 503 `IOS_DEVICE_PROOF_UNAVAILABLE` | 暂时无法验证，不显示新设备免费资格 |
| 409 `IOS_FREE_ALLOWANCE_USED` | 确定未授予本次请求，转购买；先检查是否已有本目标权益 |
| 409 `IOS_FREE_CLAIM_PENDING` | 保持原 requestId/壁纸，稍后重试，不能换目标领取 |
| 409 `IOS_FREE_CLAIM_RESET` | 清理旧待领取缓存，刷新状态，不复活旧代次权益 |
| 409 `IOS_FREE_RESET_PENDING` | 停止首免领取，推进/等待被授权重置 |
| 409 `IOS_FREE_GENERATION_CONFLICT` | 刷新代次，不继续旧领取/重置 |
| 403 `IOS_TEST_DEVICE_REQUIRED` | 普通设备或不符合条件，禁止重置 |
| 404 `IOS_FREE_RESET_NOT_FOUND` | 不存在或不属于当前安装，不泄露其他设备状态 |
| 409 `IOS_FREE_RESET_NOT_CANCELLABLE` | 已开始外部写入，需要完成核查 |
| 403 `IOS_PURCHASE_INVALID` / `IOS_PURCHASE_ENVIRONMENT_INVALID` | 不授予购买；记录原因，不让用户重复付款解决验签错误 |
| 409 `IDEMPOTENCY_CONFLICT` | 同键不同目标，提示刷新/检查请求，不自动生成无限新键 |
| 429 `RATE_LIMITED` | 按服务端建议退避，不马上重试苹果 |
| 503 `IOS_ACQUISITION_UNAVAILABLE` | 功能未就绪或暂时不可用，不用模拟结果兜底 |

苹果具体错误码写脱敏服务器日志，客户端只返回可处理的业务码。重置 GET 可以展示阶段、错误类别及下一步，不返回证书、完整 Apple 响应或凭证。

## 8. 用户按钮与前端待补清单

| 状态 | 主按钮/操作 |
| --- | --- |
| 正在确认免费资格 | 正在验证资格；已有壁纸仍按本地权益展示，下载由后端复核 |
| 未拥有且 AVAILABLE | 首次免费获取；相册保存成功后提示用户去系统设置，不宣称已自动安装壁纸 |
| 已拥有 | 下载壁纸/再次下载，继续现有相册导出交互 |
| 未拥有且 USED | 购买并下载，显示 Apple 返回价格；没有商品信息时不可用 |
| 付款待确认 | 购买处理中；不提前发资源 |
| 我的 | 已获得壁纸、恢复购买、复制安装编号 |
| 管理员重置处理中 | 测试资格重置处理中；完成后刷新为一次免费 |

前端还要解析 `installationId/freeGeneration/pendingFreeReset`，新增 FREE_RESET challenge 和 complete 请求，支持 202，取消已重置的旧请求，并更新用户可读错误。已有首免/StoreKit 骨架不等于这些变化已经完成。旧版本不支持握手时，后台应提示升级测试 App，不能绕过设备证明直接改标记。

恢复 App 时应按当前安装隔离免费历史；若现有身份或缓存可能因 Keychain/备份跨卸载保留，真机验收安装代次行为，不能仅凭 App Attest 换 key 就宣称全部业务身份已换新。重装绝不能清苹果免费标记。

## 9. 现在需要在苹果后台办理什么

### 9.1 账号持有人先做的步骤

**个人开发者可以使用苹果内购，不必为了接 Apple IAP 先申请微信/支付宝商户或 Apple Pay Merchant ID。** 但“注册了开发者账号”需要区分：仅登录 Apple ID 不够，要确认付费 Apple Developer Program 已开通且会员有效。个人上架的销售方名称为本人法定姓名，App 仍可使用“倾境壁纸”品牌名。[Apple 会员要求](https://developer.apple.com/programs/enroll/)

| 顺序 | 去哪里、怎么做 | 完成标志 |
| --- | --- | --- |
| 1 | Apple Developer 账号 → Membership，确认个人会员及有效期；未激活则完成身份核验和缴费 | 可使用 App Store Connect、证书及分发能力 |
| 2 | App Store Connect → Business/商务 → Agreements/协议，由账户持有人接受 Paid Apps Agreement | 协议状态 Active；信息待补时继续下一步 |
| 3 | 同一 Business 页面填写银行与税务资料，使用与登记个人身份对应的真实收款信息 | 银行、税务无缺失/待补；不假设提交即审核完毕 |
| 4 | Apps → ＋ → New App，选 iOS、语言、名称、正式 Bundle ID 和自定义 SKU；已有记录则复用 | 获得该 App 的数字 Apple ID |
| 5 | Developer → Certificates, Identifiers & Profiles，核对正式 App ID、App Attest 能力及分发配置 | 正式应用标识、能力、签名一致 |
| 6 | 创建 DeviceCheck key 和 In-App Purchase key，交给后端通过安全凭据渠道配置 | 后端能连接苹果；私钥不发聊天、不进仓库 |
| 7 | App → Monetization/盈利 → In-App Purchases，先创建 1–2 张真实壁纸的 Non-Consumable 商品 | 名称、描述、价格、销售地区、审核截图及商品映射齐全 |
| 8 | 配置 Sandbox 测试账号和通知 URL，开展真机/恢复/退款测试 | 真购买测试全链路通过，无真实扣款 |
| 9 | 上传 App Store 构建，补充隐私、支持链接、截图、审核说明和所需备案；首批内购与 App 首版一起提交 | App 和关联内购均可提交审核 |

Paid Apps Agreement 必须 Active 才能测试真实 Sandbox 内购；**不要求 App 已经上架**。等待协议处理期间，可以继续本地 StoreKit 交互和后端开发，但不能把本地模拟当成 Sandbox 通过。第一次内购随新 App 版本一起送审。[Apple 内购配置流程](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/overview-for-configuring-in-app-purchases/)

税务表按实际税务身份回答苹果向导，非美国个人通常由系统引导填写适用的 W-8 系列表单，不要照抄别人的税号、勾选身份或税率。银行资料按本人开户资料填写；不需要为了这一流程虚构公司。[税务资料](https://developer.apple.com/help/app-store-connect/manage-tax-information/provide-tax-information/)、[银行资料](https://developer.apple.com/help/app-store-connect/manage-banking-information/enter-banking-information/)

### 9.2 密钥和商品交接

| 项目 | 来源/接收方 |
| --- | --- |
| 正式 Bundle ID | 当前工程 Release 为 `com.qingjing.bizhi`；以准备提交的真实 App 记录再次核对。`com.qingjing.livephotolab` 是实验用途，不直接开通生产内购 |
| Team ID、App ID prefix | Apple Developer 账号和 Identifier，后端配置与签名一致 |
| App Apple ID | App Store Connect 的应用数字 ID，不是 Bundle ID，也不是单个内购 Apple ID |
| DeviceCheck Key ID + `.p8` | Developer → Certificates, Identifiers & Profiles → Keys，启用 DeviceCheck；后端调用设备位接口 |
| IAP Key ID + Issuer ID + `.p8` | App Store Connect → Users and Access → Integrations → In-App Purchase，后端调用 App Store Server API |
| Product ID ↔ 壁纸 ID | 商品创建后录入 Java 映射表，App 从服务器获取 ID、从 Apple 获取价格 |
| Sandbox / Production 通知 URL | 后端部署验证后再配置；服务端基于签名环境隔离处理 |

两类 `.p8` 用途不同，不能互换。下载后保存在安全凭据存储中；Apple 私钥通常只有一次下载机会。交接文档只记录 Key ID、用途和配置名称，不记录私钥内容。[DeviceCheck key](https://developer.apple.com/help/account/capabilities/create-a-devicecheck-private-key)、[In-App Purchase key](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/generate-keys-for-in-app-purchases)

商品 ID 示例 `com.qingjing.bizhi.wallpaper.123` 仅为命名建议，必须在苹果创建后才有效；现有 `.test.wallpaper.*` 和测试价格不能作为正式商品。每张单独售卖的壁纸有独立非消耗型商品，新商品按 Apple 要求审核。不能所有壁纸共用“购买一次”商品，否则无法准确恢复具体内容。首期先做少量商品，未配置商品的壁纸不可显示可付款按钮。[创建商品](https://developer.apple.com/help/app-store-connect/manage-in-app-purchases/create-consumable-or-non-consumable-in-app-purchases/)、[商品 ID 规则](https://developer.apple.com/help/app-store-connect/reference/in-app-purchases-and-subscriptions/in-app-purchase-information/)

### 9.3 中国大陆上架必须另外核对

**网站备案不等于 App 已完成备案。** 现有 `biguo66.top` 网站备案不能直接当成 iOS App 备案完成证明。向当前云服务商备案入口提交/补充 App 信息；已有主体信息可复用，但需登记 App 对应信息。名称、主体、包标识及签名信息以正式 App 和备案平台要求一致填写，再将有效 App 备案信息填到 App Store Connect。工信部公布的材料齐全准确后办理时限为二十个工作日，不包含补材料和前置核验所耗时间。[工信部 App 备案说明](https://www.miit.gov.cn/jgsj/xgj/hlwgl/art/2023/art_564bf0759d7e41d5b4aa8ce4996b9e84.html)

**本项目现有佛像、菩萨题材需要先确认内容资质。** Apple 对中国大陆包含宗教内容的 App 列出《互联网宗教信息服务许可证》要求；不能以“个人会员能内购”推导这类内容一定能直接上架。是否适用于本 App 的具体内容，应把真实分类、截图和描述交 Apple 审核支持及主管部门确认。若被认定需要该许可证，官方申请条件面向依法设立的法人或非法人组织，普通自然人不能据个人开发者账号视为具备资格。不应临时隐藏内容规避审核。[Apple 中国大陆材料要求](https://developer.apple.com/cn/help/app-store-connect/reference/app-information/app-information/)、[国家宗教事务局许可条件](https://www.sara.gov.cn/image/attachDir/2023/06/2023061509025979652.PDF)

此外，准备实际拥有授权的壁纸素材、可访问的隐私政策/用户协议/支持邮箱、与收集行为一致的隐私申报。一般 Apple IAP 配置不要求先办公司；具体经营内容、地区和分发材料要求需分别满足，不能承诺只开开发者会员就一定审核通过。

## 10. Java 实施顺序与上线前验收

建议依次交付，前端可继续用隔离演示并行开发：

1. 增量 Flyway、权益来源改造、商品映射及配置；先证明 Android/HarmonyOS 兑换与下载不回归。
2. App Attest 注册/挑战/断言验签、DeviceCheck 查询；真机证明有效后实现首免状态机。
3. Apple 交易验签、购买与恢复、退款回调/对账及受保护 MP4 下载。
4. 管理设备查询字段、测试标记、重置状态机和补偿；前端补齐 reset 握手。
5. Sandbox/TestFlight 联调，审查数据隔离与真实 App 身份；将已实现接口合入主 OpenAPI 并明确版本。
6. 用户另行确认后部署；确认配置和证书正确再开启前端功能开关。此文档交付不自动授权生产迁移、真实设备重置或上线。

| 验收 | 预期 |
| --- | --- |
| 新真机第一次领取 | 只获得所选一张，Apple 标记与 MySQL 一致 |
| 相册拒绝、转换失败、下载中断 | 已取得权益可以重试，不再消耗资格 |
| 同安装双击、并发选择两张 | 同代次最多一个活动领取成功 |
| 原请求断网、服务重启、Apple 超时 | 原 requestId 可恢复或明确待核查，不额外发一张 |
| 重装、换安装公钥、改本地 ID/配置 | 苹果已使用标记仍阻止再次免费，不把未知当新机 |
| 伪造/过期 challenge、断言重放、错误 Bundle | 拒绝；不能从未签名新路由漏过 |
| 完成首免后管理员仅标记测试机 | 不恢复资格，必须走一次明确重置 |
| 管理员重置，手机离线 | 等待手机，不能报完成 |
| 普通手机请求重置/复制他人 resetId | 拒绝，不调用苹果写接口 |
| 重置与领取竞争、旧 worker 迟到 | 本安装同代次操作串行，旧代次不能发权益 |
| Apple 清零成功、数据库失败 | 保持未完成并补偿，不显示成功、不重复新建重置 |
| 重置后再次领取，再重放旧 complete/claim | 不再清零，不复活旧免费权益 |
| 免费与购买同属一张壁纸 | 重置免费后购买仍有效；退款后独立免费来源不误删 |
| 真 Sandbox 购买成功、取消、pending | 分别正确授予、无权益、保持等待，不走假成功 |
| 支付成功后断网/杀进程 | 未完成交易可重新同步，确认后 finish，无重复付款要求 |
| 卸载后原 Apple 账号恢复 | 已购壁纸重新可下载，首免不恢复；免费历史不保证找回 |
| 其他 Apple 账号、复制他人交易证明 | 无对应有效证明不授予；原账号恢复路径仍可用 |
| 回调重复、乱序、退款/撤销 | 去重并以最新事实更新，多安装购买来源正确处理 |
| Xcode 本地交易发送线上 | 拒绝，不取得生产下载票据 |
| 旧免费票据在重置后使用 | 权益/代次复核拒绝；购买来源有效时按购买正常取得新票据 |
| 不支持证明或 Apple 服务故障 | 暂停首免，不静默放开；已有购买走独立、有效验证流程 |

## 11. 后端完成后在这里追加结果

请直接在本节补充，不再另建一份“完成说明”：

### 11.1 Java、管理后台和契约实现结果

- 代码提交：`33b4cfb`（`main`，已推送 GitHub）。
- 主 OpenAPI 版本：`2.14.0`；本次未部署，线上实际版本未变。
- 联调 API base：本次未启动联调环境；部署后仍使用现有 `/api/v1` 基址。
- 已增加 V15 Flyway 迁移、iOS 商品映射、安装首免状态、App Attest 挑战、公钥、首免领取、StoreKit 交易、统一权益来源、测试机重置和 Apple 通知收件箱。
- 已把兑换码、iOS 首免和 iOS 内购统一为独立权益来源；撤销某一个来源时，其他有效来源仍可保留同一壁纸的下载权益。
- 管理后台已增加：壁纸 iOS Product ID、首免资格、销售开关；设备列表的 installation UUID、iOS 测试机标记、首免代次、待重置状态、权益来源和测试机重置操作。
- Product ID 一旦出现已验证交易即锁定，管理后台不能再修改；价格不存入后台，由 App 通过 StoreKit 获取。
- 主 OpenAPI 与 iOS 增量契约已覆盖下列接口：
  - `POST /device/ios/attestation/challenges`
  - `POST /device/ios/attestation/registrations`
  - `POST /device/ios/acquisition/status`
  - `POST /device/ios/acquisition/free-claims`
  - `POST /device/ios/acquisition/purchases`
  - `POST /device/ios/acquisition/free-resets/{resetId}/complete`
  - `POST /integrations/apple/app-store-notifications`
  - `GET|PUT /admin/wallpapers/{wallpaperId}/ios-acquisition`
  - `PUT /admin/devices/{deviceId}/ios-test-status`
  - `POST /admin/devices/{deviceId}/ios-free-resets`
  - `GET /admin/devices/{deviceId}/ios-free-resets/{resetId}`
  - `POST /admin/devices/{deviceId}/ios-free-resets/{resetId}/cancel`
- 当前尚未完成真实 Apple 信任适配器：App Attest 证书链与 assertion 校验、DeviceCheck 网络调用、StoreKit JWS 和 App Store Server Notification V2 验签仍等待 Apple 标识、密钥和真实环境联调。当前 `QJ_IOS_ACQUISITION_ENABLED=false`，误开或直接调用会返回 `503 IOS_ACQUISITION_UNAVAILABLE`，不会伪造成功结果。

### 11.2 iOS 前端对接

所有 `/device/ios/**` 请求继续使用现有设备会话签名。除注册接口外，证明请求还必须带：

```http
X-App-Attest-Key-Id: <DCAppAttestService keyId>
X-App-Attest-Assertion: <Base64 App Attest assertion>
```

App Attest assertion 必须针对本次请求的原始 HTTP body 生成。调用顺序为：先申请对应 `action` 的 challenge，再提交同一 `challengeId` 和 `nonce`；challenge 只允许成功消费一次。

状态同步请求：

```json
{
  "challengeId": "...",
  "nonce": "...",
  "deviceToken": "DeviceCheck device token"
}
```

首次免费领取请求：

```json
{
  "challengeId": "...",
  "nonce": "...",
  "deviceToken": "DeviceCheck device token",
  "wallpaperId": "123",
  "requestId": "客户端生成并持久化的 UUID"
}
```

购买和恢复购买提交请求：

```json
{
  "challengeId": "...",
  "nonce": "...",
  "signedTransaction": "Transaction.jwsRepresentation",
  "signedAppTransaction": "AppTransaction.jwsRepresentation",
  "deviceVerificationId": "AppStore.deviceVerificationID 的 UUID 字符串"
}
```

App 需要完整解析状态响应：

```json
{
  "installationId": "设备 installation UUID",
  "accountToken": "传给 StoreKit 的 appAccountToken",
  "freeGeneration": 0,
  "freeAllowance": "AVAILABLE|USED|UNAVAILABLE|PENDING_RESET",
  "freeWallpaperIds": [],
  "purchasedWallpaperIds": [],
  "products": [{"wallpaperId": "123", "productId": "..."}],
  "pendingFreeReset": null,
  "checkedAt": "..."
}
```

管理后台发起测试机重置后，App 读取 `pendingFreeReset`，用其 `resetId` 申请 `FREE_RESET` challenge，再调用：

```http
POST /device/ios/acquisition/free-resets/{resetId}/complete
```

```json
{
  "challengeId": "...",
  "nonce": "...",
  "deviceToken": "DeviceCheck device token",
  "expectedGeneration": 0
}
```

前端仍需完成：

1. `apps/mobile/lib/entitlements/ios_acquisition.dart` 解析并持久化 `installationId`、`freeGeneration`、`pendingFreeReset` 和商品映射。
2. iOS 原生桥接除 `signedTransaction` 外，还要返回经 StoreKit 验证的 `signedAppTransaction` 和 Apple `AppStore.deviceVerificationID` 对应的 `deviceVerificationId`。该值不能由客户端随机生成。
3. 首免 `requestId` 在网络重试时必须复用；换壁纸或新领取才生成新 UUID。
4. StoreKit 购买必须使用 API 返回的 `accountToken` 作为 `appAccountToken`。`deviceVerificationId` 是 Apple 提供的设备验证值，用于核验交易及 AppTransaction 中的设备验证摘要；它不是 `appAccountToken`，也不是自行生成的购买参数。具体校验见第 12.5 节。[Apple 设备验证说明](https://developer.apple.com/documentation/storekit/appstore/deviceverificationid)
5. 对 `IOS_ASSERTION_REPLAY`、`IOS_CHALLENGE_EXPIRED` 重新申请 challenge；对 `IOS_FREE_ALLOWANCE_USED`、`IOS_FREE_RESET_PENDING`、`IOS_FREE_GENERATION_CONFLICT` 刷新状态；`IOS_ACQUISITION_UNAVAILABLE` 时隐藏购买入口并提示服务暂不可用。
6. 只有 `REDEEM`、已发布且后台开启 `firstFreeEligible` 的 iOS 商品才消耗首次免费名额；原本 `FREE` 的壁纸继续直接免费下载。
7. 在真实 Apple 验证、Sandbox/TestFlight 联调完成前保持 iOS 首免和内购功能开关关闭。

### 11.3 需要产品/账号侧申请和提供的内容

以下内容缺失时，Java 只能保留关闭状态，不能完成真实 Apple 闭环：

1. Apple Developer Program 有效会员，以及 App Store Connect 中已生效的 Paid Apps Agreement、银行和税务资料。
2. 确认正式 Bundle ID（当前预设 `com.qingjing.bizhi`）、Team ID、App ID Prefix、App Store 数字 App Apple ID。
3. 确认 App Attest 环境：开发联调用 `DEVELOPMENT`，TestFlight/正式包用 `PRODUCTION`。
4. DeviceCheck 私钥：Key ID 与 `.p8` 文件。
5. App Store Connect In-App Purchase 私钥：Issuer ID、Key ID 与 `.p8` 文件。
6. 每款付费壁纸创建一个 Non-Consumable Product ID，建议格式 `com.qingjing.bizhi.wallpaper.<稳定编号>`；同时配置名称、描述、价格、销售地区和审核截图。创建完成后只把 Product ID 填进管理后台。
7. API 部署出公网 HTTPS 地址后，在 App Store Connect 配置 Sandbox 和 Production 的 App Store Server Notifications V2 回调地址：`/api/v1/integrations/apple/app-store-notifications`。
8. 准备 Sandbox 测试账号和至少一台可运行 App Attest、DeviceCheck 与 StoreKit 2 的真机。
9. 上架资料：隐私政策、用户协议、客服邮箱、壁纸素材授权证明；中国大陆发布还需单独确认 App 备案，以及宗教类内容是否涉及互联网宗教信息服务许可。

`.p8` 私钥只能放入部署平台的 Secret/环境变量，不通过聊天发送，不写入 Git。收到上述标识和密钥后，还需要继续实现真实 Apple gateway 并完成 Sandbox、TestFlight、退款通知和重置故障场景测试。

### 11.4 验证记录

- Java 单元与契约覆盖：78 项通过，2 项因本机媒体能力跳过。
- 管理后台：22 项测试、TypeScript 检查和 Vite 生产构建通过。
- OpenAPI：82 个操作、103 个 Schema、184 个 Java 错误码覆盖检查通过；iOS 增量契约检查通过。
- Flyway V15 已生成但未实际执行：本机没有 Docker，也没有运行中的本地 MySQL，因此本次无法启动 Testcontainers 或临时数据库。
- 未执行真实 Apple 证明、首免、重装、恢复购买、退款通知、测试机重置、Apple 超时、MySQL 故障和并发故障注入。
- Android/HarmonyOS 现有 Java 单元与契约测试通过；未做真机回归。
- 状态：代码已提交并推送；未部署；当前不可进行真实 iOS 首免/内购联调。

仅“接口返回 200”不算完成：必须完成真实苹果证明、账本一致性、重置防重放及受保护资源交付验证。测试环境和生产环境的操作结果分别记录。

## 12. 2026-10-02 Apple 参数、密钥及真实联调交接

**Java 本次任务：利用下面已准备好的 Apple 标识和两份私钥，完成真实 `IosAppleGateway`，接通设备验证、首次免费、非消耗型购买/恢复和退款通知。前端同时对齐 `2.14.0` 契约。只添加环境变量、打开开关或返回模拟成功不算完成。**

### 12.1 已确认的账号与商品参数

| 项目 | 已确认值 / 状态 |
| --- | --- |
| App 名称 | 倾境动态壁纸 |
| Apple Developer Program | 个人会员，有效 |
| Paid Apps Agreement、税务、收款银行 | 已生效 / 可用 |
| 正式 Bundle ID | `com.qingjing.bizhi` |
| Apple Team ID | `NFXT4L28FU` |
| App ID Prefix | `NFXT4L28FU`；本次与 Team ID 相同，代码仍分别配置 |
| App Store 数字 App Apple ID | `6818362193` |
| App Attest capability | Developer 网站 App ID 已启用；App 打包的 entitlement 和证明环境仍须核验 |
| DeviceCheck Key ID | `XRWS489HC8` |
| App Store Connect IAP Key ID | `FB8P8L4QX2` |
| IAP Issuer ID | `5183ef0f-1fb4-4748-b150-bc7b29dfbc40` |
| 首个商品名称 | 普贤菩萨动态壁纸 |
| 首个 Product ID | `com.qingjing.bizhi.wallpaper.puxian` |
| 该 IAP 的数字 Apple ID | `6818448059`；仅用于定位后台商品，不能代替上面的 App Apple ID |
| 商品类型 | `NON_CONSUMABLE`，每款壁纸分别对应一个商品 |
| 测试价格及销售地区 | 中国大陆 `¥1.00`，仅用于当前 Sandbox 验证；上线前另行确定正式价格 |
| 商品提交状态 | 准备提交，未送审；审核截图等上架材料仍待补齐 |
| Sandbox 测试账户 | 已创建 1 个，中国大陆；Java 不需要账号密码，不写入本文或 Git |
| 真实测试设备 | iPhone 12，iOS `18.7.8`；开发者模式开启、电脑已配对 |
| 本次设备安装结果 | Release `1.0.0 (10021)`，开发签名；签名校验、安装及独立启动通过 |
| 签名证书 | 已有 Apple Development；本次检查尚无 Apple Distribution，上架构建另行准备，Java 无需这些证书私钥 |
| 当前 App API base | `https://wallpaper.biguo66.top/api/v1`；真实 Apple 首免/购买功能仍关闭 |
| 商店发布状态 | 未在本次操作上传 App Store 构建或提交 App / IAP 审核 |

上述账号和安装准备不等于业务联调通过。当前尚无真实首免、Apple Sandbox 购买、恢复、退款、重置验收记录。Apple 沙盒购买不会真实扣款。[Apple Sandbox 说明](https://developer.apple.com/help/app-store-connect/test-in-app-purchases/overview-of-testing-in-sandbox)

### 12.2 已下载的两份 `.p8` 及安全交付

| 用途 | 文件名 | 本机安全保存位置 |
| --- | --- | --- |
| Java 调用 DeviceCheck 查询/更新设备位的 JWT 签名 | `AuthKey_XRWS489HC8.p8` | `/Users/kele/.config/qingjing/apple-keys/devicecheck/AuthKey_XRWS489HC8.p8` |
| Java 调用 App Store Server API 的 JWT 签名 | `SubscriptionKey_FB8P8L4QX2.p8` | `/Users/kele/.config/qingjing/apple-keys/iap/SubscriptionKey_FB8P8L4QX2.p8` |

本次仅核验两个文件存在，各 `257` 字节，权限 `0600`；未读取或输出私钥内容，未上传服务器。下载目录也保留同名原件；后续交接使用上表安全目录中的文件。

交接步骤：

1. 先把本 Markdown 文档给 Java。文档含公开标识和文件位置，**不含私钥正文**。
2. Java/运维提供目标环境和安全接收方式；账号持有人直接将两份 `.p8` 录入部署平台 Secret，或交付到受控的私钥挂载目录。使用加密传输，限制为服务运行账户可读，记录交付环境和责任人。
3. 按第 12.3 节分别绑定 DeviceCheck 与 IAP 配置。保留 PEM 实际换行；不要把两份文件互换，也不要把字面量 `\n` 或本机文件路径当 PEM 内容。
4. 验证解析和 Apple 调用，只记录 Key ID、环境、成功/错误码；不记录私钥、JWT、完整设备 token 或 JWS。私钥不进聊天、Git、安装包、普通附件压缩包或公开交接目录。

这两份不是 App 签名证书。Java 不需要 Xcode 开发证书私钥、Apple 登录密码或 Sandbox 密码。基础 App Attest 注册及断言验签使用 Apple 信任根和设备已证明公钥，不需要再找一份“App Attest `.p8`”；若后续增加 receipt 风险指标查询，另按 Apple 文档接入。信任根的下载和管理由 Java 实现完成，不把客户端 `x5c` 中的根证书自动视为可信。[App Attest 验证规范](https://developer.apple.com/documentation/devicecheck/validating-apps-that-connect-to-your-server)、[Apple PKI](https://www.apple.com/certificateauthority/)

### 12.3 Java 配置与环境选择

以下环境变量名来自当前 `services/api-server/src/main/resources/application.yml`，可直接作为配置清单；私钥内容由 Secret 注入，不写入示例文件。

| 已存在的配置变量 | 配置值 |
| --- | --- |
| `QJ_IOS_ACQUISITION_ENABLED` | 实现及配置准备期间 `false`；真实适配器完成后先在隔离测试环境开启 |
| `QJ_APPLE_TEAM_ID` | `NFXT4L28FU` |
| `QJ_APPLE_APP_ID_PREFIX` | `NFXT4L28FU` |
| `QJ_APPLE_BUNDLE_ID` | `com.qingjing.bizhi` |
| `QJ_APPLE_APP_ID` | `6818362193` |
| `QJ_APPLE_APP_ATTEST_ENVIRONMENT` | 本地开发证明 `DEVELOPMENT`；TestFlight / App Store 证明 `PRODUCTION` |
| `QJ_APPLE_STORE_ENVIRONMENT` | 本次真实内购测试 `SANDBOX`；正式购买 `PRODUCTION` |
| `QJ_APPLE_DEVICECHECK_KEY_ID` | `XRWS489HC8` |
| `QJ_APPLE_DEVICECHECK_PRIVATE_KEY` | Secret 中 `AuthKey_XRWS489HC8.p8` 的完整 PEM 内容 |
| `QJ_APPLE_STORE_ISSUER_ID` | `5183ef0f-1fb4-4748-b150-bc7b29dfbc40` |
| `QJ_APPLE_STORE_KEY_ID` | `FB8P8L4QX2` |
| `QJ_APPLE_STORE_PRIVATE_KEY` | Secret 中 `SubscriptionKey_FB8P8L4QX2.p8` 的完整 PEM 内容 |

现有 `private-key` 属性接收字符串内容，尚无本文确认的 `_FILE` 配置。若运维用文件挂载，应先由 Java 增加并测试文件读取能力，不能直接把服务器路径填进上述 PEM 变量。需要新增的 DeviceCheck 环境选择、连接/读取超时、信任根加载与 Secret 文件读取配置，由 Java 同步到可执行配置并在完成确认中列明；不能假装当前已有这些配置项。

环境按下表安排，三个 Apple 环境分别选择：

| 阶段 | App Attest | DeviceCheck 服务端 | StoreKit | 业务数据 |
| --- | --- | --- | --- | --- |
| 开发签名 Release 真机联调 | 默认 `DEVELOPMENT`；若显式设置 production entitlement，则后端也使用 `PRODUCTION` | 先用开发端点验证，配置与测试策略明确记录 | `SANDBOX` | 独立测试数据 |
| TestFlight / 审核验证 | `PRODUCTION` | 生产端点，单独确认设备位测试/重置范围 | `SANDBOX` | 测试交易与正式购买隔离 |
| App Store 正式购买 | `PRODUCTION` | 生产端点 | `PRODUCTION` | 正式数据 |

TestFlight 使用生产 App Attest，但内购仍是 Sandbox。`Release` 是编译模式，不能据此认定 App Attest 或内购已经处于生产环境。App Attest 开发/生产密钥不能混用，切换环境时需重新登记对应 key；不能靠切换同一个配置值继续使用旧证明。[Apple App Attest 环境](https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.devicecheck.appattest-environment)

DeviceCheck 生产端点为 `https://api.devicecheck.apple.com`，开发端点为 `https://api.development.devicecheck.apple.com`。实现须明确环境并保存验证证据，不能从客户端 `sandbox=true` 决定端点。DeviceCheck 位在开发者范围内使用，测试前确认本 Team 的其他 App 对 bit0/bit1 的占用。[Apple DeviceCheck 接口](https://developer.apple.com/documentation/devicecheck/accessing-and-modifying-per-device-data)

正式服务需能安全处理审核使用的 Sandbox 交易，同时隔离账本和授权范围。当前单一 `store-environment` 严格模式若仅允许 PRODUCTION，将不能完成 Sandbox 审核购买。Java 应在隔离测试环境先测通，再明确审核环境路由或双验证器策略；每份交易必须通过对应环境的 Apple 签名校验，不能在任意失败时降级放行，也不能把 Sandbox 权益写成正式付款。

### 12.4 Java 剩余开发任务

| 顺序 | 任务 | 完成标准 |
| --- | --- | --- |
| J1 | 实现真实 `IosAppleGateway`，配置 Secret、信任根和环境 | 开启时装配真实实现；关闭时使用安全占位实现；缺失配置报清楚的不可用错误，不出现多个 Bean 注入冲突 |
| J2 | App Attest 注册、assertion 验证 | Apple 信任链、nonce、rpIdHash、公钥/keyId、环境、原始 body 和计数器校验通过；篡改、错误环境、跨安装和重放被拒绝；计数器及 challenge 消费具备原子并发保证 |
| J3 | DeviceCheck 网络适配 | 查询位、标记 bit0=true、管理员授权重置 bit0=false；仅更新 bit0，保留 bit1；超时保持待核查，不当成未使用或重置成功 |
| J4 | 首次免费及重置补偿 | Apple 写入与 MySQL 分离故障、重试和并发可恢复；旧 requestId 不复活已撤销权益；管理员重置需要指定设备的新证明及审计 |
| J5 | StoreKit 交易与 AppTransaction 验证 | 使用 Apple 官方 Java 库或等价完整验证；实现第 12.5 节的双 JWS、设备有效性及购买/恢复归属处理 |
| J6 | 商品映射 | 管理后台将真正的普贤菩萨 wallpaperId 绑定 `com.qingjing.bizhi.wallpaper.puxian`；确认已发布、支持 iOS、首免资格及销售开关；存在验证交易后不可换绑 |
| J7 | Notifications V2、退款及对账 | 验证 signedPayload / 可用的内层交易；合法 TEST 通知无交易时也可接收；通知去重持久化、异步重试、乱序处理；退款撤销全部对应安装的购买来源，保留其他有效免费/兑换来源 |
| J8 | 契约、迁移、测试与部署交付 | 核验 `2.14.0` 真实请求、Flyway V15 实际执行及存量兼容；交付代码版本和测试报告；部署另走现行运维流程 |

当前代码只有 `UnavailableIosAppleGateway` 实现，它的各方法都会返回 `IOS_ACQUISITION_UNAVAILABLE`。`hasAppleCredentials()` 或 Secret 配齐不会自动变成真实验签器。完成 J1 后，再逐项核验现有业务服务，不能把已提交的 Controller 等同于上述安全和故障场景已完成。

### 12.5 购买证明和 App 需要补齐的内容

以主 OpenAPI `2.14.0` 为准，购买/恢复 body 必须同时包含：

```json
{
  "challengeId": "服务端挑战 UUID",
  "nonce": "服务端 nonce",
  "signedTransaction": "StoreKit verified Transaction 的 JWS",
  "signedAppTransaction": "StoreKit verified AppTransaction 的 JWS",
  "deviceVerificationId": "Apple AppStore.deviceVerificationID 的 UUID 字符串"
}
```

`accountToken` 是服务器签发给 StoreKit 的 `appAccountToken`；`deviceVerificationId` 则由 Apple 提供。两者用途不同，不能互换。前端先取得 StoreKit `.verified` 结果并核验当前设备，再将本次原始 JSON body 交给 App Attest 生成 assertion；Java 对两份 JWS 完整验签、核对环境/App/交易/商品并验证设备摘要。按 Apple 规范分别用各 JWS 的 nonce 校验：

```text
SHA384(lowercase(deviceVerificationNonce UUID)
       + lowercase(AppStore.deviceVerificationID UUID))
```

摘要需等于对应已验签 JWS 的 `deviceVerification`，使用 ASCII/UTF-8 UUID 字符串，无分隔符；不能自行生成一个 UUID 后把它称为设备验证 ID。服务器收到的 ID 本身是客户端提交值，不能单独当永久设备 ID 或归属凭证。[Apple 设备验证 ID](https://developer.apple.com/documentation/storekit/appstore/deviceverificationid)、[Apple Transaction 设备验证](https://developer.apple.com/documentation/storekit/transaction/deviceverification)

双 JWS 验证不应被描述为绝对防复制。Java 与 iOS 共同确定并测试当前设备关联和受支持系统范围；可用时使用已验签的 AppTransaction/appTransactionId 增强关联。新版字段或策略如需改变请求，先同步 OpenAPI 和两端实现；不能因为恢复购买跨安装就取消全部归属校验，也不能因旧 `appAccountToken` 与新安装不同拒绝合法恢复。AppTransaction API 的系统版本可用性需处理，低版本或证明不可用时安全提示，不能跳过证明发权益。[Apple AppTransaction JWS](https://developer.apple.com/documentation/storekit/verificationresult/jwsrepresentation-6ma59)

推荐 Java 使用 [Apple App Store Server Library for Java](https://github.com/apple/app-store-server-library-java)，分别验证 Transaction、AppTransaction、Notification；通过 [SignedDataVerifier](https://github.com/apple/app-store-server-library-java/blob/main/src/main/java/com/apple/itunes/storekit/verification/SignedDataVerifier.java) 配置预期 Bundle ID、App Apple ID、环境和可信根，并按库规范设置在线证书检查。服务器调用 Apple API 的私钥与验证 Apple JWS 的信任根是两类材料，不能混用。

前端任务归 iOS 实现，不要求 Java 替前端补字段：

| 任务 | 当前差异 / 要求 |
| --- | --- |
| 解析状态 | Java `products` 返回数组 `[{wallpaperId, productId}]`，Flutter 当前按 Map 解析，需改为契约数组；支持 `PENDING_RESET`，保存 installationId、freeGeneration、pendingFreeReset |
| 购买证明 | 当前原生只返回 Transaction JWS，Flutter 请求缺 signedAppTransaction、deviceVerificationId；补齐后再联调，Java 不可为兼容旧 App 将必填校验放宽 |
| App Attest | 核验最终签名 entitlement、环境、注册生命周期和断言请求排队；在后端确认保存 key 后才记 registered |
| 测试机重置 | 实现 FREE_RESET challenge、complete、代次缓存失效和待处理重试；不能只删除客户端记录 |
| 购买与恢复 | 服务端确认权益后 finish；unfinished / updates 补偿；恢复入口主动调用 sync，普通详情页使用缓存、不反复恢复 |
| 页面和下载 | 首免状态未知时不默认可领；未配商品不弹虚假价格；Apple 实际 displayPrice 展示价格；取得权益后走受保护的原始 MP4 下载及本地高质量导出 |
| 功能开启 | API 验证和上述前端差异完成后，先用隔离测试 API 构建 `IOS_ACQUISITION_ENABLED=true` 的 Release 验证包；当前已安装包仍为关闭状态 |

现有 `Runner-StoreKit` / `.storekit` 演示商品仅用于本地交互，不要把 `com.qingjing.bizhi.test.wallpaper.1/2` 配进正式商品映射或用于正式授权。

### 12.6 商品映射及 Apple 通知配置

普贤菩萨的 **真实业务 wallpaperId 尚未在本次交接确认**。通过管理后台定位该壁纸，再调用现有管理配置接口；不要把本文示例 `123`、App Apple ID 或 IAP 数字 ID 当业务壁纸 ID。

```http
PUT /api/v1/admin/wallpapers/<真实wallpaperId>/ios-acquisition
```

```json
{
  "productId": "com.qingjing.bizhi.wallpaper.puxian",
  "firstFreeEligible": true,
  "enabled": true
}
```

以上是隔离测试配置示例，需确认目标素材及活动规则。商品价格从 Apple 读取，后台不添加 `0.1` 或 `1` 的支付金额字段。原本 FREE 壁纸继续免费，不消耗设备首免机会；Android/HarmonyOS 兑换规则保持现有流程。

Apple 通知的既有路由为：

```text
POST /api/v1/integrations/apple/app-store-notifications
```

基于当前正式 API 域名，生产候选 URL 为：

```text
https://wallpaper.biguo66.top/api/v1/integrations/apple/app-store-notifications
```

**候选 URL 未在本次操作配置、测试或验证部署。** Java 先完成路由、Apple 验签、TLS 和独立账本，再提供可用 Production / Sandbox 完整 URL。测试 API 的公网域名尚未确认，不编造地址，也不把 Sandbox 临时写入正式库。

账号侧设置入口：[App Store Connect](https://appstoreconnect.apple.com/) → Apps → 倾境动态壁纸 → General / App Information → App Store Server Notifications → 分别填写 Production 和 Sandbox URL，选择 **Version 2**。路由只豁免设备登录，依然严格校验 Apple 签名；按签名核验后的环境分流。调用 Apple Request a Test Notification，并用返回 token 查询实际投递结果，不能以在网页保存 URL 视为验收。[Apple URL 设置步骤](https://developer.apple.com/help/app-store-connect/configure-in-app-purchase-settings/enter-server-urls-for-app-store-notifications/)、[Apple 测试通知](https://developer.apple.com/documentation/appstoreserverapi/request-a-test-notification)

### 12.7 联调顺序与验收表

1. Java 完成真实 gateway、配置、V15 迁移验证、商品映射，提供隔离测试 API base 和提交号。
2. iOS 完成第 12.5 节差异，用匹配证明环境的 Release 包连接测试 API，先验证 ENROLL → STATUS。
3. 在真机登录 Sandbox 测试账号，验证首免、购买、恢复和资源保存。登录位置在设备“设置 → 开发者 → Sandbox Apple 账户”，部分设备需先触发一次开发签名 App 的购买才出现；无需把测试账号当设备主 Apple 账号登录。[Apple 真机 Sandbox](https://developer.apple.com/documentation/storekit/testing-in-app-purchases-with-sandbox)
4. Java/管理端完成重置、并发、外部调用/数据库故障及通知验证；再进行 TestFlight 同样流程，记录 App Attest 生产证明 + Sandbox 购买组合。
5. 验收通过后再安排正式服务配置、正式价格、分发证书/商店构建及送审；本次交接本身不授权生产发布或生产数据重置。

| 必测场景 | 通过标准 | 当前结果 |
| --- | --- | --- |
| 新设备首次免费 | 有效证明后仅一张获权；下载/保存失败可重试同一张 | 待测 |
| 同设备卸载重装 | DeviceCheck 保留已使用状态，不能重新领取；旧免费列表无需恢复 | 待测 |
| 并发与超时重试 | 一个安装不发多张；未知 Apple 写入结果有补偿，不重复扣或错误发放 | 待测 |
| 修改 ID / 证明 / body | 错误 App、环境、签名、nonce、计数器、跨安装或重放被拒绝 | 待测 |
| Apple ¥1 Sandbox 购买 | 系统支付、双 JWS 验证、持久化权益、finish、MP4 下载完整闭环 | 待测 |
| 取消 / pending / 支付后断网 | 不误发；未完成交易可在重启后继续同步并下载 | 待测 |
| 已购再次下载 | 未退款的有效权益可重下，不再收费 | 待测 |
| 卸载或换设备恢复购买 | 同购买 Apple 账号能恢复；不同账号无权；不会恢复首免额度 | 待测 |
| 拷贝他人的购买材料 | 不能经普通客户端路径获得未拥有壁纸；双 JWS 和当前设备关联策略有效 | 待测 |
| 管理后台测试机重置 | 新证明、权限、幂等、代次、审计均通过；不清购买权益，不复活旧首免请求 | 待测 |
| Apple 通知与退款 | TEST 实际投递；重复/乱序可处理；退款撤销所有对应购买来源及未使用票据 | 待测 |
| TestFlight 和旧平台回归 | 证明/购买环境组合正确；Android/HarmonyOS 兑换、下载可用 | 待测 |

Java 完成后在本文件追加一段简短确认即可：提交号、OpenAPI 版本、真实 gateway 状态、Secret 是否配置（不贴值）、App Attest/DeviceCheck/StoreKit 各环境、测试与生产 API base、真实 wallpaperId→Product ID 映射、通知 URL 和 TEST 投递结果、迁移执行情况、逐项验收结果、是否部署以及剩余限制。不能写“全部完美”或把未执行项目标通过。
