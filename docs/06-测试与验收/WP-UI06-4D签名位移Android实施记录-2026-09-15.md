# WP-UI06 4D 签名位移 Android 实施记录

**日期：** 2026-09-15

**结论：** Android 新 v2 解析和绘制实现完成，旧 v1 资源保留兼容。Web 模拟器、API 透传和管理后台改造已输出独立实施方案，本记录不把尚未实施的端到端 v2 链路写成已联调。

## 已实施

- Android 识别 `formatVersion=2` 配置，源图层从 `01` 最前景到最后背景，运行时自动反转为从背景向前景绘制。
- 每层使用签名 `offsetPercent`：正数沿手机倾斜方向，负数反向，0 保持不动。水平与垂直分别以屏幕宽度和高度计算百分比。
- `motion.maxAngle` 使用线性归一化，从中立位置的 0° 变化开始响应，最大支持 75°。v2 的传感器平滑固定在 App 内，不再暴露 `strength/responseCurve/responseGain`。
- 最后背景根据 `abs(offsetPercent)` 计算最低显示放大，保证最大反向位移时仍覆盖屏幕。前景保留透明画布，按配置位移后显示下方图层。
- 正式图层继续按源分辨率 ARGB_8888 解码，`inSampleSize=1`，禁用密度缩放，不静默降低清晰度。
- 安全包安装器可验证 v2 配置与实际图层 role/ordinal、尺寸和透明通道一致。这是客户端运行安全检查，不要求 API 复制算法校验。

## 兼容策略

未带 `formatVersion` 的现有 Android 内部 `depth/strength` 配置继续使用旧路径，不修改已发布资源的动画和签名内容。新作品由 Web 模拟器导出 `formatVersion=2`。

## 验证

```text
./gradlew :wallpaper_android:testDebugUnitTest \
  --tests com.qingjing.wallpaper_android.playback.ParallaxMotionTest \
  --tests com.qingjing.wallpaper_android.playback.ParallaxConfigurationTest \
  --tests com.qingjing.wallpaper_android.install.SecurePackageVerifierTest
```

结果：`BUILD SUCCESSFUL`，29 项通过，0 失败、0 错误、0 跳过。覆盖 v2 前后图层顺序、正负位移反向、0 固定语义、背景最大位移不露边、75° 满幅、配置拒绝边界和签名包安装；同时回归旧 v1 配置。

本轮未重新打包正式 App，因为当前 API 仍会将源配置改写为旧内部格式；真机 v2 对比应在 Web/API 完成改造后使用同一 ZIP 执行，不用旧包代替新链路证据。

## 交接

- [Web 模拟器配合方案](../01-产品需求/IXD-WP-SIM-001-4D签名位移模拟器配合方案.md)
- [API 与管理后台改造方案](../04-接口设计/API-008-4D配置透传与管理后台改造方案.md)
- [v2 JSON Schema](../../packages/wallpaper-format/parallax-source-v2.schema.json)
