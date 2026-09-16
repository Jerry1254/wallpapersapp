# 4D 壁纸工具

`parallax-studio/index.html` 是离线 Web 模拟器。它维护当前 v2 配置：全局 `maxAngleX/maxAngleY`，每层独立 `offsetXPercent/offsetYPercent`、`initialOffsetXPercent/initialOffsetYPercent`、方向、缩放、不透明度和混合模式。

- 100% 分别表示一屏宽或一屏高；运动与初始位置不设置产品上限。
- 正式图片保持原始分辨率与字节，不做静默降采样。
- 正式保存与导出只生成 v2。
- 旧 `formatVersion=1` 测试文件夹可在导入阶段一次性转换，转换后必须检查效果并重新保存。

运行检查：

```bash
node wallpaper-tools/parallax-studio/test.mjs
```
