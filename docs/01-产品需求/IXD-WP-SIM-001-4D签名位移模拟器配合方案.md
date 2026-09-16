# 4D 方向与位移比例模拟器配合方案

**日期：** 2026-09-16

**对接对象：** Web 4D 模拟器、Android 壁纸播放端

**项目文件：** [`wallpaper-tools/parallax-studio/index.html`](../../wallpaper-tools/parallax-studio/index.html)

**实施状态：** Web 与 Android 的 v2 方向/位移比例模型已同步；API 透传改造另行实施。

## 1. 核心模型

倾斜角度决定动画进度，每层的方向和位移比例决定终点：

- `direction=follow`：跟随手机倾斜方向。
- `direction=reverse`：向手机倾斜的反方向移动。
- `direction=fixed`：固定不动。
- `offsetPercent`：满幅角度下相对屏幕轴长的位移百分比，只要求是非负有限数值，不设置产品上限。
- `maxAngle`：达到该层最大位移所需的倾斜角度，支持 `1°～75°`。
- 屏幕对应轴长记为 `100%`：`10%` 移动 `0.1` 屏，`100%` 移动一整屏，`200%` 移动两屏；前景允许移出屏幕。

设倾斜输入和方向符号为：

```text
inputX = clamp(左右倾斜角 / maxAngle, -1, 1)
inputY = clamp(上下倾斜角 / maxAngle, -1, 1)
directionSign = follow ? 1 : reverse ? -1 : 0
travel = offsetPercent / 100

水平位移 = inputX × 屏幕宽度 × travel × directionSign
垂直位移 = inputY × 屏幕高度 × travel × directionSign
```

坐标正负由 Web 与 Android 的同一约定处理。以满幅角度 `75°`、位移比例 `100%` 为例：`0°` 位移为 0，`37.5°` 移动半屏，`75°` 移动一整屏。

二层默认值：前景 `8% / follow`，背景 `3% / reverse`。这是初始构图值，制作人员可以继续输入 `100%`、`200%`、`300%` 或其他非负数。

## 2. 多层编辑交互

每个图层单独提供：

- 「运动方向」：跟随、反向、固定。
- 「位移比例」：从 `0%` 起，步进 `1%`，数值输入不设产品上限；滑块会随当前数值自动扩展范围。
- `0%` 或固定方向均不移动。
- 图层编号只决定绘制顺序：`01` 为最前景，最后一层为完整背景；程序不根据编号推断运动方向。
- `Scale`、`Opacity`、`BlendMode` 只负责构图与合成。

预览区鼠标/触摸拖动、角度滑块和回正操作必须与 Android 使用同一计算规则。

## 3. v2 导出格式

```json
{
  "formatVersion": 2,
  "canvas": { "width": 2048, "height": 2048 },
  "motion": { "maxAngle": 75 },
  "layers": [
    { "index": 1, "offsetPercent": 15, "direction": "follow", "scale": 1.18, "opacity": 1, "blendMode": "normal" },
    { "index": 2, "offsetPercent": 5, "direction": "follow", "scale": 1.18, "opacity": 0.25, "blendMode": "screen" },
    { "index": 3, "offsetPercent": 0, "direction": "fixed", "scale": 1.18, "opacity": 1, "blendMode": "normal" },
    { "index": 4, "offsetPercent": 3, "direction": "reverse", "scale": 1, "opacity": 1, "blendMode": "normal" }
  ]
}
```

机器可读 Schema：[`parallax-source-v2.schema.json`](../../packages/wallpaper-format/parallax-source-v2.schema.json)。

ZIP 结构保持不变：

```text
wallpaper.zip
├── cover.jpg
├── config.json
└── layers/
    ├── 01.png
    ├── 02.png
    └── NN.jpg/png/webp
```

## 4. 一致性验收

1. `100% / follow` 在满幅角度移动一整屏，`200%` 移动两屏，`10%` 移动 `0.1` 屏；半角度产生一半配置位移，0° 位移为 0。
2. 同一强度从 follow 切换为 reverse 时立即反向；切换 fixed 或设为 0% 时不移动。
3. 同一配置在 Web 和 Android 上的图层顺序、方向和最大位移一致。
4. 导出配置保留 `offsetPercent` 和 `direction`，不转换成 `depth/strength/responseCurve`。
5. 背景按实际位移自动补足显示边缘，不修改源图字节或降低分辨率。
