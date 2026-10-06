# 1.7.0 UI 落地说明

本轮按“完整界面，科研展示现有科技”的确认范围实现。原 HTML 是交互设计稿；Java 代码和下面的入口是本版实际实现。

- StrategyHud / StrategyHudLayout：两端底栏、投影/殖民地向上菜单、概况、禁用原因、操作反馈、帮助。
- StrategyScreen / PointerGesture / SelectionState：中键短按四步循环与拖动分类、右键清空/平移、菜单/窗口优先级及高度基准。
- BuildPanel / BuildPanelLayout / BuildWindows / WindowGeometry：常驻目录、宽窗分类横排/窄窗下拉、最多八窗、叠放、拖动、缩放、归一化布局配置。
- ProjectionController / BlockDraftState：每窗草稿保存/恢复和按 UUID 的受理回执；世界只显示活动草稿，关界面不取消服务器工程。
- WorkPanel / WorkPanelLayout：任务、合成、科研概要、人物、工程五页，项目管理与打印入口。
- TechnologyPanel / TechnologyPanelLayout：独立现有四阶段科技树，服务端前置/成本/耗时，以及有效站点和已排队状态。
- ColonyWorkClient / ColonyWorkManager / TechProgress：仅扩展只读快照，传递真实工人分配和现有四个科技节点；沿用原来的服务端意图及存档格式。

快捷入口：N / 6 切换建造，P 打开预设页，B 保存选区，O 打开任务。标题栏 + 多开、拖标题移动、拖右下角缩放，格子右键收藏。中键短按切换选择，解锁后的中键拖动旋转；相似上限仍为 1–8192，默认 64。窗口布局保存到 CLIENT 配置的 buildWindows，搜索与草稿仅保留当前会话。

验证见 ../validation/1.7.0.md；手动清单见 ../testing.md 的 1.7.0 节。排版预览与游戏内验收分开记录。

