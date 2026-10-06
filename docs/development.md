# 开发规范

## 环境与命令

目标为 Minecraft 1.20.1、Forge 47.4.0、Java 17；Gradle Wrapper 8.8、ForgeGradle 6.0.54。具体 Forge、映射和模组版本以 `gradle.properties` 为准，Gradle 版本以 Wrapper 为准。不要依赖机器上另装的 Gradle，也不要将本机 JDK 路径提交到工程。

在项目根目录使用 PowerShell：

```powershell
java -version
.\gradlew.bat test
.\gradlew.bat build
.\gradlew.bat runClient
```

`java -version` 应显示 Java 17。若机器有多个 JDK，通过当前终端的 `JAVA_HOME` 指向 JDK 17；不要把个人路径写入 `gradle.properties`。首次构建可能需要下载 Gradle、Forge 和 Minecraft 依赖。

日常交付可使用包装脚本；有多个 JDK 时指定本机 Java 17 的实际路径：

```powershell
.\scripts\build.ps1 -JavaHome '本机 JDK 17 的绝对路径'
.\scripts\run-client.ps1 -JavaHome '本机 JDK 17 的绝对路径'
```

`build.ps1` 支持 `-Offline`，仅适用于依赖已缓存的环境。脚本调用 Wrapper，归档到 `dist/<version>/`，记录 SHA-256 和 `verification.json`。`build` 加入 `verifyArchitecture` 与 `verifyModJar`，验证包边界和交付包内容；这些检查不能替代游戏内验收。

1.3.0 的最终 JAR 检查还要求打印/仓储/菜单类、全部五种设施的方块状态与模型/掉落表、七种物品模型与配方存在且 JSON 可解析。实际注册和交互由服务端 GameTest 验证，创造栏外观及客户端菜单仍需实机验收。网络协议为 2，客户端/服务端不得混装 1.2.0 和 1.3.0。

原始构建产物位于 `build/libs/`。开发客户端使用 `run/`，不应将已有游玩存档作为开发测试目录。CI 和本地脚本复用 Gradle 构建，具体工作流配置以工程文件为准。

修改服务端施工后，额外运行 `.\gradlew.bat runGameTestServer`（缓存齐全可加 `--offline`）。测试源码与结构模板在 `src/gameTest` 独立源集，运行于 `build/game-test-run` 下默认新建的测试世界；不要指定已有游玩存档。可用 `-PgameTestWorld=<新的测试目录名>` 固定测试目录。正式 `build` 不自动启动游戏服务器，测试类和结构不会进入发布 JAR；结果以服务端的 required tests passed/failed 汇总为准。ECJ 脚本同时检查 `src/main`、JUnit `src/test` 和 `src/gameTest`，游戏测试仍须编译和运行验证。

编辑器空值分析与 javac 构建分开验证。使用本机 Eclipse ECJ batch compiler JAR（VS Code 的 Red Hat Java 扩展也附带该文件），运行：

```powershell
.\scripts\check-nullability.ps1 -CompilerJar '本机 ECJ 编译器 JAR 路径' -JavaHome '本机 JDK 17 的绝对路径' -Offline
```

脚本从 Gradle 导出依赖，按 `config/null-analysis.properties` 检查主源码、JUnit 和 GameTest，任何警告或错误都失败；结果以 UTF-8 写入 `build/null-analysis/check.log`。1.4.3 起先把主代码编译到本轮独有的 `build/null-analysis/main-classes-*` 目录，再逐个主文件及分别对 JUnit/GameTest 源集检查二进制依赖中的契约。不能只合并全部源码检查：ECJ 对源码和二进制中的 record 构造器、enum 方法注解存在不同分析结果，合并检查曾漏掉真实编辑器警告。检查不用旧项目类，不会把分析用类打入发布 JAR。

优先使用当前 Red Hat Java 扩展附带的 ECJ 编译器，以免旧版通过而编辑器仍有新诊断；当前基线为 ECJ 3.46.100。它不下载编译器，也不修改编辑器全局设置，当前 CI 的普通构建不包含这项可选检查。项目 VS Code 设置保持自动空值分析，明确与脚本一致的 JSR-305 注解名称。只在文件树隐藏工作区根目录的 `work` 临时参考资料，不能用 `java.project.resourceFilters` 的 `work` 或 `files.exclude` 的 `**/work` 排除所有同名目录，否则任务系统和界面包会被屏蔽。自动模式没有识别 Minecraft 自定义返回值默认注解，因此在 API 边界检查返回值，不能在批量验证中额外启用这些默认注解来得到较宽松的结果。

## 代码规则

- Java 使用 UTF-8、四空格缩进；类用 `UpperCamelCase`，方法和字段用 `lowerCamelCase`，常量用 `UPPER_SNAKE_CASE`。
- 按功能归属放代码，遵守 [架构边界](architecture.md)。不建立收纳所有逻辑的 `Utils` 类。
- 镜头数学和状态约束保持纯 Java；Minecraft 与 Forge 交互集中在适配代码中。
- 界面文字走语言文件，至少保持 `zh_cn` 与 `en_us` 键一致，中文游戏语言下显示中文。模组列表名称/介绍等元数据默认用中文，在 `gradle.properties` 中使用 Unicode 转义保存并检查最终 JAR 的 UTF-8 文本。不要把实际快捷键提示写死为 F6。
- 注释解释约束或原因，尤其是生命周期、事件阶段、访问转换器和区块范围限制。
- 修改状态的入口应处理重复调用、退出世界和界面替换。避免每帧分配不必要对象，也不要为尚未测到的问题增加复杂缓存。
- 使用 Forge 已有的 `javax.annotation.Nonnull/Nullable` 表达边界契约；可空世界/玩家先保存局部引用并检查，不能用其他方法中的检查代替当前访问的保护。分帧选择作业冻结世界上下文，世界变更时清理旧作业。纯 Java 模型不依赖 Minecraft 注解。
- 新依赖要有具体用途；优先采用现有 Forge/Minecraft 能力。复制第三方代码前核对许可和来源，不把公开源码自动视为可任意复制。

## 改动与交付

开始前检查现有文件和工作树，保留与本次任务无关的用户改动。一次改动围绕一个可验收目标；结构调整尽量保持行为，行为改变同步更新说明与验收项。

提交或交付说明写清：解决的问题、最终行为、验证方式和未验证事项。构建成功、纯 Java 测试和游戏内验收分别报告；禁止把其中一种替代另一种。建议提交消息直接写动作和目的，例如 `refactor: separate camera state from Forge events`。

生成目录、IDE 个人配置、日志和运行存档不纳入源码。保留 Wrapper 和版本定义，让新机器可以从源码构建。版本号集中维护在 `gradle.properties`；发布时同步更新变更记录和当前验收状态。

Git 仓库位于本工程目录，私有远程为 `lidaobaiqiao/colony-camera`，使用 `main` 作为主分支；V1 初版标记为 `v1.0.0`。功能开发使用简短的 `feature/`、`fix/` 或 `refactor/` 分支名；保持每次提交对应一个可验收目标。原 Forge MDK 说明与变更记录保存在 `docs/upstream/`，项目文档以根目录 `README.md` 与 `CHANGELOG.md` 为准。

当前 `colonycamera` ID 与根包用于稳定已有安装和资源路径，功能扩展时不要顺手改名。许可声明以工程现有许可文件和元数据为准，不在重构中擅自更换。

涉及新的持久化格式、网络边界或显著架构改变时，在 `docs/decisions/` 增加简短决策记录：问题、选择、理由和代价。日常小改动无需另写决策文档。
