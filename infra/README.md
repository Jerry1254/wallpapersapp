# 基础设施

该目录保存开发、测试和生产环境的可版本化配置，包括接口服务、管理后台、对象存储和 CDN。密钥及生产凭证不进入 Git。

当前 `local/compose.yaml` 只用于本地开发，包含 MySQL 8.4、Redis 7.4 和 Java API。请从仓库根目录通过 `./scripts/local-api.sh up` 启动；脚本在 `.runtime` 中生成随机基础设施凭据，Compose 文件本身不包含密码。首次启动后使用 `./scripts/local-api.sh init-admin` 交互初始化唯一管理员。

测试和生产配置不得复用本地命名卷、端口、凭据或业务数据。当前仓库没有生产部署和生产迁移入口。
