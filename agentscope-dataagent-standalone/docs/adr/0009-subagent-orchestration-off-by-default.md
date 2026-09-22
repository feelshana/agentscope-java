# ADR 0009: 子代理编排默认关闭（可配置开关）

- 状态：已采纳（2026-09-22 实施，见 [spec 003](../specs/003-subagent-orchestration-off-by-default.md)）
- 日期：2026-09-22
- 证据：`logs/LLM.log`（system message 13726 字符，其中 `## Subagents` + Task Tools 段
  6284 字符，占 45.8%；7 次工具调用中 0 次 `agent_spawn` / `agent_send` / `task_*`）；
  harness `SubagentsMiddleware.SUBAGENT_SECTION_TEMPLATE`（固定模板，约 4600 字符）；
  `src/main/resources/shared/agents/data-agent/subagents/`（`data-explorer.md` 2471B、
  `report-writer.md` 2931B）；运行态 `workspace/subagents/` 只有 `README.md`（534B）
- 关系：不改变 ADR 0003（沙箱注入）与 ADR 0005（市场共享层）；ADR 0005 的 subagent
  贡献类型在关闭期间落盘但不被加载

## 背景

本产品形态是「单 agent + TC 风格问数工具链」：schema 上下文预注入 system prompt，
工具只做「确认细节 → 查询 → 检索 → 出图」。子代理编排能力从 harness 默认继承而来，
从未被业务使用，却按轮次计费：

1. **固定模板吃掉近半 system prompt。** harness 在 `HarnessAgent#build` 里装配
   `DynamicSubagentsMiddleware`（条件 `!leafSubagent && !disableSubagents && model != null`），
   其 `onSystemPrompt` 每轮 prepend `renderSubagentSection(...)` + `buildTaskSummary(...)`。
   段落主体是 `SUBAGENT_SECTION_TEMPLATE`——agent_spawn / agent_send / agent_list 的参数说明、
   task_output / wait_async_results / task_cancel / task_list 的用法、后台任务流程、
   超时提升、生命周期、六种使用模式——**固定约 4600 字符**，与实际注册了几个子代理无关。
2. **只删内置定义几乎没有收益。** 子代理清单只体现在「### Available agent ids」几行，
   删掉 `data-explorer` / `report-writer` 省下不到整段的 5%，固定模板照旧下发，
   却丢掉了两个开箱可用的定义。
3. **工具 schema 同样每轮下发。** `agent_spawn` / `task_output` / `task_cancel` / `task_list`
   由 `DynamicSubagentsMiddleware#getTools()` 注册进 toolkit；`workspace/tools.json` 的
   `allow` 只列了 6 个文件工具，但 `ToolFilter` 对 `HarnessPlatformTools.NAMES`
   （含 agent_* / task_* / plan_* / skill_*）予以豁免，所以这些工具并未被过滤掉。
   其 schema 体积本次未单独量化。
4. **能力已接线到产品面。** `frontend/src/main.tsx` 的 `/configure/subagents` 路由 →
   `SubagentsPage` → `SubagentPanel`（增删改、从现有 agent 拉取）；
   `AgentWorkspaceController` 的 GET/PUT/POST(from-agent)/DELETE `/workspace/subagents`
   四个端点；`MarketContributionService` 支持把 subagent 审批进 `shared/`；
   `AgentCatalogService` 在创建用户自定义 agent 时物化其 `subagents/*.md`。

即：编排能力每轮占用 45.8% 的 system prompt 与若干工具 schema，收益为零；
但它不是一个孤立的 prompt 片段，直接删除会牵动前端页面、后端端点与市场贡献类型。

## 决策

1. **新增开关 `dataagent.agent.subagents-enabled`（env `DATAAGENT_SUBAGENTS_ENABLED`），
   默认 `false`。** `DataAgentConfig#builderBootstrap` 的 `configureAllAgents` 里，
   开关为 false 时调用 `b.disableSubagents()`——与既有的 `disableFilesystemTools()` /
   `disableShellTool()` 同处同风格。harness 因此不装配 `DynamicSubagentsMiddleware`，
   `## Subagents` 段与 agent_* / task_* 工具一并消失。
2. **不删任何资产。** 内置两个子代理定义留在 `src/main/resources/shared/`（seeder 继续
   物化到根 `shared/`，开关打开即生效）；前端页面、`api/subagents.ts`、后端四个端点、
   市场的 subagent 贡献类型全部保留。关闭是装配层的一个布尔，不是功能删除。
3. **消除静默失效：把开关状态告知前端（本次暂缓）。** 原计划让
   `AgentWorkspaceController.WorkspaceSummary` 增字段 `subagentsEnabled`，
   `/configure/subagents` 在 false 时显示「当前部署未启用子代理编排」并把面板置为只读。
   实施时确认当前部署只使用问答与知识库配置功能，不使用 agent 配置页面，
   因此这条连同前端改动一并暂缓——没有消费者的字段就是死代码。
   方案保留在 spec 003 改动 3 / 4 中，将来启用 configure 页面时可直接实施。
