# API-022 iOS 设备身份参数确认回复

**状态：** Java API 已实现并通过契约与签名单元测试，待部署后真机联调

**日期：** 2026-09-28

**回复对象：** `API-021 iOS 设备身份参数确认清单`

## 1. 确认结论

iOS 接受 `API-021` 第 4 节提议的统一契约，使用 Secure Enclave P-256 安装级签名密钥、X.509 SPKI 公钥、ECDSA SHA-256 DER 签名和 Base64URL 无填充编码。

安装包标识为：

- 正式 Bundle ID：`com.qingjing.bizhi`
- 当前 iOS 实验/联调 Bundle ID：`com.qingjing.livephotolab`

服务端白名单必须精确匹配，不使用通配符。后续如增加新的 iOS Debug、Internal 或 TestFlight Bundle ID，由 App 端明确提供后再加入白名单。

## 2. 参数回复

| 参数 | iOS 确认值 |
| --- | --- |
| 正式 Bundle ID | `com.qingjing.bizhi` |
| 测试 Bundle ID | `com.qingjing.livephotolab` |
| 安装级密钥保存位置 | Secure Enclave；Keychain 保存不可导出私钥引用 |
| Keychain 可访问性 | `kSecAttrAccessibleWhenUnlockedThisDeviceOnly` |
| Secure Enclave 访问控制 | `privateKeyUsage`，不要求生物识别或每次弹窗 |
| 公钥算法 | EC P-256 / `secp256r1` / `prime256v1` |
| 签名算法 | ECDSA + SHA-256 |
| Apple Security 签名算法 | `SecKeyAlgorithm.ecdsaSignatureMessageX962SHA256` |
| 公钥导出原始格式 | X9.63 未压缩 65 字节：`0x04 || X(32) || Y(32)` |
| `publicKeyPem` 生成方式 | 将 X9.63 公钥包装为 P-256 X.509 SubjectPublicKeyInfo DER，再输出 `PUBLIC KEY` PEM |
| 公钥指纹输入字节 | SPKI DER 原始字节 |
| 公钥指纹输出 | SHA-256 小写十六进制，64 字符 |
| ECDSA 签名字节格式 | ASN.1 DER，不是固定 64 字节 `r || s` |
| 签名输出编码 | Base64URL，移除末尾 `=` |
| 客户端时间戳 | UTC ISO 8601，精确到秒，无小数：`yyyy-MM-dd'T'HH:mm:ss'Z'` |
| nonce | `UUID().uuidString.lowercased()` 形式的小写标准 UUID |
| 注册签名原文 | 接受 `QJ-IOS-REGISTER-V1` |
| 会话签名原文 | 接受 `QJ-DEVICE-SESSION-V1` |
| 敏感请求签名原文 | 接受 `QJ-SIGNED-REQUEST-V1` |
| challenge algorithm | `ECDSA_P256_SHA256` |
| 卸载重装 | 生成新密钥并重新注册 |
| 密钥失效 | 清理本地 credential/session，生成新密钥并重新注册；最多自动重试一次 |

## 3. iOS 密钥生命周期

### 3.1 首次安装

1. App 在 UserDefaults 保存当前安装的初始化标记。
2. 如初始化标记不存在，先删除同 Keychain application tag 下可能遗留的旧密钥引用和旧 credential/session，再生成新的 Secure Enclave P-256 密钥。
3. 使用新密钥完成设备注册，保存 `credentialKeyId`。

原因：iOS Keychain 数据在卸载后可能仍然存在，不能仅依赖“查不到 Keychain 项”判定新安装。UserDefaults 随 App 卸载清除，可用于触发旧安装身份清理。

### 3.2 正常启动

1. 找到 Secure Enclave 密钥引用和 `credentialKeyId` 时，复用它们申请 challenge 并创建新会话。
2. 访问 Token 过期时只重建会话，不重新注册。
3. 密钥引用丢失、Secure Enclave 签名失败或服务端明确返回 credential 无效时，删除本地安装身份并重新注册。
4. 自动重新注册最多尝试一次，防止状态异常时无限循环。

