# API 服务

Java API 正式工程目录，承载内容目录、匿名设备、兑换、权益、受保护下载、设备恢复和后台管理接口。

当前基线使用 Spring Boot 3.5、Java 17 字节码、Flyway、MySQL 8.4 和 Redis 7.4。代码按 `adminidentity`、`catalog`、`asset`、`device`、`redemption`、`entitlement`、`delivery`、`audit` 和 `shared` 包保持模块化单体边界。

## 本地启动

在仓库根目录执行：

~~~bash
./scripts/local-api.sh up
~~~

该命令会：

1. 在 `.runtime/local-api/compose.env` 生成权限为仅当前用户可读的随机 MySQL/Redis 凭据。
2. 使用仓库内 Maven Wrapper 构建 Java 17 兼容 Jar。
3. 启动 MySQL、Redis 和 API，并等待 `/actuator/health/readiness` 返回成功。
4. 将 MySQL、Redis 和本地资源目录挂载到持久化卷或被 Git 忽略的运行时目录。

默认地址：

| 服务 | 地址 |
|---|---|
| API 健康检查 | `http://127.0.0.1:8080/actuator/health` |
| MySQL | `127.0.0.1:3307` |
| Redis | `127.0.0.1:6380` |

常用命令：

~~~bash
./scripts/local-api.sh status
./scripts/local-api.sh logs api
./scripts/local-api.sh init-admin
./scripts/local-api.sh down
~~~

`down` 只停止并移除本地容器，保留 MySQL 和 Redis 命名卷。脚本不提供删除数据卷的快捷命令。

本地数据库第一次启动后，执行 `init-admin` 交互创建唯一管理员。初始化密码不会写入运行时文件；服务端保存 BCrypt 摘要后，脚本会立即清除容器中的临时初始化环境变量。管理员已经存在时命令会拒绝重置，避免意外改密。

## 管理身份与内容 API

管理接口位于 `/api/v1/admin`。登录响应设置 `QJ_ADMIN_SESSION` HttpOnly、SameSite Cookie 并返回会话绑定的 CSRF token；除 GET/HEAD 外的管理请求必须同时带 Cookie 和 `X-CSRF-Token`。资源、分类、壁纸、变体、资源版本和发布接口均按 OpenAPI V1 返回字符串形式 Long ID。

分类、壁纸与变体修改使用响应中的强 ETag。客户端把该值原样放入下一次写请求的 `If-Match`；版本落后时返回 412 `VERSION_CONFLICT`，不会覆盖并发修改。资源版本创建后不可修改，重新发布通过创建新版本并在发布事务中退役旧版本完成。

## 公开目录 API

/api/v1/public 提供已发布分类树、壁纸筛选/搜索/分页、详情和受控图标/封面读取。草稿、下线和归档内容不进入公开目录；正式资源文件不能仅凭 assetId 公开读取。H5 开发服务器在 5175 端口代理同源 API，详见 API-004。

## 构建与测试

~~~bash
./mvnw test
./mvnw verify
~~~

`test` 执行快速模块结构测试；`verify` 额外使用 Testcontainers 启动 MySQL 8.4 和 Redis 7.4，验证空库迁移、16 张业务表、关键唯一约束、Redis 读写和 readiness。Maven Wrapper 会在 macOS 上自动读取当前 Docker context，以兼容 Colima 和 Docker Desktop。

从仓库根目录执行完整本地重启验收：

~~~bash
./scripts/verify-local-api.sh
~~~

该脚本验证 Flyway 历史和表数量，写入无敏感内容的本地验收标记，重启 API、MySQL 和 Redis，再确认健康检查和 MySQL 数据持久化。

## 本地资源存储

资源上传由 `FileStorage` Port 隔离文件系统。`AssetUploadService` 先把输入流写入 `.runtime/storage/.staging`，在写入过程中执行用途级大小限制和 SHA-256 计算；内容校验通过后才使用随机分片键原子移动到 `.runtime/storage/objects`。原文件名只作为展示信息清洗保存，不参与目录或存储键生成。

当前校验包括：

- JPEG、PNG 解码和像素边界；WebP RIFF、块边界和像素边界。
- MP4、QuickTime 的 `ftyp` 容器识别。
- JSON 对象完整解析，禁止尾随第二个根值。
- ZIP 条目数量、展开总量、重复名称和绝对路径、`..`、反斜杠等逃逸名称检查。
- 客户端声明 MIME 与服务端探测类型一致性；`application/octet-stream` 只作为未知声明，不代替服务端探测。

业务层读写只使用不透明暂存令牌和相对 `StorageKey`。本地 Adapter 在读取和提交时检查规范化路径、真实路径根边界、符号链接逃逸、文件大小和 SHA-256；数据库 `asset.storage_key` 不保存绝对路径。

## 配置边界

App 开工契约已冻结为 OpenAPI 1.0.1，见 [PM-003](../../docs/10-项目管理/PM-003-App开工门禁与接入清单.md)。当前设备 Provider 仅支持 local/test H5_TEST，正式平台公钥证明与 SECURE_PACKAGE 交付仍需后续实现；受保护文件读取稳定拒绝，不签发永久资源地址。不存在的 wallpaperId 兑换返回 404 WALLPAPER_NOT_FOUND、事务回滚，已存在但下线作品仍返回 422 最终兑换结果。

- `local` profile 默认连接本地 Compose，并允许应用启动时执行 Flyway。
- `test` profile 只连接 Testcontainers 创建的独立数据服务。
- 基础配置默认关闭 Flyway；后续测试和生产环境必须显式决定迁移步骤，生产迁移不与 API 流量切换绑定。
- 迁移只创建结构，不包含管理员密码、兑换码或演示业务数据。
- 本地随机凭据、上传文件、Jar、测试报告和其他运行时文件均不进入 Git。