4. **技能里的委托指引改为条件表述。** `sql-analysis` 技能原本无条件教模型
   「委托 `data-explorer` 子代理」「派生 `report-writer`」；关闭编排后 `agent_spawn`
   不在工具集里，这些指引会让模型去调不存在的工具。实施时把三处委托表述改为
   「仅当工具集中存在 `agent_spawn` 时适用」并显式写明「不要尝试调用不存在的工具」，
   由 `SharedSkillContentTest#sqlAnalysisGatesDelegationOnToolAvailability` 守卫。
   技能是静态文件无法感知开关，条件表述是让两种取值下同一份文本都自洽的唯一做法。
5. **记录装配事实，防止后人误判耦合。** 关闭子代理**不影响** gateway、会话路由与聊天：
   `HarnessAgent.Builder#buildSubagentEntries` 不检查 `disableSubagents`（始终返回
   general-purpose + 声明的子代理），`DataAgentBootstrap.Builder#build` 无条件构建
   `DefaultAgentManager` → `SessionAgentManager` → `HarnessGateway` 并绑定主 agent。
   `DataAgentBootstrap#resolveGateway` 中「the main agent has subagents disabled」的
   异常文案在当前装配路径下不可达（`gateway` 恒非 null），属历史遗留，本次不改其行为。

## 理由与权衡

- **默认关而非默认开**：按实际使用付费。当前每会话 4 轮 LLM 调用，6284 字符 ≈
  每轮约 1600–2000 token 的纯浪费；关闭后这部分连同 agent_* / task_* 的 schema 一起归零，
  而业务能力（问数、检索、出图、Python 分析）完全不受影响。
- **开关而非删代码**：子代理是已接线的产品能力，删除不可逆且横跨前端与市场层；
  「当前没用到」是部署状态而非架构结论。一个布尔 + 一行 `disableSubagents()`
  即可在任何环境恢复，代价远小于重建 UI/API/贡献类型。
- **为什么不用 `disableDynamicSubagents`**：那个开关只在 `filesystem == null` 时改变
  分支选择（退回非动态的 `SubagentsMiddleware`），段落照旧注入，省不下 token。
- **为什么保留 UI 而不是隐藏路由**：隐藏会让「已启用子代理的部署」失去管理入口；
  显示 + 只读 + 明确文案，两种部署都自洽。（本次未改 UI，见决策 3：页面照旧可编辑，
  但当前部署不使用该页面；只读提示的方案已写入 spec 003 备用。）
- **代价**：多一个配置项与一处「关着但 UI 还在」的状态需要解释；`disableSubagents()`
  会连带移除 task_* 异步工具，将来若要用后台任务需一并开启（`wait_async_results`
  的注册另受 `messageBus` 约束，当前部署未配 messageBus，本来就没有该工具）。

## 被否决的替代方案

- **只删 `data-explorer.md` / `report-writer.md`**：固定模板约 4600 字符仍在，
  收益不足 5%，还丢掉两个可用定义。
- **硬调 `disableSubagents()` 不加配置项**：无法按环境开启，且 UI 变成能编辑却永不生效
  的死功能——静默失效正是 ADR 0007 要根治的那类问题。
- **删掉前端页面、四个端点与市场贡献类型**：不可逆，改动面横跨 web / frontend / marketplace
  三层，与「暂时没用到」的实际诉求不匹配。
- **改 harness 让 `## Subagents` 段按需注入（无自定义子代理时不注入）**：跨仓库改动，
  且 general-purpose 恒存在，段落不会消失；即便实现，也应由 harness 上游决策。
- **保留能力但裁剪模板文本**：模板在 harness 内部，业务层无法覆盖；即便能覆盖，
  裁剪后仍要为未使用的能力付固定成本。

## 适用与失效条件

依赖 harness 当前行为：`disableSubagents()` 在 `HarnessAgent#build` 阻止
`DynamicSubagentsMiddleware` 装配，而 `buildSubagentEntries` 不受其影响。若 harness 将来
把 entries 提取与 middleware 装配耦合（例如禁用后不再返回 general-purpose），
需重新验证 `SessionAgentManager` 的会话路由是否仍正常。若产品引入真正的委派场景
（并行多表探查、长报告撰写）或后台异步任务，把开关置 `true` 即可，无需改代码；
届时应重新测量 system prompt 占比，并考虑用 `subagents/*.md` 的 `tools` 字段
收窄子代理工具面。多副本部署下该开关必须全副本一致，否则同一用户在不同副本上
看到的可用工具集会漂移。
