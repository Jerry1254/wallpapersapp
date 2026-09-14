# PM-003 App 开工门禁与接入清单

> 本文保留 WP-P12 的 1.0.1 开工历史基线。WP-A03 当前 Android 安装身份兼容扩展为 1.1.0，最新机器契约与 baseline-v1.json 为准，恢复/签名边界见 SEC-002；旧快照归档为 baseline-v1.0.1.json。此更新不表示 Android 真机或安全资源交付已验收。

**状态：** 已完成开工输入审计

**版本：** V1.0.0

**日期：** 2026-09-13

**工作包：** WP-P12；前置 WP-P00 至 WP-P11 已完成，当前任务状态仍以 PM-002 为准。

## 1. 门禁结论

已具备 Flutter 共享业务层、真实目录及平台接口骨架的开工输入。MySQL/Redis/FileStorage、管理端写入、H5 目录/设备兑换/权益、并发额度和整栈重启都有验收记录。WP-P12 交付契约与接入清单，未创建 Flutter 正式功能，也不将 Android PoC 计为正式 App。

正式 Android 的设备注册、兑换、加密下载、原子安装及系统设置，需要下表的后续实现通过后才能作为 App 在线闭环验收。当前服务端只允许 local/test H5 Provider；正式平台 Provider 拒绝服务，受保护文件读取也不会返回有效资源。

| 开工范围 | 当前可用输入 | 后续退出条件 |
|---|---|---|
| Flutter 共享工程、UI 和目录 | OpenAPI 1.0.1，DM/DB 1.0.1，已生成 Dart Token，真实公开目录 | 工程工具链锁定，目录/分页/错误和组件可运行，平台占位显式返回不支持 |
| Android DeviceIdentity Provider | 公钥/证据/挑战/签名字段已定义；当前未实现原生凭据验证 | 客户端安全存储 + 服务端验证/允许值共同实现，证明重放/伪造被拒绝，同设备会话恢复测试通过 |
| Android 兑换与权益 | 共用 MySQL 事务、幂等恢复和权益接口 | 正式 Android 会话接入后真机首兑/重复/未知结果/并发一致性通过 |
| 安全资源交付 | DownloadDescriptor 的 SECURE_PACKAGE 字段已定义 | 加密包制作、manifest/哈希/签名、内容密钥包装、设备与版本限定票据、受保护读取及中断续传策略落地 |
| 原生预览/安装/设置 | 根目录 Android Java PoC 和真机记录 | 经类型化平台接口迁入插件，原子安装/失败清理/系统实际能力/重启及进程结束验收通过 |
| iOS/HarmonyOS | 独立平台枚举、变体与能力接口边界 | 首发保留明确能力占位；各自正式功能另行验证，不以 Android 能力推定 |

## 2. 冻结文件与版本

机器快照为 [baseline-v1.json](../../contracts/openapi/baseline-v1.json)，保存下列 9 个文件的 SHA-256、契约版本、47 个 operation 和 72 个 Schema。`npm run baseline:check` 校验该快照；它只证明指定文件与版本未漂移，不证明 App 或正式交付已实现。

| 基线 | 权威文件 | 冻结说明 |
|---|---|---|
| 领域语义 | DM-001 | V1.0.1；对象、状态机、两级分类和 15 项不变量 |
| 数据字典 | DB-001 | V1.0.1；16 张业务表、约束和实际锁顺序 |
| DTO 与端点 | contracts/openapi/openapi.yaml、API-001 | OpenAPI 3.0.3 / info.version 1.0.1；唯一机器契约 |
| 平台与资源保护 | ARC-001、SEC-001 | 共享业务层与平台插件分离，匿名设备及安全交付边界 |
| 已执行结构 | V1__create_core_schema.sql | Flyway V1 保持原字节；没有 V2，不能以文档修订修改已执行 SQL |
| 视觉来源 | qingjing-wallpaper.tokens.json、generated/qingjing_wallpaper_tokens.dart | JSON 为源，Dart 为生成物；Token 一致性检查已通过 |

1.0.1 修订补齐 20 个已有错误码和遗漏的状态响应；不存在的 wallpaperId 兑换改为 404 WALLPAPER_NOT_FOUND，回滚幂等行，额度不变。领域/数据库文档对齐 HMAC 字段及设备行优先的锁顺序，未新增表或领域事实。已有 1.0.0 验收记录保留历史版本，App 从此快照接入。

变更任何冻结字段时，同时更新 DM、DB、OpenAPI、Java DTO、H5/管理端映射及受影响验收；更新版本和审核后的快照，不能由检查脚本自动重写摘要来掩盖漂移。结构升级新建 V2+，在隔离库测试 V1 到新版本。4DWP v0.1 仍为草案，正式安全包格式、密钥算法和票据策略在安全交付工作包另行冻结。

## 3. Flutter 字段映射

完整字段、required、nullable 和长度以机器契约为准，下表固定核心语义，不把数据库实体搬进页面。

