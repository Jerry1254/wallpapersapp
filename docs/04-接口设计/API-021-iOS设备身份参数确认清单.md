# API-021 iOS 设备身份参数确认清单

**状态：** 等待 iOS App 确认

**对接对象：** iOS App、Java API

**日期：** 2026-09-28

## 1. 目的

iOS Live Photo 的生成、管理后台处理、目录过滤和双文件下载接口已经完成。当前只缺 iOS App 的设备注册、挑战会话和敏感请求签名契约。

本文用于让 iOS 开发一次性确认全部身份参数。参数确认后，Java API 才能放行 `platform=IOS` 的注册和下载，避免两端分别实现后因公钥格式、签名编码或签名原文不同而返工。

本文不是已经生效的 iOS 身份契约。标记为“待 iOS 确认”的内容，在双方确认并完成 API 实现前不能用于正式联调。

## 2. 当前完成情况

| 模块 | 状态 |
| --- | --- |
| 从原始 MP4 生成 1 秒 Live Photo HEIC + MOV | 已完成 |
| 管理后台上传、生成状态和重新生成 | 已完成 |
| iOS 目录与详情的平台资源过滤 | 已完成 |
| iOS Live Photo 双文件下载描述 | 已完成 |
| iOS 设备注册 | 待身份参数确认和 API 实现 |
| iOS challenge 会话 | 待身份参数确认和 API 实现 |
| iOS 敏感请求签名 | 待身份参数确认和 API 实现 |

## 3. iOS 开发必须回复的参数

请 iOS 开发复制下面表格，并填写“iOS 确认值”一列。

| 参数 | iOS 确认值 | 说明 |
| --- | --- | --- |
| 正式 Bundle ID | 待填写 | 用于正式包 `appInstallScope` 白名单 |
| 测试 Bundle ID | 待填写 | 如有多个测试包，请全部列出 |
| 安装级密钥保存位置 | 待填写 | `Secure Enclave` 或普通 `Keychain` |
| 公钥算法 | 待填写 | 建议 `EC P-256`；如使用 RSA，需说明密钥保存方式 |
| 签名算法 | 待填写 | 建议 `ECDSA + SHA-256` |
| 公钥导出原始格式 | 待填写 | 例如 X9.63 65 字节公钥或 X.509 SPKI DER |
| `publicKeyPem` 生成方式 | 待填写 | 明确 PEM 内部究竟包装哪一种 DER 字节 |
| 公钥指纹输入字节 | 待填写 | 明确 SHA-256 对哪些字节计算 |
| 公钥指纹输出格式 | 待填写 | 建议小写十六进制 |
| ECDSA 签名字节格式 | 待填写 | 必须明确 ASN.1 DER 或固定 64 字节 `r || s` |
| 签名输出编码 | 待填写 | 建议 Base64URL 无填充 |
| 客户端时间戳格式 | 待填写 | 建议 ISO 8601 UTC，例如 `2026-09-28T08:30:00Z` |
| nonce 格式 | 待填写 | 建议小写标准 UUID |
| 卸载重装后的处理 | 待填写 | 是否生成新密钥并重新注册 |
| 密钥失效后的处理 | 待填写 | 如何识别并重新注册 |

## 4. 建议采用的统一契约

如果 iOS 端尚未实现身份方案，建议直接采用本节契约。iOS 开发只需确认能否实现，并提供正式包和测试包 Bundle ID。

### 4.1 密钥和公钥

- 每次 App 安装生成一把 Secure Enclave P-256 签名私钥。
- 私钥不可导出，只保存密钥引用。
- 从私钥取得公钥后，将 X9.63 未压缩公钥包装为 X.509 SubjectPublicKeyInfo DER。
- `publicKeyPem` 使用以下格式，正文为 SPKI DER 的标准 Base64：

```text
-----BEGIN PUBLIC KEY-----
<每行 64 字符的标准 Base64>
-----END PUBLIC KEY-----
```

- 公钥指纹计算方式：

```text
lowercaseHex(SHA-256(SPKI DER 原始字节))
```

- 签名算法使用 ECDSA P-256 + SHA-256。
- ECDSA 签名使用 ASN.1 DER 字节，再编码为 Base64URL 无填充字符串。

> Secure Enclave 适合 P-256 密钥。若 iOS 端选择 RSA，需要明确说明 RSA 私钥实际保存位置，不能把普通 Keychain RSA 描述为 Secure Enclave RSA。

### 4.2 注册请求

```http
POST /api/v1/device/registrations
Content-Type: application/json
```

```json
{
  "platform": "IOS",
  "appInstallScope": "<当前安装包 Bundle ID>",
  "credentialType": "PLATFORM_PUBLIC_KEY",
  "publicKeyPem": "-----BEGIN PUBLIC KEY-----\n...\n-----END PUBLIC KEY-----",
  "evidenceToken": "<Base64URL 无填充字符串>"
}
```

