# 哔方的妙妙工具 · BiFang's Bag of Curiosities (BBC)

Little tools for everyday Minecraft.

Minecraft Java 版 **1.21.1** 的 NeoForge 客户端模组。

## 安装

将 `build/libs/bifangs-bag-of-curiosities-0.4.5.jar` 放进客户端的 `mods` 目录。升级时先从 `mods` 移除旧版。

## 引路丝（Waythread）

记录走过的路线，沿粒子原路返回。灵感来自 [AtomicStryker 的 Magic Yarn](https://github.com/AtomicStryker/atomicstrykers-minecraft-mods/tree/1.7.10/MagicYarn)。

1. 短按 **J** 标记金色起点，走过的位置会留下贴地的细青线。
2. 按 **K** 切换返程。亮点沿线朝起点移动，走近的路线会从末端逐点消除。再按 **K** 恢复记录。
3. 长按 **J** 两秒清除路线。可在「选项 → 控制 → 按键绑定」更改按键。

模组按实际移动顺序记录路径点，并在走过约 4.5 格或急转弯时结束当前路段。玩家靠近后，A* 才在该段两个端点之间、实际轨迹附近尝试生成可步行的粒子路线。未找到可步行路线时，粒子会沿实际采集的路径点显示。传送前后分别记录。路线在退出世界、切换维度或重生后清除。

## 配置

在主菜单打开「模组 → BiFang's Bag of Curiosities → 配置 → 引路丝」，也可以编辑游戏目录中的 `config/waythread-client.toml`。

界面支持简体中文和英语（美国），在 Minecraft 的「选项 → 语言」切换。

| 设置 | 默认值 | 说明 |
| --- | --- | --- |
| `loadDistance` | 18 格 | 在玩家周围多远开始生成并显示路段粒子。可设 4–64 格。 |
| `recordPointSpacing` | 0.45 格 | 沿实际轨迹累计移动多远后记录下一个位置。可设 0.1–2.0 格，修改后用于后续采样。 |
| `returnEraseDistance` | 2 格 | 返程时，在这个距离内消除下一路径点。剩余点消除后，以相同距离判定到达起点。可设 0.5–6 格。 |
| `showRecordingDirection` | 开启 | 记路时显示朝路线末端移动的亮点。 |
| `showArrivalMessage` | 开启 | 返程到达起点时提示「已到达起点」，并播放一声经验球音效。 |
| `clearOnArrival` | 开启 | 返程结束后清除起点标记。关闭后保留金色起点，短按 J 可以从当前位置重新记路。 |

## 构建

使用 JDK 21，在项目根目录运行 `.\gradlew.bat build`（Windows）或 `./gradlew build`。用 `runClient` 启动开发客户端。
