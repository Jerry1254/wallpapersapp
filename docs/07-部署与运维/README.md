# 07-部署与运维

本目录用于保存 Android 发布、接口服务部署、管理后台部署、对象存储、CDN、监控和回滚方案。

环境按开发、测试、生产三套隔离。生产资源包使用不可变版本 URL；壁纸下架通过接口隐藏，不覆盖已经发布的同名文件。Android 每次发布保留签名、版本号、构建产物校验值和回滚说明。

当前文档：

- [OPS-001 本地 API 开发环境](OPS-001-本地API开发环境.md)
- [OPS-002 Android 真机连接本地 API](OPS-002-Android真机连接本地API.md)

- [OPS-003 Android 内部候选版安装说明（1.0 已完成）](OPS-003-Android内部候选版安装说明.md)
- [Android 内部候选版版本清单（1.0 已完成）](android-internal-candidate-1.0.0-10015.json)
- [OPS-004 Android 双包打包与命名规范（当前生效）](OPS-004-Android双包打包与命名规范.md)
- [OPS-005 环境与联调管理规范（草案，待确认）](OPS-005-环境与联调管理规范.md)
