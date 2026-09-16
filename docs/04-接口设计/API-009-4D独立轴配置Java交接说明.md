# API-009 4D 独立轴配置 Java 交接说明

**日期：** 2026-09-16
**交接性质：** 兼容性核对；如果 API-008 已按不透明配置透传实现，本次无需修改 Java 业务代码。

## 1. 本次前端与 Android 变化

4D 配置仍为 `formatVersion=2`，接口地址、上传 ZIP 格式、数据表和管理后台操作均未改变。本次只调整 `config.json` 内由 Web 模拟器和 Android 共同解释的算法字段：

| 原字段 | 当前字段 | 说明 |
|---|---|---|
| `motion.maxAngle` | `motion.maxAngleX`、`motion.maxAngleY` | 水平、垂直达到满幅运动所需角度，默认均为 75° |
| `layers[].offsetPercent` | `layers[].offsetXPercent`、`layers[].offsetYPercent` | 每层水平、垂直运动强度 |
| 无 | `layers[].initialOffsetXPercent`、`layers[].initialOffsetYPercent` | 每层初始水平、垂直位置修正，可为负数 |

`direction`、`scale`、`opacity`、`blendMode` 和 `layers[].index` 保持不变。算法字段的范围、默认值、公式和显示效果由模拟器与 Android 负责，API 不参与解释。

当前示例：

```json
{
  "formatVersion": 2,
  "canvas": { "width": 2048, "height": 2048 },
  "motion": { "maxAngleX": 75, "maxAngleY": 75 },
  "layers": [
    {
      "index": 1,
      "offsetXPercent": 15,
      "offsetYPercent": 10,
      "initialOffsetXPercent": 0,
      "initialOffsetYPercent": -2,
      "direction": "follow",
      "scale": 1.18,
      "opacity": 1,
      "blendMode": "normal"
    },
    {
      "index": 2,
      "offsetXPercent": 3,
      "offsetYPercent": 2,
      "initialOffsetXPercent": 0,
      "initialOffsetYPercent": 0,
      "direction": "reverse",
      "scale": 1,
      "opacity": 1,
      "blendMode": "normal"
    }
  ]
}
```

## 2. Java 是否需要修改

当前工作区核对结果：

- `ParallaxPackageParser` 已只读取稳定结构，符合 API-008。
- `SecurePackagePublisher.validateParallax` 仍对 `motion` 和每层对象调用 `exactFields(...)`。如果 Java 已合入同样实现，则需要删除这两处算法字段精确白名单；根对象、`formatVersion`、`canvas` 和 `layers[].index` 的稳定结构检查继续保留。

因此，本次不是为新增字段扩展一遍白名单，而是确认上次的解耦是否彻底完成。若线上 Java 分支已经没有算法白名单，本次为零代码改动。

按以下条件核对：

| 当前实现 | 结论 |
|---|---|
| API 只读取 `formatVersion`、`canvas.width/height`、`layers[].index`，其余 JSON 原样保存和下发 | **无需修改** |
| Jackson DTO 为算法字段建立了固定属性，并开启未知字段失败 | **需要修改**：配置改为 `JsonNode` 或保留原始字节，不绑定算法字段 |
| 上传或正式包构建对 `motion`、单层对象执行字段精确白名单 | **需要修改**：删除算法字段白名单，只保留稳定结构校验 |
| API 会把源配置转换、删字段、补默认值或重新生成 Android 配置 | **需要修改**：停止转换，保存并交付原始 `config.json` |
| 管理后台仍提供旧配置示例 | Java 无需修改；只更新静态示例 JSON/ZIP |

这次不新增接口、不修改 OpenAPI DTO、不增加数据库字段，也不需要 Flyway 迁移。

## 3. API 应继续校验的稳定结构

API 继续负责：

1. ZIP 安全、CRC、文件数量、大小、路径和 MIME/图片解码检查。
2. 根目录 `cover.jpg`、`config.json`、`layers/` 的固定文件结构。
3. `formatVersion=2`。
4. `canvas.width/height` 与图层实际尺寸一致。
5. `layers[].index` 从 1 连续编号，并与 `layers/01...NN` 一一对应。
6. 前景透明通道、最后一层背景完全不透明以及 2～12 层限制。
7. 原始配置保存、版本绑定、签名加密、完整性校验和下发。

API 不应校验：

- `motion` 中有哪些算法字段；
- 每层除 `index` 外有哪些算法字段；
- 位移、初始位置、方向、缩放、不透明度、混合模式的取值或效果；
- Web 模拟器以后增加的曲线、增益或其他算法参数。

只要 `formatVersion` 没变，算法字段增加或调整不应要求 API 发版。如果配置语义发生不兼容变化，由模拟器和 Android 升级 `formatVersion`，API 再增加对新格式版本及其稳定结构的支持。

## 4. 原始字节要求

- 正式资源包中的 `PARALLAX_CONFIG` 与上传 ZIP 内 `config.json` 必须字节一致，SHA-256 相同。
- API 不排序 JSON、不格式化、不删除未知字段、不补算法默认值。
- 预览包如果缩放图层，可仅改写派生预览配置的 `canvas.width/height`；正式配置保持不变。

## 5. Java 自查与验收

建议 Java 开发仅执行以下核对：

1. 搜索 `maxAngle`、`offsetPercent`、`offsetXPercent`、`exactFields`、`FAIL_ON_UNKNOWN_PROPERTIES` 等固定字段依赖。
2. 用本说明中的 v2 ZIP 完成上传、绑定、正式包构建和下载。
3. 在一个图层中额外加入 API 未知算法字段，例如 `"futureCurve":{"type":"spring"}`；同一 `formatVersion=2` 下仍应完成上传与正式交付，并保持原始字节。
4. 删除 `canvas.width`、制造图层跳号或图片尺寸不一致，确认仍返回结构校验失败。
5. 对比上传配置与正式包配置 SHA-256；两者必须一致。

核对结论应为以下二选一：

- **无需修改：** 当前实现已经只校验稳定结构并原样透传。
- **需要一次边界修正：** 仍存在算法 DTO、字段精确白名单或配置重写；移除后，今后的同版本算法参数调整不再通知 Java 改代码。
