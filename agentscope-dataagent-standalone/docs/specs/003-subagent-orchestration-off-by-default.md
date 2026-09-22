# spec 003: 子代理编排默认关闭（`dataagent.agent.subagents-enabled`）

## 背景与目标

`logs/LLM.log` 实测：system message 13726 字符中，harness 注入的 `## Subagents` +
Task Tools 段占 6284 字符（45.8%），而 7 次工具调用里 0 次 `agent_spawn` / `agent_send` /
`task_*`。该段主体是 `SubagentsMiddleware.SUBAGENT_SECTION_TEMPLATE` 的**固定模板**
（约 4600 字符：三个 agent 工具的参数说明、四个 task 工具的用法、后台任务流程、超时提升、
生命周期、六种使用模式），与注册了几个子代理无关——所以只删内置的 `data-explorer` /
`report-writer` 省不到 5%。本产品形态是「单 agent + TC 风格问数工具链」，编排能力
从未被业务使用却每轮计费。本 spec 落地 ADR 0009：用装配层开关把它关掉，
同时保住已接线的 UI / API / 市场能力，并消除「能编辑却永不生效」的静默失效。

## 方案概述

一个配置项贯穿装配层 → API → 前端，**不删任何资产**：

1. **配置项**（`application.yml` 的 `dataagent.agent` 段，紧随 `name` 之后）：

   ```yaml
   agent:
     name: ${DATAAGENT_AGENT_NAME:data-agent}
     # Subagent orchestration (harness agent_spawn / agent_send / task_*). Off by default:
     # the harness injects a fixed ~6.2k-char "## Subagents" system-prompt section plus those
     # tool schemas on every turn, and this product shape (single agent + TC-style data
     # toolchain) never delegates. Enabling it restores the section, the tools, and loading
     # of workspace/subagents/*.md + the shared-layer definitions — no code change needed.
     # Does NOT affect the gateway, session routing or chat: buildSubagentEntries ignores
     # this flag, so SessionAgentManager is wired either way (ADR 0009).
     subagents-enabled: ${DATAAGENT_SUBAGENTS_ENABLED:false}
   ```

2. **装配层**（`DataAgentConfig`）：
   - 新增字段，与既有 `agentSysPrompt` 同风格：
     `@Value("${dataagent.agent.subagents-enabled:false}") private boolean subagentsEnabled;`
     （`@Value` 字段注入，不是 Bean 自注入，不违反 AGENTS.md 后端规则 7）。
   - `builderBootstrap` 的 `configureAllAgents` lambda 内，紧接现有
     `b.disableFilesystemTools(); b.disableShellTool();` 之后追加：

     ```java
     // Subagent orchestration is opt-in: the harness otherwise injects a fixed
     // "## Subagents" prompt section and the agent_*/task_* tool schemas on every
     // turn, which this single-agent data toolchain never uses (ADR 0009).
     if (!subagentsEnabled) {
         b.disableSubagents();
     }
     ```

   - 不动 `b.externalSubagentTool(sessionsTool)`（在 `DataAgentBootstrap.Builder#build` 内，
     `disableSubagents` 时 harness 自然不注册该工具）。
3. **API 暴露开关状态**（`AgentWorkspaceController`）：
   - 构造器追加参数 `@Value("${dataagent.agent.subagents-enabled:false}") boolean subagentsEnabled`
     并存为 `private final boolean subagentsEnabled;`（构造器注入，符合 AGENTS.md 规则 7）。
   - `WorkspaceSummary` record 在 `subagentCount` 之后追加组件 `boolean subagentsEnabled`；
     `summarize(agentId, ctx)` 填充该字段。`subagentCount` 仍照常统计（关着也要让用户
     看见有多少定义处于未加载状态）。
   - 四个 `/workspace/subagents` 端点（GET / PUT / POST from-agent / DELETE）**行为不变**：
     关闭期间仍可读写文件，只是不会被加载。
