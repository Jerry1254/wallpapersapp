# OPS-007 API 制品版本一致性与发布门禁

**状态：** 生效

**版本：** 1.0

**日期：** 2026-09-17

**适用范围：** Java API、Flyway、OpenAPI、LOCAL_DEVICE 真机联调和 ONLINE_MAIN 发布

## 1. 问题和结论

2026-09-17 真机联调时，源码、OpenAPI 和自动测试已到 2.1.0，但 8083 进程仍加载 1.8.0 Jar。App 调用新增的 `PUT /api/v1/device/me/capabilities` 时，旧 API 返回 `NoResourceFoundException`。

根因是“代码完成”和“运行制品已更新”之间没有机器门禁。旧流程只检查端口和健康状态，旧 Jar 同样可以返回 `UP`；单独的 PID 或状态文件也可能过期。

从本规范生效起，任何环境必须同时确认以下六项，才能宣称 API 已部署：

1. Git 提交；
2. API 源码范围 SHA-256；
3. OpenAPI 版本；
4. 运行 Jar SHA-256；
5. Flyway 版本；
6. 环境 ID 和部署阶段。

Git 提交记录制品的构建起点；源码范围哈希用于判断后续提交是否真正改动 API 或 OpenAPI。只改文档或 App 时，当前 API 制品仍可通过状态核对；API 范围任何字节变化都会立即报错。

API 通过 `/actuator/info` 返回这些运行元数据。发布脚本必须从正在运行的 API 回读并核对，不能只信任构建目录、文件名或本地记录。

## 2. 什么变更必须重新部署 API

| 变更 | 必须重建/部署 | 必须核对 |
|---|---|---|
| Java 业务代码、路由、DTO、安全规则 | 是 | Git、Jar SHA-256、相关接口 |
| Flyway 迁移 | 是 | Git、Jar SHA-256、Flyway 最终版本 |
| OpenAPI 路由或结构 | 是 | Git、OpenAPI 版本、Jar SHA-256 |
| API `application*.yml` 或依赖 | 是 | Git、Jar SHA-256、运行元数据 |
| 只修改 App | 否 | App 构建提交和目标 API 版本 |
| 只修改管理后台 | 否 | 后台构建提交和代理目标 |
| 只修改文档 | 否 | 文档提交 |

只要前四类 API 范围文件有未提交变更，LOCAL_DEVICE 部署脚本就必须拒绝发布。先完成实现、测试和 Git 提交，再部署可追溯制品。

## 3. 强制闭环

API 改动后必须按以下顺序完成：

```text
实现和测试
→ 更新 OpenAPI/迁移/文档
→ Git 提交
→ 从该提交重新构建不可变 Jar
→ 原子换包并重启
→ 回读 /actuator/info
→ 核对 Git/OpenAPI/Jar/Flyway/环境
→ 再开始 App 或管理后台验收
→ 写入任务看板和验收记录
```

“进程在运行”、“端口可访问”或“健康状态是 UP”都不能单独代表部署完成。

## 4. LOCAL_DEVICE 唯一入口

部署当前已提交的 API：

```bash
python3 scripts/local-device-api.py deploy
```

脚本固定使用：

- API 8083；
- MySQL 3311 / `wallpaper_android`；
- Redis 6391；
- `.runtime/android-api12/storage`；
- `LOCAL_DEVICE / LOCAL`；
- `com.qingjing.bizhi.local`、`com.qingjing.bizhi.internal`、`com.qingjing.bizhi.lab` 三个本地 scope。

`deploy` 会使用项目 `.runtime/media-tools` 内固定的 ffmpeg/ffprobe 执行 Maven `verify`，从当前提交生成带 OpenAPI 版本、Git 短提交和哈希的不可变 Jar，只停止已受管的 8083 Java 进程，然后从实际 API 回读元数据。它不初始化、造数、清理或重置业务数据。Flyway 只执行制品携带的尚未应用迁移。

只读检查当前运行制品：

```bash
python3 scripts/local-device-api.py status
```

当 API 范围还有未提交变更时，`status` 返回 `UNCOMMITTED_API_CHANGES`；当运行 API 与当前 API 源码、OpenAPI、Jar 或 Flyway 目标不一致时，返回 `STALE_OR_WRONG_API`。两种情况都必须非零退出。

## 5. ONLINE_MAIN 发布规则

1. 只接受已通过 LOCAL_DEVICE 和 `ONLINE_MAIN / VALIDATION` 验证的不可变 Jar。
2. 同一次提升不得在服务器上重新编译；Git 提交、OpenAPI 版本和 Jar SHA-256 必须与验收记录一致。
3. 启动后先回读 `/actuator/info` 和 readiness，再允许流量或 App 验收。
4. Flyway 迁移后不自动换回旧 Jar。需要回滚时，必须先确认旧制品与当前 schema 兼容，并使用正式 API 发布/回滚流程。
5. 生产密钥、数据库和资源不从 LOCAL_DEVICE 复制。

## 6. 禁止操作

- 直接运行 `target/*.jar` 并宣称已发布。
- 复用上一次命名为 `current`、`preview` 或手工复制的 Jar。
- 只看 PID、文件时间、进程名、端口或 readiness 判断版本。
- 在 API 范围文件尚未提交时部署联调环境。
- 把本地状态 JSON 当作运行事实；运行 API 自身的 `/actuator/info` 才是当前制品事实。
- 为了换包清空 MySQL、Redis、资源目录或 App 数据。

## 7. 强制回报模板

```text
环境：LOCAL_DEVICE / LOCAL
Git 提交：<40 位 commit>
API 源码 SHA-256：<64 位 hash>
OpenAPI：<version>
运行 Jar：<immutable filename>
Jar SHA-256：<64 位 hash>
Flyway：<Vn>
API readiness：UP
MySQL：3311 / wallpaper_android
Redis：6391
资源目录：.runtime/android-api12/storage
手机入口：8443 -> 8083
本次是否执行业务数据写入/清理：否
```

任何一项缺失或不一致，都必须报告“环境未就绪”，不得让 App 继续产生误导性测试结论。
