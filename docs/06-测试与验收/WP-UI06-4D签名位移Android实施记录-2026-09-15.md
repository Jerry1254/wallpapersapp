# WP-UI06 4D 方向与强度 Android 实施记录

**日期：** 2026-09-15

**结论：** Android 只接受当前 v2 配置，方向与 `0～100%` 强度解析和绘制已完成，旧带符号位移及 v1 `depth/strength` 路径不再接受。API 透传和管理后台改造仍是独立任务。

## 已实施

- Android 仅识别 `formatVersion=2` 配置，源图层从 `01` 最前景到最后背景，运行时自动反转为从背景向前景绘制。
- 每层使用 `offsetPercent` 强度和独立 `direction`。强度范围 `0～100%`；`follow` 跟随、`reverse` 反向、`fixed` 固定。100% 在满幅角度时移动对应屏幕轴长的 15%。
- `motion.maxAngle` 使用线性归一化，从中立位置的 0° 变化开始响应，最大支持 75°。v2 的传感器平滑固定在 App 内，不再暴露 `strength/responseCurve/responseGain`。
- 最后背景根据强度换算后的实际位移计算最低显示放大，保证满幅时仍覆盖屏幕。前景保留透明画布，按配置位移后显示下方图层。
- 正式图层继续按源分辨率 ARGB_8888 解码，`inSampleSize=1`，禁用密度缩放，不静默降低清晰度。
- 安全包安装器可验证 v2 配置与实际图层 role/ordinal、尺寸和透明通道一致。这是客户端运行安全检查，不要求 API 复制算法校验。

## 测试阶段收口

产品尚未正式上线，不保留旧协议兼容。未带 `formatVersion=2` 的配置会明确失败；现有 v1 测试包和数据由 API/DB 改造时清理。

## 验证

```text
./gradlew :wallpaper_android:testDebugUnitTest \
  --tests com.qingjing.wallpaper_android.playback.ParallaxMotionTest \
  --tests com.qingjing.wallpaper_android.playback.ParallaxConfigurationTest \
  --tests com.qingjing.wallpaper_android.install.SecurePackageVerifierTest
```

结果：`BUILD SUCCESSFUL`，27 项通过，0 失败、0 错误、0 跳过。覆盖 v2 前后图层顺序、方向切换、0%/固定语义、100% 最大安全位移、背景满幅不露边、75° 满幅、旧配置拒绝和签名包安装。

本轮未重新打包正式 App，因为当前 API 仍会将源配置改写为旧内部格式；真机 v2 对比应在 Web/API 完成改造后使用同一 ZIP 执行，不用旧包代替新链路证据。

## 交接

- [Web 模拟器配合方案](../01-产品需求/IXD-WP-SIM-001-4D签名位移模拟器配合方案.md)
- [API 与管理后台改造方案](../04-接口设计/API-008-4D配置透传与管理后台改造方案.md)
- [v2 JSON Schema](../../packages/wallpaper-format/parallax-source-v2.schema.json)
