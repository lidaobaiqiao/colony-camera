# 项目工作约定

## 目标与基线

- 核心循环：第一人称亲手设计预设 → 战略视角规划殖民地 → 后续由施工系统落实。
- 当前 V1（1.0.0）完成镜头、选择与战略 UI。Minecraft 1.20.1、Forge 47.4.0、Java 17；`colonycamera` mod id 保持稳定。
- 2026-10-04 用户宣布 V1 初版开发完成，作为开发里程碑确认；完整的 V1 游戏回归清单仍需单独记录。
- 2026-10-03 用户确认 0.1.0 视角正常。重构、构建、单元测试不能替代改动版本的游戏验收。
- 2026-10-03 用户确认 0.1.5 选中效果已生效；只作为该版本对应功能基线，不自动覆盖后续多选交互的验收。
- 开始修改前检查现状和 Git diff，保留用户改动、`run/saves`、本地配置与旧 JAR。

## 结构

- 单 Gradle 工程，按功能和执行侧分包。入口 `ColonyCamera` 不引用客户端类。
- `client.camera` 管镜头状态/生命周期，`client.input` 管输入与 Forge 事件桥接，`client.screen` 管界面。
- `StrategyHudLayout` 保持纯 Java，绘制和命中使用同一份 GUI 坐标矩形。UI 先于世界鼠标动作处理，帮助暂停镜头输入；进入/离开面板的高度输入必须重设基准，GLFW 延迟光标任务执行前再次检查 UI 命中。
- `client.selection` 管摄像机射线、本地选区、表面/立体扫描及轮廓缓存；`SelectionState`、`SelectionBoxes`、`FaceSelection`、`SurfaceSelectionJob`、`HeightSelection` 保持纯 Java。框选固定鼠标按下时实际命中面的世界参考平面，普通模式每列沿面法线取最外侧一个非空 OUTLINE 方块；立体模式在固定底面上用鼠标竖直位移选择法线方向层数，滚轮始终缩放。选择仅查询已加载世界，不执行挖放或其他世界修改。
- 高度阶段的鼠标横向限制仅在当前窗口活跃且未拖动镜头时执行；镜头拖动须重设鼠标高度基准，缩放及镜头位移不能直接改变层数。失焦、缩放窗口、取消或退出时结束高度与光标约束，保留既有选区的取消路径不变。
- 加载状态用 `ClientChunkCache.hasChunk`（`level.getChunkSource().hasChunk`）核验，不用 `ClientLevel.hasChunk`：后者在该版本恒为 true。表面扫描冻结搜索窗口和区块依赖，完成前复查，任何依赖卸载均拒绝整次操作并保留已提交集合。
- `CameraState` 保持纯 Java。订阅器明确 `Dist.CLIENT`，注册按键走 MOD 总线，tick/viewport 走 FORGE 总线。
- 后续 `preset`、`planning`、`construction` 的职责见 `docs/architecture.md`；实现功能时才创建代码，不放空接口与占位服务。
- 将来改变世界、扣材料、执行施工必须由服务端校验与执行；客户端只提交意图和显示预览。

## 代码与维护

- Java 17，UTF-8，四空格；JSON/YAML 两空格。遵循 `.editorconfig`。
- 命名表达职责；类用 PascalCase，方法/字段用 camelCase，常量用 UPPER_SNAKE_CASE。
- 玩家文字放语言资源，保持中英文 key 同步；中文游戏语言下默认显示中文。模组列表名称/介绍等元数据默认用中文，Gradle properties 中用 Unicode 转义保存中文并核对最终 JAR 的 UTF-8 文本。事件处理保持薄，业务逻辑集中到功能类。
- 注释解释约束与原因，避免复述代码；不用庞大 Utils、反射框架或提前设计插件体系。
- 依赖固定版本；新增 AT/Mixin 要说明必要性、执行侧与影响，并验证打包结果。
- 保存格式引入 `schemaVersion` 并定义迁移后再落盘；拒绝加载不支持的格式时给出明确错误。
- 保留现有授权状态，不擅自给原代码选择开源许可。第三方代码需记录来源与许可。

## 验证与交付

- 修改逻辑后运行 `scripts/build.ps1`（支持 `-JavaHome` 与 `-Offline`），或 wrapper 的 `build`。
- `build` 执行 JUnit、`verifyArchitecture`、`verifyModJar`；检查会在重混淆之后验证最终 JAR。
- 回归用例覆盖有实际失败风险的状态/数学边界，不为简单文案建立镜像测试。
- 摄像机、输入、渲染修改需要 `docs/testing.md` 的游戏回归；准确报告已执行与待执行项。
- 版本变化记录 `CHANGELOG.md`，重要边界变化记录 ADR；当前路线见 `docs/roadmap.md`。
- 发布脚本归档到 `dist/<version>/` 并给出 SHA256 和验证记录，不自动安装到用户存档或模组目录。
- 不提交 build/run/bin/dist、凭据或个人绝对路径，不推送/发布到外部服务除非用户要求。