| DTO / 字段 | Flutter 业务含义与约束 |
|---|---|
| LongId / id、wallpaperId、assetId、resourceVersion.id | 正十进制 String；不得转为浮点数或自行编码设备归属 |
| Timestamp / createdAt、grantedAt、publishedAt、expiresAt | 带时区 ISO 8601；解析为 UTC 比较，本地化仅用于显示 |
| PageMetadata | page 从 1 开始，pageSize 默认 20、最大 100；items 与 page 分开；刷新第一页处理数据变化，翻页失败保留已有项 |
| PublicRootCategory / children、icon、wallpaperCount | 两级业务树，子节点不含图标；推荐/静态/上新是查询视图，不新增虚拟分类事实 |
| PublicWallpaperSummary / kind、rootCategory、childCategory、cover、capabilities | 作品是兑换单位；childCategory 可空，兼容性结合 capabilities 与平台探测，不能只根据 kind 判断 |
| PublicWallpaperDetail / copyrightNote、publishedAt | 真实目录与版权；封面 contentUrl 是受控公开媒体引用，不是正式资源包地址 |
| DeviceRegistrationResponse / credentialKeyId、credentialType、credentialSecret | credentialKeyId 为 UUID，不是用户设备编号；secret 只允许 H5_TEST 一次性返回，Android 不把它当正式设备凭据 |
| DeviceSessionChallenge / challengeId、nonce、algorithm、expiresAt | 一次性服务端挑战，签名算法以服务端批准结果为准，过期或失效重新取挑战 |
| DeviceSession / accessToken、tokenType、expiresAt、platform | 短期 Bearer 会话；鉴权设备范围来自服务端会话，客户端不传 deviceId 查询别人的权益 |
| EntitlementSummary / id、wallpaper、grantedAt | “已获得”的唯一客户端读取来源，服务端只返回当前设备 ACTIVE 权益；下线后依权益摘要显示，封面 404 使用占位 |
| RedemptionResult / idempotencyKey、result、quotaDelta、entitlement、errorCode | GRANTED 消耗 1，ALREADY_OWNED 消耗 0；成功时 entitlement 存在，拒绝不在本地授予权益 |
| DownloadDescriptor / deliveryMode、wallpaperId、cover | 按模式分支。H5_PLACEHOLDER 的 ticket/downloadUrl/expiresAt/resourceVersion/package 为空，不能进入正式安装或标记资源交付完成 |
| DownloadResourceVersion / id、versionNo、platform、resourceType、manifestSha256 | App 安全交付的版本身份；检查平台、类型、版本和 manifest 摘要，不覆盖已发布版本 |
| SecurePackageMetadata / sizeBytes、encryptedSha256、wrappedContentKey、keyAlgorithm | 待实现安全包元数据；包装密钥不等于明文内容密钥，下载后必须由安全交付流程验证/解包 |
| 客户端下载/安装/设置/试用状态 | 设备本地事实；既不消耗码额度，也不能写成服务端权益，只有真实安装/系统结果才能更新正式状态 |

DevicePlatform 为 ANDROID / IOS / HARMONYOS / H5_TEST，DeliveryPlatform 额外含 UNIVERSAL 且不含 H5_TEST。WallpaperKind 为 PARALLAX_4D / DYNAMIC / STATIC；ResourceType 为 LAYER_PARALLAX / VIDEO / LIVE_PHOTO / STATIC_IMAGE / THEME_PACKAGE。Dart 枚举保留未知值回退，不能复用 H5 页面的小写展示枚举作为传输值。

## 4. 设备与兑换调用顺序

1. 平台接口取得能力和获准的设备证明，生成/使用平台安全存储中的安装密钥，提交 registrations 的 platform、appInstallScope、credentialType、publicKeyPem、evidenceToken。当前 H5 实现只作为协议参考，不允许在正式环境启用 H5_TEST 来绕过 Android Provider。
2. 申请 session-challenges，按 API-001 的 UTF-8 载荷签名，用 credentialKeyId、challengeId、clientTimestamp、proof 建立 sessions。proof 是 Base64URL 无填充；时间字符串与 Java Instant 的规范表示一致，零毫秒不输出 .000Z。请求与验证测试使用同一实际字符串。
3. 读取 me/entitlements；凭据正常但会话过期可重新挑战。会话续期合并并发请求，最多自动续期一次，拒绝/时钟偏差/Provider 不可用不能无限重试。
4. 整理兑换码为 20 位大写系统字符；生成 UUID Idempotency-Key，在发出请求前保存 key、wallpaperId、body SHA-256，不保存明文码或 body。签名覆盖准确 body 字节、方法、规范化路径、时间和新 UUID nonce。
5. POST redemptions 的 200/201/422 都可能是最终 RedemptionResult；422 按业务结果显示，不能当通用网络错误。202 PROCESSING、超时、连接断开、409 处理中及 5xx 保留原意图；GET redemptions/{原 key} 确认。
6. GET 404 REDEMPTION_REQUEST_NOT_FOUND 表示暂未找到结果；可以继续确认，或用户输入原码后比对原 bodyHash、复用原 key 再 POST。每次传输生成新 nonce/签名；不能换码、换壁纸或换 key 重试同一未知结果。
7. definite 400/401/403/404/429 ErrorEnvelope 在自动续期后仍失败时结束本次提交并显示 code；其中 404 WALLPAPER_NOT_FOUND 不存在最终权益。原 key 异参冲突保留恢复入口，不自动创建新意图。
8. 成功只采纳服务端 entitlement，再刷新权益；重复兑换/下载均先核验服务端事实。下载票据请求的 platform 与会话平台一致，supportedResourceTypes 来自平台能力。正式 App 收到占位模式必须停止安装流程。

