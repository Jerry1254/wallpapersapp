# IXD-WP-SIM-001 4D Web 模拟器配合方案

## 目标

Web 模拟器是 4D 效果参数的制作工具。制作人员导入分层图片，逐层调整运动与构图，在浏览器里预览后导出正式 v2 ZIP。正式 App 与模拟器执行相同字段和公式，API 不计算动画参数。

## 当前参数

全局参数位于 `motion`：

- `maxAngleX`：水平满幅角度，`1～75°`，默认 `75°`。
- `maxAngleY`：垂直满幅角度，`1～75°`，默认 `75°`。

每层参数：

- `direction`：`follow`、`reverse`、`fixed`。
- `offsetXPercent`：满幅水平运动强度，非负、无产品上限；100 为一屏宽。
- `offsetYPercent`：满幅垂直运动强度，非负、无产品上限；100 为一屏高。
- `initialOffsetXPercent`：中立姿态水平位置，可正负、无产品上限。
- `initialOffsetYPercent`：中立姿态垂直位置，可正负、无产品上限。
- `scale`、`opacity`、`blendMode`：显示缩放、不透明度与混合模式。

滑块用于快速调整，数字框允许直接输入更大的有限数。运动强度超过当前滑块范围时，上限扩展到下一个整百；初始位置按绝对值扩展为对称范围。参数变化立即反映在预览中，保存时整体写入配置。

## 预览与导出

预览按以下规则计算：

```text
progressX = clamp(yaw / maxAngleX, -1, 1)
progressY = clamp(pitch / maxAngleY, -1, 1)
x = initialOffsetXPercent - directionSign * progressX * offsetXPercent
y = initialOffsetYPercent + directionSign * progressY * offsetYPercent
```

实际像素再分别乘屏幕宽、高。前景允许移出屏幕；背景按初始位置和最大运动量自动补足保护缩放。导出保留原始图片字节和分辨率，不做静默降采样。

正式导出配置：

```json
{
  "formatVersion": 2,
  "canvas": { "width": 2048, "height": 2048 },
  "motion": { "maxAngleX": 75, "maxAngleY": 75 },
  "layers": [
    { "index": 1, "offsetXPercent": 15, "offsetYPercent": 10, "initialOffsetXPercent": 0, "initialOffsetYPercent": 0, "direction": "follow", "scale": 1.18, "opacity": 1, "blendMode": "normal" },
    { "index": 2, "offsetXPercent": 3, "offsetYPercent": 2, "initialOffsetXPercent": 0, "initialOffsetYPercent": 0, "direction": "reverse", "scale": 1, "opacity": 1, "blendMode": "normal" }
  ]
}
```

## 旧测试工程导入

正式 App 和正式导出只接受当前 v2。Web 模拟器额外支持导入旧 `formatVersion=1` 文件夹，目的是找回测试素材：

1. `sensor.maxAngle` 同时迁移到 `maxAngleX/maxAngleY`。
2. 旧 `depth × strength × responseGain` 换算为水平和垂直运动强度。
3. 初始位置默认为 0；强度为 0 的层迁移为 `fixed`，其他层迁移为 `follow`。
4. 导入后明确提示“已转换”，工程标记为未保存；制作人员检查效果后保存或导出新的 v2。

这是制作工具的导入迁移，不是 Android 运行时兼容路径。
