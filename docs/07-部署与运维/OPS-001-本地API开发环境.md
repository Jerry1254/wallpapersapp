# OPS-001 本地 API 开发环境

**状态：** 已验证

**日期：** 2026-09-13

**适用范围：** 本地开发与自动验收，不适用于测试或生产发布

本环境的固定 ID 为 `LOCAL_DEV`。跨端连接、数据清理和验收记录统一遵守 [OPS-005 环境与联调管理规范](OPS-005-环境与联调管理规范.md)。

## 1. 环境组成

本地栈由 `infra/local/compose.yaml` 定义：

| 服务 | 版本线 | 持久化 | 用途 |
|---|---|---|---|
| Java API | Spring Boot 3.5，Java 17 字节码，JRE 21 容器 | 无业务状态 | 模块化单体与健康检查 |
| MySQL | 8.4 | Compose 命名卷 | 唯一业务事实源与 Flyway schema |
| Redis | 7.4 | AOF 命名卷 | 管理/设备会话、限流、挑战、短时票据和幂等协调 |
| 本地资源目录 | `.runtime/storage` | 宿主机目录 | FileStorage 本地 Adapter 的运行时根目录 |

API 默认映射 8080，MySQL 默认映射 3307，Redis 默认映射 6380，避免与常见的宿主机 3306/6379 服务冲突。三个端口都只绑定 `127.0.0.1`，不会监听宿主机的外部网卡。

## 2. 凭据与运行时文件

首次执行 `./scripts/local-api.sh up` 时，脚本使用 `openssl rand` 生成 MySQL 应用密码、MySQL root 密码、Redis 密码和 32 字节设备/兑换加密主密钥，并写入 `.runtime/local-api/compose.env`。脚本先设置 `umask 077`，该文件不进入 Git。已有本地环境缺少 `QJ_SECURITY_MASTER_KEY` 时，脚本会原地补充，不改动数据库和其他凭据。

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

H5 使用同一 API，单独启动：

~~~bash
cd apps/h5-prototype
npm ci
npm run dev
~~~

默认访问 http://127.0.0.1:5175/home，使用相同的同源代理配置。首页、分类、搜索和详情读取真实已发布目录；预览和系统壁纸设置仍为 H5 占位演示。

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

`verify` 使用隔离的 Testcontainers 数据库，不读本地 Compose 业务数据。当前集成测试同时验证管理身份/内容、设备挑战与签名、批次一次性交付、兑换并发额度、权益和 Redis 丢失后的 MySQL 结果恢复。`verify-local-api.sh` 验证实际 Compose 栈的空库迁移、16 张业务表、健康检查和重启持久化。

### 5.1 H5 全链路剧本

默认本地端口、已有管理员及 API Maven 构建就绪后，从仓库根目录执行：

~~~bash
python3 scripts/verify-local-flow.py prepare
# 在 H5 查看新发布的 4D 壁纸，使用 .runtime/local-flow-fixture.json 中的码兑换一次。
# 首次兑换后重复准备下载演示；已用额度应仍为 1。
python3 scripts/verify-local-flow.py complete
~~~

`prepare` 通过管理 API 创建带随机后缀的两级分类、图标、4D 封面/背景/透明前景/配置、不可变资源版本和单码额度 3 的批次。一次性交付后的明文码仅在权限 0600、已忽略的 `.runtime/local-flow-fixture.json` 中暂存，用完后由 `complete` 删除。H5 输入表单清空后，服务端业务表只保存码摘要、尾号和额度。失败时保留夹具供定位，不在终端打印码；不要再次 prepare 或生成新码掩盖原结果。

`complete` 要求该批次恰有 1 次 H5 首兑，再启动 5 个独立测试设备争抢剩余 2 个额度，验证同键结果恢复、异参冲突、重复兑换/下载、无权益下载拒绝和下线后的权益保留。它会重启本地 API/MySQL/Redis，在 120 秒内等待 readiness，核对封面字节与 MySQL 额度/权益/事件一致，并仅删除本夹具获益设备的 Redis 会话验证续期。成功输出脱敏 `.runtime/local-flow-report.json`。脚本固定调用 `127.0.0.1:8080` 和本仓库 local Compose，不接受远程或生产目标；自定义本地端口需先调整脚本，不能直接套用。

为了自动测试真实登录，脚本将已有本地管理员原 BCrypt 哈希暂存为 0600 的 `.runtime/local-flow-admin-backup.json`，临时替换为随机密码哈希，通过 POST `/admin/sessions` 登录后立即恢复并读回核对原哈希；会话在退出时撤销。内容与兑换事实仍经正式 API 写入，SQL 只用于测试登录夹具和只读一致性断言。下一次运行会优先恢复中断遗留的原哈希。该步骤只用于本地验收，不是管理员密码重置功能。

如果需要浏览器登录验收，可单独运行 `python3 scripts/verify-local-flow.py login-ui`。它在 0600 的 `.runtime/local-flow-ui-login.json` 中暂存登录材料；在 120 秒内登录本地后台后删除该文件，脚本立即恢复原哈希。验收完成后在后台退出浏览器会话。超时会清理材料并恢复原哈希；中断后先运行脚本恢复，不把这些文件复制到文档或 Git。

本剧本会保留本地分类、资源、兑换批次、匿名设备和审计记录，以便重启读回；普通停止命令保留它们。并发完成后失败的夹具不保证可从头重跑，应核对原批次最终事实并记录失败阶段。执行成功和失败的证据均见 [全链路验收记录](../06-测试与验收/H5全链路验收记录-2026-09-13.md)。

## 6. 环境隔离

本地、测试和生产必须使用不同 schema、Redis、存储目录和凭据。基础应用配置默认关闭 Flyway，只有 local/test profile 显式开启。生产发布、生产迁移和生产数据操作必须使用后续独立流程，并在执行前获得明确确认。
