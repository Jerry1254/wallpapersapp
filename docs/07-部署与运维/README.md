# 07-部署与运维

本目录用于保存 Android 发布、接口服务部署、管理后台部署、对象存储、CDN、监控和回滚方案。

当前使用 `LOCAL_DEV`、`LOCAL_DEVICE` 两套本地隔离环境；服务器只规划一套 `ONLINE_MAIN`，通过 `VALIDATION/PRODUCTION` 阶段区分上线前验证和正式运营。正式资源包使用不可变版本 URL；壁纸下架通过接口隐藏，不覆盖已经发布的同名文件。Android 每次发布保留签名、版本号、构建产物校验值和回滚说明。

当前文档：

- [OPS-001 本地 API 开发环境](OPS-001-本地API开发环境.md)
- [OPS-002 Android 真机连接本地 API](OPS-002-Android真机连接本地API.md)

- [OPS-003 Android 内部候选版安装说明（1.0 已完成）](OPS-003-Android内部候选版安装说明.md)
- [Android 内部候选版版本清单（1.0 已完成）](android-internal-candidate-1.0.0-10015.json)
- [OPS-004 Android 双包打包与命名规范（当前生效）](OPS-004-Android双包打包与命名规范.md)
- [OPS-005 环境与联调管理规范（单一线上数据库草案，待确认）](OPS-005-环境与联调管理规范.md)
- [OPS-006 App 本地测试与线上环境同步说明（当前交接版）](OPS-006-App本地测试与线上环境同步说明.md)
