# iOS 下载积分直接购买实施清单

本次将 iOS 新购买改为共用下载积分商品。用户选择壁纸后直接调用 Apple 内购，付款验证完成后自动兑换当前壁纸并下载。无充值页、积分商城或钱包。Android、HarmonyOS 保持原兑换码流程。服务器部署由用户执行。

## 已确认的产品规则

- 按钮：`X个积分兑换壁纸`；附近固定说明：`1积分＝1元`。
- 点击直接打开 Apple 系统付款窗口，不增加 App 自有确认弹窗。
- 仅支持中国大陆商店。本次内购商品可售地区限定中国大陆；App 分发地区也需限定中国大陆。
- 共用三个消耗型商品，分别提供 1、2、3 下载积分，中国区价格分别为 ¥1、¥2、¥3。
- 后台填写 iOS 整数售价，自动转换为同数量积分。选取能精确支付、购买数量不超过 10 的单个积分包。不能拼接不同商品成一次付款，不向上取整。
- 支持售价：1～10、12、14、15、16、18、20、21、24、27、30 元。其他金额保存时拒绝并提示可选价格。
- 首次免费仍由 App Attest、DeviceCheck 和服务端共同验证；不发放可积累的赠送积分。
- 已购买壁纸可再次下载；通过 Apple 签名的 AppTransaction 身份恢复服务端权益，不增加 App 登录页面。
- 付款、积分获取、消费、壁纸权益分别留存。下载失败不重新收费。未确认的 Apple 交易不 finish，留待重试。
- 旧非消耗型商品及已购买权益保留恢复能力，不修改旧商品类型、不删除历史交易。

## Apple 商品配置

| 积分包 | Product ID | 类型 | 中国区价格 | 状态 |
| --- | --- | --- | --- | --- |
| 1下载积分 | `com.qingjing.bizhi.credits.1` | CONSUMABLE | ¥1 | 已创建：6818689321；价格、CHN、中文说明及审核备注已核验 |
| 2下载积分 | `com.qingjing.bizhi.credits.2` | CONSUMABLE | ¥2 | 已创建：6818694586；价格、CHN、中文说明及审核备注已核验 |
| 3下载积分 | `com.qingjing.bizhi.credits.3` | CONSUMABLE | ¥3 | 已创建：6818694921；价格、CHN、中文说明及审核备注已核验 |

Bundle ID：`com.qingjing.bizhi`；Team ID：`NFXT4L28FU`；App Apple ID：`6818362193`。密钥继续使用现有安全配置，不将私钥写入文档或 Git。

App 已设置仅中国大陆供应；App 本身免费，付费内容通过这三个积分商品获取。三个商品当前为草稿（Apple API：MISSING_METADATA，页面：准备提交），尚未上传新的积分 UI 审核截图、尚未提交审核。三个商品首次审核需随 App 版本提交；后台创建和 Sandbox 可测试不等于审核通过。新增壁纸复用这些商品，无需为每张壁纸再创建 Product ID。

## 工程实施清单

- [x] Flyway 新增积分包、壁纸积分价、Apple 匿名账户、订单、积分流水、恢复关联。
- [x] Apple 价格同步支持消耗型商品，并核验积分包金额严格等于积分数。
- [x] 管理后台允许设置整数价格，自动显示积分包和数量；无须每张壁纸填写 Product ID。
- [x] API 创建订单：服务端确定壁纸、积分、商品、数量及订单专属 appAccountToken。
- [x] API 购买同步：App Attest + 双 JWS + 当前 Apple 交易核验；数量、账户、订单匹配；幂等兑换。
- [x] API 恢复权益：验证 AppTransaction 和设备绑定；退款通知撤销该交易对应的权益。
- [x] iOS StoreKit 支持消耗型数量购买、订单绑定、未完成交易补偿、服务端权益恢复。
- [x] Flutter 显示积分按钮及固定说明；付款成功自动下载，失败或取消不发放权益。
- [x] 更新 OpenAPI 并完成直接相关测试、构建及密钥检查。
- [x] Apple 三款商品创建、价格、地区、中文说明和审核备注核验。
- [ ] 新积分 UI 审核截图上传和首次商品审核提交（部署联调通过后执行）。
- [x] Release 签名检查、安装至当前 iPhone、启动检查。
- [x] 提交并推送工程变更；附用户部署步骤和实际验收状态。

