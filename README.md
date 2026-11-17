# 哔方的妙妙工具 · BiFang's Bag of Curiosities (BBC)

Little tools for everyday Minecraft.

Minecraft Java 版 **1.21.1** 的 NeoForge 客户端模组。

## 安装

将 `build/libs/bifangs-bag-of-curiosities-0.5.1.jar` 放进客户端的 `mods` 目录。升级时先从 `mods` 移除旧版。

## 引路丝（Waythread）

记录走过的路线，沿粒子原路返回。灵感来自 [AtomicStryker 的 Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn)。

1. 短按 **J** 标记起点，走过的位置会留下贴地的粒子细线。
2. 按 **K** 切换返程。亮点沿线朝起点移动。走近任意记录点时，路线会截断到消除范围内最近的记录点。再按 **K** 恢复记录。
3. 长按 **J** 两秒清除路线。
4. 按 **Ctrl+J** 切换路线粒子的显示。隐藏时仍可记路和返程，重新启动客户端后默认显示。可在「选项 → 控制 → 按键绑定」更改按键。

模组沿实际移动的路线采样，默认每 5 格记录一个点，明显转折处会提前留点并封段。玩家靠近后，A* 才在该段两个端点之间、采样轨迹附近尝试生成可步行的粒子路线。未找到可步行路线时，粒子会沿采样点连线显示。传送前后分别记录。路线在退出世界、切换维度或重生后清除。

## 配置

在主菜单打开「模组 → BiFang's Bag of Curiosities → 配置 → 引路丝」，也可以编辑游戏目录中的 `config/waythread-client.toml`。

界面支持简体中文和英语（美国），在 Minecraft 的「选项 → 语言」切换。

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `loadDistance` | 18 格 | 在玩家周围多远开始生成并显示路段粒子。可设 4–64 格。 |
| `recordPointSpacing` | 5 格 | 沿实际轨迹累计移动多远后记录下一个位置。可设 2–10 格，旧配置超出范围时自动调整到最近的边界。 |
| `maxRecordedPoints` | 20,000 点 | 起点计入，路段共用的端点只计一次。达到上限时暂停新增记录并提示一次。可设 2–200,000 点。调低上限时保留当前路线，直到返程消点或手动清除。 |
| `returnEraseDistance` | 2 格 | 返程时匹配这个距离内最近的记录点，截去它之后的路线。匹配到起点时结束返程。可设 0.5–6 格。 |
| `particleStyle` | 细尘 | 路线粒子可选细尘（`DUST`）、电火花（`SPARK`）、荧光（`GLOW`）和薄雾（`MIST`）。 |
| `particleDensity` | 标准 | 路线粒子密度可选低（`LOW`）、标准（`STANDARD`）和高（`HIGH`）。 |
| `trailColor` | `#38E3FF` | 路线与方向亮点的颜色，使用 `#RRGGBB` 格式。 |
| `anchorColor` | `#FFB036` | 起点标记的颜色，使用 `#RRGGBB` 格式。 |
| `showRecordingDirection` | 开启 | 记路时显示朝路线末端移动的亮点。 |
| `showArrivalMessage` | 开启 | 返程到达起点时提示「已到达起点」，并播放一声经验球音效。 |
| `clearOnArrival` | 开启 | 返程结束后清除起点标记。关闭后保留起点标记，短按 J 可以从当前位置重新记路。 |

## 构建

使用 JDK 21，在项目根目录运行 `.\gradlew.bat build`（Windows）或 `./gradlew build`。用 `runClient` 启动开发客户端。

版本和未发布改动见 [开发日志](DEVLOG.md)。
