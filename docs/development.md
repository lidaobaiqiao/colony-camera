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

原始构建产物位于 `build/libs/`。开发客户端使用 `run/`，不应将已有游玩存档作为开发测试目录。CI 和本地脚本复用 Gradle 构建，具体工作流配置以工程文件为准。

## 代码规则

- Java 使用 UTF-8、四空格缩进；类用 `UpperCamelCase`，方法和字段用 `lowerCamelCase`，常量用 `UPPER_SNAKE_CASE`。
- 按功能归属放代码，遵守 [架构边界](architecture.md)。不建立收纳所有逻辑的 `Utils` 类。
- 镜头数学和状态约束保持纯 Java；Minecraft 与 Forge 交互集中在适配代码中。
- 界面文字走语言文件，至少保持 `zh_cn` 与 `en_us` 键一致，中文游戏语言下显示中文。模组列表名称/介绍等元数据默认用中文，在 `gradle.properties` 中使用 Unicode 转义保存并检查最终 JAR 的 UTF-8 文本。不要把实际快捷键提示写死为 F6。
- 注释解释约束或原因，尤其是生命周期、事件阶段、访问转换器和区块范围限制。
- 修改状态的入口应处理重复调用、退出世界和界面替换。避免每帧分配不必要对象，也不要为尚未测到的问题增加复杂缓存。
- 新依赖要有具体用途；优先采用现有 Forge/Minecraft 能力。复制第三方代码前核对许可和来源，不把公开源码自动视为可任意复制。

## 改动与交付

开始前检查现有文件和工作树，保留与本次任务无关的用户改动。一次改动围绕一个可验收目标；结构调整尽量保持行为，行为改变同步更新说明与验收项。

提交或交付说明写清：解决的问题、最终行为、验证方式和未验证事项。构建成功、纯 Java 测试和游戏内验收分别报告；禁止把其中一种替代另一种。建议提交消息直接写动作和目的，例如 `refactor: separate camera state from Forge events`。

生成目录、IDE 个人配置、日志和运行存档不纳入源码。保留 Wrapper 和版本定义，让新机器可以从源码构建。版本号集中维护在 `gradle.properties`；发布时同步更新变更记录和当前验收状态。

Git 仓库位于本工程目录，私有远程为 `lidaobaiqiao/colony-camera`，使用 `main` 作为主分支；V1 初版标记为 `v1.0.0`。功能开发使用简短的 `feature/`、`fix/` 或 `refactor/` 分支名；保持每次提交对应一个可验收目标。原 Forge MDK 说明与变更记录保存在 `docs/upstream/`，项目文档以根目录 `README.md` 与 `CHANGELOG.md` 为准。

当前 `colonycamera` ID 与根包用于稳定已有安装和资源路径，功能扩展时不要顺手改名。许可声明以工程现有许可文件和元数据为准，不在重构中擅自更换。

涉及新的持久化格式、网络边界或显著架构改变时，在 `docs/decisions/` 增加简短决策记录：问题、选择、理由和代价。日常小改动无需另写决策文档。