## 后端必须保持的安全约束

1. 订单金额和商品来自 MySQL 配置，客户端不能指定价格、积分包或发放数量。
2. Apple transactionId 全局按环境和 Bundle ID 去重；一笔交易只兑换订单固定的壁纸。
3. 消耗型购买必须核对 Apple 签名中的类型、数量、币种、金额、appAccountToken 和 AppTransaction 身份，不能以客户端 PURCHASED 状态授权。
4. Apple 签名 `price` 为含购买数量的交易总额（毫元），必须等于订单积分总数 × 1000；不能误当作单价。订单价格为创建时快照。付款期间管理员改价不改变已有订单，也不允许将历史购买重新兑换为另一张壁纸。
5. 买积分和消费积分在数据库事务内记账；发放失败保留可重试的付款，成功后才能 finish。
6. 恢复只重建壁纸权益，不再次发放或消费历史积分。退款与重放不得让已撤销权益重新生效。

## 部署与验收

### 已完成的工程验证（2026-10-03）

- OpenAPI `2.17.0`；新增 Flyway `V19__ios_direct_credit_purchases.sql`。
- 后端：103 项单元测试（2 项依赖媒体工具的测试跳过），本次 4 项 MySQL / Redis 集成测试通过。覆盖精确金额、数量、币种、重复交付、跨安装恢复、退款、后台改价、取消后重新下单、订单重复请求禁止再次拉起付款。
- Flutter：41 项首免、内购和 UI 测试通过；新增代码静态检查通过。
- 管理后台：26 项测试与生产构建通过；OpenAPI 通用及 iOS 契约检查通过。
- iPhone 12 已覆盖安装 Release 构建 `10022`，签名校验通过，启动后进程存活。API 指向 `https://wallpaper.biguo66.top/api/v1`。
- 此次真机包采用 DEVELOPMENT App Attest，与当前服务端测试环境匹配。Release 编译模式和 App Attest 环境是两个不同设置；App Store / TestFlight 上架包需改用 PRODUCTION App Attest 并配置匹配的服务端环境。
- 尚未部署服务器，尚未完成此流程的 Apple Sandbox 真机付款；代码测试通过不代表付款联调已通过。

### 接口与管理后台

所有路径以前缀 `/api/v1` 开始，沿用现有设备注册、会话、App Attest proof 和 challenge 机制。

| 操作 | 接口 | 要点 |
| --- | --- | --- |
| 商品展示 | `GET /device/ios/products` | 读取服务端积分价、共用 Product ID、积分包、购买数量、首免状态；展示不能代替授权 |
| 创建付款订单 | `POST /device/ios/acquisition/credit-orders` | challenge action=`CREDIT_ORDER`，壁纸 ID + AppTransaction JWS + deviceVerificationId；不能传价格 |
| 同步已付款交易 | `POST /device/ios/acquisition/purchases` | 沿用双 JWS 和设备验证，服务端按交易的 appAccountToken 找订单 |
| 恢复已兑换权益 | `POST /device/ios/acquisition/credit-restores` | action=`CREDIT_RESTORE`，验证同一 Apple 账户的 AppTransaction，刷新退款事实后重建权益 |
| 取消确定未付款订单 | `POST /device/ios/acquisition/credit-orders/{orderId}/cancel` | action=`CREDIT_CANCEL`，须验证订单所属 Apple 匿名账户；仅明确取消或付款前校验失败时调用 |
| 后台配置 | `GET/PUT /admin/wallpapers/{wallpaperId}/ios-acquisition` | 新购买填写 `acquisitionMode=CREDITS`、整数 `credits`、`enabled`、`firstFreeEligible`，不再逐张填 Product ID |
| 后台核验积分包 | `POST /admin/wallpapers/{wallpaperId}/ios-acquisition/price-sync` | 登录及 CSRF，核验三个积分包在 Apple 的消耗型类型和中国区金额 |

订单返回 `orderId / wallpaperId / productId / packCredits / quantity / credits / amount / accountToken / priceVersion / status / paymentAllowed`。