## 4. 编码细节

### 4.1 SPKI DER

`SecKeyCopyExternalRepresentation` 导出的 P-256 公钥是 65 字节 X9.63 未压缩点。`publicKeyPem` 包装为以下 SPKI：

```text
SEQUENCE
  SEQUENCE
    OBJECT IDENTIFIER 1.2.840.10045.2.1     # id-ecPublicKey
    OBJECT IDENTIFIER 1.2.840.10045.3.1.7   # prime256v1
  BIT STRING 0x04 || X || Y
```

P-256 65 字节 X9.63 公钥的固定 SPKI DER 前缀为：

```text
3059301306072A8648CE3D020106082A8648CE3D030107034200
```

前缀后追加 65 字节 X9.63 公钥，总长度为 91 字节。

### 4.2 签名和编码

- 对契约规定的 UTF-8 原文使用 `ecdsaSignatureMessageX962SHA256` 签名，不先在业务层自行做二次 SHA-256。
- `SecKeyCreateSignature` 输出的是 ASN.1 DER ECDSA 签名。
- 对 DER 字节执行 Base64URL：`+` 替换为 `-`，`/` 替换为 `_`，移除末尾所有 `=`。
- 签名原文字段之间只使用单个 `LF` (`0x0A`)，末尾没有换行。
- 业务请求体哈希必须对最终发送的原始字节计算。签名后不得重新排序 JSON 字段或重新序列化。

## 5. 非生产联调测试向量

以下密钥专门用于 Java/iOS 自动测试，不是任何真实安装的生产密钥。测试签名已使用 OpenSSL 验证可由下列公钥通过。

### 5.1 测试公钥

```text
-----BEGIN PUBLIC KEY-----
MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE0At0cIFeoeu0IWxtKDUMAVVIyPsc
CCGH2+i84oDGdhHC4SiRF+MqKo3nwtOagCcCkVh0Wx8XePikNxH/29WVxw==
-----END PUBLIC KEY-----
```

SPKI DER SHA-256 指纹：

```text
fb1cbfec0d7cb62275413fee43cd40cb272787bf690faf22e14c3465062494ce
```

### 5.2 注册签名向量

原文是 UTF-8 字节，行尾为 `LF`，最后一行后没有换行：

```text
QJ-IOS-REGISTER-V1
com.qingjing.livephotolab
fb1cbfec0d7cb62275413fee43cd40cb272787bf690faf22e14c3465062494ce
2026-09-28T08:30:00Z
123e4567-e89b-12d3-a456-426614174000
```

ASN.1 DER 签名的 Base64URL 无填充编码：

```text
MEQCIC0CfShI5S4na7dgT8BJ4KLgye0CkZir8DAkSIodh0WVAiAIwgDJI84OS5ewA9ouQoDiatF2b1XP-q3DmRuDW0EZyg
```

### 5.3 会话签名向量

```text
QJ-DEVICE-SESSION-V1
11111111-1111-4111-8111-111111111111
22222222-2222-4222-8222-222222222222
qj-test-challenge-nonce-v1
2026-09-28T08:31:00Z
```

ASN.1 DER 签名的 Base64URL 无填充编码：

```text
MEYCIQC0TdP0M2Onixi4NwzDx1iQh9PHnAd7Z6613ODxBDBOggIhALzLHRrRTXaSw4fVdo27u-0JvxiYqjp-ToAQZFDufdDV
```

### 5.4 业务请求签名向量

请求体原始 UTF-8 字节（无换行）：

```json
{"deliveryPlatform":"IOS","resourceType":"LIVE_PHOTO"}
```

请求体 SHA-256：

```text
4944d90113fa0a1c413a35f6af4989a21d5204620865b05519ee525e7aabc34c
```

签名原文：

```text
QJ-SIGNED-REQUEST-V1
POST
/api/v1/device/wallpapers/42/download-tickets
2026-09-28T08:32:00Z
33333333-3333-4333-8333-333333333333
4944d90113fa0a1c413a35f6af4989a21d5204620865b05519ee525e7aabc34c
```