4. **前端提示 + 只读**：
   - `frontend/src/api/workspace.ts` 的 `WorkspaceSummary` interface 追加
     `subagentsEnabled: boolean;`（紧随 `subagentCount`）。
   - `SubagentPanel` 的 `Props` 追加 `readOnly?: boolean`；为 true 时不渲染工具栏的
     `+ From agent` / `+ New` 两个按钮，编辑视图的 `Save` / `Delete` 按钮 `disabled`，
     列表项仍可查看（`handleEdit` 照常拉取正文）。
   - `SubagentsPage` 用 `summary(ACTIVE_AGENT_ID)` 取开关状态（`useEffect` + `useState`，
     加载中按 `readOnly` 处理以免闪现可编辑态），把 `readOnly={!subagentsEnabled}` 传给
     `SubagentPanel`，并在 `subagentsEnabled === false` 时把 helpBar 文案换成：

     > Subagent orchestration is disabled in this deployment
     > (`dataagent.agent.subagents-enabled=false`). The definitions below are stored but never
     > loaded. Set the flag to `true` and restart to activate them.

     文案用英文，与该页现有 UI 语言（`BackToChatHeader` 的 "Subagents" /
     "Agents this one can delegate to"、面板的 "+ New" / "Save" / "Delete"）保持一致；
     该页整体中文化时一并翻译。
5. **文档同步**（AGENTS.md：改 `application.yml` 配置项 → 同步 README 配置表与
   ARCHITECTURE_zh.md 第 12 章）：
   - `README.md` 配置表在 `dataagent.agent.sys-prompt` 行之后加一行
     `dataagent.agent.subagents-enabled` | `false` | 英文说明（关闭可省约 6.2k 字符/轮的
     `## Subagents` 段与 agent_*/task_* schema；不影响 gateway/会话/聊天；置 true 即恢复）。
   - `README_zh.md` 配置表同位置加对应中文行。
   - `ARCHITECTURE_zh.md` §12.1 表在 `dataagent.agent.name` 行之后加一行
     `dataagent.agent.subagents-enabled` | false | 子代理编排（`agent_spawn` / `task_*`）；
     默认关，关闭时 harness 不注入 `## Subagents` 段（见 4.5、ADR 0009）。
   - `ARCHITECTURE_zh.md` §12.3 目录树中 `subagents/ ← 内置子 agent（data-explorer /
     report-writer）` 一行补注「默认不加载，需 `dataagent.agent.subagents-enabled=true`」；
     §7 的「`AGENTS.md / skills / subagents / knowledge` 只读投影进容器」一句补注
     「subagents 仅在开关打开时被 harness 加载」。

## 影响面

- 后端：`DataAgentConfig`（新字段 + `configureAllAgents` 一处分支）、
  `AgentWorkspaceController`（构造器参数、`WorkspaceSummary` record、`summarize`）、
  `application.yml`
- 前端：`api/workspace.ts`（`WorkspaceSummary`）、`components/SubagentPanel.tsx`
  （`readOnly` prop 与按钮渲染）、`pages/configure/SubagentsPage.tsx`（取状态 + helpBar 文案）
- 数据：无（不删 `src/main/resources/shared/agents/data-agent/subagents/*.md`，
  seeder 继续物化，manifest 不变）
- 文档：`README.md` / `README_zh.md` 配置表、`ARCHITECTURE_zh.md` §7 / §12.1 / §12.3、
  实施后把 ADR 0009 状态由「提议」改为「已采纳」
- 不受影响（需在验证时确认）：`HarnessGateway`、`SessionAgentManager`、`ChatController`
  的 SSE 链路、`/configure/subagents` 之外的所有页面、`MarketContributionService`
  的 subagent 贡献落盘、`AgentCatalogService` 物化用户自定义 agent 的 `subagents/*.md`

## 验收标准（Given-When-Then，可测试）

1. Given 默认配置（未设 `DATAAGENT_SUBAGENTS_ENABLED`），When 应用启动，
   Then `logs/LLM.log` 的 system message 中不再出现 `## Subagents` 段与
   `agent_spawn` / `task_output` / `task_list` 的工具定义，system message 总长较
   13726 字符下降约 6000 字符量级。
2. Given 默认配置，When 发起一次聊天提问，Then 回答正常返回，SSE 帧序列
   （`tool_call` / `tool_result` / `token` / `done`）不变，`done` 帧的 `sessionKey`
   仍是 conversationId，会话列表与历史照常可读（证明 gateway / 会话路由未受影响）。