客户端只有 `status=OPEN` 且 `paymentAllowed=true` 时才能发起新的 Apple 付款。支付开始前持久化原订单；重复请求或换安装遇到已发起订单，服务端返回 `paymentAllowed=false`，优先补偿未完成交易或恢复权益，不能重新扣款。付款结果不明确且无法恢复的订单保留等待核查，不以超时或清除缓存判断为取消。明确取消成功后可创建新订单；迟到的有效付款仍按原价格快照交付。

### 用户部署步骤（按顺序执行）

1. 在 GitHub main 选择本次最新提交，按现行 OPS-005 执行手动部署，选择 API 和管理后台一同更新。推送分支不会自动发布服务器。先备份数据库，校验发布包和提交标识。
2. 让 Flyway 正常执行 V19。只新增积分业务表并扩展 challenge action 约束，不删旧内购映射、交易、首免和权益。确认蓝绿 API 的健康检查、契约版本 `2.17.0`、迁移版本 `19` 及发布提交一致。
3. 保留现有 DeviceCheck / StoreKit / App Attest 私钥和价格同步密钥，不需要因这次业务改造重新生成。环境保持 `QJ_IOS_ACQUISITION_ENABLED=true`、`QJ_IOS_PRICE_SYNC_ENABLED=true`；`QJ_APPLE_ACCEPTED_BUNDLE_VERSIONS` 加入 `10022`，并保留仍需联调的旧构建号。
4. 当前手机测试继续使用 DEVELOPMENT App Attest + SANDBOX StoreKit；价格同步沿用 `QJ_ASC_PRICE_ISSUER_ID / QJ_ASC_PRICE_KEY_ID / QJ_ASC_PRICE_PRIVATE_KEY_FILE`。私钥仅留在部署 Secret 或受限文件中。
5. 打开管理后台编辑 iOS 壁纸：确认已发布的 iOS 资源、积分售价、启用购买和首免选项，点击积分商品价格核验。只有 READY 才允许付款；价格缺失、同步过期、非消耗型或金额不匹配均关闭购买。
6. V19 对历史壁纸迁移时保留 enabled / firstFreeEligible，并仅将有效的既有整数中国区价格复制为积分价。原价缺失、不是允许档位时积分价为 NULL，须在管理后台明确填写，不猜测售价或批量改业务数据。
7. 使用中国大陆 Sandbox 账户验证下表。需测试三个共用 SKU 时，可分别选售价 9、14、21 元壁纸（数量分别为 9、7、7），不要只用 1～10 元价格，因为这些优先使用 1 积分包。
8. 验收通过后再准备 App Store 构建；PRODUCTION App Attest、生产 DeviceCheck 和正式/测试 StoreKit 环境须分别匹配，不能将 DEVELOPMENT 配置直接当作上架配置。

### 尚需真机验收

- [ ] 新设备首免、首免重复点击、卸载后不能重复获取首免、测试机重置。
- [ ] 三个 SKU 的系统付款窗口显示正确总额，成功自动兑换并进入下载。
- [ ] 取消后可再次购买；Ask to Buy 或结果不明时不得再次付款。
- [ ] 支付后断网、服务端暂时失败或 App 重启，补偿成功后只交付一次并 finish。
- [ ] 下载失败重试不收费；已购再次下载不收费。
- [ ] 同一 Apple 账户换安装，通过“我的→恢复购买”恢复已兑换壁纸；旧非消耗型权益仍可恢复。
- [ ] Sandbox 退款撤销权益，旧交易重放不会恢复被撤销权益。
- [ ] 后台改变壁纸积分售价，已安装 App 刷新展示；现有付款订单仍按创建时价格交付。
- [ ] 抓取新的积分购买 UI 审核截图并上传三个 SKU；随新 App 版本提交首次消耗型内购。

本次文档记录工程、Apple 配置、部署与验收的同一条流程；尚未执行的验收项不勾选。

依据：[Apple 交易 price 包含数量总额](https://developer.apple.com/documentation/AppStoreServerAPI/price)、[Apple 购买数量上限](https://developer.apple.com/documentation/storekit/product/purchaseoption/quantity%28_%3A%29)、[Apple 积分及内购规则](https://developer.apple.com/app-store/review/guidelines/#in-app-purchase)、[首次内购提交要求](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-in-app-purchase)。
