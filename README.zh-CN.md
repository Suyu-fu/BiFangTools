<p align="center">
  <strong>简体中文</strong> · <a href="README.md">English</a>
</p>

<p align="center">
  <img src="artwork/bifangs-bag-of-curiosities-comic.png" alt="Q 版 BiFang 正在翻找小包" width="224">
</p>

<h1 align="center">哔方的妙妙工具 · BiFang Tools</h1>

<p align="center">
  <img src="https://img.shields.io/badge/Minecraft%20Java-26.1-397348" alt="Minecraft Java 版 26.1">
  <img src="https://img.shields.io/badge/NeoForge-Client-b4502d" alt="NeoForge 客户端模组">
  <a href="#许可证"><img src="https://img.shields.io/badge/license-MIT%20%7C%20CC%20BY--NC--SA%204.0-green" alt="代码采用 MIT，素材采用 CC BY-NC-SA 4.0"></a>
</p>

<p align="center">
  <a href="#快捷键">快捷键</a> ·
  <a href="#配置">配置</a> ·
  <a href="CHANGELOG.md">版本日志</a> ·
  <a href="https://github.com/Suyu-fu/BiFangTools/releases">下载发布</a> ·
  <a href="https://github.com/Suyu-fu/BiFangTools/issues">问题反馈</a>
</p>

**哔方的妙妙工具（BiFang Tools）** 是一款纯客户端实用模组。`26.x` 分支面向 **Minecraft Java 26.1（NeoForge）**，使用 Java 25。将对应 Minecraft 版本的 JAR 放入客户端 `mods` 文件夹即可使用。

## 工具

| 工具 | 功能 |
| --- | --- |
| **引路丝（Waythread）** | 探险时记录走过的路径，沿粒子轨迹原路返回起点，支持跨维度追踪。 |

### 引路丝机制简介

- **记路与返程**：标记起点（默认为带亮色外框的浮空立方体）并沿途留下荧光轨迹；切换至返程模式后，沿记录路径回溯并逐步消除已走过的点。
- **跨维度衔接**：支持跨维度记录分段路线，返程时按去程逆序逐段回溯。路线显示与寻路仅读取当前维度，记录点数上限和返程进度则在所有维度间共用。
- 当前跨维度中继点会显示为随路线配色的光环小球。普通返程跨维度后若未自动接回，靠近原路线后按 **K** 确认。
- 重生后靠近任一记录点按 **K**，即可从该处沿路线前往死亡地点。接入距离沿用 `returnEraseDistance`，默认 2 格。跨维度后若停在上一段，靠近下一相连路段入口的中继点，再按 **K** 继续。
- **单人进度保存**：单人世界的路线与返程进度会保存在客户端配置目录中，重新进入同一世界即可恢复。

## 快捷键

| 按键 | 操作 |
| --- | --- |
| **J**（短按） | 标记起点并开始记路；等待接回时，从当前位置另起一段。 |
| **K** | 在记路与返程模式之间切换；等待接回时，靠近原路线按下即可接回。 |
| **J**（长按 2 秒） | 清除整条路线。 |
| **Ctrl+J** | 显示或隐藏路线粒子。 |

隐藏粒子时仍可正常记路与返程。重新启动客户端后，粒子默认恢复显示。可在「选项 → 控制 → 按键绑定」自定义快捷键。

## 配置

在主菜单打开「模组 → BiFang Tools → 配置 → 引路丝」，也可以直接编辑游戏目录中的 `config/waythread-client.toml`。

设置界面分为「路线」「起点」「记录与返程」三个页签，常用的显示距离、粒子间距与数量上限位于「路线」页签。

界面支持简体中文、繁體中文（台灣）、English (US)、日本語、한국어、Español（España / México）和 Português (Brasil)，可在 Minecraft 的「选项 → 语言」切换。

<details>
<summary>完整配置表</summary>

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `loadDistance` | 40 格 | 在玩家周围多远范围内生成并显示路段粒子。可设 4–64 格。 |
| `recordingMode` | 按行走距离 | 可按实际行走距离（`TRAVELED_DISTANCE`）或距上一个记录点的直线距离（`DIRECT_DISTANCE`）记录。 |
| `recordPointSpacing` | 5 格 | 记录新点所需的距离，按所选记录方式计算。可设 2–10 格。 |
| `maxRecordedPoints` | 8,192 点 | 跨维度共用同一上限；起点计入总数，路段共用的端点只计一次。达到上限时暂停新增记录并提示一次。可设 1,024–16,384 点。调低上限时保留当前路线，直到返程消点或手动清除。 |
| `returnEraseDistance` | 2 格 | 返程时匹配该范围内最近的记录点，截去其后的路线；匹配到起点时结束返程。可设 1–16 格。 |
| `displayMode` | 轨迹线 | `LINE` 显示连续轨迹，`WAYPOINTS` 只标记记录点。两种方式都支持起点标记与移动的指路亮点。 |
| `particleStyle` | 荧光 | 路线粒子可选细尘（`DUST`）、电火花（`SPARK`）、荧光（`GLOW`）和薄雾（`MIST`）。起点选用粒子外观时也使用该样式。 |
| `trailParticleSpacing` | 0.7 格 | 轨迹线粒子之间的间距，可设 0.1–2 格。 |
| `trailParticleLimit` | 无上限 | 每次刷新最多绘制的路线粒子数，可设 1–2,048 个。滑块最右侧为「无上限」，对应配置值 `0`。 |
| `trailColor` | `#38E3FF` | 路线与方向亮点的颜色，使用 `#RRGGBB` 格式。 |
| `anchorFollowsTrailColor` | 开启 | 起点标记与路线使用相同的颜色。 |
| `anchorColor` | `#FFB036` | 关闭颜色同步时使用的起点颜色，格式为 `#RRGGBB`。 |
| `anchorAppearance` | 浮空立方体 | 起点可选粒子（`PARTICLES`）、浮空球（`ORB`）和浮空立方体（`CUBE`）。浮空模型带有外框，其中浮空立方体为亮色外框。 |
| `anchorSeeThrough` | 关闭 | 隔着方块透视显示起点标记本身。 |
| `anchorSeeThroughDistance` | 512 格 | 开启起点透视时的最远显示距离，可设 4–4,096 格。路线仍使用自身的加载距离，远处的起点标记会保留可辨识的大小。 |
| `showRecordingDirection` | 开启 | 记路时显示朝路线末端移动的方向亮点。 |
| `showReturnProgress` | 开启 | 返程时显示进度条。 |
| `showReturnPercentage` | 开启 | 显示本次返程已消除的记录点比例。 |
| `returnProgressFontSize` | 7 px | 返程百分比的文字大小，可设 5–16 个游戏界面像素，随 Minecraft 界面缩放。 |
| `showArrivalMessage` | 开启 | 返程到达起点时提示「到达起点」，并播放一声经验球音效。 |
| `clearOnArrival` | 开启 | 返程结束后清除起点标记。关闭后保留起点标记，短按 **J** 可从当前位置重新记路。 |

</details>

## 致谢

作者 **BiFang**。引路丝的灵感来自 [AtomicStryker 的 Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn)。模组 ID 为 `bifangtools`。

## 许可证

- 代码采用 MIT，允许商用、修改和再分发，分发时须保留版权与许可声明。[代码许可证](LICENSE)
- 封面和模组图标采用 CC BY-NC-SA 4.0，允许署名后非商业分享和改编。发布改编图像时须标明改动并沿用相同许可。分享含有这些图像的模组包时，也须遵守素材许可。[素材许可证](LICENSE-ASSETS)
