# BiFang Tools 仓库维护说明

## 项目范围

- BiFang Tools（哔方的妙妙工具）是 BiFang 制作的纯客户端实用模组。`1.21.1` 分支维护 Minecraft Java 1.21.1 和 NeoForge，使用 Java 21。其他 Minecraft 版本各自维护分支，兼容范围以构建和模组元数据为准。
- 保持模组 ID `bifangtools`、现有配置键及资源命名空间的兼容性。客户端功能应可在服务端未安装本模组时使用。
- Java 源码在 `src/main/java/dev/waythread/`。快捷键和客户端事件入口是 `Waythread`，路线模型是 `DimensionalTrail` 和 `BreadcrumbTrail`，单人路线读写由 `TrailSave` 负责。模组元数据在 `src/main/resources/META-INF/neoforge.mods.toml`。

## 引路丝行为

- 记录的数据是实际走过的路径点。A* 只在玩家附近、同一路段的相邻记录点之间生成显示路径。保留路线经过的位置，避免跨断口补线或跳过绕行。
- 跨维度按每次访问分段，返程沿去程的逆序衔接。显示和寻路只处理当前维度，各维度共用记录点数上限与返程进度。接回目标不明确时，保留已有路线并等待确认。
- 单人路线和返程进度保存在客户端的 `config/bifangtools/waythread/`，按世界区分。修改存储时检查既有数据能否读取。存档目录可能被改名或移动，暂缓自动清理失去匹配的路线文件。
- 配置定义和默认值见 `WaythreadConfig`，界面见 `TrailConfigurationScreen`。改动配置或提示时，同步检查相关界面和 `src/main/resources/assets/bifangtools/lang/` 下的全部语言文件，保留相同的键和格式化占位符。提示语保持简短。

## 构建与验证

- 在仓库根目录用 Gradle Wrapper 构建。Windows 运行 `.\gradlew.bat build`，其他平台运行 `./gradlew build`。成品位于 `build/libs/`。
- 开发客户端使用 `.\gradlew.bat runClient` 或 `./gradlew runClient`。用户负责游戏内实测。报告时写清自动检查和实际进游戏验证各覆盖了哪些行为。
- 路线逻辑按改动检查同维度折返、跨维度回程、接回失败、点数上限及保存后重开世界。发布前核对 JAR 内的版本、Minecraft 兼容范围和资源。

## 文档与发布

- `README.md` 为默认英文首页，`README.zh-CN.md` 提供简体中文版，两页保留互链。工具用表格简述，配置详情可折叠。按已验证的行为更新两版说明。传送和重生相关的草稿等功能实现并验证后再随功能推送。
- 对外版本变更记入 `CHANGELOG.md`。草稿和本地待办放在已忽略的 `docs/`。提交时保留用户已有改动，只纳入任务涉及的文件；构建产物、运行配置、测试存档和日志留在本地。
- 按 `RELEASE.md` 命名发布包和 GitHub Release。版本号由 `build.gradle` 提供，正式版本标签采用 `v{版本号}`。明确要求发布时，再推送对应版本分支及标签，并将已验证的 JAR 附到 GitHub Release。提交身份使用仓库配置的 GitHub `noreply` 邮箱。
- 代码使用 `LICENSE` 中的 MIT 许可，封面和模组图标使用 `LICENSE-ASSETS` 中的 CC BY-NC-SA 4.0 许可。分发成品时保留对应的许可文件。
