# 壁纸制作工具

## 4D 视差模拟器

[`parallax-studio/index.html`](parallax-studio/index.html) 是项目维护的离线 4D 壁纸制作工具，可直接用浏览器打开。它负责分层素材导入、逐层方向与位移比例配置、手机预览、工程保存和 v2 壁纸 ZIP 导出。

- `offsetPercent` 直接表示满幅角度下的屏幕轴长百分比；`100%` 移动一整屏，`200%` 移动两屏，只要求是非负有限数值。
- `direction` 单独选择 `follow`、`reverse` 或 `fixed`。
- `0%` 或 `fixed` 均不移动。
- `maxAngle` 支持 `1°～75°`，从中立位置开始线性响应。
- 正式导出保留源图像素尺寸和原始图片字节，不静默降分辨率。

运行自动检查：

```bash
node wallpaper-tools/parallax-studio/test.mjs
```

工具只生成配置和图片资源，不能向资源包注入可执行代码。
