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

当前真实模块：概览、分类管理、壁纸管理、资源上传、不可变资源版本、发布、下架和归档。兑换码、兑换记录和设备权益页面已移除 Mock 数据，等待 WP-P08 服务端接口。

```bash
npm test
npm run build
```
