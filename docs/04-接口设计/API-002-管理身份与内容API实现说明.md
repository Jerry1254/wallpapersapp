# API-002 管理身份与内容 API 实现说明

**状态：** 已实现

**日期：** 2026-09-11

**契约：** `contracts/openapi/openapi.yaml` V1.0.0

## 1. 实现范围

WP-P06 实现冻结契约中的 25 个管理身份与内容操作：

- 管理员登录、查询当前会话、退出和首页汇总。
- 资源上传、元数据查询和管理员受控内容读取。
- 两级分类树的创建、查询、修改和软删除。
- 壁纸草稿的分页、创建、查询、修改和无历史草稿删除。
- 平台变体的创建、修改和无历史删除。
- 不可变候选资源版本的创建、查询，以及壁纸发布、重新发布、下架和归档。

兑换码批次、兑换记录和设备查询不在本文件范围内，当前实现见 [API-003](API-003-兑换设备权益API实现说明.md)。

## 2. 管理会话

管理员密码使用 BCrypt 保存，强度通过 `qingjing.admin.password-strength` 配置。系统只允许数据库中存在一行管理员账号；迁移和源码不带默认账号或密码。

登录成功后服务端生成 256 位随机会话令牌和独立 CSRF token：

- 浏览器只通过 `QJ_ADMIN_SESSION` HttpOnly、SameSite=Strict Cookie 持有会话令牌。
- Redis key 使用会话令牌的 SHA-256，不使用原令牌；会话默认 8 小时固定过期。
- 所有管理写请求还必须提交与该会话绑定的 `X-CSRF-Token`。
- 登录失败按来源地址和规范化用户名在 Redis 中进行 5 分钟窗口限流。
- 本地 profile 关闭 Cookie Secure 以支持 `http://127.0.0.1`；其他 profile 默认开启 Secure。

## 3. 内容规则

分类写入强制以下规则：

- 一级分类必须有 READY 图片图标，且不得有父节点。
- 二级分类必须引用未删除的一级分类，且不得保存独立图标。
- 删除前锁定分类并检查子节点与壁纸引用；被引用时返回 `RESOURCE_IN_USE`。
- 修改和删除使用强 ETag/`If-Match` 乐观锁。

壁纸与资源写入强制以下规则：

- 数据库只保存最深分类节点；请求中的根、子分类关系在事务内校验并加共享锁。
- 封面和所有资源绑定必须引用 READY Asset，资源角色与服务端探测 MIME 匹配。
- `PARALLAX_4D` 只允许 Android 分层资源；`STATIC` 只允许 Universal 静态图片；Live Photo 只允许 iOS。
- 分层资源必须有背景、透明格式前景和 JSON 配置；视频、Live Photo、静态图和主题包必须有各自完整角色集合。
- 候选版本创建后只有读取和发布入口，没有绑定或摘要修改入口；有任何版本历史的变体不得修改类型或删除。
- 发布事务锁定壁纸、校验 ETag 和候选版本归属，先退役旧发布版本，再发布每个选中变体的新版本。MySQL 唯一约束继续保证每个变体最多一个 PUBLISHED 版本。
- `PUBLISHED` 只能下架为 `OFFLINE`；草稿或已下架内容可归档；归档内容不可恢复或编辑。

## 4. 审计与错误

管理写请求由统一审计 Filter 记录请求 UUID、管理员、动作、聚合类型、聚合 ID、HTTP 结果和安全变更摘要。登录失败和 CSRF 失败同样产生 FAILED 审计；密码、会话令牌、CSRF token、文件内容和存储键不进入审计摘要。

统一异常映射返回 OpenAPI `ErrorEnvelope`。客户端只依赖稳定错误码，例如 `UNAUTHORIZED`、`CSRF_INVALID`、`VERSION_CONFLICT`、`RESOURCE_IN_USE`、`DOMAIN_RULE_VIOLATION` 和 `ASSET_VALIDATION_FAILED`。

## 5. 本地单管理员初始化

先启动本地栈，再执行：

~~~bash
./scripts/local-api.sh init-admin
~~~

脚本只允许在管理员表为空时初始化一次。密码通过隐藏输入或当前进程的 `QJ_ADMIN_PASSWORD` 读取，不写入 Git 或运行时配置文件。API 完成 BCrypt 持久化后，脚本立即在不带明文初始化变量的情况下重建容器，清除容器元数据中的临时凭据。
