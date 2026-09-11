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
./scripts/local-api.sh down
~~~

`down` 只停止并移除本地容器，保留 MySQL 和 Redis 命名卷。脚本不提供删除数据卷的快捷命令。

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

## 配置边界

- `local` profile 默认连接本地 Compose，并允许应用启动时执行 Flyway。
- `test` profile 只连接 Testcontainers 创建的独立数据服务。
- 基础配置默认关闭 Flyway；后续测试和生产环境必须显式决定迁移步骤，生产迁移不与 API 流量切换绑定。
- 迁移只创建结构，不包含管理员密码、兑换码或演示业务数据。
- 本地随机凭据、上传文件、Jar、测试报告和其他运行时文件均不进入 Git。