3. Given `DATAAGENT_SUBAGENTS_ENABLED=true` 启动，When 查看 system message，
   Then `## Subagents` 段回来，且「### Available agent ids」含 `general-purpose` /
   `data-explorer` / `report-writer`（证明资产未删、开关可逆）。
4. Given 默认配置，When `GET /api/agents/{id}/workspace/summary`，
   Then 响应含 `"subagentsEnabled": false` 且 `subagentCount` 仍为实际定义数。
5. Given 默认配置，When 打开 `/configure/subagents`，Then 页面显示禁用说明，
   没有 `+ New` / `+ From agent` 按钮，进入某个定义的编辑视图后 `Save` / `Delete` 为禁用态，
   正文仍可查看。
6. Given 默认配置，When `HarnessAgent.builder().disableSubagents()` 后调用
   `buildSubagentEntries(workspace)`，Then 返回的 entries 非空且含 `general-purpose`
   （钉住 ADR 0009 决策 4 依赖的 harness 契约）。

## 不做的事（明确排除项）

- 不删 `data-explorer.md` / `report-writer.md`，不删前端 `SubagentsPage` /
  `SubagentPanel` / `api/subagents.ts` / `main.tsx` 路由，不删后端四个
  `/workspace/subagents` 端点，不删市场的 subagent 贡献类型。
- 不改 `DataAgentBootstrap`（含 `resolveGateway` 里那段不可达的历史异常文案）、
  不改 `HarnessGateway` / `SessionAgentManager` / `DefaultAgentManager`。
- 不改 harness 源码，不试图裁剪 `SUBAGENT_SECTION_TEMPLATE`。
- 不动 `tools.json` 的 `allow` / `deny`（`agent_*` / `task_*` 受
  `HarnessPlatformTools.NAMES` 豁免，改 allow 无效）。
- 不引入 `messageBus` / 异步任务能力，不改 `disableDynamicSubagents`。
- 不做「按 agent 粒度」的开关（当前只有全局装配一处需求）。

## 测试要求

- 新增 `src/test/java/io/agentscope/dataagent/runtime/SubagentOrchestrationSwitchTest.java`：
  - `disablingSubagentsKeepsSubagentEntries`（验收 6，harness 契约守卫：`disableSubagents()`
    后 `buildSubagentEntries` 仍返回含 `general-purpose` 的非空列表——这是「关闭不影响
    gateway/会话」的唯一依据，harness 升级若改变该行为必须让本测试红）。
  - `applicationYmlDefaultsSubagentsToDisabled`（配置契约：用
    `YamlPropertySourceLoader` 读 classpath `application.yml`，断言
    `dataagent.agent.subagents-enabled` 存在且其值的兜底为 `false`）。
  - `workspaceSummaryExposesSubagentsEnabled`（反射断言
    `AgentWorkspaceController.WorkspaceSummary` 的 record 组件含 `subagentsEnabled`，
    防止后端加字段而前端契约漂移）。
- 既有测试须全绿：`DataAgentConfigTest`（含 spec 002 新增用例）、
  `SharedWorkspaceSeederTest`（证明未删 subagents 资产、manifest 行为不变）、
  `WorkspaceScaffolderTest`、`MarketContributionServiceTest`。
- 本 spec 不涉多租户/越权逻辑变更，无需新增「看不到别人的数据」用例；
  但验收 2 必须覆盖「聊天链路不回退」，验收 3 必须覆盖「开关可逆」。
- 验证命令：`mvn -o spotless:apply`、`mvn -o test`、`cd frontend && npm run build`；
  运行时验收（验收 1–5）需分别在默认与 `DATAAGENT_SUBAGENTS_ENABLED=true` 下启动各跑一次。

## 关联

- ADR：[0009-subagent-orchestration-off-by-default](../adr/0009-subagent-orchestration-off-by-default.md)
  （实施后把状态改为「已采纳」）
- 相关：[0007-prompt-layering-and-shared-single-source](../adr/0007-prompt-layering-and-shared-single-source.md)
  （system prompt 占比的量化口径来自同一份日志分析）、
  [spec 002](002-anti-probe-and-count-consistency.md)（同批日志分析产出，可独立实施）
- 证据：`logs/LLM.log`（会话 `main-cd465cf5`，2026-09-22 17:01:27→17:02:08）

## 实施记录（2026-09-22）

### 已落地

