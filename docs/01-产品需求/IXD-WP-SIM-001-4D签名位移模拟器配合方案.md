# 4D 签名位移模拟器配合方案

**日期：** 2026-09-15

**对接对象：** Web 4D 模拟器、Android 壁纸播放端

**项目文件：** [`wallpaper-tools/parallax-studio/index.html`](../../wallpaper-tools/parallax-studio/index.html)

**实施状态：** Web 模拟器 v2 已于 2026-09-16 纳入项目维护；API 透传改造另行实施。

## 1. 确认后的核心模型

4D 效果只由手机倾斜和每层的签名位移百分比决定。

- `offsetPercent > 0`：图层跟随手机倾斜方向。
- `offsetPercent < 0`：图层向手机倾斜的反方向移动。
- `offsetPercent = 0`：图层保持不动。
- `maxAngle`：达到配置位移百分比所需的倾斜角度；从中立位置的 0° 变化开始线性响应，没有启动死区。

屏幕坐标中，设当前倾斜归一化结果为 `x/y`，范围为 `-1～1`：

```text
x = clamp(左右倾斜角 / maxAngle, -1, 1)
y = clamp(上下倾斜角 / maxAngle, -1, 1)

水平位移 = x × 屏幕宽度 × offsetPercent / 100
垂直位移 = y × 屏幕高度 × offsetPercent / 100
```

二层默认值：前景 `+8%`，背景 `-3%`。手机左倾/下倾时，前景左移/下移，背景右移/上移。

## 2. 多层编辑交互

保留现有图层列表、图层顺序、缩略图和实时预览。将当前「景深 Depth」改为「位移 Offset」：

- 滑块以 0 为中心，可输入负数、0 和正数。
- v2 建议可视滑块范围为 `-15%～+15%`，数值框允许 `-25%～+25%`，步进 `0.1%`。
- 负数区显示「反向」，0 显示「固定」，正数区显示「跟随」。
- 图层编号只决定绘制顺序：`01` 为最前景，最后一层为完整背景。模拟器不根据编号猜测运动方向。
- 新增二层项目时自动填入 `+8% / -3%`。新增多层项目可给出起始值，但必须允许制作人逐层调整。
- `Scale`、`Opacity`、`BlendMode` 继续负责构图和图层合成，不参与运动方向判断。

预览区的鼠标/触摸拖动必须与 Android 使用同一坐标约定，并提供中立位置复位。

## 3. v2 导出格式

```json
{
  "formatVersion": 2,
  "canvas": { "width": 2048, "height": 2048 },
  "motion": { "maxAngle": 75 },
  "layers": [
    { "index": 1, "offsetPercent": 8, "scale": 1.18, "opacity": 1, "blendMode": "normal" },
    { "index": 2, "offsetPercent": 5, "scale": 1.18, "opacity": 0.25, "blendMode": "screen" },
    { "index": 3, "offsetPercent": 0, "scale": 1.18, "opacity": 1, "blendMode": "normal" },
    { "index": 4, "offsetPercent": -3, "scale": 1, "opacity": 1, "blendMode": "normal" }
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

1. 二层配置 `+8/-3`，水平和垂直四个方向的前后景均相反。
2. 任一层从正数拖到负数时，预览位移方向立即反转；设为 0 时固定。
3. 同一配置在 Web 预览和 Android 真机上的图层顺序、方向和最大位移一致。
4. 导出 ZIP 后的 `config.json` 与工程当前参数逐项一致，不在导出时转换为 `depth/strength/responseCurve`。
5. 背景预览使用与其最大位移相匹配的显示放大，不修改源图字节或降低分辨率。
