# API 服务

Java API 正式工程目录，承载内容目录、匿名设备、兑换、权益、受保护下载、设备恢复和后台管理接口。

当前基线使用 Spring Boot 3.5、Java 17 字节码、Flyway、MySQL 8.4 和 Redis 7.4。代码按 `adminidentity`、`catalog`、`asset`、`parallax`、`device`、`redemption`、`entitlement`、`delivery`、`tutorial`、`audit` 和 `shared` 包保持模块化单体边界。

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

管理接口位于 `/api/v1/admin`。登录响应设置 `QJ_ADMIN_SESSION` HttpOnly、SameSite Cookie 并返回会话绑定的 CSRF token；除 GET/HEAD 外的管理请求必须同时带 Cookie 和 `X-CSRF-Token`。资源、分类、壁纸、变体、资源版本、发布和五类固定设置教程接口均按 OpenAPI V1 返回字符串形式 Long ID。

分类、壁纸与变体修改使用响应中的强 ETag。客户端把该值原样放入下一次写请求的 `If-Match`；版本落后时返回 412 `VERSION_CONFLICT`，不会覆盖并发修改。资源版本创建后不可修改，重新发布通过创建新版本并在发布事务中退役旧版本完成。

## 公开目录 API

`/api/v1/public` 提供已发布分类树、壁纸筛选/搜索/分页、详情、受控图标/封面读取，以及已启用设置教程列表与支持 Range/HEAD 的公开视频流。草稿、下线、归档内容和停用教程不进入公开目录；正式资源文件不能仅凭 assetId 公开读取。

## 构建与测试

~~~bash
./mvnw test
./mvnw verify
~~~

完整媒体回归需要可执行的 FFmpeg 与 ffprobe。若未安装到 PATH，显式设置 `QJ_FFMPEG`、`QJ_FFPROBE` 为本机工具绝对路径，并使用 UTF-8 locale；本机已有工具位于仓库被忽略的 `.runtime/media-tools/`。缺少工具时外部媒体快速测试会跳过，涉及真实 MP4 的集成测试不能计为通过。

`test` 执行快速模块和媒体校验测试；`verify` 额外使用 Testcontainers 启动 MySQL 8.4 和 Redis 7.4，验证空库/升级迁移、23 张业务及清理任务表、关键唯一约束、ZIP 导入/版本/发布/回滚、Redis 和 readiness。Maven Wrapper 会在 macOS 上自动读取当前 Docker context，以兼容 Colima 和 Docker Desktop。媒体测试需要 `QJ_FFMPEG`、`QJ_FFPROBE` 指向可用解码器。

### 固定 4D ZIP

`POST /api/v1/admin/parallax-packages` 同步导入，`POST /api/v1/admin/variants/{id}/parallax-resource-versions` 创建关联版本。参见 [制作说明](../../packages/wallpaper-format/4D源包制作说明.md) 和 API-007。失败后的存储清理任务每分钟重试一次，仅处理失败导入对象；不自动回收成功但未引用的源包。

本地草稿上传无需签名私钥；发布制作正式/预览包需要在忽略的 `.runtime/local-api/compose.env` 设置独立的 `QJ_PACKAGE_SIGNING_KEY_ID` 和 `QJ_PACKAGE_SIGNING_PRIVATE_KEY`（RSA 2048 PKCS8 DER 的 Base64）。本地 Compose 已透传这两项，默认空；不得复用生产密钥，也不要将私钥写入 Git。手机只有配置匹配的验签公钥才能安装该环境制作的资源包。

从仓库根目录执行完整本地重启验收：

~~~bash
./scripts/verify-local-api.sh
~~~

该脚本验证 Flyway 历史和表数量，写入无敏感内容的本地验收标记，重启 API、MySQL 和 Redis，再确认健康检查和 MySQL 数据持久化。

## 本地资源存储

资源上传由 `FileStorage` Port 隔离文件系统。`AssetUploadService` 先把输入流写入 `.runtime/storage/.staging`，在写入过程中执行用途级大小限制和 SHA-256 计算；内容校验通过后才使用随机分片键原子移动到 `.runtime/storage/objects`。原文件名只作为展示信息清洗保存，不参与目录或存储键生成。

当前校验包括：

- JPEG、PNG 解码和像素边界；WebP RIFF、块边界和像素边界。
- 普通 MP4、QuickTime 的 `ftyp` 容器识别；教程 MP4 额外校验 `moov`、`mvhd`、`trak`、`mdat` 结构并读取不超过 15 分钟的时长。
- JSON 对象完整解析，禁止尾随第二个根值。
- ZIP 条目数量、展开总量、重复名称和绝对路径、`..`、反斜杠等逃逸名称检查。
- 客户端声明 MIME 与服务端探测类型一致性；`application/octet-stream` 只作为未知声明，不代替服务端探测。

业务层读写只使用不透明暂存令牌和相对 `StorageKey`。本地 Adapter 在读取和提交时检查规范化路径、真实路径根边界、符号链接逃逸、文件大小和 SHA-256；数据库 `asset.storage_key` 不保存绝对路径。

## 配置边界

当前契约版本为 OpenAPI 1.8.0；历史开工冻结规则见 [PM-003](../../docs/10-项目管理/PM-003-App开工门禁与接入清单.md)。Android 正式设备身份与安全资源包交付已经接入；HarmonyOS 与 iOS Provider 仍按各自工作包实现。不存在的 wallpaperId 兑换返回 404 `WALLPAPER_NOT_FOUND` 并回滚事务，已存在但下线作品仍返回 422 最终兑换结果。`FREE` 作品免权益正式交付，误兑换返回 `WALLPAPER_FREE`。

- `local` profile 默认连接本地 Compose，并允许应用启动时执行 Flyway。
- `test` profile 只连接 Testcontainers 创建的独立数据服务。
- 基础配置默认关闭 Flyway；后续测试和生产环境必须显式决定迁移步骤，生产迁移不与 API 流量切换绑定。
- 迁移只创建结构，不包含管理员密码、兑换码或演示业务数据。
- 本地随机凭据、上传文件、Jar、测试报告和其他运行时文件均不进入 Git。