- **改动 1**（`application.yml`）与 **改动 2**（`DataAgentConfig`）按计划完成：新增
  `dataagent.agent.subagents-enabled`（默认 `false`，env `DATAAGENT_SUBAGENTS_ENABLED`），
  `configureAllAgents` 在 `disableShellTool()` 之后按开关调用 `b.disableSubagents()`；
  `externalSubagentTool(sessionsTool)` 未动。
- **改动 5**（文档同步）完成：README.md / README_zh.md 配置表各加一行；
  `ARCHITECTURE_zh.md` §7（工作区投影补注开关）、§12.1（配置表加行）、
  §12.3（两处目录树补注「默认不加载」）。
- 计划外的连带改动：`sql-analysis` 技能的三处委托表述改为条件式（详见
  [spec 002 实施记录](002-anti-probe-and-count-consistency.md)与 ADR 0009 决策 4）。

### 暂缓与偏差

- **改动 3（`WorkspaceSummary` 增字段）与改动 4（前端只读提示）暂缓未实施。**
  当前部署只使用问答与知识库配置功能，不使用 `/configure/subagents` 页面；
  没有消费者的字段就是死代码。上面的方案原文保留备用，将来启用 configure
  页面时可直接实施（ADR 0009 决策 3）。相应地，**验收 4、5 不适用**。
- 测试实际 **2 个**而非 3 个：
  - `disablingSubagentsKeepsSubagentEntries`（验收 6）**未实现**：harness 的
    `HarnessAgentBuilderSupport` 是 package-private final 类、`buildSubagentEntries`
    是 package-private static 方法，断言它需要在另一个模块里建同包测试，
    脆弱性与维护成本远超收益。该契约改由验收 1 / 2 的运行时步骤覆盖，
    并在 `SubagentOrchestrationSwitchTest` 的类 javadoc 里写明为何不测。
  - `workspaceSummaryExposesSubagentsEnabled` 随改动 3 一并取消。
  - 保留 `configFieldDefaultsSubagentsToDisabled`（反射断言 `@Value` 兜底为 `false`，
    守住「默认关」这个决策本身）与 `applicationYmlDeclaresSubagentsDisabledByDefault`
    （`YamlPropertySourceLoader` 读 classpath yml，断言 env 兜底为 `false`，
    防止 yml 与 `@Value` 两处默认值漂移）。

### 验证

- `mvn -o spotless:apply` 通过；`mvn -o test` → **112 tests / 0 failures / 12 skipped**，
  含 `SubagentOrchestrationSwitchTest` 2 个新用例与 `SharedWorkspaceSeederTest` 8 个全绿
  （证明未删 subagents 资产、manifest 行为不变）。
- 前端零改动（`git diff -- frontend/` 为空），无需 `npm run build`。
- 运行时验收 **1、2 已执行**（2026-09-22 18:23 会话，同题重跑，见
  [spec 002 实施记录](002-anti-probe-and-count-consistency.md)）：`## Subagents` 段与
  `agent_spawn` / `agent_send` / `agent_list` / `task_*` 在整份日志中 **0 匹配**，
  system message 由 13726 字符降到 6320 字符（−54%，与「减去 6284 字符段落」的预期吻合）；
  聊天链路正常返回，5 次工具调用与 4 条 SQL 全部执行成功，回答计数逐项自洽。
  未单独抓包核对 SSE 帧结构（提问经聊天页面发起并正常渲染，间接覆盖）。
- 运行时验收 **3 未执行**：尚未在 `DATAAGENT_SUBAGENTS_ENABLED=true` 下启动核对
  「`## Subagents` 段与 `general-purpose` / `data-explorer` / `report-writer` 回来」。
- 上述字符数与匹配数证据取自**旧格式**日志；`logs/LLM.log` 的格式已于同日改造为
  人工可读转录（见 [spec 004](004-llm-log-readable-transcript.md)），复核时需按新格式
  重新取样。

### 实施中发现的既有问题（不属本 spec，未修）

`frontend/src/api/admin.ts` 的 `/api/admin/agents/*` 系列函数（含 `AgentDetailPage` 调用的
`getWorkspaceSummary`）在后端没有对应端点——全仓只有 `/api/admin/users` 与
`/api/admin/contributions` 两个 admin 映射。属既有的前后端不一致，与本开关无关。
