# DB-001 MySQL 数据库设计

**状态：** 已确认  
**版本：** V1.0.1

**日期：** 2026-09-13
**数据库：** MySQL 8.4 LTS  
**关联领域：** [DM-001 统一领域模型](DM-001-统一领域模型.md)  
**适用阶段：** WP-P02 至 WP-P12

## 1. 设计结论

数据库保存分类、内容发布、资源元数据、匿名设备、兑换额度、设备权益和审计等业务事实。Redis 只保存会话、限流计数、短时下载票据和可丢失缓存；本地 FileStorage 保存文件字节。前端和 App 不直接访问数据库。

首版使用一个 MySQL schema：`wallpaper_app`。服务端保持模块化单体边界，不按页面或接口拆表，不建立多租户字段、角色权限表、万能配置表和通用动态字段表。

物理模型采用以下规则：

- 主键统一为正数 `BIGINT`，由数据库 `AUTO_INCREMENT` 生成；对 JavaScript 返回时转为字符串。
- 字符集为 `utf8mb4`，排序规则为 `utf8mb4_0900_ai_ci`；码摘要、哈希、slug 等字段单独使用 ASCII 排序规则。
- 时间使用 `DATETIME(6)` 并按 UTC 写入，API 转换为带时区的 ISO 8601 字符串。
- 金额当前不入库；兑换额度使用 `INT`，并通过 `CHECK` 和事务条件更新限制范围。
- 外键默认 `RESTRICT`。只允许草稿内部明细使用受控 `CASCADE`，历史事实不得级联删除。
- 可编辑聚合使用 `lock_version BIGINT NOT NULL DEFAULT 0` 做乐观锁。
- 已发布资源和事件表只追加，不允许原地覆盖。

## 2. 物理模型纠偏

管理表单仍让管理员联动选择一级和二级分类，但 `wallpaper` 表只保存 `category_id`，含义为“最深选中的分类节点”：

- 只选一级分类时，`category_id` 指向一级节点。
- 选了二级分类时，`category_id` 指向二级节点，一级分类从 `category.parent_id` 一跳得到。

这样数据库不会同时保存可能互相矛盾的 `root_category_id` 和 `child_category_id`。API DTO 可以同时返回一级、二级分类摘要，但它们是同一关系的投影，不是两份可独立修改的事实。

“推荐”由 `wallpaper.featured_rank` 形成系统视图，“静态”由已发布平台变体的 `resource_type=STATIC_IMAGE` 形成系统视图，二者都不写入 `category` 表。

## 3. ER 图

