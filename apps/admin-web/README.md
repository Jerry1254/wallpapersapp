# 倾境壁纸管理后台

管理端 Mock 原型，沿用考试项目技术基线：Vue 3、TypeScript、Vite、Element Plus、Pinia、Vue Router。

```bash
npm install
cp .env.example .env.local
npm run dev
```

默认地址：`http://127.0.0.1:5176`

本地原型管理员凭据来自 `.env.local`，该文件不进入 Git。接入 Java API 后由服务端保存密码哈希、签发会话并执行登录限制。

当前模块：概览、分类管理、壁纸管理与分类型上传、兑换码批次、兑换记录、设备权益。