ASN.1 DER 签名的 Base64URL 无填充编码：

```text
MEUCICRuxj0YbitVxnPY-27_UtKJO4CEi8daOCuPPXQIKbJuAiEAnsZrCItIKEXk_ClJD92piHZLdMOuUI9nxeTOWqCHv00
```

### 5.5 ECDSA 向量注意事项

ECDSA 签名默认包含随机数，因此 iOS 使用同一私钥和同一原文时，不要求每次生成与上述字符串完全相同的签名。Java 测试应使用公钥验证上述固定签名；iOS 测试应验证新生成的签名可被对应公钥验证，不做签名字节直接相等断言。

## 6. Java API 实现要求

1. `platform=IOS` 只接受上述精确 Bundle ID 白名单。
2. 解析 `PUBLIC KEY` PEM 为 X.509 SPKI P-256 公钥，拒绝错误曲线、非 EC 公钥和多余 PEM 块。
3. 使用 `SHA256withECDSA` 验证 ASN.1 DER 签名。
4. challenge 返回 `algorithm=ECDSA_P256_SHA256`。
5. 注册 evidence、会话 proof 和 `X-Request-Signature` 均使用同一公钥契约。
6. 保持已有时间窗口、nonce 防重放、challenge 单次使用和请求体原始字节哈希规则。
7. Java 自动测试必须使用第 5 节三组向量进行成功验签，并增加原文篡改、签名篡改和错误公钥的失败用例。

## 7. iOS App 实现要求

1. 安装身份模块不将私钥、完整 Token、签名请求头或请求体写入日志。
2. 签名前完成请求体唯一一次序列化，签名和发送复用完全相同的 `Data`。
3. 设备时间与服务端窗口不同步时，向用户显示可理解的系统时间校正提示。
4. 只在服务端明确返回 credential 失效或本地密钥不可用时重建安装身份，不因临时网络错误重置密钥。

## 8. Java 实现结果

Java 已按本文件完成实现，供 iOS 端据此接入：

- OpenAPI 版本：`2.10.0`。
- 已放行 Bundle ID：`com.qingjing.bizhi`、`com.qingjing.livephotolab`。
- 已完成设备注册、challenge、会话创建和敏感请求验签。
- challenge 固定返回 `algorithm=ECDSA_P256_SHA256`。
- 第 5 节注册、会话、敏感请求三组测试向量已全部验证通过。
- 当前 Java 提交：`80f8432`；代码已提交但尚未部署，部署完成后 iOS 才能进行真机联调。

接口前缀为 `/api/v1`，iOS 端按以下顺序调用：

1. `POST /device/registrations`：注册安装公钥并取得 `credentialKeyId`。
2. `POST /device/session-challenges`：使用 `credentialKeyId` 申请一次性 challenge。
3. `POST /device/sessions`：签署 challenge，取得短期 Bearer `accessToken`。
4. 调用敏感业务接口时携带 Bearer Token、`X-Request-Timestamp`、`X-Request-Nonce` 和 `X-Request-Signature`；当前包括 `POST /device/redemptions` 与 `POST /device/wallpapers/{wallpaperId}/download-tickets`。
5. Live Photo 下载票据签发后，分别请求 `GET /delivery/live-photo/image` 和 `GET /delivery/live-photo/video`，再将配套 HEIC 与 MOV 保存为系统 Live Photo。

主要错误码：`DEVICE_PROVIDER_NOT_ALLOWED`、`CREDENTIAL_INVALID`、`PROOF_INVALID`、`TIMESTAMP_INVALID`、`REQUEST_NONCE_REUSED`、`CREDENTIAL_NOT_FOUND`、`CREDENTIAL_REVOKED`、`CHALLENGE_INVALID`、`SESSION_EXPIRED`、`SIGNED_REQUEST_INVALID`、`REQUEST_SIGNATURE_INVALID`、`RATE_LIMITED`。