一次性码、设备证据、secret、私钥、proof、签名、会话 token、交付 ticket 和内容密钥不进入日志、分析、崩溃上报、URL、剪贴板调试或 Git。客服页面不伪造设备编号，不承诺换机迁移。安装密钥本身不证明卸载重装后仍能恢复同一设备；正式 Provider 必须单独定义并验证重新关联证据，丢失证明时失败并保留支持入口，不自动迁移权益。

## 5. 本地接入和检查

本地运行命令见 [OPS-001](../07-部署与运维/OPS-001-本地API开发环境.md)。宿主机 API 为 `127.0.0.1:8080/api/v1`，H5/管理端分别为 5175/5176。MySQL 3307、Redis 6380、资源目录属于服务端，App 不直接连接。模拟器/真机需要独立的开发网络或转发配置，不能把其 localhost 当作宿主机；不能为联调直接公开数据库、Redis 或 Actuator。

App debug 与正式构建分开配置 API 来源、证据允许值和网络策略。local/test/production 的数据库、Redis、存储、主密钥、管理员和设备证明配置完全独立；不复制环境间业务数据。正式包禁止 H5 Provider 和明文开发例外。工具链版本在 WP-W17 创建工程时核实并锁定，本工作包未安装或验证 Flutter SDK。

~~~bash
# 仓库根目录
./scripts/local-api.sh status
curl --fail http://127.0.0.1:8080/actuator/health/readiness

cd contracts/openapi
npm ci
npm test
npm run baseline:check
npm run bundle

cd ../../services/api-server
./mvnw verify

cd ../../apps/admin-web
npm run check

cd ../h5-prototype
npm run check
npm run build
~~~

需要重做实际 H5 闭环时使用 verify-local-flow.py prepare → 浏览器首兑/重复下载 → complete；会新增本地夹具并重启栈，凭据清理和失败阶段见 OPS-001。普通契约审查无需重建业务夹具。

## 6. 后续工作清单

| 顺序 | 关联工作项 | 交付与验收 |
|---|---|---|
| 1 | WP-W17 | 创建 Flutter 共享工程和四个平台接口/适配包，迁入生成 Token，真实公开目录、分页/错误、详情及权益状态组件；暂不以 Mock 或 H5 secret 宣称 Android 首兑成功 |
| 2 | WP-W18 / WP-W19 身份前置 | Android Keystore 和服务端 PLATFORM_PUBLIC_KEY 验证配套实现，安装范围允许值/挑战算法/证据恢复策略冻结，隔离环境测试伪造、重放、撤销和重装证明 |
| 3 | WP-W19 安全交付前置 | 冻结安全包格式与算法、制作/校验/加密管线、短票据受保护下载，设备和版本绑定、过期、无权益、哈希不匹配和中断恢复拒绝路径均验证 |
| 4 | WP-W18 | 将 PoC 的姿态、渲染和 WallpaperService 迁入 Android 插件，经类型化接口提供预览、原子安装及系统确认；Flutter 页面不直接调用 Android 通道 |
| 5 | WP-W19 | 正式 Android 真机在线目录→设备证明→首兑→权益→安全下载→安装→系统设置，重启/杀进程/卸载重装恢复与额度一致性验收 |
| 6 | WP-W20 | 厂商设备矩阵、功耗/内存、隐私与素材版权、签名构建、监控/回滚和上架材料；生产发布/迁移/数据操作执行前另获明确确认 |

## 7. 门禁验证记录

- WP-P11 提交 67c21f9 已同步 GitHub；真实 4D 两轮完整剧本及三个事实源一致见 [全链路验收记录](../06-测试与验收/H5全链路验收记录-2026-09-13.md)。
- WP-P12 修正后 API verify 18 个快速测试 + 6 个真实隔离集成场景通过；新增缺失作品 404、幂等行回滚和批次额度不变断言。OpenAPI lint/专项检查/bundle 通过，97 个直接 ApiException 字面量均在 ErrorCode 中。
- 冻结 SHA-256/版本/47 个操作/72 个 Schema 校验、Token 一致性通过。以新 Jar 重建本地 API 后，签名请求验证缺失作品 404、无幂等行，原壁纸 5 和批次 7 的三份权益/额度/成功事件保持不变；现有 V1 Flyway 再次校验成功。提交前文档链接、diff、暂存区密钥及运行时排除检查通过。
- 未测范围明确：正式 Android Provider/安全包、Flutter 编译、原生插件/正式 App 真机及生产流程属于后续清单；没有 V2，未声称执行不存在的升级迁移。本门禁通过仅表示开工输入齐备。