~~~mermaid
erDiagram
    admin_account ||--o{ code_batch : creates
    admin_account ||--o{ audit_event : performs
    admin_account ||--o{ asset : uploads

    asset ||--o{ category : icon_for
    category ||--o{ category : parent_of
    category ||--o{ wallpaper : classifies
    asset ||--o{ wallpaper : cover_for
    wallpaper ||--o{ wallpaper_variant : provides
    wallpaper_variant ||--o{ resource_version : versions
    resource_version ||--|{ resource_binding : contains
    asset ||--o{ resource_binding : supplies

    anonymous_device ||--o{ device_credential : authenticates_with
    code_batch ||--|{ redemption_code : generates
    anonymous_device ||--o{ device_entitlement : owns
    wallpaper ||--o{ device_entitlement : grants
    redemption_code ||--o{ device_entitlement : sourced_by

    anonymous_device ||--o{ redemption_request : submits
    redemption_request ||--o| redemption_event : resolves_to
    redemption_code ||--o{ redemption_event : consumed_by
    wallpaper ||--o{ redemption_event : targeted_by
    device_entitlement ||--o{ redemption_event : referenced_by

    device_entitlement ||--o{ download_event : authorizes
    resource_version ||--o{ download_event : delivers
~~~

## 4. 表清单与归属

| 表 | 模块 | 类型 | 删除策略 |
|---|---|---|---|
| `admin_account` | adminidentity | 主数据 | 不删除 |
| `asset` | asset | 文件元数据 | 未引用文件软删除，延迟物理清理 |
| `category` | catalog | 主数据 | 未引用节点软删除，slug 不复用 |
| `wallpaper` | catalog | 聚合根 | 草稿无引用可硬删除，其余归档 |
| `wallpaper_variant` | catalog | 聚合明细 | 随未发布作品草稿删除；发布后保留 |
| `resource_version` | asset/catalog | 不可变版本 | 退役，不删除已发布版本 |
| `resource_binding` | asset/catalog | 版本明细 | 仅随未发布版本删除 |
| `anonymous_device` | device | 安全主数据 | 不删除，可内部禁用 |
| `device_credential` | device | 安全凭据 | 撤销，不删除 |
| `code_batch` | redemption | 业务事实 | 不删除 |
| `redemption_code` | redemption | 额度事实 | 不删除 |
| `device_entitlement` | entitlement | 权益事实 | 撤销，不删除 |
| `redemption_request` | redemption | 幂等协调事实 | 按保留期归档，不在业务流程删除 |
| `redemption_event` | redemption | 不可变事件 | 不删除 |
| `download_event` | delivery | 不可变事件 | 按审计保留策略归档 |
| `audit_event` | audit | 不可变事件 | 按合规保留策略归档 |

## 5. 数据字典

下面只列出业务字段。所有可变主表均包含 `created_at`、`updated_at`；事件表包含 `created_at`。字段定义将原样转入 Flyway V1 迁移。

### 5.1 `admin_account`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK，自增 |
| `singleton_key` | `TINYINT` | 否 | 固定为 1 且唯一，从数据库层限制单管理员 |
| `username` | `VARCHAR(64)` | 否 | 唯一，保存规范化小写账号 |
| `password_hash` | `VARCHAR(255)` | 否 | Argon2id 或 Spring Security 支持的强哈希 |
| `password_changed_at` | `DATETIME(6)` | 否 | 修改密码后使旧会话失效 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

首版只允许存在一个管理员账号，`CHECK (singleton_key=1)` 与唯一约束共同强制单行。账号由本地初始化命令从环境变量创建，密码和默认密码不得写入迁移文件。

### 5.2 `asset`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `storage_key` | `VARCHAR(512)` | 否 | 唯一、随机、相对存储键；禁止绝对路径 |
| `original_filename` | `VARCHAR(255)` | 否 | 仅展示，读取时不能拼接路径 |
| `mime_type` | `VARCHAR(100)` | 否 | 服务端探测结果，不只相信上传声明 |
| `file_extension` | `VARCHAR(16)` | 否 | 规范化小写后缀 |
| `size_bytes` | `BIGINT` | 否 | `>= 0` |
| `sha256` | `CHAR(64)` | 否 | 小写十六进制摘要 |
| `width_px` / `height_px` | `INT` | 是 | 图片或视频尺寸，均须 `> 0` |
| `duration_ms` | `BIGINT` | 是 | 视频时长，须 `>= 0` |
| `validation_status` | `VARCHAR(16)` | 否 | `UPLOADING/VALIDATING/READY/REJECTED` |
| `validation_error_code` | `VARCHAR(64)` | 是 | 稳定内部错误码，不存异常堆栈 |
| `created_by_admin_id` | `BIGINT` | 是 | FK；系统生成衍生资源时为空 |
| `deleted_at` | `DATETIME(6)` | 是 | 软删除标记；被引用时不得设置 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

`storage_key` 和 `sha256` 使用 ASCII 排序规则。相同内容可以被多个资源版本复用，因此 SHA-256 建普通索引而非唯一索引。

### 5.3 `category`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `parent_id` | `BIGINT` | 是 | FK 到本表；一级为空，二级指向一级 |
| `level` | `TINYINT` | 否 | 只允许 1 或 2 |
| `name` | `VARCHAR(20)` | 否 | 展示名 |
| `slug` | `VARCHAR(32)` | 否 | 全局唯一，不随删除复用 |
| `icon_asset_id` | `BIGINT` | 是 | 一级必填，二级必须为空 |
| `sort_order` | `INT` | 否 | 0 至 999999，同父节点排序 |
| `deleted_at` | `DATETIME(6)` | 是 | 软删除 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

数据库 `CHECK` 强制一级/二级的本行形状；“二级节点的父节点必须是一级节点”需要服务在同一事务中锁定父节点并校验，因为 MySQL 的行级 `CHECK` 不能读取另一行。服务禁止形成环和第三级。

为解决 `NULL` 在唯一索引中可重复的问题，增加持久化生成列 `parent_scope_id = COALESCE(parent_id, 0)`，并建立 `(parent_scope_id, name)` 唯一索引，使同一父节点下名称唯一。

### 5.4 `wallpaper`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `title` | `VARCHAR(40)` | 否 | 作品名称 |
| `slug` | `VARCHAR(64)` | 否 | 全局唯一，不复用 |
| `kind` | `VARCHAR(20)` | 否 | `PARALLAX_4D/DYNAMIC/STATIC` |
| `category_id` | `BIGINT` | 否 | FK，最深选中的分类节点 |
| `cover_asset_id` | `BIGINT` | 否 | FK，必须是 READY 图片资源 |
| `featured_rank` | `INT` | 是 | 空表示不推荐；非空越小越靠前 |
| `sort_order` | `INT` | 否 | 普通列表排序 |
| `copyright_note` | `VARCHAR(500)` | 否 | 素材原创或授权摘要 |
| `status` | `VARCHAR(16)` | 否 | `DRAFT/PUBLISHED/OFFLINE/ARCHIVED` |
| `published_at` | `DATETIME(6)` | 是 | 首次或最近一次发布时刻 |
| `archived_at` | `DATETIME(6)` | 是 | 归档时间 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

发布前服务必须确认分类未软删除、封面 READY、至少一个平台变体存在 PUBLISHED 资源版本。归档壁纸从公开查询移除，但仍可被历史权益和事件引用。

搜索首版对 `title` 和受控关键词使用 `LIKE`，只扫描 `PUBLISHED` 小数据集并限制 `pageSize`。达到 1 万条内容或出现慢查询后，再通过新迁移增加 ngram FULLTEXT 或独立搜索 Adapter，避免在没有查询证据时提前引入搜索基础设施。

### 5.5 `wallpaper_variant`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `wallpaper_id` | `BIGINT` | 否 | FK |
| `platform` | `VARCHAR(16)` | 否 | `ANDROID/IOS/HARMONYOS/UNIVERSAL` |
| `resource_type` | `VARCHAR(24)` | 否 | `LAYER_PARALLAX/VIDEO/LIVE_PHOTO/STATIC_IMAGE/THEME_PACKAGE` |
| `minimum_os_version` | `VARCHAR(32)` | 是 | 平台版本约束，保持原始规范形式 |
| `capability_requirements` | `JSON` | 否 | 受控能力字符串数组；服务端按 OpenAPI 枚举校验，不作为任意扩展字段 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

唯一约束为 `(wallpaper_id, platform, resource_type)`。数据库 CHECK 要求 `capability_requirements` 为 JSON 数组；服务校验数组成员和允许的组合，例如 `PARALLAX_4D + ANDROID + LAYER_PARALLAX`。业务查询不依赖 JSON 内字段，兼容性由读取变体后在应用层计算。

### 5.6 `resource_version`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `variant_id` | `BIGINT` | 否 | FK |
| `version_no` | `INT` | 否 | 同变体内单调递增 |
| `status` | `VARCHAR(16)` | 否 | `DRAFT/VALIDATING/READY/PUBLISHED/RETIRED/REJECTED` |
| `manifest_sha256` | `CHAR(64)` | 是 | 规范化 manifest 摘要 |
| `published_at` / `retired_at` | `DATETIME(6)` | 是 | 生命周期时间 |
| `created_by_admin_id` | `BIGINT` | 是 | FK |
| `lock_version` | `BIGINT` | 否 | 发布前乐观锁；发布后不可编辑 |

唯一约束 `(variant_id, version_no)`。增加生成列 `published_slot = CASE WHEN status='PUBLISHED' THEN 1 ELSE NULL END` 和唯一索引 `(variant_id, published_slot)`，保证同一平台变体最多一个已发布资源版本。

### 5.7 `resource_binding`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `resource_version_id` | `BIGINT` | 否 | FK |
| `asset_id` | `BIGINT` | 否 | FK |
| `role` | `VARCHAR(32)` | 否 | DM-001 定义的 AssetRole |
| `ordinal` | `SMALLINT` | 否 | 同角色多个文件时的稳定顺序，默认 0 |

唯一约束 `(resource_version_id, role, ordinal)`。发布服务按资源类型校验角色集合，数据库保证绑定不重复和文件引用完整。

### 5.8 `anonymous_device`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK，内部关联使用 |
| `public_id` | `CHAR(36)` | 否 | 唯一、不透明 UUID，不在“我的”页面展示 |
| `platform` | `VARCHAR(16)` | 否 | `ANDROID/IOS/HARMONYOS/H5_TEST` |
| `app_install_scope` | `VARCHAR(64)` | 否 | 区分签名、渠道或测试 Provider 范围 |
| `evidence_hash` | `CHAR(64)` | 否 | 设备证据 keyed hash，不保存原值 |
| `status` | `VARCHAR(16)` | 否 | 内部安全状态 `ACTIVE/REVIEW/DISABLED` |
| `last_seen_at` | `DATETIME(6)` | 否 | 最近成功会话时间 |
| `lock_version` | `BIGINT` | 否 | 凭据轮换和内部状态变更 |

唯一约束 `(platform, app_install_scope, evidence_hash)` 用于同设备自动关联。H5 测试证据和正式 App 证据使用不同 `app_install_scope`，禁止把测试设备升级为生产设备。

### 5.9 `device_credential`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `device_id` | `BIGINT` | 否 | FK |
| `credential_key_id` | `CHAR(36)` | 否 | 唯一，客户端选择凭据时使用 |
| `credential_type` | `VARCHAR(24)` | 否 | `PLATFORM_PUBLIC_KEY/H5_TEST_SECRET` |
| `public_key_pem` | `TEXT` | 是 | 正式 App 安装公钥 |
| `secret_hash` | `CHAR(64)` | 是 | H5 测试凭据摘要，不保存明文 |
| `status` | `VARCHAR(16)` | 否 | `ACTIVE/REVOKED` |
| `last_used_at` / `revoked_at` | `DATETIME(6)` | 是 | 使用与撤销时间 |

`CHECK` 要求两种凭据只填写对应字段。增加生成列 `active_slot` 和唯一索引 `(device_id, credential_type, active_slot)`，确保每种凭据类型至多一个 ACTIVE 凭据。

### 5.10 `code_batch`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `batch_no` | `CHAR(26)` | 否 | 唯一 ULID，便于客服沟通和时间排序 |
| `name` | `VARCHAR(50)` | 否 | 批次名 |
| `generated_count` | `INT` | 否 | 1 至 10000 |
| `quota_per_code_snapshot` | `INT` | 否 | 1 至 100，生成后不改 |
| `created_by_admin_id` | `BIGINT` | 否 | FK |
| `delivery_confirmed_at` | `DATETIME(6)` | 是 | 只记录一次性交付确认，不影响码有效性 |

批次没有启用、停用和有效期字段。汇总已用额度从 `redemption_code` 计算，避免额外维护一份可能漂移的总数。

### 5.11 `redemption_code`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `batch_id` | `BIGINT` | 否 | FK |
| `code_hash` | `CHAR(64)` | 否 | 唯一，HMAC-SHA-256 十六进制摘要 |
| `code_key_version` | `SMALLINT` | 否 | HMAC 密钥版本，支持轮换 |
| `code_suffix` | `VARCHAR(8)` | 否 | 管理查询用尾号，不能用于认证 |
| `total_quota` | `INT` | 否 | 从批次快照复制，1 至 100 |
| `used_quota` | `INT` | 否 | 0 至 totalQuota |
| `lock_version` | `BIGINT` | 否 | 并发更新版本 |

完整码绝不入库。数据库 `CHECK (used_quota BETWEEN 0 AND total_quota)`；核销使用行锁或 `UPDATE ... WHERE used_quota < total_quota`。页面展示的 AVAILABLE/EXHAUSTED 由额度计算，不保存状态列。

### 5.12 `device_entitlement`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `device_id` | `BIGINT` | 否 | FK |
| `wallpaper_id` | `BIGINT` | 否 | FK |
| `source_code_id` | `BIGINT` | 否 | 首次获得时使用的码，FK |
| `status` | `VARCHAR(16)` | 否 | 内部状态 `ACTIVE/REVOKED` |
| `granted_at` | `DATETIME(6)` | 否 | 首次获得时间 |
| `revoked_at` / `revoke_reason` | 时间/文本 | 是 | 仅内部安全处置，MVP 无管理入口 |
| `lock_version` | `BIGINT` | 否 | 乐观锁 |

唯一约束 `(device_id, wallpaper_id)` 保证重复兑换不会产生第二份权益。恢复被撤销权益时更新原行，不能插入同义事实。

### 5.13 `redemption_request`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `device_id` | `BIGINT` | 否 | FK，来自设备会话 |
| `idempotency_key` | `CHAR(36)` | 否 | 客户端 UUID |
| `request_hash` | `CHAR(64)` | 否 | 规范化请求摘要，防止同键异参 |
| `status` | `VARCHAR(16)` | 否 | `PROCESSING/SUCCEEDED/REJECTED` |
| `completed_at` | `DATETIME(6)` | 是 | 最终完成时间 |

唯一约束 `(device_id, idempotency_key)`。同键请求摘要不一致返回冲突；相同摘要通过 `redemption_event.request_id` 返回已存结果。PROCESSING 超时由恢复任务检查，不直接重复扣减。

### 5.14 `redemption_event`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `request_id` | `BIGINT` | 否 | 唯一 FK 到 redemption_request |
| `device_id` | `BIGINT` | 否 | FK |
| `wallpaper_id` | `BIGINT` | 否 | FK |
| `code_id` | `BIGINT` | 是 | 无效码时为空 |
| `code_suffix` | `VARCHAR(8)` | 是 | 只保存规范化尾号 |
| `entitlement_id` | `BIGINT` | 是 | 成功或已拥有时的权益 FK |
| `result` | `VARCHAR(32)` | 否 | DM-001 定义的最终结果 |
| `quota_delta` | `TINYINT` | 否 | 只允许 0 或 1 |
| `error_code` | `VARCHAR(64)` | 是 | 失败时的稳定错误码 |
| `created_at` | `DATETIME(6)` | 否 | UTC，不可修改 |

事件只保存最终事实。`GRANTED` 必须 `quota_delta=1` 且引用 code 和 entitlement；其他结果必须为 0。跨列语义由服务事务校验，关键取值由数据库 CHECK 约束。

### 5.15 `download_event`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `entitlement_id` | `BIGINT` | 否 | FK |
| `resource_version_id` | `BIGINT` | 否 | FK |
| `ticket_jti_hash` | `CHAR(64)` | 否 | 唯一；原票据 ID 不落库 |
| `result` | `VARCHAR(24)` | 否 | `ISSUED/STARTED/COMPLETED/REJECTED/EXPIRED` |
| `request_id` | `CHAR(36)` | 否 | 链路请求 ID |
| `created_at` | `DATETIME(6)` | 否 | UTC |

短时票据正文只在 Redis，Redis 丢失最多导致重新签发，不影响设备权益。

### 5.16 `audit_event`

| 字段 | 类型 | 空值 | 约束/说明 |
|---|---|---|---|
| `id` | `BIGINT` | 否 | PK |
| `actor_admin_id` | `BIGINT` | 是 | 系统动作可为空 |
| `request_id` | `CHAR(36)` | 否 | 请求链路 ID |
| `action` | `VARCHAR(64)` | 否 | 稳定动作名 |
| `aggregate_type` | `VARCHAR(32)` | 否 | 受控聚合枚举 |
| `aggregate_id` | `VARCHAR(64)` | 否 | 字符串保存，适配 Long/ULID |
| `result` | `VARCHAR(16)` | 否 | `SUCCEEDED/FAILED` |
| `change_summary` | `JSON` | 是 | 白名单字段摘要，不保存秘密和文件路径 |
| `created_at` | `DATETIME(6)` | 否 | UTC，不可修改 |

审计表是明确的跨模块附加事实，允许受控 JSON 摘要；它不是业务数据扩展表，业务查询不能依赖 JSON 内字段。

## 6. 约束矩阵

| 不变量 | 数据库保障 | API 事务保障 |
|---|---|---|
| 分类最多两级 | level 和本行形状 CHECK、parent FK | 锁定父节点，要求父 level=1，拒绝环 |
| 一级有图标、二级无图标 | 条件 CHECK | 校验 Asset 为 READY 图片 |
| 壁纸分类一致 | 只存一个 category_id | 请求中的一二级联动转换为最深节点 |
| 壁纸 slug 不重复 | UNIQUE | 规范化、友好冲突提示 |
| 已发布内容资源完整 | FK、单变体唯一发布版本 | 发布前检查封面、变体和角色集合 |
| 已发布版本不可覆盖 | 状态和绑定引用 | 发布后禁止 UPDATE/DELETE，创建新版本 |
| 同设备同壁纸一份权益 | UNIQUE(device_id, wallpaper_id) | 已有时返回 ALREADY_OWNED |
| 额度不超发 | CHECK + 行锁/条件 UPDATE | 权益、额度和事件同事务 |
| 同一意图只执行一次 | UNIQUE(device_id, idempotency_key) | 同键异参冲突，同键同参复用结果 |
| 码无明文 | 只有 code_hash 和 suffix 字段 | 日志脱敏、一次性交付文件短时保存 |
| 平台变体不重复 | UNIQUE(wallpaper_id, platform, resource_type) | 校验 kind 与组合规则 |
| 单变体最多一个发布版本 | 生成列唯一索引 | 退役旧版并发布新版的单事务 |
| 资源路径不可穿越 | storage_key 唯一、数据库不存绝对路径 | FileStorage 规范化、根目录边界检查 |

## 7. 索引设计

除主键和唯一键外，首版建立以下索引。每个索引对应已确认查询，不为假设场景建索引。

| 索引 | 支持的查询 |
|---|---|
| `category(parent_id, deleted_at, sort_order, id)` | 一级/二级分类按顺序展示 |
| `wallpaper(status, featured_rank, sort_order, id)` | 首页推荐和默认列表 |
| `wallpaper(category_id, status, sort_order, id)` | 二级分类或指定节点列表 |
| `wallpaper(status, kind, updated_at, id)` | 管理端按类型和状态筛选 |
| `wallpaper(updated_at, id)` | 管理端默认倒序分页 |
| `asset(sha256)` | 上传重复检测和排障 |
| `wallpaper_variant(wallpaper_id, platform)` | 按设备平台选择变体 |
| `resource_version(variant_id, status, version_no)` | 当前发布版本和版本历史 |
| `device_entitlement(device_id, status, granted_at, id)` | “我的壁纸”游标分页 |
| `redemption_code(batch_id, id)` | 批次明细与汇总 |
| `redemption_code(code_suffix, id)` | 管理端按尾号排查 |
| `redemption_event(device_id, created_at, id)` | 设备兑换历史 |
| `redemption_event(wallpaper_id, created_at, id)` | 壁纸兑换历史 |
| `redemption_event(code_id, created_at, id)` | 单码使用明细 |
| `redemption_event(created_at, id)` | 管理端时间倒序列表 |
| `download_event(entitlement_id, created_at, id)` | 权益下载审计 |
| `audit_event(actor_admin_id, created_at, id)` | 管理员操作审计 |
| `audit_event(aggregate_type, aggregate_id, created_at, id)` | 对象变更历史 |

公开列表采用稳定的 `(sort_order, id)` 或 `(featured_rank, sort_order, id)` 游标；管理端低频列表可使用有最大页数保护的 offset 分页。查询计划和慢日志在真实数据出现后复核。

## 8. 删除、归档与保留

### 8.1 软删除对象

- Category：删除写 `deleted_at`。有未归档壁纸或未删除子分类时拒绝操作。slug 永不复用，避免旧链接指向新内容。
- Asset：只有没有任何封面、图标和资源绑定引用时才能写 `deleted_at`；后台任务经过宽限期后删除文件字节。

所有查询必须显式区分用户查询和审计查询。公开/管理常规查询默认加 `deleted_at IS NULL`；审计和恢复工具可显式读取软删除记录。

### 8.2 生命周期替代软删除

- Wallpaper 使用 `ARCHIVED` 表达长期停止运营，保留历史权益引用。未发布、无版本、无事件、无权益引用的 DRAFT 才允许硬删除。
- ResourceVersion 使用 `RETIRED`；任何 PUBLISHED 或 RETIRED 版本不可删除。
- DeviceCredential 和 DeviceEntitlement 使用 REVOKED；不删除安全和权益历史。

### 8.3 永不由业务接口删除

CodeBatch、RedemptionCode、RedemptionEvent 和 AuditEvent 是额度或审计事实。业务接口不提供删除。达到保留期后的离线归档必须保留可核对摘要，并通过独立运维流程执行。

## 9. 并发与事务

### 9.1 兑换事务

隔离级别沿用 MySQL 默认 `REPEATABLE READ`，关键行显式加锁：

1. 根据设备会话取得 device_id，先锁定 anonymous_device 行，再非锁定读取/插入 `redemption_request`，同设备写入由设备行串行化，不锁定不存在幂等行的索引范围。
2. 同幂等键异参立即返回冲突；已完成请求直接返回原结果。
3. 查询 `(device_id, wallpaper_id)` 权益。已存在时写 ALREADY_OWNED，额度不变；不存在的作品返回 404 并回滚请求，已下线作品保存最终拒绝事件。
4. 计算兑换码 HMAC，`SELECT ... FOR UPDATE` 锁定单码行。
5. 校验 `used_quota < total_quota`，插入权益。
6. 以条件 UPDATE 增加 `used_quota`，写 GRANTED 事件和最终请求结果。
7. 任何 SQL 失败全部回滚。唯一键冲突重新读取权益或幂等结果，不盲目重放。

数据库锁内不执行文件 IO、网络调用、密码哈希或媒体探测。

批次并发生成单独采用 READ COMMITTED 和批次号唯一约束收敛。WP-P12 的 V1.0.1 文档修订仅对齐现有实现与验收结果，Flyway V1 SQL 保持原样；当前没有 V2 结构变更，后续升级场景待实际迁移创建后验证。

### 9.2 内容编辑与发布

- Category、Wallpaper、Variant 和发布前 ResourceVersion 更新时带 `lock_version`；受影响行数为 0 返回 VERSION_CONFLICT。
- 发布事务锁定 Wallpaper、目标 Variant 和当前发布版本，检查完整性，先将旧版置 RETIRED，再将新版置 PUBLISHED，最后更新作品状态。
- 管理端上传流程先在事务外完成文件写入和校验，再用短事务登记元数据；失败文件进入补偿清理清单。

### 9.3 统计一致性

批次使用进度、分类壁纸数和平台集合首版按事实表查询，不维护可写计数列。出现明确性能瓶颈后再增加可重建读模型；读模型不得成为额度和权益判定依据。

## 10. Redis 与 MySQL 边界

| 数据 | 位置 | Redis 丢失后的行为 |
|---|---|---|
| 管理员会话 | Redis | 重新登录 |
| 设备会话/挑战随机数 | Redis | 重新认证设备 |
| 登录、兑换、下载限流 | Redis | 重建计数并记录监控事件 |
| 下载票据 | Redis | 重新申请票据 |
| 公开目录缓存 | Redis | 从 MySQL 回源 |
| 兑换幂等最终结果 | MySQL | 不丢失，可重查 |
| 权益、额度、发布状态 | MySQL | Redis 不得覆盖或独立修改 |

生产限流需要在 Redis 不可用时采用保守降级；本地开发可明确记录后允许受限直连，以免把缓存可用性误当成业务数据可用性。

## 11. 初始化与迁移顺序

Flyway 迁移在 WP-P04 创建，空库按照以下依赖顺序建表：

1. `admin_account`
2. `asset`
3. `category`
4. `wallpaper`
5. `wallpaper_variant`
6. `resource_version`
7. `resource_binding`
8. `anonymous_device`
9. `device_credential`
10. `code_batch`
11. `redemption_code`
12. `device_entitlement`
13. `redemption_request`
14. `redemption_event`
15. `download_event`
16. `audit_event`

迁移只建结构，不写默认密码、兑换码和业务演示数据。开发数据由仅在 local profile 可运行的幂等 seed 命令创建；测试环境使用独立 fixture；生产不启用 seed。

已执行迁移不得修改。需要增加列、索引或约束时创建后续版本，并采用“先新增、再切换、最后清理”的兼容方式。

## 12. 关键查询验收

WP-P04 将使用空库迁移和集成测试验证以下查询：

1. 按父节点返回未删除分类，排序稳定。
2. 按推荐、一级分类、二级分类、类型和关键词分页查询已发布壁纸。
3. 为指定平台选择唯一已发布资源版本及完整绑定。
4. 按匿名设备分页查询有效权益，并关联仍可展示的作品摘要。
5. 根据码 HMAC 唯一定位额度行，不通过尾号核销。
6. 在 20 个并发请求争抢最后额度时，最终 `used_quota` 不超过 `total_quota`。
7. 相同设备和幂等键重复请求只产生一个最终事件和最多一份权益。
8. 按批次、尾号、设备、壁纸和时间查询兑换记录。
9. 重启 Redis 后仍能从 MySQL 查询内容、额度、权益和幂等最终结果。
10. 软删除分类和 Asset 后，常规查询不可见，历史审计仍能关联。

## 13. 明确不建的表

- 用户、手机号、支付订单和购物车：不在 MVP 范围。
- 角色、权限、菜单和管理员关联表：单管理员无需 RBAC。
- 租户和租户资源关联表：本项目不是多租户系统。
- 客户端下载、安装、设置和两分钟试用状态表：这些是设备本地交互状态。
- 兑换码启停、有效期和状态历史表：产品明确没有该能力。
- 独立推荐分类和静态分类：它们是查询视图。
- 永久下载链接表：只允许短时票据。
- 万能配置、键值业务表：稳定对象使用明确字段和约束。

## 14. Flyway 落地检查清单

- 所有表、索引、外键和 CHECK 名称显式且稳定。
- 使用 `ENGINE=InnoDB`、`utf8mb4` 和统一排序规则。
- 每个枚举同时有数据库 CHECK 与 Java 枚举校验。
- 外键动作显式声明，不依赖 MySQL 默认值。
- 生成列唯一索引在 MySQL 8.4 容器中实际验证。
- 空库迁移、重复启动、从 V1 升级到后续版本均通过。
- Testcontainers 集成测试覆盖分类层级、单发布版本、权益唯一、兑换幂等和并发额度。
- 生产迁移与应用启动解耦；迁移失败时不切换 API 流量。
