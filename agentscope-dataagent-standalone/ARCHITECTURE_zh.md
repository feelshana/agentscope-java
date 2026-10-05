
# agentscope-dataagent 架构与流程详解

> 本文档面向开发者与 AI 编码助手，基于当前代码实际实现梳理（非上游 agentscope 示例的原始版本）。
> 核心流程：**通过 Wren 进行语义建模 + 问数**——数据集组即 wren project，表结构变化自动播种并发布
> 基础 MDL；建模助手（modeling-agent）经对话完善关系/Cube/视图/业务规则后发布；问数助手（data-agent）
> 在已发布语义模型上统一执行逻辑查询（`wren_describe_model` / `wren_run_sql` / `wren_query_cube`），
> 配合 `retrieve_evidence` / `render_chart`。知识图谱构建链路已停用（见第 6 章）。
> 引用代码位置时使用「类名 + 方法名」锚点，不引用行号（行号会漂移，类名可由 SearchSymbol 定位）。

---

## 目录

1. [项目定位与分层架构](#1-项目定位与分层架构)
2. [启动装配流程](#2-启动装配流程)
3. [主流程：Wren 语义建模与问数](#3-主流程wren-语义建模与问数)
4. [问数工具链（Wren-only + 知识检索 + 可视化）](#4-问数工具链)
5. [数据集与知识库](#5-数据集与知识库)
6. [知识图谱（已停用）](#6-知识图谱已停用)
7. [会话管理与沙箱](#7-会话管理与沙箱)
8. [共享层](#8-共享层)
9. [通道与主动外发](#9-通道与主动外发)
10. [认证、权限、审计与用量](#10-认证权限审计与用量)
11. [前端结构](#11-前端结构)
12. [配置与启动](#12-配置与启动)
13. [关键类与文件索引](#13-关键类与文件索引)

---

## 1. 项目定位与分层架构

dataagent 为每位数据分析师提供专属的数据 Agent，核心流程是**通过 Wren 进行语义建模 + 问数**：
上传 Excel/CSV 或关联外部数据库表即自动播种并发布基础语义模型（MDL，5.6）；建模助手
（modeling-agent）经对话完善关系/Cube/视图/业务规则后发布（3.2）；问数助手（data-agent）
在已发布语义模型上作答（3.1）。知识库（Knowledge Base）承载口径文档、表关系说明与语义术语；
知识图谱构建已停用（第 6 章）。

```
┌─────────────────────────────────────────────────────────────────────┐
│  Web 业务层（本模块 web/ 包）                                        │
│  ChatController / JWT认证 / ACL权限 / 数据集·知识库·语义建模 API /    │
│  ToolEventBus(SSE) / 审计 / 用量 / AgentCatalog(内置agent目录)        │
├─────────────────────────────────────────────────────────────────────┤
│  Agent 工具层（tools/data/ 包）                                    │
│  WrenToolkit(问数: wren_describe_model / wren_run_sql /            │
│  wren_query_cube) ModelingToolkit(建模: YAML写面·关系确认·预检)     │
│  DataAgentToolkit(retrieve_evidence / render_chart) + run_python      │
├─────────────────────────────────────────────────────────────────────┤
│  数据资产层（dataset/ 包）                                           │
│  DatasetService(上传导入·外部表关联) / MdlSuggestionService(LLM关系建议)│
│  MdlPublishService·MdlSeeder(MDL基线发布链) / SchemaGenerationService │
├─────────────────────────────────────────────────────────────────────┤
│  Runtime 层（runtime/ 包，复用 agentscope-harness/core）             │
│  DataAgentBootstrap / HarnessGateway(沙箱注入+并发门) /              │
│  SessionAgentManager / WebhookChannel / OutboundTool                 │
├─────────────────────────────────────────────────────────────────────┤
│  Agent 核心层（agentscope-core/harness 框架，跨模块复用）            │
│  ReActAgent(reasoning↔acting) / HarnessAgent / SandboxManager        │
├─────────────────────────────────────────────────────────────────────┤
│  基础设施层                                                          │
│  MySQL(数据集物理表 ds_*) / H2或MySQL(平台元数据 JPA) /              │
│  Docker沙箱(run_python) / Redis(可选分布式会话) / Git·Nacos(技能市场) │
└─────────────────────────────────────────────────────────────────────┘
```

**设计哲学**：业务层只写 Web API + 数据工具 + 数据资产服务；ReAct 循环、沙箱生命周期、
会话路由全部复用 harness/core 框架，禁止在业务层重复实现。

**多租户模型**：所有数据资产按 `ownerId` 隔离；数据集组织为「知识库（DatasetGroup）→ 数据集（Dataset）→ 物理表」三级；
运行时通过 `DatasetScope(ownerId, groupIds?)` 把租户与知识库过滤注入每个 agent turn。

---

## 2. 启动装配流程

### 2.1 入口

- `web/DataAgentApp.java` — `@SpringBootApplication(scanBasePackages = "io.agentscope.dataagent")`，仅启动类，无装配逻辑。
- `web/config/DataAgentConfig.java` — 全部 Spring 装配的核心。

### 2.2 模型装配（优先级从高到低）

`DataAgentConfig` 中：

1. 容器中已存在 `Model` Bean（其他配置类提供）→ 直接使用；
2. `dataagent.openai.api-key` 非空 → 创建 `OpenAIChatModel`（任意 OpenAI 兼容端点：DeepSeek/vLLM/one-api/DashScope 兼容模式），默认关闭 thinking；
3. 否则 `dataagent.dashscope.api-key` 非空 → 创建 `DashScopeChatModel`；
4. 都没有 → 应用照常启动，但聊天端点返回「未配置模型」错误（`ChatController.NO_MODEL_MESSAGE`）。

注意：Model 注入采用 `@Bean` 方法参数注入（非字段 `@Autowired`），避免与本类中 Model Bean 定义的循环依赖。

### 2.3 DataAgentBootstrap 装配

`DataAgentConfig#builderBootstrap(...)` 执行：

```
1. resolveCwd()                → dataagent.workspace，缺省为 JVM 工作目录
2. ensureAgentscopeConfig()    → ~/.agentscope/dataagent/agentscope.json 不存在则生成默认配置，
                                 并调用 WorkspaceScaffolder 生成私有层工作区种子
                                 （AGENTS.md=系统提示词、tools.json、skills/、subagents/、memory/）
3. builder.model(model)
4. builder.configureAllAgents((agentId, b) -> { ... })
                                 ← 对【每个】HarnessAgent 生效的横切配置（agent-id 感知：
                                   免沙箱 agent 走豁免分支，见 7.3）：
   - middleware: ToolNotificationMiddleware（工具事件 → ToolEventBus → SSE）
   - middleware: ModelingHitlMiddleware（仅 `modeling-agent` 安装；在 acting 阶段把首个建模写调用
     标记为 `ASKING`，发出原生确认事件并暂停；普通问数 Agent 和读取工具不受影响）
   - middleware: DebugLoggingMiddleware（把消息写成人工可读转录：每条记录
     只有 role / type / text 三个字段；同一 session 内按消息 id 去重，因此整份文件是一条
     线性对话而不是每轮重放的历史；工具入参按 `key: value` 逐行展开（SQL 保留换行），
     工具结果反转义为真实文本，图片/音视频等媒体块只留占位符。转录 logger 按 agent 注入：
     默认 `logs/LLM.log`（LLM_DEBUG）；建模助手 modeling-agent 走独立文件
     `logs/LLM-modeling.log`（LLM_MODELING_DEBUG），建模会话与问数流量分开成两份文件。
     不经中间件链的直调 LLM 由 AgentDraftService#callModelBlocking 自己以同一结构转录：
     语义建模 AI 建议 → logs/LLM-modeling.log，agent 草稿 → logs/LLM.log）
   - middleware: DataDynamicContextMiddleware（每轮重建 [DATA_SOURCES_OVERVIEW]
     与 [KNOWLEDGE_BASE_OVERVIEW] 追加到 system prompt；数据源概览只渲染可查询的
     已发布逻辑模型/Cube 轻量目录（Cube 行含度量/维度/时间维度名），字段与关系详情由
     wren_describe_model 按需获取，见 4.6）
   - stateStore: AgentStateStore（默认 InMemoryAgentStateStore；生产应提供分布式实现）
   - filesystem: DockerFilesystemSpec(IsolationScope.USER)，镜像 dataagent.sandbox.image
     —— 仅问数 agent；sandboxlessAgents（当前 = modeling-agent）显式设 LocalFilesystemSpec +
     GroupScopedMemoryNamespace（specs/029、ADR 0039：长时记忆按知识库分域——单库对话
     ns=[owner,"kb-<groupId>"]，切断 A 库建模记忆经 <memory_context> 注入 B 库），
     走本地文件系统 + 独立空工作区（见 7.3「免沙箱 agent」）
   - disableFilesystemTools() + disableShellTool()  ← 数据分析场景不需要文件/Shell 工具
   - toolResultEviction: 将 run_python 加入排除名单，保证产物元数据不被驱逐
   - modeling-agent：configureAgent("modeling-agent", ...) 编程注册建模助手（specs/013 M1、ADR 0024 D2）
     ——中文剧本 sysPrompt、maxIters=30、独立空工作区
     ~/.agentscope/dataagent/workspace-modeling-agent（免沙箱姿态，见 7.3）；
     二十一个建模工具由 ModelingToolkitRegistrar 后置挂载（见 2.5）；
     官方 wren skills 经 FileSystemSkillRepository 原生挂载（specs/022、ADR 0035）：
     WrenSkillsLocator 从已安装 wrenai 包 skills_content 解析目录（dataagent.wren.skills-dir 可显式指定），
     SkillFilter 白名单只放 generate-mdl/enrich-context/usage/onboarding，
     load_skill_through_path 与 CLI 通道同源同文；包缺失仅 warn 降级（fail-soft，见 5.6）
5. builder.build()             → DataAgentBootstrap.Builder.build() 三阶段（见 2.4）
6. bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry)
   + bootstrap.gateway().setSandboxlessAgents(sandboxlessAgents)
                                 ← 网关在每轮 turn 注入用户专属 Docker 容器（见 7.3）；
                                 ← sandboxlessAgents（当前 = modeling-agent）跳过 borrow
7. 创建 ChatUiChannel（dmScope 默认 PER_ACCOUNT_CHANNEL_PEER：每个 conversationId 一个隔离会话）
8. bootstrap.start(webChannel)
```

### 2.4 DataAgentBootstrap.Builder.build() 三阶段

`runtime/DataAgentBootstrap.java`：

- **Phase 1 共享会话基础设施**：读取 `agentscope.json` → 主 agent 提取子 agent 条目（data-explorer、report-writer）
  → `WorkspaceManager` + `DefaultAgentManager` → `SessionStore`（元数据落平台库 `session_registry` 表，JPA）
  → `SessionAgentManager` + `ChannelManager` + `HarnessGateway`
  → 共享工具：`SessionsTool`（子 agent 派生）、`OutboundTool`（主动推送，预注册进每个 agent 的 Toolkit）。
- **Phase 2 逐个构建 HarnessAgent**：`applyFileEntry`（应用 agentscope.json 的 name/sysPrompt/maxIters/workspace/skillRepositories）
  → 注入模型与 SessionsTool → 内置上下文管理配置：
  - `toolResultEviction`：maxResultChars=4000、previewChars=500（大工具结果落盘，上下文保持精简）；
  - `compaction`：triggerMessages=80、keepMessages=20、prune protectTokens=10000（降低长会话压缩频率与成本）；
  → 应用 per-agent 与 global customizers（即 2.3 第 4 步）。
- **Phase 3 注册路由**：所有 agent 注册进 `HarnessGateway.agentRegistry`，`bindMainAgent` 指定默认 agent。

### 2.5 工具后置注册

`tools/data/DataToolkitRegistrar.java`（`@PostConstruct`）在 Bootstrap 构建完成后，
向主 agent 的 Toolkit 注册（建模助手挂载走同款 `ModelingToolkitRegistrar`，见末条）：

- `DataAgentToolkit` — 知识检索与服务端图表工具（`retrieve_evidence` / `render_chart`）；
- `WrenToolkit` — 唯一结构化问数通道（`wren_describe_model` / `wren_run_sql` /
  `wren_query_cube`，见 4.6），依赖 `WrenQueryGateway`（`WrenInstanceRegistry` 的接口）、
  `MdlCatalog`、`DatasetGroupService` 与 `ConversationScopeRegistry`；
- `RunPythonTool(new SandboxBackedFilesystem())` — 沙箱 Python 执行工具。
  此处用独立代理实例即可：每轮调用的真实沙箱由 SandboxLifecycleMiddleware 绑定在
  RuntimeContext 上，工具调用拿到的就是同一个上下文。
- `tools/data/ModelingToolkitRegistrar.java` — 向 `modeling-agent` 后置挂载 `ModelingToolkit`
  二十一个建模工具（specs/019，ADR 0033 YAML-first）：工程文件工具 `list_files` / `read_file` /
  `write_file` / `patch_file`——写路径经 `ModelingHitlMiddleware` HITL 确认，写前三重闸
  （YAML parse → scratch 副本 `context validate --strict` → scratch 副本 `context build` +
  `dry-plan "SELECT 1"` 物化预检，specs/023；官方 validate 不反序列化 Cube/关系成员，
  缺 `type`/`join_type` 类 serde 错误只有 dry-plan 门能拦），只允许落在组工作区内、
  禁写 `wren_project.yml` 等平台文件；官方 CLI 命令包
  `wren_skills_list/get`（剧本按需投递；specs/022 起官方剧本首选 harness 原生技能挂载，
  此 CLI 通道为包缺失时的回退，见 5.6）、`wren_context_show/instructions`、`wren_dry_plan` /
  `wren_dry_run`、`wren_cube_list/describe/query`（`--sql-only` 模式免库预检）；关系工具
  `suggest_relations` / `decide_relation`（统一 CONFIRM/ADJUST/REJECT/SKIP）/ `decide_relations`
  （specs/024 批量多选：一次 HITL 卡列出全部候选——specs/036 起为三要素「表.字段 → 表.字段 +
  连接基数」，业务含义与置信度退场——用户勾选后一次写入并附 `context validate --strict` 诊断；
  validate 失败仅报告不回滚，因为写入侧是平台结构化渲染而非自由 YAML；确认一次即生效，之后
  不再复述或二次确认，纯重算的 `suggest_relations` 已从写工具门禁摘除）/ `add_relation`
  与 `validate_mdl`——确认动作直接写工程 `relationships.yml`（幂等 upsert：模型对相同且
  条件列互为子集视为同一条，命中做文本手术替换、未命中尾部追加；DB 仅保留候选建议队列，
  REJECTED 不动文件，specs/019 §7）；只读工具 `list_modeling_state` / `list_terms` /
  `list_business_rules`——`list_modeling_state` 渲染工作区快照（`ModelingToolkit#renderState`：
  模型/列/关系/Cube/视图清单与解析 issue，未播种时回落 DB 字段清单），业务规则读工作区文件
  （`MdlWorkspaceReader#readKnowledgeRules`，官方 load_knowledge_rules 语义：文件名排序拼接），
  术语仍读全局词典（`MdlSuggestionService#listTerms`）。Cube/View/术语/规则结构化写工具已全部
  退役（守卫 `ModelingToolkitTest#retiredStructuredWriteToolsAreGone`），`DocEnhanceService`
  不再进入建模工具链。

### 2.6 tools.json 与 MCP 挂载（WrenAI 语义层）

内置 agent 的 MCP server 声明在 `~/.agentscope/dataagent/workspace/tools.json` 的
`mcpServers` 段（当前保留 wren 演示声明：stdio 常驻子进程、指向演示 project、
`enableTools` 白名单只开 `run_sql` + `describe_schema`——M3 起问数运行期不再依赖该声明，
真实通道由 `WrenInstanceRegistry` 按知识库动态 spawn（见 4.6），演示声明保留仅作向后兼容）。

**沙箱盲点与 override 加载**：HarnessAgent 构建期本应经 `ToolsConfigLoader.load(wsManager)`
读取该文件，但 dataagent 的 agent filesystem 是沙箱态（`SandboxBackedFilesystem`），
bootstrap 期尚无沙箱容器，读取静默失败 → MCP 声明历史上从未生效。修复：
`DataAgentConfig#builderBootstrap` 用本地磁盘的
`WorkspaceManager(DataAgentBootstrap.DEFAULT_WORKSPACE_ROOT)` 显式加载 tools.json，
经 `b.toolsConfig(...)` 作为 override 传入——HarnessAgent 构建流程中 override 优先于
wsManager 读取。

**注册时机与容错**：`McpServerRegistrar.register` 在 HarnessAgent 构建线程（即 Spring
main 线程）同步执行，每个 server 经 `McpClientBuilder.buildAsync().block()` 阻塞构建
（stdio transport 直接 spawn 常驻子进程）；单个 server 失败只 warn 不中断启动，启动日志
可见 `Registered MCP server 'wren' (transport=stdio, enableTools=[...])`。

**enableTools 白名单**：per-server allowlist（`Toolkit#registration().enableTools`），
未列出的 MCP 工具不进入模型可见面。

工具职责与安全模型见 4.6；决策依据见 ADR 0018。

---

## 3. 主流程：Wren 语义建模与问数

平台的两条核心流程都围绕 Wren 语义层展开：**语义建模**（modeling-agent 把表结构 + 文档提炼为
关系/Cube/视图/业务规则并发布）与**问数**（data-agent 在已发布语义模型上执行逻辑查询）。
数据流向：上传/关联 → 自动基线发布（5.6）→ 对话建模完善（3.2）→ 显式发布 → 问数作答（3.1）。

### 3.1 问数链路（data-agent）

**示例**：用户 bob 在 Web UI 问「各项目的存储使用率是多少？」

```
浏览器 POST /api/agents/data-agent/chat/stream
  { message, sessionKey(=conversationId), groupIds(选中的知识库) }
    ↓
[1] ChatController.stream()        JWT 解 userId → AgentAccessGuard 校验 Tier.RUN
                                   → conversationId 规范化（缺省 mint UUID）
                                   → 审计 RUN_SESSION（每会话一次）
                                   → 斜杠命令短路（/new /reset /identity /dock_*）
    ↓
[2] ChatController.buildInbound()
   - conversationScopes.put(conversationId, groupIds)   ← 知识库范围落服务端注册表
   - RuntimeContext: userId + DatasetScope(ownerId, groupIds) [+ requestId]
   - InboundMessage(channelId=chatui, peer=bob, accountId=conversationId,
                    preferredAgentId=catalogService.resolveGatewayAgentId(...))
    ↓
[3] ChatUiChannel.dispatchStream() → ChannelRouter 解析路由
   → gateKey = MsgContext.canonicalKey()（含 userId/agentId/conversationId，会话隔离核心）
    ↓
[4] runtime/gateway/HarnessGateway.runStream()
   - resolveOrCreateMainSession(gateKey)  ← gateKey→sessionKey 映射（重启后从 session_registry 恢复）
   - attachUserSandboxContext(): UserSandboxRegistry.borrow(userId, agentId)
     → SandboxContext.externalSandbox（Priority-1 acquire，与浏览器工作区读写同一容器）
   - withGatedStream(gateKey, ...)         ← 同会话并发门（LocalSessionTurnGate）
    ↓
[5] HarnessAgent → ReActAgent ReAct 循环
   每轮 reasoning 前：
   - DataDynamicContextMiddleware.onSystemPrompt()
     按 DatasetScope 重建 [DATA_SOURCES_OVERVIEW]（只含可查询知识库的逻辑模型/Cube
     轻量目录，不含物理表名）与 [KNOWLEDGE_BASE_OVERVIEW]
     （知识文档、语义术语、业务规则与语义视图——规则与视图读组工作区文件
     knowledge/rules/ 与 views/，specs/019 §7）追加进 system prompt
   模型流式输出 → TextBlockDeltaEvent
   工具调用 → ToolNotificationMiddleware 发布 TOOL_CALL/TOOL_RESULT 到 ToolEventBus
   建模写调用 → ModelingHitlMiddleware 将首个调用置为 ASKING
              → RequireUserConfirmEvent + RequestStopEvent(PERMISSION_ASKING)
    ↓
[6] ChatController SSE 合并两条流：
   - 工具事件流：toolEventBus.events() 按 gateKey 懒匹配（首轮会话 mid-flight 创建也能命中）
   - token 流：TextBlockDeltaEvent → token 帧
   帧类型：tool_call / tool_result / hitl_request / token / done / error
   hitl_request 携带 replyId 与待确认工具 ID/name/input；done 帧携带 conversationId
   （不是存储层 sessionKey，防止前端错用导致会话分裂）
    ↓
浏览器 chat.ts 解析帧渲染（工具块、markdown、图表、Python 产物）
```

**SSE 帧示例**：

```
event: tool_call    data: {"type":"tool_call","toolName":"wren_run_sql","toolInput":"{...}","requestId":"...","seq":3}
event: tool_result  data: {"type":"tool_result","toolName":"wren_run_sql","toolResult":"## wren 语义查询结果\n\n..."}
event: hitl_request data: {"type":"hitl_request","replyId":"...","toolCalls":[{"id":"...","name":"decide_relation","input":{...}}]}
event: token        data: {"type":"token","data":"各项目存储使用率如下"}
event: done         data: {"type":"done","sessionKey":"a7c3f1e8-..."}
```

**同步端点** `POST /api/agents/{agentId}/chat/send` 走 `dispatch()`（非流式），返回完整回复。
**会话探测** `GET /api/agents/{agentId}/chat/session` 返回该 conversationId 是否已注册会话；
对 `modeling-agent` 还返回持久化 `ASKING` 调用，供页面刷新后恢复确认卡（见 3.2）。

### 3.2 语义建模链路（modeling-agent）

**示例**：用户 bob 在知识库的语义建模页（`DatasetGroupPage` 第五 Tab）发起对话
「帮我把订单表和客户表关联起来，并统计有效订单金额」。

```
浏览器 ModelingChatPanel
  POST /api/agents/modeling-agent/chat/stream
  { message, sessionKey(=modeling-<groupId>，同库会话跨刷新延续), groupIds=[当前知识库] }
    ↓
[1] ChatController.stream()        JWT → AgentAccessGuard → conversationId 规范化
    ↓
[2] ChatController.buildInbound()
   - RuntimeContext: userId + DatasetScope(ownerId, [groupId])
     ← 建模会话固定单库范围，也是长时记忆 kb-<groupId> 分域的依据（7.3）
    ↓
[3] HarnessGateway.runStream()     modeling-agent 免沙箱：跳过容器 borrow（7.3），
                                   本地文件系统工作区 workspace-modeling-agent
    ↓
[4] modeling-agent ReAct 循环      转录 logs/LLM-modeling.log（2.3）
   - 盘点：list_modeling_state / wren_context_show 看工作区现状
     （模型/列/关系/Cube/视图与解析 issue）
   - 规范：官方 wren skills 按需加载（generate-mdl / enrich-context / usage，specs/022）；
     MODELING_SCRIPT 只承载平台协议（HITL 纪律 / 写工具约束 / 发布出口 / 中文）
   - 写入：变更一律 write_file / patch_file 落工程 YAML（relationships.yml、
     cubes/<name>/metadata.yml、views/<name>/、knowledge/rules/）
    ↓
[5] ModelingHitlMiddleware         首个建模写调用置 ASKING 并暂停（2.3）
   → SSE hitl_request 帧 → 前端 ModelingHitlCard
   → 卡片挂载即调 POST .../modeling/workspace/preview（与真实写入完全相同的
     写前三重闸：YAML parse → scratch validate --strict → scratch build + dry-plan，
     不落盘）展示预校验结论 + 行级 diff，支持全文/替换文本编辑与重新预检
   → 用户确认/调整/拒绝 → 写盘即生效（工作区即真相，5.6）；同一轮只确认一项
    ↓
[6] 发布（语义建模页显式动作）→ MdlPublishService#doPublish（5.6 发布链 v2）
   → WrenQueryGateway#invalidate → 该组问数下次查询按新快照重新 spawn
```

**建模恢复** `POST /api/agents/modeling-agent/chat/confirm` 校验用户、知识库范围、replyId、
工具 ID/name、白名单和当前 ASKING 状态，再用 `Msg.METADATA_CONFIRM_RESULTS` 携带
`ConfirmResult` 沿原会话继续 SSE。

与问数链路的分工：两个 agent 共享同一 harness 管线（动态上下文注入、SSE 帧协议、HITL
中间件模式），差异在——建模助手免沙箱（本地文件系统 + 知识库级记忆分域，7.3/7.4）、
工作面是组工作区（wren project，5.6）、工具面是 ModelingToolkit 的 YAML-first 写面（2.5）；
问数助手工作面是已发布语义模型（4.6），经 per-user 容器执行 Python。建模产物（关系/Cube/
视图/规则）经发布成为问数的唯一查询边界（Wren-only，ADR 0029）。

---

## 4. 问数工具链

> Wren-only 风格：prompt 只预注入逻辑模型/Cube 轻量目录，详细 schema 按需获取；工具负责
> 「查看语义模型 → 执行逻辑查询 → 检索知识 → 出图」。注册方式见 2.5。

### 4.1 工具清单

| 工具 | 类/方法 | 职责 | 关键约束 |
|---|---|---|---|
| `wren_describe_model` | `WrenToolkit#wrenDescribeModel` | 按需查看逻辑模型字段、关系和相关 Cube | 仅接受已发布逻辑模型名；单次 1–5 个；关系投影默认折叠，`expand_relation_fields=true` 才展开；不暴露 datasetId、sourceId、schema 或物理表名；视图/Cube 名命中时报错并引导到 wren_run_sql / wren_query_cube（specs/020） |
| `wren_run_sql` | `WrenToolkit#wrenRunSql` | 在已发布语义模型上执行逻辑 SQL并返回自文档化 Markdown | 仅 SELECT/WITH；禁止 SQL 内显式 LIMIT/OFFSET；`group_id` 支持知识库名称（推荐）或完整 UUID（可见集合内解析，名称寻址见 4.2）；无有效快照时拒绝查询；结果同时落盘 data/ 数据文件（见 4.6 数据交接） |
| `wren_query_cube` | `WrenToolkit#wrenQueryCube` | Cube 结构化聚合查询 | cube+measures 必填；成员名以 Cube 清单为准；时间区间左闭右开；排序成员用原始名（输出别名如 order_time__month 由 `normalizeOrderBy` 归一回原始名）；DIRTY 组继续使用上次成功快照并提示；结果同时落盘 data/ 数据文件（见 4.6 数据交接） |
| `retrieve_evidence` | `DataAgentToolkit#retrieveEvidence` | 从知识库检索口径/制度/关系文档片段 | 应用层关键词召回（`DatasetContextProvider#evidenceFor` → `KnowledgeEvidence`），返回带出处的 top-3 片段；短文档可整篇注入 |
| `render_chart` | `DataAgentToolkit#renderChart` | 传入 columns+rows，服务端自动推断图表类型生成 ECharts option | 禁止模型自构 option；支持 KPI 目标参考线；option 落 `ChartOptionEntity` |
| `run_python` | `RunPythonTool#runPython` | 沙箱执行 pandas/matplotlib/scipy 代码，产出图片/CSV/md 到 `outputs/`；可直接 `pd.read_csv` 读取 wren 查询落盘的 `data/` 数据文件 | 沙箱 `--network=none`；120s 超时；强制中文字体 preamble；数据通道见 4.6 |

### 4.2 租户与知识库范围（DatasetScope）

`WrenToolkit#effectiveScope` 优先取 RuntimeContext 上的 `DatasetScope`（ownerId + groupIds）；
harness 带外执行工具时退化为 `rc.getUserId()`，再按 sessionId 从 `ConversationScopeRegistry`
补齐会话选择的知识库范围。`resolveGroup` 在“当前 owner 可见集合”（凡有会话选择则再按 scope 收窄）
内把 `group_id` 参数解析为知识库：优先按名称、回退完整 UUID——36 位 UUID 全上下文仅注入一次，
模型逐字符复现易错一位（2026-10-05 LLM.log 抄错一位得到权限色彩误报），短名称寻址在源头避开复现字符串；
解析不越出可见集合，缺失/越权/不在范围统一返回“未知或无权访问的知识库”，避免泄漏资源存在性。
`DataAgentToolkit#retrieveEvidence` 使用同一范围规则过滤知识内容。

### 4.3 SQL 安全护栏

- Agent 不接触 JDBC 连接、物理 schema 或 `ds_*` 表名；查询边界由已发布 MDL 限定。
- `WrenToolkit#wrenRunSql` 仅接受 SELECT/WITH，并拒绝显式 LIMIT/OFFSET，返回行数统一由工具参数控制。
- `WrenToolkit#resolveGroup` 在调用引擎前校验 `DatasetScope`、知识库归属、MDL 状态/版本和 manifest；`group_id` 可用知识库名称（推荐）或完整 UUID，解析不越出可见集合。
- Wren 只能访问快照所绑定的单一连接；混合上传+外部源、多外部源、非 MySQL 外部源在发布期拒绝。

### 4.4 结果形态

`WrenToolkit#wrenRunSql` / `wrenQueryCube` 返回自文档化 Markdown：知识库、问题或 Cube 参数、
逻辑 SQL和结果表；前端可直接展示并从中提取引用。

查询结果同时落盘为数据文件（specs/016，ADR 0030）：成功后服务端把结果 payload 渲染成
RFC 4180 CSV 写入会话沙箱工作区 `/workspace/runpython/<sessionId>/data/<sha256 前 12 位>.csv`
（与 `run_python` 执行目录同根，内容寻址幂等覆盖），返回文本在结果表后附
「**数据文件：** data/<文件>.csv（N 行 × M 列；run_python 中 pd.read_csv(...) 读取）」——
数据行走文件通道而不经 LLM 上下文搬运。降级语义：无沙箱代理 / 写入失败 / 超过 10MB 时只省略该行，
markdown 表照常返回；沙箱 idle 回收后文件随容器消失，模型重查自愈。

`run_python` 返回结构化报告：`executed_code` / `exit_code` / `stdout` / `artifacts`
（图片含 `image_ref: ![name](sandbox path)`，要求模型原样复制到最终回复，前端经工作区二进制 API 取图）。

### 4.5 提示词三层分工

规则按「是否每轮都在上下文里」分层，避免同一条规则在多处重复而互相漂移（ADR 0007）：

| 层 | 载体 | 何时进入上下文 | 放什么 |
|---|---|---|---|
| 常驻·人格与流程 | `DataAgentConfig#DEFAULT_AGENT_SYS_PROMPT` → `WorkspaceScaffolder` 写进 `workspace/AGENTS.md` | 每轮（harness `WorkspaceContextMiddleware#onSystemPrompt` 追加） | 只放「模型加载任何技能之前就必须成立」的内容：角色、最高优先级原则、工作流程骨架、输出语言与产物门（不主动出图 / 不主动出文件 / 全中文输出）、回答中的计数必须与自己列出的明细行数一致的自检义务、指向 `sql-analysis` 技能的一句话 |
| 常驻·动作点硬门 | `WrenToolkit` 的 `@Tool` 描述 | 每轮（工具 schema 随请求下发） | 已发布 MDL 前提、逻辑名称约束、SELECT/WITH 白名单、LIMIT/OFFSET 约束、Cube 参数与时间区间契约 |
| 按需·操作手册 | `shared/agents/data-agent/skills/*/SKILL.md` | 模型判断需要时才加载技能体 | 模型发现、JOIN/CTE 与去重写法、反探查规则、报告结构、matplotlib 标签语言与 CJK 字体、反模式清单 |

取舍：Wren-only 路由与禁止物理回退属于常驻硬约束；如何组织复杂 SQL、校验结果与生成报告等细节放技能。

一条规则到底该常驻还是该下沉，准绳是「它是否需要在模型还没选定技能时就生效」：是则常驻，
否则下沉。例如「不主动生成图表」必须常驻——`chart-rendering` 只在模型已决定出图后才会被加载，
下沉等于门失效；而 matplotlib 的标签语言与字体可以下沉——写这类代码前必然已加载
`python-analysis`。同一条规则的**同一句话**只在一层出现，两处都写就会漂移（ADR 0006 的规则失效正是
yml / 常量 / SKILL.md 三处各说一半的结果）。但「门 vs 手册」是刻意的分工：常驻层写一句话禁令或义务，
技能层写例子、替代做法与校验清单（如禁摸底查询、JOIN 去重），两者角色不同因而不算重复（ADR 0008）。

覆盖优先级：`DATAAGENT_AGENT_SYS_PROMPT` > `dataagent.agent.sys-prompt` > 内置常量。
`application.yml` 有意不声明 `sys-prompt` 键——声明为空会把 scaffold 出的 AGENTS.md 变成空提示词。

**运维事实（易踩）**：`WorkspaceScaffolder` 只在 `DataAgentConfig#ensureAgentscopeConfig`
发现 `agentscope.json` 缺失时执行一次，此后 `writeIfMissing` 不再覆盖用户编辑。因此内置提示词
的任何更新对已有部署**不会自动生效**；而删掉 `workspace/AGENTS.md` 也**不会**被重建——
`<agents_context>` 会变成空标签，常驻层 B 整段丢失（角色、原则、输出语言、产物门全部失效），
且启动日志里看不出错。要让新的内置提示词落地：删掉
`~/.agentscope/dataagent/agentscope.json` 后重启（`workspace/` 下的 `tools.json` /
`skills/` 等由 `writeIfMissing` 保留），或调用 `POST /api/agents/{agentId}/workspace/scaffold`。
共享层的技能不受此限制——`SharedWorkspaceSeeder` 有 sha256 簿记，未被手改的副本会自动升级（见第 8 章）。

守卫：`DataAgentConfigTest`（常驻两层，含「不复述技能细节」的反重复断言）、`SharedSkillContentTest`（技能层：非空、有 frontmatter、无退役工具名、确实承载了下沉的细节）。

### 4.6 WrenAI 单一语义查询通道

> 决策与实证依据见 ADR 0018 / 0020 / 0021 / 0029 / 0030。运行期统一为 `WrenToolkit` 三个 Agent 工具 →
> `WrenQueryGateway` → `WrenInstanceRegistry`（per-group `wren serve mcp` 子进程池）；
> `MdlCatalog` + `DataDynamicContextMiddleware` 只预注入轻量逻辑目录，发布后重建实例。

**通道定位（ADR 0029）**：Wren 是唯一结构化问数通道。数据上传、批量上传、外部表关联和删除后，
`BaselineMdlService` 自动发布基础 MDL；不存在物理 SQL 直查回退。`NONE` / `INITIALIZING` /
`FAILED` 组不可问数；`PUBLISHED` 使用当前快照；`DIRTY` 继续使用上一次成功快照，并明确提示草稿尚未发布。

**运行期链路（specs/010 M3，ADR 0020）**：

```
[DATA_SOURCES_OVERVIEW] 逻辑段（group_id + 逻辑模型 + Cube 清单）
  → 模型选 wren_run_sql / wren_query_cube（系统提示词官方决策树 + 工具描述）
  → WrenToolkit：租户/状态校验（引导错误 + LIMIT/OFFSET 硬门）→ WrenQueryGateway#call
  → WrenInstanceRegistry：per-group 实例（按需 spawn + 空闲回收）
      spawn: wren serve mcp --project <mdlRoot>/<groupId>/published --profile <profile>
      profile = published/wren-source.properties 钉入的 profile（M4；缺失回落默认 dataagent）
      env: WREN_HOME=<mdlRoot>/.wren（平台托管 profiles.yml，WrenProfileHome#ensureAll）
  → MCP 工具 run_sql / query_cube → 渲染 markdown 表回模型
发布成功（SemanticModelingController#mdlPublish）→ WrenQueryGateway#invalidate
  → 下次查询重新 spawn（Publish = rebuild；引擎启动期冻结 manifest）
```

**进程形态（stdio 子进程，非 web 服务）**：wren 不以独立 web 服务运行——`WrenInstanceRegistry#spawn`
经 MCP SDK 的 `StdioClientTransport`（底层 `ProcessBuilder`）把 `wren serve mcp --project
<mdlRoot>/<groupId>/published --profile <profile> --quiet` 作为 **Java 进程的 Python 子进程**拉起
（环境注入 `PYTHONUTF8=1`、`PYTHONIOENCODING=utf-8`、`WREN_HOME`），双方在 **stdin/stdout 管道**上
跑 MCP 协议（JSON-RPC 2.0）：启动 `initialize` 握手（60s 预算）→ `tools/list` 发现并缓存工具 →
每次查询 `tools/call`（run_sql / query_cube）把请求写进子进程 stdin、从 stdout 读回结果；stderr 只走
错误/日志。全程零端口、零网络——wren 的 `ui` 命令是带界面的完整 web 发行形态，本平台不用；MCP 协议
本身也定义了 SSE / Streamable HTTP 传输（SDK 均实现），但会引入端口管理与按租户路由的运维负担，
单机部署下 stdio 是最简形态。子进程收到 run_sql 后按启动期冻结的 `mdl.json` 把逻辑表/列名重写为
物理 SQL，经 profile 指定的 MySQL 连接执行。代价：一条管道一个 client 且 SDK 的 stdio client
非线程安全——同组调用必须串行（ADR 0023）；将来需要真并发时，出路是同组多实例轮转，而非给单进程
换 HTTP。

**外部数据源组的连接选择（M4，ADR 0021）**：外部源是运行期页面配置的（重启后才入库），
其表在远程实例上——一个 wren 工程一次只绑一条连接，所以发布期由
`MdlPublishService#selectWrenSource` 解析组的目标：全上传组 → 默认 profile（M3 行为不变）；
全部数据集来自同一 MySQL 外部源 → 专属 profile `ext-<datasourceId>`；其余（混合/多源/
源已删/非 MySQL）拒绝发布并返回结构化中文错误（跨实例 JOIN 物理不可能，响亮拒绝优于
静默产出坏快照）。选定后：`WrenSourceProber`（JDBC 实现）用目标连接按 information_schema
验证组内每张物理表存在（把 1146 从运行期提前到发布期），`WrenProfileHome#ensureAll` 幂等
全量重建 profiles.yml（默认 + 每个 MySQL 外部源一条，无库名 URL 的 database 写空串——
wrenai pydantic 接受空串、MySQL 允许不选库连接、重写后的物理 SQL 自带 schema 限定），
最后把选择钉入快照 `published/wren-source.properties`（`profile=<name>`），
`WrenInstanceRegistry#spawn` 读它选 `--profile`，缺失回落默认（M3 存量组零迁移）。profile
属于快照——DIRTY 组旧快照继续用旧连接作答，与「旧版本继续服务」语义一致。

**三工具分工**（Agent 侧 `WrenToolkit`；MCP 查询侧对应 `run_sql` / `query_cube`）：

| 工具 | 模式 | 职责 | 关键事实 |
|---|---|---|---|
| `wren_describe_model` | 元数据 | 按需返回 1–5 个逻辑模型的字段、关系与相关 Cube | 详情来自发布 manifest；关系 alias 及其计算投影默认折叠为关联字段组，按需展开；无关计算列保持可见；输出上限 20000 字；误传视图/Cube 名时整批拒绝并给对应工具引导（specs/020，ADR 0034） |
| `wren_run_sql` | 自由 | LLM 写全 SQL（逻辑模型名），wren 编译重写为物理表 SQL并执行 | wrenai connector 会在 SQL 尾部追加行数上限；入口正则拒绝显式 LIMIT/OFFSET，引导改用 limit 参数 |
| `wren_query_cube` | 结构化 | LLM 只出 cube + 指标 + 维度 + 过滤 + 排序，JOIN/聚合由 wren-core 按 MDL 声明确定性编译 | 命名指标优先；time_dimension 为左闭右开，`normalizeTimeDimension` 对同端点日历区间自动扩一档；order_by 成员必须是本次选中的原始名，`normalizeOrderBy` 把时间维度输出别名（如 order_time__month）归一回原始名 |

**Wren-only 路由（官方决策树，specs/025；覆盖判定 2026-10-05）**：`DataAgentConfig#DEFAULT_AGENT_SYS_PROMPT`、动态目录说明、
`sql-analysis` 与工具描述使用同一 prefer 决策树：① 聚合指标问题先核对 Cube 清单，已发布 Cube 成员
覆盖时优先用 `wren_query_cube`（引擎确定性编译聚合，错误率更低）——「覆盖」指问题的度量、分组维度、
时间粒度全部命中 Cube 成员；「按 X 的排名 / TOP-N」要求 X 是 Cube 维度成员（`dimensions=[X]` + `order_by`
度量 + `limit`），缺该维度即不覆盖、改用 `wren_run_sql` 按 View / 逻辑模型 `GROUP BY X` 排名，禁止不分组
而对度量排序取 TOP-N（那只是对聚合行排序，不是实体排名——官方 usage 技能同此口径，2026-10-05 探针
实测维度分组编译为 `GROUP BY 1, 2 ORDER BY 3 DESC LIMIT 10`）；② 已发布 View 能直接覆盖问题时
优先直接按视图名查询（视图口径已经建模审阅）；③ 跨模型属性先在 many 侧模型用
`expand_relation_fields=true` 展开关联字段组，查询单一逻辑模型及投影列，由 Wren 根据 relationship
condition 自动 JOIN；④ 语义资产都无法表达时才用 `wren_run_sql` 编写其他逻辑 SQL，显式 JOIN 是最后
兜底且只能引用逻辑模型名。全程禁止猜测物理表名、datasetId、sourceId 或回退物理 SQL。
路由措辞一律为 prefer 软引导，不存在确定性视图路由门——原 `WrenToolkit#preferredViewRouteError`
n-gram 意图门已删除（specs/025、ADR 0036）：词面匹配会误伤视图不覆盖的问题（问题词汇与视图名天然
重叠即触发）且无逃生通道，2026-10-04 LLM.log 实证造成整会话死锁；官方 usage 技能对语义资产
复用本就是决策树软引导，无任何硬拦截。

**轻量预注入**：`DataDynamicContextMiddleware` 按 `DatasetScope` 和组状态读取 `MdlCatalog#load`。
`PUBLISHED` / `DIRTY` 且 manifest 有效时，只渲染知识库 `group_id`、版本、逻辑模型名称与描述
（并在标题括号内明示「wren 工具的 group_id 参数可直接填本知识库名称」，名称寻址见 4.2），
Cube 行除名称和基础模型外还携带度量（含聚合表达式，如
`paying_customers=COUNT(DISTINCT customer_id)`）、维度与时间维度（空成员段整行省略）及截断 100 字的说明
（`DataDynamicContextMiddleware#buildLogicalSection` 与 `memberDefs`）；字段、关系和成员的类型与完整
描述仍由 `wren_describe_model` 按需返回。目录携带成员名的动机（2026-10-03 LLM.log 实证）：只有
Cube 名时模型无法把问题措辞（如「访问用户数 TOP10」）映射到 Cube 度量，命名指标问题会绕过
Cube 手写 SQL，口径固化价值落空；`wren_query_cube` 工具描述本就要求「度量、维度名称以
Cube 清单为准」，成员名进目录后该契约才真正成立。表达式与说明的补充动机（2026-10-05 LLM.log
实证）：首问「25年2月付费客户 top10」仅凭成员名把 `paying_customers` 词面匹配成「按客户排名」，
误调 Cube 得 1 行后才自纠——度量表达式（`COUNT(DISTINCT customer_id)` 不按客户分组）与
Cube 说明让模型能在目录层判「是否覆盖」；对应「覆盖判定」文案同步进入路由行、工具描述与
`sql-analysis`。中间件不再输出物理表 bullet、sourceId
或直查指令；`INITIALIZING` / `FAILED` 输出不可用状态，避免模型猜测查询。

**实例池事实（M3；调用串行化 2026-09-29，ADR 0023）**：按需 spawn（per-group 双检锁；
初始化 60s 预算）+ reaper 60s 轮询空闲回收（`dataagent.wren.instance-idle-seconds`，
默认 1800s）；传输失败 → 实例作废 + 503 引导重试；查询级错误（`ok=false`）保留实例、
原样回传诊断；spawn 前校验 `published/target/mdl.json` 存在（缺失 409 引导重新发布）。
实例为组级共享（查询彼此无状态）；同组调用经 per-group 门**串行**执行——MCP stdio
transport 非线程安全（`Sinks#tryEmitNext` 并发返回 FAIL_NON_SERIALIZED，并发 tool_use
必死、单调用正常，2026-09-29 实证），跨组互不阻塞。

**安全模型**：`WrenToolkit` 服务端校验租户可见性（scope 不含该组 / 组不属于调用者折叠为
同一不可区分错误「未知或无权访问的知识库」）和有效发布快照；MDL 编译边界在规划期阻断未建模表。
MDL 边界只管可见模型集合，不自动禁止未声明关系的 JOIN，因此关联路径仍由 MDL relationships
与 `sql-analysis` 规则共同约束。

**schema 上下文获取路径**：`[DATA_SOURCES_OVERVIEW]` 只注入逻辑模型/Cube 目录（Cube 行含成员名、
度量表达式与截断说明）；需要字段、关系、计算列或成员的类型与完整描述时调用 `wren_describe_model`。
关系投影默认只显示对端模型、字段数和自动 JOIN 提示；确定需要跨模型列时再传 `expand_relation_fields=true`。该折叠只影响
描述输出，发布 MDL 中的投影列保持完整。不调用通用 `describe_schema`，也不暴露物理 schema。
视图 schema 不走 describe：`[KNOWLEDGE_BASE_OVERVIEW]` 语义视图小节直接注入每视图折叠截断后的
statement（上游 `_describe_view` 对齐，specs/020 / ADR 0034）；describe 误传视图/Cube 名时由
`WrenToolkit#unknownModelMessage` 返回对应工具的路由引导。

**查询结果数据交接（specs/016，ADR 0030）**：`WrenToolkit` 注入独立 `SandboxBackedFilesystem`
代理（真实沙箱由 SandboxLifecycleMiddleware 绑定于 RuntimeContext，与 `RunPythonTool` 同款模式），
`wren_run_sql` / `wren_query_cube` 成功后把结果 payload 渲染为 CSV 落盘到会话沙箱
`/workspace/runpython/<sessionId>/data/`（与 `RunPythonTool` 同 workDir，code 内
`pd.read_csv('data/<文件>')` 相对路径即达；文件名 = payload sha256 前 12 位，同结果幂等覆盖，
服务端零状态）。动机：此前查询结果只能由模型抄写成 Python 字面量经 code 参数搬运，数据量增长后
参数序列化失败、分批灌入拼接污染、迭代耗尽；消费侧引导（`run_python` 工具描述与 `python-analysis`
技能模板）同步改为 read_csv 并明令禁止字面量抄写。`run_python` 接口零改动；落盘 best-effort
（无沙箱/写失败/超 10MB 只省略数据文件行，不阻塞查询）；不提供任何绕过 Wren 直读物理表的通道。

**实测要点（探针 → 已修复）**：`query_cube` 编译 SQL 与手写 SQL 交叉对数完全一致；上传
`ds_` 表混合 collation（utf8mb4_unicode_ci vs utf8mb4_0900_ai_ci）会使跨表 JOIN 报
Illegal mix of collations——`TableProvisioner` 建表 DDL 已统一 `utf8mb4_0900_ai_ci`（M3，
ADR 0020 D11）。

---

## 5. 数据集与知识库

### 5.1 概念模型

```
DatasetGroup（知识库，ownerId 隔离）
 ├── DatasetKnowledge（知识文档：口径、表关系、业务约束，一组一份）
 ├── Dataset（数据集）
 │    ├─ origin=上传: 物理表 ds_<owner8>_<dataset8>_<name> 落在 dataagent.dataset.datasource 指向的 MySQL
 │    └─ origin=datasource: 外部库已有表的只读关联（不复制数据）
 ├── DatasetRelation（表间关系边：SAME_COLUMN/SUFFIX/DOC）
 ├── SemanticBusinessRule（ownerId + groupId 隔离的审阅台账；生效面为组工作区
 │    knowledge/rules/*.md，specs/019 §7）
 ├── DocEnhanceTask / DocEnhanceProposal（文档差异分析任务与待审提案）
 └── KnowledgeGraph（历史 KG 表：已停用，见第 6 章）
SemanticTerm（语义术语：全局业务词典，term→解释/同义词/范围）
SemanticView（命名 SQL 视图：单条 SELECT/WITH，工作区 views/<name>/ 文件，见 5.6）
ExternalDataSource（用户配置的外部 JDBC 连接，可选采样）
```

元数据全部走 JPA（默认 H2：`~/.agentscope-dataagent/db.*`；生产 `jdbc` profile 切 MySQL/PG）。

### 5.2 上传导入流程

`DatasetService#ingest`（API：`POST /api/datasets`，multipart）：

```
1. 校验知识库归属 + 同名冲突 + 文件类型（.xlsx/.xls/.csv）
2. DatasetImportService#importFile：EasyExcel/CSV 流式解析 → TypeInferrer 类型推断
   → TableProvisioner 在数据集 MySQL 中建 ds_ 前缀物理表并写入数据
3. SchemaGenerationService#generateSchema：AI 生成表描述与每列业务描述
   （结合 JDBC 注释、低基数列采样值）
4. 元数据落 DatasetEntity（含 columnSchemaJson）→ registry.add(toDataSource)
5. Controller 在单次上传或整批上传完成后调用 BaselineMdlService，一次性发布可查询基础 MDL
```

`DatasetService#rebuildRegistry`（@PostConstruct）：启动时从 JPA 重建内存 DataSourceRegistry，数据集跨重启存活。

### 5.3 外部数据源关联

`ExternalDataSourceController`（`/api/datasources`）管理用户级 JDBC 连接；
`DatasetService#associateTables` 把外部库已有表关联进知识库：`DataSourceIntrospector`
读取列元数据/表注释/行数，可选低基数列采样（≤20 distinct 取 3 个样例），再经
`SchemaGenerationService` 生成 AI 描述。`origin=datasource`，删除时不 drop 物理表。

Wren 知识库当前只允许关联同一个 MySQL 外部数据源，拒绝与上传表混合、跨多个外部源或关联
PostgreSQL。`DatasetService#prepareAssociatedTables` 在任何 repository/registry 写入前完成整批表名、
重复项、已有关联、字段元数据、描述与行数预检；任一表失败时整批不写入。全部准备成功后再统一
持久化并注册，Controller 最后只触发一次基础 MDL 发布。

### 5.4 关系判定（全 LLM，ADR 0046）

**候选生成（唯一自动通道：LLM）**：表间关系候选全部由 `MdlSuggestionService#suggestRelationsLlm`
产生（`refreshRelations` 触发，两步语义判定——先按表描述/表行数/示例数据判定各表是事实表还是
维表，再只提议事实→维方向的关联：维表被引用列不一定是主键、业务键关联合法；事实表↔事实表、
维表↔维表严禁推荐；解析层仅当提案恰好维侧为主键形态列而事实侧非时做方向反转修复，其余原方向
保留、永不丢弃，人工兜底）。知识文档不再走正则解析为 DOC 边——文档内容仍经
[KNOWLEDGE_BASE_OVERVIEW] 注入，由建模对话的 LLM 自行据此提议关系；建模页人工录入
（`addManualRelation`）是第二个非自动入口。原规则推断层（`RelationInferenceService` 的
SAME_COLUMN/SUFFIX 推断与 specs/036 XOR 定向、`SchemaRelationInferrer` 图谱现场推断）已整体
退役删除：主键形态机械过滤会误杀非主键业务键关联（订单表.商品编码 ↔ 商品表.商品编码），
语义等价但写法不同（`user_id` vs `用户id`）的场景规则无法桥接——两能力都只有 LLM 能覆盖；
上传/关联/保存文档入口不再触发任何关系重建。

**候选管理（specs/010 M1）**：DB `DatasetRelationEntity` 是候选建议队列（PENDING/CONFIRMED/
REJECTED，specs/013 起支持复合键 `source_columns`/`target_columns`——发布时
`MdlPublishService#buildRelations` 逐列校验渲染 `a.c1 = b.c1 AND a.c2 = b.c2`）；记录永不自动
删除（无重建即无清理），`joinType`（MANY_TO_ONE 等）由唯一性探测补齐
（`MdlSuggestionService#probeJoinTypes`，COUNT DISTINCT vs 行数比对，绝不猜测）。
`DatasetGroupEntity` 的 `mdlState/mdlVersion/mdlPublishedAt` 为发布流程预留。

`DatasetService#relationsFor(owner, table)` 把命中边渲染为可读行 + 建议 JOIN 片段；当前无生产调用方（为 specs/010 建议服务预留）。

**关系候选 → MDL relationships 的现行通路（specs/019 YAML-first → specs/036 一次即生效）**：
LLM 建议候选（唯一自动通道，见上）+ joinType 唯一性探测构成候选池；建模对话经
`decide_relation` / `decide_relations` HITL 确认后直接写工程 `relationships.yml`（幂等 upsert，
DB 边仅保留候选建议队列，见 2.5 与 5.6）；确认一次即生效（specs/036：确认即写入并附
`context validate --strict` 结论，之后不复述不二次确认）。知识图谱 Tab 的图节点按数据集列
schema 现场构造、边只来自持久化关系（LLM/历史 DOC 行/manual），见 `DatasetGroupController#graph`。
见 ADR 0018 D2/D6/D9、ADR 0044 与 ADR 0046。

### 5.5 语义术语与口径落库（specs/011，ADR 0027）

`SemanticTermController`（`/api/semantic-terms`）维护全局业务词典；
`DatasetService#semanticTermsText()` 渲染进 `[KNOWLEDGE_BASE_OVERVIEW]` 的「业务术语」小节。

**建模对话口径入口（specs/011 M1 → specs/019 调整）**：术语管理走「语义配置」页 REST
（`SemanticTermController`，`/api/semantic-terms`）；建模对话保留只读 `list_terms`
（`ModelingToolkit#listTerms`，读全局词典），写入类工具 `create_term` / `delete_term` 已随
specs/019 结构化写面退役。原路由决策树在 YAML-first 形态下的对应落点：

| 口径形态 | 落点 | 生效方式 |
|---|---|---|
| 纯词义声明（"活跃用户=30天内登录过"） | 术语页登记（全局词典） | 保存即生效，下轮 SystemMessage 重建即注入 |
| 业务规则/口径约束 | `write_file` 写 `knowledge/rules/<name>.md` | 文件即生效（`MdlWorkspaceReader#readKnowledgeRules` 注入） |
| 单表聚合指标 | `write_file` 写 `cubes/<name>/metadata.yml` | 发布后可查（HITL 确认 + 三重闸） |
| HAVING/窗口/多表 JOIN | `write_file` 写 `views/<name>/` | 发布后可按视图名查询 |

术语不触发 MDL 重建（官方 MDL schema 无术语段，属知识层而非模型层）。术语租户隔离按方案 A
后置：全局字典 + `scope` 自由标签标注知识库。

### 5.6 数据集组 → WrenAI 语义层发布流（M1/M2/M3 已实施）

产品形态（ADR 0029）：**数据集组 == wren project**。表结构变化立即自动生成并发布基础 MDL；
语义建模页、文档增强和对话建模在此基础上完善关系、Cube、View、术语与业务规则。

```
上传 / 批量上传 / 外部表关联 / 删除数据集
  → BaselineMdlService#publishAfterDatasetChange（每次业务操作只发布一次）
  → MdlPublishService#doPublish（specs/019 M2，发布链 v2，与显式发布同一链条）：
     MdlSeeder#reconcile 播种对账（新数据集播种模型文件、缺失列追加、删除清理，只增不改）
     → 拷贝工作区到 staging（排除 .platform/、target/）
     → wren context validate --strict → wren context build
     → ref_sql 物化（staging target/mdl.json 改写为物理全限定名，specs/035/ADR 0043）
     → 逐视图 dry-run（ADR 0032）→ 逐派生模型 dry-run（ADR 0042）
     → 逐 cube `cube query --sql-only`（坏度量发布期拦截）
     → 原子替换 published/ 与 mdl.json → version +1
  → WrenQueryGateway#invalidate，下一次查询按新快照重新 spawn
```
工作区即真相（specs/019 拍板）：建模对话经 HITL 确认的文件变更即时生效于工作区；上述任一入口的
发布都全量快照当前工作区，语义建模页的显式发布按钮走同一 doPublish 链条（BASELINE/SEMANTIC
双模式与 DRAFT 草稿概念已随编译器退役）。
```

**发布串行化（per-group 互斥，2026-10-03）**：`MdlPublishService#publishBaseline` / `publish` 与
`validate` 全程持有组级 `ReentrantLock`。原因：前端多文件上传走并发单文件请求
（`Promise.allSettled`），每个上传各触发一次 baseline 发布，而所有发布共享同一 staging 目录与
固定 `published.next`/`mdl.json.next` 路径——无锁时后到的 `deleteTree` 会删掉先到者
正在校验的工程文件（实证错误：`wren_project.yml not found or empty`）。锁内后到的发布
基于最新数据重新 assemble，最终快照必然包含全部表；锁对象不驱逐（驱逐会破坏互斥）。
守卫：`MdlPublishServiceTest#concurrentBaselinePublishesAndValidateAreSerialisedPerGroup`。

**发布合并（specs/015，2026-10-03）**：自动基线发布统一经 `BaselineMdlService#publishAfterDatasetChange`，
入口取 `System.nanoTime()` 作为请求 tick（严格晚于本请求已提交的变更）；`MdlPublishService#publishBaseline(groupId, requestTick)`
在组锁内比对 `assembledAtTicks`——某次成功发布的 assemble 晚于本请求 tick 开始，必然已读到本请求的变更，
本次排队请求直接跳过，不再付出一轮 validate+build 子进程开销，并发上传风暴只触发必要次数的发布。
失败不记录覆盖，下一个排队请求仍会补发；空组重置路径经 `recordBaselineCoverage` 记录覆盖。
仅单实例有效（与文件型 staging 同一假设）；显式入口 `publishBaseline(groupId)` 永不合并。
守卫：`MdlPublishServiceTest#coalescedBaselineSkipsWhenLaterPublishAlreadyObservedTheChange` 等 3 用例、
`BaselineMdlServiceTest`（空组路径记录覆盖）。

**实施进度（specs/010）**：**M1 已落地**——`MdlSuggestionService`（`refreshRelations` =
LLM 建议合并去重 + `probeJoinType` 唯一性探测（规则边重建已随 ADR 0046 退役）；人审
`confirmRelation`/`rejectRelation`/`addManualRelation`；`suggestCubes` 与 cube CRUD）、
`SemanticModelingController`（`/api/dataset-groups/{id}/modeling`，每端点先经
`DatasetGroupService#getGroup` 做多租户 404 校验）、前端 `SemanticModelingPage`
（`DatasetGroupPage` 第五 Tab「语义建模」）；新表 `dataagent_semantic_cube`、
`DatasetRelationEntity.status/joinType` 与 `DatasetGroupEntity.mdlState/mdlVersion/mdlPublishedAt`
已随 M1 加列（5.4）。

**M2 已落地**（ADR 0019）——`MdlPublishService`（`preview` 纯内存拼装 + 快照 diff 对比；
`validate` 本地校验短路 → `wren context validate --strict`；`publish` = validate →
`context build` → `published/` 快照 → `mdl.json` → 组状态 PUBLISHED、版本 +1，任一步
失败旧快照与版本保持不变；`deleteArtifacts` 删知识库级联）、`WrenCli`/`WrenProperties`
（配置 `dataagent.wren.*`；类型经 `wren utils parse-types` 归一化禁手写，ADR 0018 D2）、
MDL 三端点 `GET /mdl` / `POST /mdl/validate` / `POST /mdl/publish`、前端
`MdlPublishPanel`（YAML 行级 diff + 验证/发布，失败横幅「已发布版本保持不变」）。
存量库兼容（M1 加列回填）：`ddl-auto=update` 只加列不回填，M1 之前创建的组行
`mdl_version` 为 NULL——加载 primitive int 字段即抛 JpaSystemException（知识库列表接口
500）。启动时 `DatasetGroupService#assignOrphans` 先调
`DatasetGroupRepository#backfillMdlDefaults`（原生 SQL 回填 `mdl_version=0`、
`mdl_state='NONE'`）再加载任何组实体；守卫 `DatasetGroupMdlBackfillTest`（先证明 NULL
行必炸，再证明回填后可加载）。
**M3 + Wren-only 收敛已落地**（ADR 0020、0029）——`WrenToolkit` 提供
`wren_describe_model` / `wren_run_sql` / `wren_query_cube`；`WrenInstanceRegistry` 管理 per-group
`wren serve mcp` 实例池（按需 spawn、空闲回收、发布后 invalidate、传输失败重建）；
`WrenProfileHome` 托管连接档案；`MdlCatalog` + `DataDynamicContextMiddleware` 仅渲染轻量逻辑目录；
旧物理直查工具与 JDBC connector 已删除。
**M4 已落地**（ADR 0021）——外部数据源组发布链路：`MdlPublishService#selectWrenSource`
发布期解析组连接目标（全上传 → 默认 profile；单一 MySQL 外部源 → `ext-<id>`；混合/多源/
源已删/非 MySQL → 结构化中文错误拒绝发布）、`WrenSourceProber`/`JdbcWrenSourceProber`
发布期连通性预检（information_schema 验证表存在，连接超时 8s，失败拒绝并报表名）、
`WrenProfileHome#ensureAll` profiles.yml 幂等全量重建（发布路径重渲染，单一事实源是数据库）、
`published/wren-source.properties` 快照连接钉入 + `WrenInstanceRegistry#resolveSnapshotProfile`
spawn 读取（缺失回落默认，M3 存量组零迁移）、`wren_run_sql` 入口 LIMIT/OFFSET 硬门
（wrenai `_apply_limit` 无条件尾部叠加的 1064 坑，工具描述同步禁止）；守卫：
`MdlPublishServiceTest`（混合组拒绝/多源拒绝/源删拒绝/非 MySQL 拒绝/预检失败不落快照/
ext marker 钉入）、`WrenProfileHomeTest`（多条目渲染/无库名空串/非 MySQL 跳过/DB 收敛）、
`WrenInstanceRegistryTest`（marker 读取/缺失回落/畸形回落）、`WrenToolkitTest`
（显式 LIMIT 拒绝）。PostgreSQL 外部源发布与跨实例 JOIN 明确不做（ADR 0021）。

**specs/019 读面切换已落地（YAML-first，ADR 0033 M4）**——关系确认写面改写工程
`relationships.yml`：`MdlSuggestionService#persistConfirmedRelation` 按「无序模型对 +
条件列互为子集」定位同一条，命中 `replaceEntryBlock` 文本手术（首个 `- ` 行固定顶层缩进，
仅同缩进行算条目起点，条目内嵌套序列不误计）、未命中尾部追加，幂等重复确认与交换方向
重确认都是原地替换；DB `DatasetRelationEntity` 降级为候选建议队列，REJECTED 不动文件。
问数注入切工作区文件源：业务规则 `DatasetService#semanticBusinessRulesText` →
`MdlWorkspaceReader#readKnowledgeRules`（官方 load_knowledge_rules 语义，按组分节），
语义视图 `DatasetService#semanticViewsText` → 工作区 `views/`（`DataDynamicContextMiddleware`
视图小节同步改口径「工程 views/ 文件，wren_run_sql 可直接按视图名查询」）；
`ModelingToolkit#listBusinessRules` 切文件源，`DocEnhanceService` 退出建模工具链；
DocEnhance 规则采纳双写（见 5.7）。守卫：`MdlSuggestionServiceTest`（幂等确认/交换重确认
替换不重复/拒绝不动文件/复合键生长）、`MdlWorkspaceReaderTest#readKnowledgeRules*`、
`ModelingToolkitTest`（listBusinessRules 读文件 + 外组拒绝 + 退役守卫）、
`DataDynamicContextMiddlewareTest`（标题与空源回归）。

**对话式建模入口（specs/013/017 → specs/019 YAML-first，ADR 0024/0031/0033）**：独立「建模助手」agent（`modeling-agent`，装配见 2.3/2.5）以文件为工作面：先 `list_modeling_state` / `wren_context_show` 盘点工作区现状（模型/列/关系/Cube/视图与解析 issue），写法规范以官方 wren skills 为唯一事实源（specs/022、ADR 0035：`FileSystemSkillRepository` 把 wrenai 包内 skills_content 原生挂载到本 agent，`load_skill_through_path` 按需加载 generate-mdl/enrich-context/usage 的 SKILL.md 与 references；`wren_skills_get` 为包缺失时的 CLI 回退通道），`MODELING_SCRIPT` 只承载平台协议层（HITL/写工具纪律/播种约定/发布出口/中文），YAML 结构与模板细节不再复述；变更一律经 `write_file` / `patch_file` 写工程 YAML（`relationships.yml`、`cubes/<name>/metadata.yml`、`views/<name>/`、`knowledge/rules/`），复杂口径经 `create_view` 一次成对写视图双文件并通过 scratch 三道预检（specs/035/ADR 0043）；ref_sql 派生模型仅人工编辑、agent 不主动创建，写路径由 `ModelingHitlMiddleware` 置为 `ASKING` 并暂停，前端 `ModelingHitlCard` 对文件写工具渲染专用变更卡：挂载即调 `SemanticModelingController` 的 `POST .../modeling/workspace/preview`（租户校验后经 `ModelingToolkitRegistrar#toolkit()` 复用与真实写入完全相同的闸①②代码路径，不落盘）展示预校验结论与行级 diff（`utils/diff.ts` 自实现 LCS），支持全文/替换文本编辑与重新预检；用户确认/调整/拒绝后由 `ChatController#confirmModeling` 通过原生 `ConfirmResult` 恢复（写前三重闸见 2.5；specs/019 §5）。同一轮只确认一项；关系候选走统一 `decide_relation`（CONFIRM/ADJUST/REJECT/SKIP），确认动作直接写 `relationships.yml`（幂等 upsert，DB 仅候选队列），避免拒绝后再发起第二次工具确认。发布仍是语义建模页的显式动作；「MDL 视图」与确认卡复用 `ReadOnlyRelationGraph` 的只读 G6 呈现。过程呈现任务行化（specs/021）：`ModelingChatPanel` 按事件到达顺序渲染「叙述段 ↔ 任务行」交替流（修正此前工具堆在文本前的时序错乱），每个工具一行状态（执行中 spinner/待确认/完成/失败/已拒绝）+ 中文任务标签（`taskLabel` 由工具名与参数派生，如「写入 views/xxx/sql.yml」），详情默认收起、点击展开完整 input/result（超长截断）；`hitl_request.call.id` 与 `toolCallId` 同源配对驱动「待确认」态；`MODELING_SCRIPT` 硬约束第 6 条要求过程一句话汇报、草案全文不进对话流（SSE 帧协议零改动）。

**语义视图（specs/011 M2 → specs/019 文件化，ADR 0027/0033；定位回归见 specs/035/ADR 0043）**：复杂
口径（跨表 JOIN/窗口/CTE/复合过滤）的 agent 首选载体（对齐官方 enrich-context sink 决策树）——建模
对话经 `create_view` 写 views/<name>/ 双文件（HITL 闸门 + scratch 三道预检 validate+build+dry-plan），
方言绑定风险由「工具描述函数对照表 + `requireViewSql` 黑名单附替代写法 + 发布链逐视图 dry-run」三层
机制化解；轻量口径仍可经 `write_file` 手工写入。事实源为工作区
`views/<name>/{metadata.yml,sql.yml}`（`statement` 块标量承载 SQL，`create_view` 渲染 `|-` 字面块；
建模对话经 `create_view` 写入并过 HITL 三重闸）。视图名与 model/cube 名经 `sanitizeIdentifier` 归一后
互斥；wren 引擎 `queryable_names` 含视图名，`wren_run_sql` 可直接按视图名查询。概览注入
`DatasetService#semanticViewsText` 渲染工程 `views/` 文件，每视图在名称与描述下附一行折叠、
超 400 字截断的 `SQL: <statement>`（上游 `_describe_view` 对齐：SQL 原文即视图的 schema，
问数模型免 describe 即得输出列；specs/020，ADR 0034）（`[KNOWLEDGE_BASE_OVERVIEW]`
「语义视图」小节；specs/019 §7 起以工作区文件为事实源；specs/037 起 REST 写侧与对话写侧同权——
`createCube`/`createView` 等 CRUD 在工作区锁内镜像写/删对应工程文件（文件写先于 DB 落库，
绝不单边写，ADR 0045））；
语义建模页视图卡片与术语只读卡片（管理在「语义配置」页）不变。

**派生模型（ref_sql 模型，specs/034 → specs/035 降级人工载体，ADR 0043）**：手工精修载体——
`models/<name>/{metadata.yml, ref_sql.sql}` 双文件（文件优先于 metadata.yml 内联 `ref_sql` 键），
模型不指物理表而是一段 SQL，走 sqlglot 直查路径、无视图那种 DuckDB 规划期函数绑定，用于承载
view 函数表达力覆盖不到的极端口径；官方实验证伪「引擎自解析内层逻辑名」假设——ref_sql 体内
裸名按连接默认库直通解析（MySQL 1146），故发布链在 `context build` 后把 staging
`target/mdl.json` 的 `refSql` 物化为物理全限定名（`RefSqlMaterializer`：词法提取 FROM/JOIN
引用、CTE/字符串/注释掩码、已限定名跳过；未解析引用报 error 中止发布），工作区文件保持逻辑名。
agent 不主动创建（`propose_derived_model` 已删除；`MODELING_SCRIPT` 硬规矩改为复杂口径一律
`create_view`），通用 `write_file`/`patch_file` 手工面保留。读取器 `MdlWorkspaceReader` 解析
refSql/refSqlPath（models/、views/ 缺 metadata.yml 的静默跳过已改 error issue）；`MdlView`
单列 `derivedModels`（`MdlModelView.refSqlPath` 指向 ref_sql.sql）；发布链逐派生模型
`dry-run -s 'SELECT * FROM "<name>"'` 兜底；`mdl.json` 带 `derived` 标记。语义建模页「表与
关系」后保留「派生模型」tab 作为手工资产只读展示面（AssetYamlBrowser 右栏渲染 ref_sql.sql）。

**发布语义与状态机（specs/019 M2，发布链 v2）**：

- 事实源唯一：语义资产以工作区工程文件为准，HITL 确认写入即生效；发布 = 全量校验 + 快照 + 版本。
  BASELINE/SEMANTIC 双模式与 DRAFT 草稿已退役（DB 语义实体停止作为事实源）；specs/037 起读侧同源
  （Cube/视图页签计数与内容同读工作区）且 REST 写侧（Cube/视图 CRUD）同步镜像工作区文件，
  文件写先于 DB 落库，DB 与工作区不再分叉（ADR 0045）。
- 发布链（`MdlPublishService#doPublish`）：`MdlSeeder#reconcile` 播种对账 → staging 拷贝（排除
  `.platform/`、`target/`）→ `context validate --strict` → `context build` → ref_sql 物化（staging
  `target/mdl.json` 改写物理全限定名，specs/035/ADR 0043）→ 逐视图 `dry-run`（ADR 0032）
  → 逐派生模型 `dry-run`（ref_sql 模型全链路解析兜底，ADR 0042）→ 逐 cube `cube query --sql-only`
  → 原子快照；任一环节失败产生 error issue、中止发布并保留旧快照
  （`mdlLastError` 记录原因）。
- 状态为 `NONE → INITIALIZING → PUBLISHED`；首版失败进入 `FAILED`。已有成功快照的重建失败进入
  `DIRTY`（工作区与快照存在待发布差异），保留旧快照、版本和可查询能力。空知识库删除全部工件并回到 `NONE`。
- 发布使用 `published.next` / `mdl.json.next` 预构建和原子替换；替换异常时恢复 previous 备份。
- 实例热加载不可行，发布成功后必须 `WrenQueryGateway#invalidate`，下一次查询重新 spawn。
- 文档类 RAG 继续由平台 `retrieve_evidence` 提供；历史知识库不迁移，由上线前清理。

### 5.7 文档驱动语义增强（specs/014，ADR 0028）

语义文档上传（前端入口为语义建模页「上传语义文档」，specs/028）仍经 `DatasetGroupController#uploadKnowledge` 保存；随后调用
`DocEnhanceService#triggerAnalyzeQuietly` 在 `boundedElastic` 上异步分析（上传自动触发知识图谱构建已废弃，ADR 0038）。增强失败只把任务置为
FAILED，不回滚已保存的文档，也不阻断上传响应。手动重跑和审阅端点位于
`/api/dataset-groups/{id}/modeling/enhance`，所有入口先以 ownerId + groupId 校验租户归属。

`DocEnhanceService#buildPrompt` 把当前表结构、关系、Cube、View、术语和组级业务规则与文档原文
同时交给模型；模型逐条返回 `NEW` / `PARTIAL` / `CONFLICT` / `COVERED`。`COVERED` 不落提案，
其余保存为 `DocEnhanceProposalEntity`，类型为 `TERM` / `BUSINESS_RULE` / `RELATIONSHIP` /
`CUBE` / `VIEW` / `MANUAL_FIX`。表和列引用在服务端重新解析；无法安全解析的 payload 降级为
`MANUAL_FIX`。同一 ownerId + groupId 下，PENDING 或 ADOPTED 指纹用于抑制重复提案。

采纳必须由用户在 `SemanticModelingPage` 的“文档语义增强”卡片逐条执行：Term、Relationship、
Cube、View 复用 `MdlSuggestionService` 的既有写入路径，Business Rule 由
`DocEnhanceService#createBusinessRule` **双写**（specs/019 §7）：先经 `MdlWorkspaceService`
写组工作区 `knowledge/rules/<sanitizeIdentifier(name)>.md`（问数生效面；同名文件自动追加
`_2.._99` 后缀，不覆盖既有规则），再保存 `SemanticBusinessRuleEntity` 审阅台账（同名校验、
ADOPTED 状态）；文件写入失败则整个采纳中止，DB 不留已采纳行。`CONFLICT` 与 `MANUAL_FIX`
只展示，不允许直接采纳。Relationship、Cube、View 采纳后标记 MDL DIRTY 并立即执行
`MdlPublishService#validate`，失败不关闭提案；Term 与 Business Rule 无需发布。规则注入经
`DatasetService#semanticBusinessRulesText` 按 ownerId 过滤组后由
`MdlWorkspaceReader#readKnowledgeRules` 按组分节（【组名】）渲染，
`DataDynamicContextMiddleware` 每轮加入 `[KNOWLEDGE_BASE_OVERVIEW]` 的「业务规则」小节。

不上传文档时，用户可把同样正文粘贴到建模对话。`MODELING_SCRIPT` 要求先调用
`list_modeling_state`、`list_terms`、`list_business_rules` 比较现状，再按内容形态选写面
（业务规则 → `write_file` 写 `knowledge/`；结构变更 → 模型/Cube/视图 YAML）；
已覆盖不重复创建、部分覆盖说明差异、冲突先询问。Calculated Column 直接写在模型 YAML 的
列上（`is_calculated: true` + `expression`），无需独立写入实体。

---

## 6. 知识图谱（已停用）

知识图谱构建链路已停用：平台不再从知识文档与数据集 schema 中提炼实体/关系三元组，跨表关系与
Schema Linking 职责由 Wren 语义层承接（`relationships.yml` / Cube / View，见 3.2 与 5.6）。
时间线：上传自动建图先于 2026-10-04 废弃（ADR 0038）；随后手动构建与查询链路一并停用（ADR 0040），
不再是任何现行主流程的一部分。

代码与数据表保留（未删除）：`dataset/KnowledgeGraphService`（`triggerBuild` / `semanticContext`）、
`KnowledgeGraphController`（`/api/dataset-groups/{id}/kg`）、`knowledge_graph_*` 系列 JPA 表、
前端 `KnowledgeGraphView`/`KgBuildConfigModal` 与「知识图谱」Tab——仅供历史数据查看，
日常建模与问数不经过该链路。

---

## 7. 会话管理与沙箱

### 7.1 会话隔离与并发

- `gateKey = MsgContext.canonicalKey()`，由 (channelId, userId, agentId, conversationId) 决定——
  每用户每会话互相不可见的实现。
- chatui 通道默认 `DmScope.PER_ACCOUNT_CHANNEL_PEER`：conversationId 流入 `MsgContext.group`，
  实现 ChatGPT 式多会话。
- `HarnessGateway.withGatedTurn/withGatedStream`：同一 gateKey 同一时刻仅一个 turn；
  忙时 `TurnBusyException` → 丢弃（Mono/Flux.empty()）。
- `contextKeyToSessionKey`：gateKey→sessionKey 内存映射；启动时 `restorePersistedMainSessions`
  从持久化会话存储恢复（平台库 `session_registry` 表，受 SessionResetPolicy 新鲜度过滤）。

### 7.2 会话状态持久化

- 对话上下文：每轮结束由 ReActAgent 写入 `AgentStateStore`（默认 `InMemoryAgentStateStore`；
  生产应提供分布式实现，见 `DataAgentConfig` 注释与 `dataagent.session.redis.*`）。建模 HITL 的
  `replyId` 和 `ToolCallState.ASKING` 同样保存在该状态中，刷新后由 session 探测接口恢复卡片。
- 会话元数据：`SessionStore` 落平台库 `session_registry` 表（JPA；网关启动时经 `restorePersistedMainSessions` 恢复 MAIN 会话路由）。
- 长会话控制：toolResultEviction（4000 字符驱逐大结果，run_python 豁免）+ compaction（80 触发/保留 20）。
- 斜杠命令：`/new`（新 conversationId）、`/reset`（清当前会话历史）、`/identity`、`/dock_<channel> <id>`（身份链接）。

### 7.3 每用户沙箱

`web/workspace/UserSandboxRegistry.java`：

- 键：`(userId, agentId)` → 一个长存 Docker 容器（镜像 `dataagent.sandbox.image`，
  内置 Python3 + pandas/matplotlib/scipy/numpy + Noto CJK 字体，`--network=none`）。
- `borrow()` 取用（无则创建并启动），`peek()` 只读探测（UI 文件树避免冷启动），
  `invalidate(userId, agentId)` 失效重建（userId 传 null 时对全用户生效），下次 borrow 重建容器并投影最新共享层。
- 空闲回收：`dataagent.sandbox.idle-ttl-min`（默认 15min），轮询 `eviction-poll-sec`（默认 60s）。
- 工作区投影：容器启动时把宿主机共享层 `shared/agents/{agentId}/` 下的
  `AGENTS.md / skills / subagents / knowledge` 只读投影进容器（`subagents/` 仅在
  `dataagent.agent.subagents-enabled=true` 时被 harness 加载，见 12.1）。
- 网关注入：`HarnessGateway#attachUserSandboxContext` 把该容器设为
  `SandboxContext.externalSandbox`（SandboxManager Priority-1 获取路径），
  保证 agent 执行与浏览器工作区 API 读写**同一个容器**。
- **免沙箱 agent**：`HarnessGateway#setSandboxlessAgents` 登记的 id（当前 = `modeling-agent`，
  由 `DataAgentConfig` 启动接线）在 `attachUserSandboxContext` 中直接跳过 borrow——既不创建
  容器，也不受 Docker 不可用影响；其装配期同样不设 `DockerFilesystemSpec`（无沙箱 spec → 无
  SandboxLifecycleMiddleware 与兜底 defaultSandboxContext），显式设 `LocalFilesystemSpec` +
  `GroupScopedMemoryNamespace`：本地文件系统命名空间按每轮 `DatasetScope` 分域，长时记忆
  （MEMORY.md / memory 日账本 / consolidation 状态）落在 `"kb-<groupId>"` 子目录，不同知识库
  的建模对话互不可见（specs/029、ADR 0039），工作区为独立空目录
  `~/.agentscope/dataagent/workspace-modeling-agent`（守卫
  `HarnessGatewaySandboxlessTest`；免沙箱决策见 ADR 0025）。
- **部署约束**：注册表是 JVM 内存态，多副本必须按 userId 做 sticky LB，
  否则两个 Pod 会为同一用户各起一个容器（见 `DataAgentConfig` javadoc）。

### 7.4 长时记忆体系（harness 内置管线，2026-10-05 调研）

两个 agent 共用同一套 harness 长时记忆管线（平台侧零配置即全开）：`MEMORY.md`
（consolidation 合并后的结构化记忆）+ `memory/YYYY-MM-DD.md`（flush 日账本）+
`.consolidation_state`（合并进度指针）。写入三通道：模型主动 `memory_save`、
`MemoryFlushMiddleware` 每轮结束后 LLM 抽取追加日账本、`MemoryMaintenanceMiddleware` +
`MemoryConsolidator` 按 ≥30min 最小间隔合并重写 MEMORY.md；读取是每轮无条件行为：
`WorkspaceContextMiddleware#onSystemPrompt` 把 MEMORY.md 包进 `<memory_context>` 注入
system prompt（空记忆时为空标签）。排查助手"重复犯错"先看该段注入内容，注意分通道：
建模走 `logs/LLM-modeling.log`、问数走 `logs/LLM.log`。

| | 问数助手（data-agent） | 建模助手（modeling-agent） |
|---|---|---|
| 载体 | per-`(userId, agentId)` 容器**可写层**（无 volume；宿主机只投影只读共享层，见 7.3） | 宿主机 `workspace-modeling-agent/`（免沙箱本地文件系统，见 7.3） |
| 隔离 | `IsolationScope.USER` 用户级：跨全部知识库共享同一份 MEMORY.md（聊天请求 groupIds 只进 DatasetScope 用于工具可见性与动态上下文注入，不参与记忆路径） | 知识库级 `kb-<groupId>`：跨库物理隔离（specs/029、ADR 0039） |
| 持久性 | 随容器销毁丢失（空闲 `dataagent.sandbox.idle-ttl-min` 回收 / 服务重启清空 registry / marketplace 审批 `invalidate`），属"容器会话内滚动记忆" | 磁盘持久，同库跨会话长期有效 |

问数助手跨库共享是已知且可接受的现状（硬事实每轮由 [DATA_SOURCES_OVERVIEW] 等动态注入，
记忆只是偏好性软背景，且容器短命使共享窗口有限）；若后续需对齐知识库级隔离，路径为给
`DockerFilesystemSpec` 增加与 `LocalFilesystemSpec` 同名的 `namespaceFactory` 扩展点并复用
`GroupScopedMemoryNamespace`——调研全文见 specs/029 附录（2026-10-05）。

---

## 8. 共享层

本平台不提供用户自建/发布 agent 的能力：智能体与能力清单由平台内置——内置技能
`sql-analysis` / `chart-rendering` / `python-analysis`，内置子 agent `data-explorer` /
`report-writer`；技能可由管理员在工作区编辑（`AgentSkillsController`，`/api/agents/{agentId}/skills`）。
内置内容经共享层分发到每个用户的沙箱投影（见 7.3）。

**共享层单一事实源**：出厂内容只在 `src/main/resources/shared/`（classpath）维护；项目根 `shared/`
是运行态目录（已 gitignore），由 `SharedWorkspaceSeeder#seedSharedTree` 在每次启动时物化。
写入策略是三态的，簿记在 `shared/.seed-manifest.json`（relPath → 出厂内容 sha256）：

| 磁盘状态 | 动作 |
|---|---|
| 文件缺失或 0 字节 | 写入出厂内容（**0 字节自愈**——空 SKILL.md 会因 frontmatter 解析失败而从 `available_skills` 静默消失） |
| 与 manifest 记录一致、但出厂内容已变 | 覆盖升级（内置技能随版本演进） |
| 与 manifest 记录不一致（运营手改） | 保留不动 |
| 不在 manifest 中（历史文件） | 保留不动 |

manifest 尚不存在时（首次升级到该策略）执行一次性「采纳」：出厂路径全部按 classpath 覆盖，让历史漂移的
副本收敛。删 `.seed-manifest.json` 可重新采纳，删单个文件可只强制重新物化该文件。

---

## 9. 通道与主动外发

| 通道 | 实现 | 说明 |
|---|---|---|
| `chatui` | harness `ChatUiChannel` | 主通道，始终开启 |
| `dingtalk` | harness 扩展（可选依赖） | agentscope.json 中 opt-in |
| `webhook` | `runtime/channel/webhook/WebhookChannel` | 通用 HTTP 入站：HMAC-SHA256 签名（`WebhookSignature`）、IP 白名单；回包支持 callback / long-poll 两种模式（`WebhookCallbackController` `/api/webhook/**`，公开端点） |

**主动外发**：`OutboundTool`（预注册进每个 agent）+ `OutboundService`/`OutboundController`（`/api/outbound/send`）
+ `ChannelManager.deliver`。子 agent 完成时 `HarnessGateway#tryDispatchAnnounce` 在请求者 gate 上
调度一轮 agent turn，把 announce 文本送回来源通道/用户。

---

## 10. 认证、权限、审计与用量

- **认证**：`SecurityConfig`（WebFlux Security）：`POST /api/auth/login`、actuator health/info、`/api/webhook/**` 公开；
  其余 `/api/**` 需 JWT（`JwtAuthFilter` 校验 Bearer 或 `?token=` 查询参数，后者供 `<img src>` 使用）。CORS 放开（含 Vite dev 5173）。
- **授权**：`web/share/AgentAclService` + `AgentAccessGuard`：每个 agent 按 Tier（RUN/EDIT/…）鉴权，
  Controller 入口统一 `guard.require(userId, agentId, Tier.X)`。
- **agent 目录**：`web/catalog/AgentCatalogService`（内置 agent 目录；平台不提供用户自建 agent）。
- **审计**：`web/audit/AgentActivityStore`（RUN_SESSION 等事件，`AgentActivityController` 查询）。
- **用量**：`web/usage/UsageStore`（每 turn 记录 userId/agentId/耗时，admin 端 `/api/admin/usage/...` 聚合）。
- **AI 草稿**：`web/ai/AgentDraftService`（聊天式生成 agent 定义，`/api/agents/draft`），语义建模页 AI 建议（`dataset/MdlSuggestionService`，经 `chatBlockingModeling` 转录进 `logs/LLM-modeling.log`）也复用它；直调模型时以与 DebugLoggingMiddleware 相同的 role/type/text 结构把输入输出转录进对应文件（见 2.3）。

---

## 11. 前端结构

React 18 + TypeScript + Vite SPA（`frontend/`），构建产物进 `classpath:/static/` 由后端托管。

- **页面**：
  - 聊天：`ChatPage`（SSE 消费 `api/chat.ts`，`ChatPanel`/`ToolCallBlock`/`EChartsBlock`/`PythonArtifactsPanel`/`Markdown` 渲染），`OntologyChatPage`
  - 配置：`configure/`（DatasetsPage、DatasetDetailPage、DatasetGroupPage（左侧导航：文件/知识图谱（已停用，历史展示）/树结构目录/语义建模；独立「关系说明文档」视图已删除，上传统一为建模页「上传语义文档」，specs/028。第五 Tab「语义建模」= 独立全屏路由 `/configure/modeling/:groupId`（specs/030 工作台，ADR 0041）：`AppShell` 在该路径隐藏全局会话侧栏、页面独占整个视口；顶栏 = 返回链 + 组名 + 状态徽章 + `ModelingChatPanel` 对话 dock 开关（默认展开可折叠、固定 520px，`variant=dock` 内联渲染无遮罩；建模变更经 `modeling:updated` 事件触发页面 refresh，不重置当前 tab）+ 语义完善度三档徽章（基线/完善中/已完善，specs/027）+ 七资产 tab 分段控件（总览｜表与关系｜派生模型｜Cube｜视图｜术语与规则｜MDL，active 为浅紫底紫边 pill，带计数徽章；tab 与选中资产进 URL `?asset=<key>&selected=<name>`，刷新/分享不丢位；旧 `?view=modeling` 链接重定向至本路由）+ 模型/派生模型/Cube/视图四 tab 为「左资产列表 + 右工作区 YAML 高亮」双栏浏览器（`components/modeling/AssetYamlBrowser`，行级「未发布」橙点 = `/mdl` preview 工作区 vs publishedFiles 内容差异；资产→文件映射由 `MdlWorkspaceReader` path 透传，派生模型右栏渲染 ref_sql.sql，见 5.6）+ DIRTY 状态条带草稿计数与「去发布」入口 + 文档语义增强多类型提案审阅卡（specs/011、014），助手回复按 Markdown 渲染）、SemanticConfigPage、SkillsPage、SubagentsPage、ToolsPage、ChannelsPage、SettingsPage）
  - 管理：`admin/`（Overview、Agents、Users、Channels、Instances、Sessions、Usage、Config、Debug）
  - 其他：Login、Profile、Workspace、Usage、Appearance、UserBindings
- **MDL 可视化与确认**：`MdlPublishPanel`（YAML diff + 验证/发布）、`MdlGraphView`（G6 只读 ERD）与 `ModelingHitlCard`（写工具确认/调整；文件写工具 `write_file`/`patch_file` 走专用变更卡——服务端预检结论、行级 diff 折叠、全文/替换文本编辑、重新预检，specs/019 §5，行级 diff 由 `utils/diff.ts` 自实现）；`MdlGraphView` 和关系确认卡复用 `ReadOnlyRelationGraph`，保持只读语义。
- **API 层**：`frontend/src/api/*.ts` 一一对应后端控制器（chat/datasets/datasources/knowledgeGraph/semantic/semanticModeling/ontology/sessions/…）。
- **工作区**：`WorkspaceFileTree` + `WorkspaceEditor` 经 `AgentWorkspaceController` 读写用户沙箱文件。

---

## 12. 配置与启动

### 12.1 关键配置（`application.yml`，`dataagent.*` 前缀 / `DATAAGENT_*` 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `dataagent.jwt.secret` | dev 占位 | 生产必须覆盖（≥32 字符） |
| `dataagent.workspace` | JVM cwd | 运行态工作目录 |
| `dataagent.agent.name` | `data-agent` | 自动生成 `agentscope.json` 时的 agent 名；系统提示词默认用内置常量，覆盖用 `DATAAGENT_AGENT_SYS_PROMPT`（见 4.5） |
| `dataagent.agent.subagents-enabled` | false | 子代理编排（harness `agent_spawn` / `agent_send` / `task_*`）。关闭可省下每轮固定约 6.2k 字符的 `## Subagents` 提示词段与相应工具 schema；**不影响网关、会话路由与聊天**（`buildSubagentEntries` 不读该开关），内置定义也不删（ADR 0009） |
| `dataagent.openai.*` | DeepSeek 默认值 | 主模型（任意 OpenAI 兼容端点） |
| `dataagent.dashscope.*` | — | 兜底模型 |
| `dataagent.sandbox.image` | `agentscope/dataagent-sandbox:latest` | 沙箱镜像（`docker/sandbox.Dockerfile` 构建） |
| `dataagent.dataset.datasource.*` | 本地 MySQL `data_agent` 库 | **上传数据集物理表所在库** |
| `dataagent.wren.*` | `wren` CLI / `~/.agentscope/dataagent/mdl` / `mysql` / 180s / 1800s | WrenAI CLI 与 MDL 产物根（拼装/validate/build/发布快照，specs/010 M2，见 5.6）；`profile`/`instance-idle-seconds` 供运行期 `serve mcp` 实例池用（M3，见 4.6/ADR 0020）。M4 起 `profile` 是默认回落值——外部数据源组的实际连接由快照内 `wren-source.properties` 钉入（`ext-<datasourceId>`，发布期解析，见 4.6/ADR 0021）。`executable` 默认值按平台探测：Linux 先查 `~/.local/bin`/`/usr/local/bin`/`/usr/bin` 再回落 PATH（`pip install` 后零配置），Windows 需显式指向 venv `wren.exe`；`skills-dir` 指向 wrenai 包内官方 skills 根（specs/022、ADR 0035，空值从 executable 位置推断，失效仅 warn 降级 CLI 通道）；CLI 与 serve mcp 两处 spawn 失败统一回跨平台安装引导（`WrenProperties#resolveExecutable`/`unavailableMessage`），集群部署前置见 `docs/cluster-deploy.md` |
| `dataagent.analytics.enabled` | false | 预置分析库（tenant_storage_utilization / project_info） |
| `dataagent.expose-app-db` | false | 是否把平台元数据库暴露为数据源 |
| `dataagent.session.redis.*` | 关闭 | 分布式会话（HA 必需；多副本另需 sticky LB，见 7.3） |
| `spring.datasource.*` | H2 文件库 | 平台元数据（JPA）；生产激活 `jdbc` profile 切 MySQL/PG |

### 12.2 构建与运行

```bash
# 构建（含前端：自动安装 Node → npm build → 后端打包）
mvn -pl agentscope-examples/agents/agentscope-dataagent -am package -DskipTests

# 格式门禁（CI 必过）
mvn -pl agentscope-examples/agents/agentscope-dataagent spotless:check

# 沙箱镜像（一次性）
docker build -f docker/sandbox.Dockerfile -t agentscope/dataagent-sandbox:latest .

# 运行
java -jar target/agentscope-dataagent-*-exec.jar   # → http://localhost:8080
```

H2 种子账号：`bob/bob`、`alice/alice`（`data-h2.sql`，幂等 MERGE）。

### 12.3 首次启动自动生成的文件

```
~/.agentscope/dataagent/
├── agentscope.json          ← agent 定义（main=data-agent, maxIters=20）+ 通道配置
├── workspace-modeling-agent/ ← 建模助手独立空工作区（免沙箱本地文件系统；长时记忆按
│                               知识库分域 <owner>/kb-<groupId>/，specs/029、ADR 0039，见 7.3）
└── workspace/               ← 私有层工作区种子（WorkspaceScaffolder 生成）
    ├── AGENTS.md            ← data-agent 系统提示词（可编辑；分层见 4.5）
    ├── tools.json           ← MCP server 声明（构建期本地加载 override，见 2.6）
    ├── skills/              ← 私有层技能（example-skill 示例；内置业务技能在下面的共享层）
    ├── subagents/           ← 子 agent 定义（开关关闭时不加载，见 12.1）
    └── memory/              ← 运行时长期记忆（自动管理）

${cwd}/shared/               ← 运行态共享层（SharedWorkspaceSeeder 物化，已 gitignore）
├── .seed-manifest.json      ← 出厂内容 sha256 簿记（见第 8 章）
└── agents/data-agent/
    ├── skills/              ← 内置技能（sql-analysis / chart-rendering / python-analysis）
    └── subagents/           ← 内置子 agent（data-explorer / report-writer；默认不加载，见 12.1）

~/.agentscope/dataagent/mdl/  ← MDL 产物根：<groupId>/{project,published,mdl.json} + .wren/profiles.yml（specs/010 M2/M3；published/wren-source.properties 钉入组连接选择，M4）
~/.agentscope-dataagent/db.* ← H2 平台元数据库（用户/数据集/图谱等 JPA 表）
```

---

## 13. 关键类与文件索引

| 关注点 | 类（锚点） |
|---|---|
| 应用入口 | `web/DataAgentApp` |
| Spring 装配/模型 | `web/config/DataAgentConfig`（`builderBootstrap`、`openaiModel`、`dashscopeModel`） |
| 安全 | `web/config/SecurityConfig`（`JwtAuthFilter`） |
| Bootstrap 三阶段 | `runtime/DataAgentBootstrap`（`Builder.build`、`applyFileEntry`） |
| 网关/沙箱注入/并发门/announce | `runtime/gateway/HarnessGateway`（`runStream`、`attachUserSandboxContext`、`setSandboxlessAgents`、`withGatedStream`、`tryDispatchAnnounce`） |
| 聊天端点/SSE/HITL 恢复 | `web/api/ChatController`（`stream`、`confirmModeling`、`currentSession`、`buildInbound`、`toAgentFrame`、`toToolFrame`） |
| 动态上下文注入 | `runtime/session/DataDynamicContextMiddleware`（`onSystemPrompt`；M3 按组分流读 `MdlCatalog`） |
| Wren 问数工具 | `tools/data/WrenToolkit`（`wrenDescribeModel`、`wrenQueryCube`、`wrenRunSql`）与 `tools/data/DataAgentToolkit`（`retrieveEvidence`、`renderChart`） |
| Python 沙箱工具 | `tools/data/RunPythonTool`（`runPython`） |
| 图表生成 | `tools/data/ChartBuilder`（`build`、`applyMarkLine`） |
| 工具注册 | `tools/data/DataToolkitRegistrar` |
| 数据源注册表/种子 | `tools/data/InMemoryDataSourceRegistry`、`tools/data/DataToolkitConfig` |
| 数据集生命周期 | `dataset/DatasetService`（`ingest`、`associateTables`、`rebuildRegistry`、`relationshipsText`、`semanticTermsText`、`semanticViewsText`、`relationsFor`） |
| 文件解析/导入 | `dataset/DatasetImportService`、`dataset/parser/{ExcelParser,CsvParser,TypeInferrer}` |
| AI 字段描述 | `dataset/parser/SchemaGenerationService` |
| 外部库内省 | `dataset/DataSourceIntrospector`、`dataset/TableProvisioner` |
| 语义建模（M1） | `dataset/MdlSuggestionService`（`refreshRelations`、`confirmRelation`、`addManualRelation`、`suggestCubes`、`createCube`/`createView` 及 specs/037 工作区镜像写、`persistConfirmedRelation`）、`web/api/SemanticModelingController` |
| 对话式建模与原生 HITL（specs/013/014/017/019/022） | `tools/data/ModelingToolkit`（二十工具，YAML-first 写面 `write_file`/`patch_file` + 统一 `decide_relation` + HITL 预检 `previewChange`）、`web/middleware/ModelingHitlMiddleware`、`tools/data/ModelingToolkitRegistrar`（单例 `toolkit()` 供 HTTP 预检复用同闸）、`web/api/SemanticModelingController`（`workspace/file`、`workspace/preview`）、`web/config/DataAgentConfig`（剧本 + 免沙箱装配 + 技能挂载 `mountWrenSkills`）、`dataset/WrenSkillsLocator`（官方 skills 目录解析）、前端 `components/{ModelingChatPanel,ModelingHitlCard,ReadOnlyRelationGraph}` + `utils/diff.ts` |
| 文档语义增强（specs/014） | `dataset/DocEnhanceService`（`triggerAnalyzeQuietly`、`adopt`）、`web/api/SemanticModelingController`（enhance 端点）、`web/persistence/jpa/{DocEnhanceTaskEntity,DocEnhanceProposalEntity,SemanticBusinessRuleEntity}`、前端 `pages/configure/SemanticModelingPage` |
| MDL 拼装/发布（M2 → specs/019 v2） | `dataset/MdlPublishService`（`preview`、`validate`、`publish`、`deleteArtifacts`、`view` 只读视图）、`dataset/{MdlSeeder,MdlWorkspaceService,MdlWorkspaceReader,WrenTypeNormalizer}`（播种/工作区/读面）、`dataset/{WrenCli,WrenProperties}`、前端 `components/{MdlPublishPanel,MdlGraphView}` |
| Wren 运行期（M3） | `tools/data/WrenToolkit`（`wrenRunSql`、`wrenQueryCube`）、`runtime/wren/{WrenQueryGateway,WrenInstanceRegistry}`（`call`、`invalidate`、`resolveSnapshotProfile`）、`dataset/{WrenProfileHome,MdlCatalog}` |
| 外部源发布链（M4） | `dataset/MdlPublishService`（`selectWrenSource`、`writeWrenSourceProperties`）、`dataset/{WrenSourceProber,JdbcWrenSourceProber}` |
| 知识图谱（已停用） | `dataset/KnowledgeGraphService`、`dataset/SchemaTripleExtractor`（链路停用，见第 6 章） |
| 会话管理 | `runtime/session/SessionAgentManager`、`SessionStore`、`SessionEntry` |
| 会话作用域(知识库) | `web/session/ConversationScopeRegistry` |
| 每用户沙箱 | `web/workspace/UserSandboxRegistry`（`borrow`、`invalidate`）、`DataAgentWorkspaceConfig` |
| 工作区种子 | `web/scaffold/WorkspaceScaffolder`、`web/workspace/SharedWorkspaceSeeder` |
| ACL 鉴权 | `web/share/{AgentAclService,AgentAccessGuard}` |
| Webhook 通道 | `runtime/channel/webhook/WebhookChannel`、`WebhookCallbackController`、`WebhookSignature` |
| 主动外发 | `runtime/outbound/{OutboundTool,OutboundService,OutboundController}` |
| 会话/工具事件历史 | `web/session/SessionTurnParser`、`web/toolbus/{ToolEventBus,ToolNotificationMiddleware}` |

---

> 维护约定：修改主流程/装配顺序/工具链/数据模型时，必须同步更新本文档对应章节；
> 架构取舍（为什么这么做）写到 `docs/adr/`，本文档只描述「是什么」。
