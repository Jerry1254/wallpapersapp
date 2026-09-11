# OPS-001 本地 API 开发环境

**状态：** 已验证

**日期：** 2026-09-11

**适用范围：** 本地开发与自动验收，不适用于测试或生产发布

## 1. 环境组成

本地栈由 `infra/local/compose.yaml` 定义：

| 服务 | 版本线 | 持久化 | 用途 |
|---|---|---|---|
| Java API | Spring Boot 3.5，Java 17 字节码，JRE 21 容器 | 无业务状态 | 模块化单体与健康检查 |
| MySQL | 8.4 | Compose 命名卷 | 唯一业务事实源与 Flyway schema |
| Redis | 7.4 | AOF 命名卷 | 后续会话、限流、短时票据和幂等协调 |
| 本地资源目录 | `.runtime/storage` | 宿主机目录 | FileStorage 本地 Adapter 的运行时根目录 |

API 默认映射 8080，MySQL 默认映射 3307，Redis 默认映射 6380，避免与常见的宿主机 3306/6379 服务冲突。三个端口都只绑定 `127.0.0.1`，不会监听宿主机的外部网卡。

## 2. 凭据与运行时文件

首次执行 `./scripts/local-api.sh up` 时，脚本使用 `openssl rand` 生成 MySQL 应用密码、MySQL root 密码和 Redis 密码，并写入 `.runtime/local-api/compose.env`。脚本先设置 `umask 077`，该文件不进入 Git。

Compose 只引用环境变量，不保存默认密码。Flyway V1 只建结构，不创建管理员账号、默认密码、兑换码或演示数据。

本地资源目录包含 `.staging` 和 `objects`。`.staging` 只保存尚未通过校验的随机暂存文件；`objects` 按随机 UUID 的前四位分成两级目录。目录、上传内容和文件系统路径均被 `.gitignore` 排除。清理 `.runtime/storage` 会永久删除本地上传资源，因此普通停止命令不会自动清理它。

## 3. 启动与停止

~~~bash
./scripts/local-api.sh up
./scripts/local-api.sh status
./scripts/local-api.sh logs api
./scripts/local-api.sh init-admin
./scripts/local-api.sh down
~~~

`up` 构建 Jar 和 API 镜像，等待 MySQL、Redis 容器健康后再启动 API，并等到 readiness 成功才返回。`down` 保留命名卷，因此再次 `up` 会验证 Flyway 重复启动并继续使用原 MySQL 数据。

`init-admin` 只在 `admin_account` 为空时工作。脚本隐藏读取 12 至 128 位密码，使用临时环境变量重建 API 触发 BCrypt 初始化，随后再次重建 API 清除容器元数据里的明文变量。现有账号不会被该命令重置；迁移、Compose 和运行时配置文件都不保存默认管理员密码。

管理后台在 API 就绪后单独启动：

~~~bash
cd apps/admin-web
npm ci
npm run dev
~~~

默认访问 `http://127.0.0.1:5176`。Vite 把同源 `/api` 请求代理到 `http://127.0.0.1:8080`；如需调整，仅在本机环境设置 `VITE_API_PROXY_TARGET`。

## 4. 健康检查

- `/actuator/health/liveness`：Java 进程可工作。
- `/actuator/health/readiness`：应用可接流量，依赖初始化完成。
- `/actuator/health`：本地 profile 返回 MySQL、Redis、磁盘和可用状态的详细结果。

管理端和 H5 后续只把 readiness 用作联调依赖检查，不把 Actuator 详情暴露到公网。

## 5. 验证命令

~~~bash
cd services/api-server
./mvnw test
./mvnw verify

cd ../..
./scripts/verify-local-api.sh
~~~

`verify` 使用隔离的 Testcontainers 数据库，不读本地 Compose 业务数据。`verify-local-api.sh` 验证实际 Compose 栈的空库迁移、16 张业务表、健康检查和重启持久化。

## 6. 环境隔离

本地、测试和生产必须使用不同 schema、Redis、存储目录和凭据。基础应用配置默认关闭 Flyway，只有 local/test profile 显式开启。生产发布、生产迁移和生产数据操作必须使用后续独立流程，并在执行前获得明确确认。
