# 倾境壁纸管理后台

管理端真实 API 客户端，使用 Vue 3、TypeScript、Vite、Element Plus、Pinia 和 Vue Router。

```bash
cd /path/to/wallpapersapp
./scripts/local-api.sh up
./scripts/local-api.sh init-admin
cd apps/admin-web
npm ci
npm run dev
```

默认地址：`http://127.0.0.1:5176`

Vite 将 `/api` 同源代理到 `http://127.0.0.1:8080`。管理员密码只在初始化和登录时交给 Java API；服务端仅保存 BCrypt 哈希，浏览器依靠 HttpOnly Cookie 和内存中的 CSRF Token 恢复会话。

本地可通过 `VITE_ADMIN_USERNAME` 预填管理员用户名；未配置时保持为空，不再使用硬编码账号。密码始终不预填、不写入前端环境变量。

当前真实模块：概览、分类管理、壁纸管理、资源上传、不可变资源版本、发布、下架和归档，以及兑换码批次生成与一次性交付、兑换记录和设备权益查询。4D 资源通过 Web 模拟器导出的完整 v2 ZIP 导入，后台只展示文件与稳定结构摘要；发布后通过正式 App 和多设备验收核对 MySQL 事实。

```bash
npm test
npm run build
```
