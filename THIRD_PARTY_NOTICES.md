# 第三方来源

| 来源 | 用途 | 许可与记录 |
| --- | --- | --- |
| Minecraft Forge MDK 1.20.1-47.4.0 | 工程模板、Forge 开发运行时与事件 API | 原模板许可保存在 `docs/licenses/forge-mdk-LICENSE.txt`；原 `CREDITS.txt` 保留 |
| Gradle Wrapper 8.8 | 固定构建工具版本 | Apache-2.0；保留原 wrapper 文件头 |
| JUnit Jupiter 5.10.2 | 开发期单元测试 | EPL-2.0；不打进模组 JAR |

Minecraft 本体和映射遵循 Mojang 的相应条款，未作为项目源码重新许可。

Reign of Nether 只曾作为视角实现的研究参考，本项目没有复制其源码。若以后引入第三方代码、素材或库，需要在此记录具体来源、版本、许可和实际使用范围。

Litematica 的 `pre-rewrite/fabric/1.20.1` 源码包（LGPL-3.0）用于研究区域捕获、相对坐标、预设与放置实例分离的设计。1.1.0 没有复制其源码，也没有引入 Litematica、MaLiLib 或 Fabric 运行依赖。

2026-10-04 静态研究用户提供的 Iron Chests `1.20.x` 源码包（包内含 GPL v3 许可文本）与 Sophisticated Backpacks `1.20.x` 源码包（All Rights Reserved），以及作者公开的 SophisticatedCore `1.20.x` 库存/堆叠升级实现，用于设计容量升级、容器局部堆叠与统一存取。没有复制源码或素材，没有执行这些项目的脚本，也没有添加运行依赖；来源文件、压缩包 SHA256 和研究边界见 [决策 0009](docs/decisions/0009-reachable-resources-and-storage.md)。
