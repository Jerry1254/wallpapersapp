# SEC-004 Android 受限试用交付协议

版本：1.3.0。WP-A09 实现输入；真机行为见实施记录。

## 权限与用途

未获得权益时允许三类 App 内 120 秒实际效果试用。试用不创建设备权益、不扣兑换额度，不进入系统壁纸设置，不导出正式资源。本文最初规定的降分辨率、转码和水印媒体策略已被 1.0 产品决定替代：当前详情预览按 [API-010](../04-接口设计/API-010-1.0原资源详情预览Java交接说明.md) 使用正式版本绑定的原始 payload。APP_PREVIEW 的短时票据、用途隔离、独立加密包、临时存储和不授予权益等安全边界继续有效。原资源在用户控制的设备上解密显示后仍可能被专业逆向提取，不宣称 DRM 或绝对防复制。

正式包继续使用 [SEC-003](SEC-003-Android安全资源交付协议.md) format 2，正式权益和兑换规则保持。已获得入口读取正式资源；服务端受限票据仍只允许受限字节，不具备正式授权。

## 签名与加密

受限包前 8 字节为 ASCII `QJPV0001`，随后 12 字节 nonce、AES-256-GCM 密文和 16 字节 tag。明文 ZIP 结构、原始清单签名、严格解压边界、文件角色与实际媒体校验沿用正式包规则，清单额外且必须具有 `purpose: APP_PREVIEW`，`formatVersion: 3`。清单签名用构建固定的 RSA-2048 信任根、SHA256withRSA；不接收包内公钥。

AES-GCM AAD 为 UTF-8，下列各项以换行分隔，末尾无额外换行：

~~~text
QJ-PREVIEW-V1
{wallpaperId}
{variantId}
{versionNo}
{resourceType}
~~~

内容密钥按当前安装的独立 RSA 解密公钥包装，算法仍为 `RSA-OAEP-SHA256-MGF1-SHA1`。设备私钥、解包路径和明文内容密钥不返回 Dart；使用后清零临时 AES 数组。格式/AAD/清单用途均由可信原生入口选择，不能由描述字段放宽。正式安装与持久化校验器拒绝试用包；试用校验器拒绝正式包及错误用途。

## API 与数据

`POST /device/wallpapers/{wallpaperId}/preview-tickets` 需要 Android 设备短期会话和精确正文持钥签名，含 timestamp、nonce、signature。平台、安装允许 scope、解密公钥、作品/版本发布状态和兼容变体来自服务端事实。响应模式和用途为 APP_PREVIEW，durationSeconds 为 120，package.formatVersion 为 3；resourceVersion.manifestSha256 对应预览清单。无兼容预览包返回 422 PREVIEW_RESOURCE_NOT_READY。

`GET /preview/files` 固定路径，票据只在 Authorization Bearer 中，不进入查询参数。90 秒票据仅控制下载窗口，不等于预览计时；响应 application/octet-stream、长度、Digest、Cache-Control:no-store。Redis key 使用独立 `preview-ticket-v1:` 加 HMAC，禁止与 `download-ticket-v2:` 互用。创建限流 6 次/设备/分钟，读取限流 12 次/设备/分钟，活跃流最多 4。开始实际读取前再次判断票据、安装公钥/凭据、设备、scope 和发布状态；失效返回 401 PREVIEW_TICKET_INVALID。已开始的流可完成，客户端仍须按 120 秒限制使用。

Flyway V3 新增独立 `preview_resource_package`，保存受限包自身摘要、大小、签名 key ID、StorageKey 和加密内容密钥，密钥保护域为 `preview-package-key-v1:{resourceVersionId}`。MySQL 保存资源事实，Redis 只保存可丢失票据/限流，不记录权益。制作新版本时正式/受限包事务成对提交，回滚清理本次两个对象；补建历史已包装版本的受限包保留正式字节与清单。V1/V2 不修改。

## Android 生命周期

受限资源仅安装在独立私有临时库 `wallpaper-app-preview-v1`，不登记正式库或 live 服务引用；Flutter 仅取得随机 trialId。私有 NativeTrialActivity 只实现查看与结束，不包含系统设置或导出入口。

第一张图片实际绘制、视频首帧回调或 4D 首帧成功提交后，按 `SystemClock.elapsedRealtime()` 固定 120 秒截止时间。该时钟计入休眠；后台停播并释放图片、播放器、传感器和临时使用租约，计时继续。恢复不会重置截止时间；持久化原始起点/截止/BOOT_COUNT，进程重建继续剩余时间。检测设备重启、时钟回退、未知启动编号或超过未开始素材 5 分钟准备窗口时放弃旧试用。

到期、提前结束、准备取消或打开失败清除会话与资源保留标记。删除等待渲染器/解码任务的租约释放后执行，避免仍在使用时删文件；进程重建清理未完成暂存和无有效会话资源。Android 没有调低时长的测试开关；真机验收须实际等待 120 秒。运行时文件和凭据不得进入 Git。