注册签名原文建议固定为以下 UTF-8 文本。字段之间只有一个 `LF` 换行，末尾没有换行：

```text
QJ-IOS-REGISTER-V1
<appInstallScope>
<公钥指纹>
<timestamp>
<nonce>
```

`evidenceToken` 是以下 UTF-8 JSON 的 Base64URL 无填充编码：

```json
{
  "timestamp": "2026-09-28T08:30:00Z",
  "nonce": "<UUID>",
  "proof": "<注册签名，Base64URL 无填充>"
}
```

服务端验证时间窗口、nonce 防重放、Bundle ID 白名单、公钥指纹和签名。服务端不会接收或保存私钥。

### 4.3 challenge 和会话

申请 challenge：

```http
POST /api/v1/device/session-challenges
Content-Type: application/json
```

```json
{
  "credentialKeyId": "<注册响应中的 UUID>"
}
```

建议 iOS challenge 返回：

```json
{
  "challengeId": "<UUID>",
  "nonce": "<服务端随机值>",
  "algorithm": "ECDSA_P256_SHA256",
  "expiresAt": "<ISO 8601 UTC>"
}
```

会话签名原文沿用现有统一格式：

```text
QJ-DEVICE-SESSION-V1
<credentialKeyId>
<challengeId>
<challenge.nonce>
<clientTimestamp>
```

创建会话：

```http
POST /api/v1/device/sessions
Content-Type: application/json
```

```json
{
  "credentialKeyId": "<UUID>",
  "challengeId": "<UUID>",
  "clientTimestamp": "2026-09-28T08:30:00Z",
  "proof": "<会话签名，Base64URL 无填充>"
}
```

成功后返回 `deviceAccessToken`。后续请求使用：

```http
Authorization: Bearer <deviceAccessToken>
```

### 4.4 敏感业务请求签名

兑换、领取下载票据等敏感请求需要使用同一安装级私钥签名。签名原文为：

```text
QJ-SIGNED-REQUEST-V1
<大写 HTTP 方法>
<不含域名的规范路径>
<ISO 8601 UTC 时间戳>
<UUID nonce>
<请求体 SHA-256 小写十六进制>
```

请求头：

```http
X-Request-Timestamp: <与签名原文一致>
X-Request-Nonce: <与签名原文一致>
X-Request-Signature: <签名的 Base64URL 无填充字符串>
```

GET 或空请求体也必须对零字节请求体计算 SHA-256，不能省略最后一行。

## 5. iOS 开发需要提供的联调样例

除第 3 节参数外，请提供一组固定的非生产测试向量：

1. 测试公钥 PEM；
2. 对应的公钥指纹；
3. 一份注册签名原文和期望签名；
4. 一份会话签名原文和期望签名；
5. 一份业务请求签名原文和期望签名；
6. 签名字节采用 ASN.1 DER 还是 `r || s` 的明确说明；
7. Base64URL 是否移除末尾 `=` 的明确说明。

固定测试向量用于 Java 自动测试和 iOS 单元测试，保证两端对同一原文得到可互相验证的结果。不得提供生产私钥；可以提供专门生成的测试密钥，或者只提供公钥、原文和签名结果。

## 6. Java API 收到参数后的工作

参数确认后，Java API 将完成：

1. 增加 iOS 安装级公钥注册验证器；
2. 增加正式包和测试包 Bundle ID 白名单；
3. 允许 `platform=IOS` 创建 challenge 和会话；
4. 根据 iOS 密钥类型返回正确的 `challenge.algorithm`；
5. 使用同一算法验证会话 proof 和敏感业务请求签名；
6. 将 iOS 会话接入现有目录、权益、兑换和 Live Photo 下载票据；
7. 同步 OpenAPI 和自动化测试。

## 7. 验收标准

- iOS 真机首次安装可以注册并获得 `credentialKeyId`；
- challenge 只能使用一次，过期或重放会被拒绝；
- iOS 私钥可以成功创建会话，错误签名会被拒绝；
- App 重启后可以复用安装级密钥重新创建会话；
- 卸载重装或密钥失效后可以生成新密钥并重新注册；
- 敏感请求被篡改、时间戳过期或 nonce 重放时会被拒绝；
- iOS 会话只能获取 iOS Live Photo 和真正的全平台静态资源；
- 同一张 Live Photo 的 HEIC 和 MOV 使用同一下载票据交付并分别校验哈希。

## 8. 最小回复模板

iOS 开发至少回复以下内容，API 才能开始实现身份部分：

```text
正式 Bundle ID：
测试 Bundle ID：
密钥保存位置：
公钥算法：
签名算法：
公钥导出原始格式：
publicKeyPem 生成方式：
公钥指纹计算方式：
ECDSA 签名字节格式：
签名输出编码：
时间戳格式：
nonce 格式：
是否接受本文第 4 节签名原文：
测试公钥、三组签名原文与签名结果：
卸载重装和密钥失效处理：
```
