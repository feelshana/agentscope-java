# agentscope-dataagent 架构与流程详解

> 本文档面向开发者与 AI 编码助手，基于当前代码实际实现梳理（非上游 agentscope 示例的原始版本）。
> 核心差异：问数工具链已重写为 TC-DataAgent 风格（`prepare_data_context` / `query_structured_data` / `retrieve_evidence` / `render_chart`），
> 并新增数据集/知识库/语义术语/知识图谱/外部数据源关联等模块。
> 引用代码位置时使用「类名 + 方法名」锚点，不引用行号（行号会漂移，类名可由 SearchSymbol 定位）。

---

## 目录

1. [项目定位与分层架构](#1-项目定位与分层架构)
2. [启动装配流程](#2-启动装配流程)
3. [主流程：一次提问的完整链路](#3-主流程一次提问的完整链路)
4. [问数工具链（TC 式四工具 + run_python）](#4-问数工具链)
5. [数据集与知识库](#5-数据集与知识库)
6. [知识图谱](#6-知识图谱)
7. [会话管理与沙箱](#7-会话管理与沙箱)
8. [能力市场与共享层](#8-能力市场与共享层)
9. [通道与主动外发](#9-通道与主动外发)
10. [认证、权限、审计与用量](#10-认证权限审计与用量)
11. [前端结构](#11-前端结构)
12. [配置与启动](#12-配置与启动)
13. [关键类与文件索引](#13-关键类与文件索引)

---

## 1. 项目定位与分层架构

dataagent 为每位数据分析师提供专属的数据 Agent：上传 Excel/CSV 或关联外部数据库表即可问数；
知识库（Knowledge Base）承载口径文档、表关系说明与语义术语；知识图谱做 Schema Linking；
能力市场让技能/子 Agent 经审批后全局共享。

```
┌─────────────────────────────────────────────────────────────────────┐
│  Web 业务层（本模块 web/ 包）                                        │
│  ChatController / JWT认证 / ACL权限 / 数据集·知识库·图谱 API /        │
│  ToolEventBus(SSE) / 审计 / 用量 / AgentCatalog(用户自定义agent)      │
├─────────────────────────────────────────────────────────────────────┤
│  问数工具层（tools/data/ 包）                                        │
│  DataAgentToolkit(prepare_data_context / query_structured_data /     │
│    retrieve_evidence / render_chart) + RunPythonTool(run_python)     │
│  JdbcSqlConnector / ChartBuilder / InMemoryDataSourceRegistry        │
├─────────────────────────────────────────────────────────────────────┤
│  数据资产层（dataset/ 包）                                           │
│  DatasetService(上传导入·外部表关联) / RelationInferenceService /     │
│  KnowledgeGraphService / SchemaGenerationService(AI生成字段描述)      │
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
                                 并调用 WorkspaceScaffolder 生成共享工作区种子
                                 （AGENTS.md=系统提示词、skills/、subagents/、knowledge/）
3. builder.model(model)
4. builder.configureAllAgents(b -> { ... })   ← 对【每个】HarnessAgent 生效的横切配置：
   - middleware: ToolNotificationMiddleware（工具事件 → ToolEventBus → SSE）
   - middleware: DebugLoggingMiddleware（调试日志）
   - middleware: DataDynamicContextMiddleware（每轮重建 [DATA_SOURCES_OVERVIEW]
     与 [KNOWLEDGE_BASE_OVERVIEW] 追加到 system prompt —— TC 式动态上下文注入）
   - stateStore: AgentStateStore（默认 InMemoryAgentStateStore；生产应提供分布式实现）
   - filesystem: DockerFilesystemSpec(IsolationScope.USER)，镜像 dataagent.sandbox.image
   - disableFilesystemTools() + disableShellTool()  ← 数据分析场景不需要文件/Shell 工具
   - toolResultEviction: 将 run_python 加入排除名单，保证产物元数据不被驱逐
5. builder.build()             → DataAgentBootstrap.Builder.build() 三阶段（见 2.4）
6. bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry)
                                 ← 网关在每轮 turn 注入用户专属 Docker 容器（见 7.3）
7. 创建 ChatUiChannel（dmScope 默认 PER_ACCOUNT_CHANNEL_PEER：每个 conversationId 一个隔离会话）
8. bootstrap.start(webChannel)
```

### 2.4 DataAgentBootstrap.Builder.build() 三阶段

`runtime/DataAgentBootstrap.java`：

- **Phase 1 共享会话基础设施**：读取 `agentscope.json` → 主 agent 提取子 agent 条目（data-explorer、report-writer）
  → `WorkspaceManager` + `DefaultAgentManager` → `SessionStore`（元数据落 `sessions.json`）
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
向主 agent 的 Toolkit 注册：

- `DataAgentToolkit` — 四个 TC 式问数工具（见第 4 章）；
- `RunPythonTool(new SandboxBackedFilesystem())` — 沙箱 Python 执行工具。
  此处用独立代理实例即可：每轮调用的真实沙箱由 SandboxLifecycleMiddleware 绑定在
  RuntimeContext 上，工具调用拿到的就是同一个上下文。

同理，`web/marketplace/ContributionToolRegistrar` 注册 `contribute_to_workspace` 工具。

---

## 3. 主流程：一次提问的完整链路

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
   - resolveOrCreateMainSession(gateKey)  ← gateKey→sessionKey 映射（重启后从 sessions.json 恢复）
   - attachUserSandboxContext(): UserSandboxRegistry.borrow(userId, agentId)
     → SandboxContext.externalSandbox（Priority-1 acquire，与浏览器工作区读写同一容器）
   - withGatedStream(gateKey, ...)         ← 同会话并发门（LocalSessionTurnGate）
    ↓
[5] HarnessAgent → ReActAgent ReAct 循环
   每轮 reasoning 前：
   - DataDynamicContextMiddleware.onSystemPrompt()
     按 DatasetScope 重建 [DATA_SOURCES_OVERVIEW]（数据源+表名+描述）
     与 [KNOWLEDGE_BASE_OVERVIEW]（知识文档全文+语义术语表）追加进 system prompt
   模型流式输出 → TextBlockDeltaEvent
   工具调用 → ToolNotificationMiddleware 发布 TOOL_CALL/TOOL_RESULT 到 ToolEventBus
    ↓
[6] ChatController SSE 合并两条流：
   - 工具事件流：toolEventBus.events() 按 gateKey 懒匹配（首轮会话 mid-flight 创建也能命中）
   - token 流：TextBlockDeltaEvent → token 帧
   帧类型：tool_call / tool_result / token / done / error
   done 帧携带 conversationId（不是存储层 sessionKey，防止前端错用导致会话分裂）
    ↓
浏览器 chat.ts 解析帧渲染（工具块、markdown、图表、Python 产物）
```

**SSE 帧示例**：

```
event: tool_call    data: {"type":"tool_call","toolName":"query_structured_data","toolInput":"{...}","requestId":"...","seq":3}
event: tool_result  data: {"type":"tool_result","toolName":"query_structured_data","toolResult":"## 查询结果\n\n..."}
event: token        data: {"type":"token","data":"各项目存储使用率如下"}
event: done         data: {"type":"done","sessionKey":"a7c3f1e8-..."}
```

**同步端点** `POST /api/agents/{agentId}/chat/send` 走 `dispatch()`（非流式），返回完整回复。
**会话探测** `GET /api/agents/{agentId}/chat/session` 返回该 conversationId 是否已注册会话。

---

## 4. 问数工具链

> TC-DataAgent 风格：schema 上下文**预注入 prompt**（而非靠工具逐步发现），工具只负责
> 「确认细节 → 执行查询 → 检索知识 → 出图」。注册方式见 2.5。

### 4.1 工具清单

| 工具 | 类/方法 | 职责 | 关键约束 |
|---|---|---|---|
| `prepare_data_context` | `DataAgentToolkit#prepareDataContext` | 查看表的描述与完整列结构（列名/类型/AI 生成描述/维度示例） | **纯元数据，不查库**；支持 `tables` 数组批量取表（≤5 张，逐条可见性校验、失败内联报错） |
| `query_structured_data` | `DataAgentToolkit#queryStructuredData` | 执行只读 SQL 并返回自文档化结果（问题+SQL+markdown 结果表） | 仅 SELECT/WITH；跨表越权校验；行数默认 20、上限 100；10s 超时 |
| `retrieve_evidence` | `DataAgentToolkit#retrieveEvidence` | 从知识库检索口径/制度/关系文档片段 | 当前实现返回知识文档全文（`DatasetContextProvider#relationshipsText`） |
| `render_chart` | `DataAgentToolkit#renderChart` | 传入 columns+rows，服务端自动推断图表类型生成 ECharts option | 禁止模型自构 option；支持 KPI 目标参考线（mark_line）；option 落 `ChartOptionEntity` 由前端 `/api/charts/{id}` 取 |
| `run_python` | `RunPythonTool#runPython` | 沙箱执行 pandas/matplotlib/scipy 代码，产出图片/CSV/md 到 `outputs/` | 沙箱 `--network=none`；120s 超时；强制中文字体 preamble；图片以沙箱路径引用（不嵌 base64） |

### 4.2 租户与知识库范围（DatasetScope）

`DataAgentToolkit#effectiveScope`：优先取 RuntimeContext 上的 `DatasetScope`（含 ownerId + groupIds）；
harness 带外执行工具时退化为 `rc.getUserId()`；无 group 过滤时再从 `ConversationScopeRegistry`
按 sessionId 补知识库范围（harness chatui 不透传 typed attributes，sessionId 能透传——这是该注册表存在的原因）。

`visible(scope)` 规则：
- scope 为 null（非聊天通道）→ 仅全局数据源（properties 无 ownerId）；
- 有 groupIds → 仅这些知识库内的数据集（TC 式「在选中知识库内作答」）；
- 否则 → 全局数据源 + ownerId 属于我的数据集。

### 4.3 SQL 安全护栏

- 语句白名单：`SELECT` / `WITH` 开头（`DataAgentToolkit#queryStructuredData`）。
- 跨表越权：所有上传数据集物理表都是 `ds_` 前缀且同库，`checkCrossTable` 用正则
  `ds_[a-z0-9_]+` 扫描 SQL，凡引用不在调用者可见集合内的 `ds_*` 表直接拒绝。
- 连接级：`JdbcSqlConnector.open()` 设 `setReadOnly(true)`，查询超时 10s；
  `setMaxRows(limit+1)` 探测截断并提示；单元格 200 字符截断。
- 表名插值仅允许 `^[A-Za-z0-9_]+(\.[A-Za-z0-9_]+)?$`（describeTable 的 COUNT/采样语句）。

### 4.4 结果形态

`JdbcSqlConnector#previewQuery` 返回自文档化 markdown：查询问题 → SQL 代码块 → 结果表 → 截断提示。
前端无需额外解析即可展示「问了什么、查了什么、得到什么」。

`run_python` 返回结构化报告：`executed_code` / `exit_code` / `stdout` / `artifacts`
（图片含 `image_ref: ![name](sandbox path)`，要求模型原样复制到最终回复，前端经工作区二进制 API 取图）。

### 4.5 提示词三层分工

规则按「是否每轮都在上下文里」分层，避免同一条规则在多处重复而互相漂移（ADR 0007）：

| 层 | 载体 | 何时进入上下文 | 放什么 |
|---|---|---|---|
| 常驻·人格与流程 | `DataAgentConfig#DEFAULT_AGENT_SYS_PROMPT` → `WorkspaceScaffolder` 写进 `workspace/AGENTS.md` | 每轮（harness `WorkspaceContextMiddleware#onSystemPrompt` 追加） | 只放「模型加载任何技能之前就必须成立」的内容：角色、最高优先级原则、工作流程骨架、输出语言与产物门（不主动出图 / 不主动出文件 / 全中文输出）、回答中的计数必须与自己列出的明细行数一致的自检义务、指向 `sql-analysis` 技能的一句话 |
| 常驻·动作点硬门 | `DataAgentToolkit#queryStructuredData` 的 `@Tool` 描述 | 每轮（工具 schema 随请求下发） | 不可违反的 SQL 硬门：多表关联在同一条 SQL 内用 JOIN/CTE 完成、禁止把上一步结果字面量复制进 `IN (...)`、维表一个关联键对应多行时先 `WITH ... SELECT DISTINCT` 去重防扇出、禁止仅用于了解数据规模 / 日期范围 / 取值分布的摸底查询（`prepareDataContext` 描述则声明字段描述已含取值提示，即该门的替代方案） |
| 按需·操作手册 | `shared/agents/data-agent/skills/*/SKILL.md` | 模型判断需要时才加载技能体 | 完整步骤、批量 prepare 的表数与 `tables` 参数用法、CTE 写法、报告结构、matplotlib 标签语言与 CJK 字体、反模式清单 |

取舍：技能体不是常驻的（常驻上下文里只有技能名+描述），模型可能不加载技能就直接调
`query_structured_data`；所以「必须成立」的约束放工具描述，「怎么做才好」的细节放技能。

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
`~/.agentscope/dataagent-standalone/agentscope.json` 后重启（`workspace/` 下的 `tools.json` /
`skills/` 等由 `writeIfMissing` 保留），或调用 `POST /api/agents/{agentId}/workspace/scaffold`。
共享层的技能不受此限制——`SharedWorkspaceSeeder` 有 sha256 簿记，未被手改的副本会自动升级（见第 8 章）。

守卫：`DataAgentConfigTest`（常驻两层，含「不复述技能细节」的反重复断言）、`SharedSkillContentTest`（技能层：非空、有 frontmatter、无退役工具名、确实承载了下沉的细节）。

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
 └── KnowledgeGraph（实体+关系，见第 6 章）
SemanticTerm（语义术语：全局业务词典，term→解释/同义词/范围）
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
   → 立即可被 [DATA_SOURCES_OVERVIEW] 与工具看到
5. RelationInferenceService#reinferGroup 重建该知识库的关系边
```

`DatasetService#rebuildRegistry`（@PostConstruct）：启动时从 JPA 重建内存 DataSourceRegistry，数据集跨重启存活。

### 5.3 外部数据源关联

`ExternalDataSourceController`（`/api/datasources`）管理用户级 JDBC 连接；
`DatasetService#associateTables` 把外部库已有表关联进知识库：`DataSourceIntrospector`
读取列元数据/表注释/行数，可选低基数列采样（≤20  distinct 取 3 个样例），再经
`SchemaGenerationService` 生成 AI 描述。`origin=datasource`，删除时不 drop 物理表。
支持 MySQL / PostgreSQL（引号风格区分）。

### 5.4 关系推断

`RelationInferenceService#reinferGroup`（上传/关联/保存知识文档后触发，整组重建）：

| 边类型 | 规则 | 置信度 |
|---|---|---|
| `SAME_COLUMN` | 两数据集共享非主键列名 | 0.7 |
| `SUFFIX` | 列名去 `_id`/`_key` 后缀后等于另一表名/数据集名 | 0.6 |
| `DOC` | 知识文档中的显式表达（正则 `A.x = B.y`、`A 通过 x 关联 B`，支持中文标识符） | 0.9 |

`DatasetService#relationsFor(owner, table)` 把命中边渲染为可读行 + 建议 JOIN 片段，供工具/上下文使用。

### 5.5 语义术语

`SemanticTermController`（`/api/semantic-terms`）维护全局业务词典；
`DatasetService#semanticTermsText()` 渲染进 `[KNOWLEDGE_BASE_OVERVIEW]` 的「业务术语」小节。

---

## 6. 知识图谱

`dataset/KnowledgeGraphService.java`（API：`KnowledgeGraphController` `/api/dataset-groups/{id}/kg`）：

**构建（triggerBuild）**：
- 入参：是否包含知识文档 + 选中的数据集；同组并发构建由 JVM 内锁拒绝（409），旧图整体重置。
- 构建单元（KnowledgeGraphBuildUnit）：知识文档按 CHUNK_CHARS 分块 + 每个数据集 schema 各一单元。
- 事务提交后异步执行（boundedElastic，`BUILD_CONCURRENCY` 并发），单元级成功/失败落库，
  任务终态 COMPLETED / PARTIAL_FAILED。

**抽取双通道**：
- 数据集 schema → `SchemaTripleExtractor`：**确定性规则，无 LLM**（表/字段/主外键等三元组），`persistTriples` upsert。
- 知识文档 → LLM 抽取实体与关系（SYSTEM_PROMPT + 本体约束，`parseWithRetry` 两次重试，JSON 解析），`persistExtraction` 归一化去重落库。

**查询（semanticContext）**：Schema Linking / 溯源。给定业务词或指标名，跨租户知识库匹配实体，
返回其关联事实（计算口径/粒度/范围/示例）、字段归属表（经「包含字段」边解析为 table.column）与 JOIN 提示。

前端：`KgBuildConfigModal` 配置构建，`KnowledgeGraphView`/`GraphView` 展示。

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
  从 `sessions.json` 恢复（受 SessionResetPolicy 新鲜度过滤）。

### 7.2 会话状态持久化

- 对话上下文：每轮结束由 ReActAgent 写入 `AgentStateStore`（默认 `InMemoryAgentStateStore`；
  生产应提供分布式实现，见 `DataAgentConfig` 注释与 `dataagent.session.redis.*`）。
- 会话元数据：`SessionStore` 落 `~/.agentscope/dataagent-standalone/workspace/sessions.json`。
- 长会话控制：toolResultEviction（4000 字符驱逐大结果，run_python 豁免）+ compaction（80 触发/保留 20）。
- 斜杠命令：`/new`（新 conversationId）、`/reset`（清当前会话历史）、`/identity`、`/dock_<channel> <id>`（身份链接）。

### 7.3 每用户沙箱

`web/workspace/UserSandboxRegistry.java`：

- 键：`(userId, agentId)` → 一个长存 Docker 容器（镜像 `dataagent.sandbox.image`，
  内置 Python3 + pandas/matplotlib/scipy/numpy + Noto CJK 字体，`--network=none`）。
- `borrow()` 取用（无则创建并启动），`peek()` 只读探测（UI 文件树避免冷启动），
  `invalidate(userId, agentId)` 贡献审批通过后批量失效重建。
- 空闲回收：`dataagent.sandbox.idle-ttl-min`（默认 15min），轮询 `eviction-poll-sec`（默认 60s）。
- 工作区投影：容器启动时把宿主机共享层 `shared/agents/{agentId}/` 下的
  `AGENTS.md / skills / subagents / knowledge` 只读投影进容器（`subagents/` 仅在
  `dataagent.agent.subagents-enabled=true` 时被 harness 加载，见 12.1）。
- 网关注入：`HarnessGateway#attachUserSandboxContext` 把该容器设为
  `SandboxContext.externalSandbox`（SandboxManager Priority-1 获取路径），
  保证 agent 执行与浏览器工作区 API 读写**同一个容器**。
- **部署约束**：注册表是 JVM 内存态，多副本必须按 userId 做 sticky LB，
  否则两个 Pod 会为同一用户各起一个容器（见 `DataAgentConfig` javadoc）。

---

## 8. 能力市场与共享层

**贡献流**（`web/marketplace/`）：

```
用户（或 agent 经 contribute_to_workspace 工具）提交工作区文件
  POST /api/me/contributions { targetType, targetPath, rationale, payload }
    ↓ PENDING（JPA 持久化）
管理员 /admin/approvals 审批（ContributionApprovalController）
    ↓ APPROVED
MarketContributionService 物化到共享层 shared/agents/<agentId>/{skills|subagents}/...
    ↓
UserSandboxRegistry.invalidate(null, agentId)  ← 全用户沙箱失效，
    下次 borrow 重建容器并投影到最新共享内容
```

**市场源**（`runtime/marketplace/`，`UserMarketplaceRegistry` 按 type 水合每用户市场）：

| type | 实现 | 内容来源 |
|---|---|---|
| `local` | `LocalApprovalMarketplace` | 审批落盘的 `shared/agents/data-agent/skills`（与沙箱投影同一目录，即时可见） |
| `git` | `GitDataAgentMarketplace` | Git 仓库克隆（per-user 克隆目录 `.cache/marketplaces/{userId}/{id}`） |
| `nacos` | `NacosDataAgentMarketplace` | Nacos 配置中心 |

用户 API：`MarketplacesController`（`/api/me/marketplaces`）。
技能也可直接在工作区编辑：`AgentSkillsController`（`/api/agents/{agentId}/skills`）。
内置技能：`sql-analysis` / `chart-rendering` / `python-analysis`；内置子 agent：`data-explorer` / `report-writer`。

**共享层单一事实源**：出厂内容只在 `src/main/resources/shared/`（classpath）维护；项目根 `shared/`
是运行态目录（已 gitignore），由 `SharedWorkspaceSeeder#seedSharedTree` 在每次启动时物化，同时也是审批
贡献的落盘位置。写入策略是三态的，簿记在 `shared/.seed-manifest.json`（relPath → 出厂内容 sha256）：

| 磁盘状态 | 动作 |
|---|---|
| 文件缺失或 0 字节 | 写入出厂内容（**0 字节自愈**——空 SKILL.md 会因 frontmatter 解析失败而从 `available_skills` 静默消失） |
| 与 manifest 记录一致、但出厂内容已变 | 覆盖升级（内置技能随版本演进） |
| 与 manifest 记录不一致（运营手改） | 保留不动 |
| 不在 manifest 中（审批贡献） | 保留不动 |

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
- **用户与 agent 目录**：`web/catalog/AgentCatalogService`（内置 data-agent + 用户自定义 agent `uca-`/`uda-` 命名空间，
  fork/clone 见 `AgentCloneController`；`UserAgentDefinitionStore` 持久化）。
- **审计**：`web/audit/AgentActivityStore`（RUN_SESSION 等事件，`AgentActivityController` 查询）。
- **用量**：`web/usage/UsageStore`（每 turn 记录 userId/agentId/耗时，admin 端 `/api/admin/usage/...` 聚合）。
- **AI 草稿**：`web/ai/AgentDraftService`（聊天式生成 agent 定义，`/api/agents/draft`），知识图谱 LLM 抽取也复用它。

---

## 11. 前端结构

React 18 + TypeScript + Vite SPA（`frontend/`），构建产物进 `classpath:/static/` 由后端托管。

- **页面**：
  - 聊天：`ChatPage`（SSE 消费 `api/chat.ts`，`ChatPanel`/`ToolCallBlock`/`EChartsBlock`/`PythonArtifactsPanel`/`Markdown` 渲染），`OntologyChatPage`
  - 配置：`configure/`（DatasetsPage、DatasetDetailPage、DatasetGroupPage、SemanticConfigPage、SkillsPage、SubagentsPage、ToolsPage、ChannelsPage、SettingsPage）
  - 管理：`admin/`（Overview、Agents、Users、Approvals、Channels、Instances、Sessions、Usage、Config、Debug）
  - 其他：Login、Profile、Workspace、Contributions、Usage、Appearance、UserBindings
- **API 层**：`frontend/src/api/*.ts` 一一对应后端控制器（chat/datasets/datasources/knowledgeGraph/semantic/ontology/marketplaces/sessions/…）。
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
| `dataagent.analytics.enabled` | false | 预置分析库（tenant_storage_utilization / project_info） |
| `dataagent.expose-app-db` | false | 是否把平台元数据库暴露为数据源 |
| `dataagent.session.redis.*` | 关闭 | 分布式会话（HA 必需；多副本另需 sticky LB，见 7.3） |
| `dataagent.marketplace.*` | 开启 | 贡献审批与载荷上限 |
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
~/.agentscope/dataagent-standalone/
├── agentscope.json          ← agent 定义（main=data-agent, maxIters=20）+ 通道配置
└── workspace/               ← 私有层工作区种子（WorkspaceScaffolder 生成）
    ├── AGENTS.md            ← data-agent 系统提示词（可编辑；分层见 4.5）
    ├── skills/              ← 私有层技能（example-skill 示例；内置业务技能在下面的共享层）
    ├── subagents/           ← 子 agent 定义（开关关闭时不加载，见 12.1）
    ├── knowledge/
    └── sessions.json        ← 会话元数据（运行时生成）

${cwd}/shared/               ← 运行态共享层（SharedWorkspaceSeeder 物化，已 gitignore）
├── .seed-manifest.json      ← 出厂内容 sha256 簿记（见第 8 章）
└── agents/data-agent/
    ├── skills/              ← 内置技能（sql-analysis / chart-rendering / python-analysis）+ 审批贡献
    └── subagents/           ← 内置子 agent（data-explorer / report-writer；默认不加载，见 12.1）

~/.agentscope-dataagent/db.* ← H2 平台元数据库（用户/数据集/图谱/贡献等 JPA 表）
```

---

## 13. 关键类与文件索引

| 关注点 | 类（锚点） |
|---|---|
| 应用入口 | `web/DataAgentApp` |
| Spring 装配/模型/市场工厂 | `web/config/DataAgentConfig`（`builderBootstrap`、`openaiModel`、`dashscopeModel`） |
| 安全 | `web/config/SecurityConfig`（`JwtAuthFilter`） |
| Bootstrap 三阶段 | `runtime/DataAgentBootstrap`（`Builder.build`、`applyFileEntry`） |
| 网关/沙箱注入/并发门/announce | `runtime/gateway/HarnessGateway`（`runStream`、`attachUserSandboxContext`、`withGatedStream`、`tryDispatchAnnounce`） |
| 聊天端点/SSE | `web/api/ChatController`（`stream`、`buildInbound`、`handleSlashCommand`、`toToolFrame`） |
| 动态上下文注入 | `runtime/session/DataDynamicContextMiddleware`（`onSystemPrompt`） |
| 问数四工具 | `tools/data/DataAgentToolkit`（`prepareDataContext`、`queryStructuredData`、`retrieveEvidence`、`renderChart`、`effectiveScope`、`checkCrossTable`） |
| SQL 执行器 | `tools/data/JdbcSqlConnector`（`runSqlPreview`、`previewQuery`、`open`） |
| Python 沙箱工具 | `tools/data/RunPythonTool`（`runPython`） |
| 图表生成 | `tools/data/ChartBuilder`（`build`、`applyMarkLine`） |
| 工具注册 | `tools/data/DataToolkitRegistrar` |
| 数据源注册表/种子 | `tools/data/InMemoryDataSourceRegistry`、`tools/data/DataToolkitConfig` |
| 数据集生命周期 | `dataset/DatasetService`（`ingest`、`associateTables`、`rebuildRegistry`、`relationshipsText`、`semanticTermsText`、`relationsFor`） |
| 文件解析/导入 | `dataset/DatasetImportService`、`dataset/parser/{ExcelParser,CsvParser,TypeInferrer}` |
| AI 字段描述 | `dataset/parser/SchemaGenerationService` |
| 外部库内省 | `dataset/DataSourceIntrospector`、`dataset/TableProvisioner` |
| 关系推断 | `dataset/RelationInferenceService`（`reinferGroup`）、`dataset/SchemaRelationInferrer` |
| 知识图谱 | `dataset/KnowledgeGraphService`（`triggerBuild`、`executeBuild`、`semanticContext`）、`dataset/SchemaTripleExtractor` |
| 会话管理 | `runtime/session/SessionAgentManager`、`SessionStore`、`SessionEntry` |
| 会话作用域(知识库) | `web/session/ConversationScopeRegistry` |
| 每用户沙箱 | `web/workspace/UserSandboxRegistry`（`borrow`、`invalidate`）、`DataAgentWorkspaceConfig` |
| 工作区种子 | `web/scaffold/WorkspaceScaffolder`、`web/workspace/SharedWorkspaceSeeder` |
| 能力市场 | `web/marketplace/MarketContributionService`、`runtime/marketplace/{LocalApproval,Git,Nacos}DataAgentMarketplace`、`UserMarketplaceRegistry` |
| 用户 agent 目录/ACL | `web/catalog/AgentCatalogService`、`web/share/{AgentAclService,AgentAccessGuard}` |
| Webhook 通道 | `runtime/channel/webhook/WebhookChannel`、`WebhookCallbackController`、`WebhookSignature` |
| 主动外发 | `runtime/outbound/{OutboundTool,OutboundService,OutboundController}` |
| 会话/工具事件历史 | `web/session/SessionTurnParser`、`web/toolbus/{ToolEventBus,ToolNotificationMiddleware}` |

---

> 维护约定：修改主流程/装配顺序/工具链/数据模型时，必须同步更新本文档对应章节；
> 架构取舍（为什么这么做）写到 `docs/adr/`，本文档只描述「是什么」。
