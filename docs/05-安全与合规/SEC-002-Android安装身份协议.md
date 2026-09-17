# SEC-002 Android 安装身份协议

版本 1.0.0，2026-09-14，WP-A03 实施设计；API 兼容扩展目标版本 1.1.0。实现和验收进度以 PM-002 为准。

## 身份与恢复边界

Android 通过 AndroidKeyStore 生成 RSA 2048 安装密钥，私钥不导出，签名操作在原生工作线程完成。服务端验证持钥证明，不发 H5 secret。此协议证明同一安装私钥的持有，不证明物理设备唯一性、安装包来源或硬件安全级别；不把自签公钥称为 Google/厂商远程认证。硬件能力记录按真机 KeyInfo 结果，不能依据模拟器宣称 TEE/StrongBox。

普通重启/升级沿用安装密钥。清数据或卸载使凭据丢失，重新生成密钥即新设备身份，不能依据本地 ID、ANDROID_ID、复制证据或客服文案自动迁移旧权益。仅服务端验证同一公钥的新持钥证明可恢复 credentialKeyId。此阶段不支持跨安装自动找回，不绕过 MySQL 的权益事实。

## 注册与签名

现有 registrations 路由、PLATFORM_PUBLIC_KEY 类型和 publicKeyPem 字段复用，算法新增 RSA_SHA256（SHA256withRSA / PKCS#1 v1.5），挑战及签名请求现有载荷格式保持不变。Android scope 按环境允许列表固定：prod 为 `com.qingjing.bizhi`，Lab 为 `com.qingjing.bizhi.lab`，local/internal 分别为 `com.qingjing.bizhi.local`、`com.qingjing.bizhi.internal`；生产禁用 H5。scope 为协议命名空间，不将客户端传入的 scope 当作 APK 签名验证。

publicKeyPem 为 X.509 SubjectPublicKeyInfo RSA 2048 / exponent 65537 公钥。evidenceToken 为 Base64URL 无填充 UTF-8 JSON，字段 timestamp（规范 UTC Instant）、nonce（UUID）、proof（Base64URL 无填充 RSA 签名）。注册签名 UTF-8 载荷：

```
QJ-ANDROID-REGISTER-V1
{appInstallScope}
{SHA-256 of DER public key, lowercase hex}
{timestamp}
{nonce}
```

无末尾换行。时间允许偏差沿用服务端 5 分钟；验签后用 Redis 单次 nonce 拒绝短窗重放。device evidence_hash 使用服务端 keyed hash(scope + 公钥指纹)，不基于可变签名。相同公钥再次持钥注册返回同一 ACTIVE credential，不撤销或替换现有凭据；已禁用设备/已撤销凭据拒绝，不通过重新注册复活。

会话挑战为一次性，服务端按公钥验签；敏感请求以现有方法、规范路径、时间、新 nonce 与准确 body SHA-256 载荷验签。会话续期在 Flutter 合并并发，只在 401 后自动续期一次，不吞掉永久拒绝或无限重试。服务端每次查询会话仍校验凭据/设备活动状态。

## 存储与兼容

数据库现有 device_credential 已支持 public_key_pem/PLATFORM_PUBLIC_KEY，无新增事实字段，不修改 Flyway V1、不伪造 V2。同步更新 DM/DB 的身份语义、OpenAPI/DTO、消费者枚举和版本快照；保留 1.0.1 历史快照。H5 的 HMAC 协议和测试范围保持兼容。

客户端只持久化 credentialKeyId 及后续原意图非秘密字段；会话 Token 仅内存。私钥、proof、evidenceToken、兑换码与 Token 不进日志/Git。局域网/adb 明文仅本地 debug；非调试 HTTPS 不降级。

参考：[Android Keystore](https://developer.android.com/privacy-and-security/keystore)、[KeyGenParameterSpec](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec)。RSA 密钥用途的签名与未来资源内容密钥解密分别受 Keystore 参数限制，资源解密协议在 A05 冻结，当前不声称已完成安全交付。
