# API-004 公开目录 API 与 H5 联调说明

**状态：** 已实现
**日期：** 2026-09-12
**对应工作包：** WP-P09
**契约版本：** OpenAPI V1.0.0

## 1. 公开目录

| 路由 | 行为 |
|---|---|
| GET /api/v1/public/categories | 只返回包含已发布壁纸的一级分类及有已发布内容的二级分类，包含图标、排序和数量 |
| GET /api/v1/public/wallpapers | 按分类、系统视图、壁纸类型、平台、关键词和排序查询已发布壁纸，使用标准分页 |
| GET /api/v1/public/wallpapers/{wallpaperId} | 返回已发布壁纸的摘要、版权说明和发布时间 |
| GET /api/v1/public/assets/{assetId}/content | 读取已发布目录引用的分类图标或壁纸封面，返回内容 SHA-256 对应的 ETag 和 public,max-age=3600 |

公开列表和详情不展示 DRAFT、OFFLINE 或 ARCHIVED 壁纸。设备权益的摘要读取继续按权益事实工作，不因公开目录的状态过滤改变已有权益。

一级分类包含直属及二级分类下的已发布壁纸数量。没有已发布内容的分类不会出现在分类树。二级分类筛选必须同时提供其所属一级分类 ID；关系错误返回 400 VALIDATION_FAILED。

FEATURED 视图筛选非空 featuredRank，并按 featuredRank、ID 排序。STATIC 视图按已发布的 STATIC_IMAGE 变体计算，不依赖一级分类名称。DEFAULT 按 sortOrder、ID 排序；NEWEST 按 publishedAt、ID 倒序排列。

平台筛选同时接受目标平台和 UNIVERSAL 的已发布变体。详情保留全部已发布能力描述，将目标平台能力排在首位，UNIVERSAL 次之。

关键词去除首尾空格后接受 1 至 40 个字符，匹配标题或 slug。所有查询参数通过绑定变量传递，百分号、下划线和感叹号作为普通字符匹配。slug 的搜索表达式转换到 UTF-8，避免中文关键词与 ASCII slug 的字符集冲突。

## 2. 公开资源边界

分类图标必须被含有已发布内容的一级分类引用；封面必须被已发布壁纸引用。独立上传资源和正式资源版本绑定的背景、前景、配置、视频及静态原图不能仅凭 assetId 公开读取。

文件字节通过 FileStorage Port 打开，客户端只收到受控 URL、MIME、尺寸和摘要。服务端存储键、绝对路径、资源绑定和内容密钥不进入公开 DTO。

## 3. H5 接入

H5 的 domain/catalog.ts 定义与 OpenAPI 对应的目录 DTO，repositories/http/catalogRepository.ts 负责真实 HTTP 请求。首页、分类、搜索和详情已移除 Mock 目录依赖。

首页的精选、最近上新、4D 和静态入口分别使用 FEATURED、NEWEST、PARALLAX_4D 和 STATIC 查询。分类图标与卡片图片使用公开受控地址。分类和搜索按 20 条分页加载，追加失败保留已有结果并允许重试。

useWallpaperPage 用请求版本丢弃旧查询和旧追加请求的结果，避免快速切换分类或关键词时出现过期卡片。每次公开 HTTP 请求限制为 15 秒；页面提供加载骨架、错误重试和空结果状态。

本工作包的详情仍以真实封面演示预览。兑换、权益及下载将在 WP-P10 接入服务端；4D 姿态、动态视频和系统壁纸设置继续使用占位交互。

## 4. 本地联调

先在仓库根目录启动 API：

~~~bash
./scripts/local-api.sh up
~~~

再启动 H5：

~~~bash
cd apps/h5-prototype
npm ci
npm run dev
~~~

访问 http://127.0.0.1:5175/home。开发服务器把同源 /api 转发到 http://127.0.0.1:8080，可通过本机 VITE_API_PROXY_TARGET 调整。生产构建需由部署入口提供同源 /api 路由，Vite preview 不承担 API 代理。

~~~bash
npm run check
npm run build
~~~

服务端验证使用 services/api-server 下的 ./mvnw verify；验收结果记录在对应 WP-P09 验收文档。
