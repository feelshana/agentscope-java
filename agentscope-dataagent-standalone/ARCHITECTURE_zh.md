
# agentscope-dataagent 架构与流程详解

> 本文档面向开发者与 AI 编码助手，基于当前代码实际实现梳理（非上游 agentscope 示例的原始版本）。
> 核心流程：**通过 Wren 进行语义建模 + 问数**——数据集组即 wren project，表结构变化自动播种并发布
> 基础 MDL；建模助手（modeling-agent）经对话完善关系/模型口径/明细视图/业务规则后发布；问数助手（data-agent）
> 在已发布语义模型上按调用作用域注册官方 MCP 工具（如 `run_sql`、`recall_queries`），
> 配合 `run_python` / `render_chart` 做结果分析与展示。知识图谱构建链路已停用（见第 6 章）。
> 引用代码位置时使用「类名 + 方法名」锚点，不引用行号（行号会漂移，类名可由 SearchSymbol 定位）。

---

## 目录

1. [项目定位与分层架构](#1-项目定位与分层架构)
2. [启动装配流程](#2-启动装配流程)
3. [主流程：Wren 语义建模与问数](#3-主流程wren-语义建模与问数)
4. [问数工具链（官方 Wren MCP + Python/图表）](#4-问数工具链)
5. [数据集与知识库](#5-数据集与知识库)
6. [知识图谱（已停用）](#6-知识图谱已停用)
7. [会话管理与沙箱](#7-会话管理与沙箱)
8. [共享层](#8-共享层)
9. [通道与主动外发](#9-通道与主动外发)
10. [认证、权限、审计与用量](#10-认证权限审计与用量)
11. [前端结构](#11-前端结构)
12. [配置与启动](#12-配置与启动)
13. [关键类与文件索引](#13-关键类与文件索引)
14. [建模与问数的当前运行约束](#14-建模与问数的当前运行约束)

---

## 1. 项目定位与分层架构

dataagent 为每位数据分析师提供专属的数据 Agent，核心流程是**通过 Wren 进行语义建模 + 问数**：
上传 Excel/CSV 或关联外部数据库表即自动播种并发布基础语义模型（MDL，5.6）；建模助手
（modeling-agent）经对话完善关系/模型口径/明细视图/业务规则后发布（3.2）；问数助手（data-agent）
在已发布语义模型上作答（3.1）。知识库（Knowledge Base）承载口径文档、表关系说明与语义术语；
知识图谱构建已停用（第 6 章）。

```
┌─────────────────────────────────────────────────────────────────────┐
│  Web 业务层（本模块 web/ 包）                                        │
│  ChatController / JWT认证 / ACL权限 / 数据集·知识库·语义建模 API /    │
│  ToolEventBus(SSE) / 审计 / 用量 / AgentCatalog(内置agent目录)        │
├─────────────────────────────────────────────────────────────────────┤
│  Agent 工具层（tools/data/ 包）                                    │
│  WrenMcpToolkit(官方 MCP 发现与透传) + 原生文件工具                  │
│  OfficialWrenToolkit(建模) + DataAgentToolkit(render_chart)          │
│  RunPythonTool(run_python)                                           │
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

**多租户模型**：数据资产保留 `ownerId` 归属；数据集组织为「知识库（DatasetGroup）→ 数据集（Dataset）→ 物理表」三级。
知识库通过 QUERY/MANAGE 显式共享，创建者与全局管理员天然管理；`DatasetScope` 注入请求用户和本轮授权范围，库 owner 仅用于资源路径。私有会话、沙箱、附件与反馈不随知识库共享扩大，见 ADR 0077。

---

## 2. 启动装配流程

> 当前主 Agent 装配以 ADR 0069 为准：建模与问数均挂载同版本官方 Wren 技能；问数按 RuntimeContext 创建作用域 Toolkit，并通过 AgentScope 原生 MCP 注册当前授权 project 的官方工具。下述非主 data-agent 的平台能力继续保留。

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
   - middleware: ModelingHitlMiddleware（仅 `modeling-agent` 安装；工程文件写入直接进入草稿、
     不再暂停确认（ADR0111），只对遗留写工具发出原生确认事件；普通问数 Agent 和读取工具不受影响）
   - middleware: DebugLoggingMiddleware（把消息写成人工可读转录：每条记录
     只有 role / type / text 三个字段；同一 session 内按消息 id 去重，因此整份文件是一条
     线性对话而不是每轮重放的历史；工具入参按 `key: value` 逐行展开（SQL 保留换行），
     工具结果反转义为真实文本，图片/音视频等媒体块只留占位符。转录 logger 按 agent 注入：
     默认 `logs/LLM.log`（LLM_DEBUG）；建模助手 modeling-agent 走独立文件
     `logs/LLM-modeling.log`（LLM_MODELING_DEBUG），建模会话与问数流量分开成两份文件。
     不经中间件链的直调 LLM 由 AgentDraftService#callModelBlocking 自己以同一结构转录：
     语义建模 AI 建议 → logs/LLM-modeling.log，agent 草稿 → logs/LLM.log）
   - middleware: 主 data-agent 使用 WrenQueryContextMiddleware 注入本轮授权发布工程目录；
     modeling-agent 使用 WrenProjectContextMiddleware 注入单库工程身份与资料位置。
     DataDynamicContextMiddleware 仅为非主问数兼容装配保留，不能恢复平台语义路由。
   - stateStore: AgentStateStore（默认 InMemoryAgentStateStore；生产应提供分布式实现）
   - filesystem: DockerFilesystemSpec(IsolationScope.USER)，镜像 dataagent.sandbox.image
     —— 仅问数 agent；sandboxlessAgents（当前 = modeling-agent）显式设 LocalFilesystemSpec +
     GroupScopedMemoryNamespace（specs/029、ADR 0039：长时记忆按知识库分域——单库对话
     ns=[owner,"kb-<groupId>"]，切断 A 库建模记忆经 <memory_context> 注入 B 库），
     走本地文件系统 + 独立空工作区（见 7.3「免沙箱 agent」）
   - disableFilesystemTools() + disableShellTool()  ← 数据分析场景不需要文件/Shell 工具
   - toolResultEviction: 将 run_python 加入排除名单，保证产物元数据不被驱逐
   - modeling-agent：configureAgent("modeling-agent", ...) 编程注册建模助手（specs/013 M1、ADR 0024 D2）
     ——通用权限提示 sysPrompt、disableWorkspaceContext、maxIters=30、独立空工作区
     ~/.agentscope/dataagent/workspace-modeling-agent（免沙箱姿态，见 7.3）；
     原生文件工具与官方 CLI 权限适配由 ModelingToolkitRegistrar 后置挂载（见 2.5）；
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

- `DataAgentToolkit` — 仅提供服务端图表工具（`render_chart`）；
- `WrenMcpToolkit` — 官方 MCP schema 发现与原始调用透传（`wren_list_tools` / `wren_call_tool`），
  依赖 `WrenQueryGateway`（`WrenInstanceRegistry` 的接口）与当前发布工程；
- `RunPythonTool(new SandboxBackedFilesystem())` — 沙箱 Python 执行工具。
  此处用独立代理实例即可：每轮调用的真实沙箱由 SandboxLifecycleMiddleware 绑定在
  RuntimeContext 上，工具调用拿到的就是同一个上下文。
- `ModelingToolkitRegistrar#registerOfficialTools` 向 modeling-agent 挂载 AgentScope 原生 FilesystemTool（list_files/read_file/write_file/edit_file/grep_files/glob_files）与 OfficialWrenToolkit 的单一 wren_cli 工具。旧 ModelingToolkit 不再注册，set/get_enrich_context_mode、关系/视图/问题专用工具与阶段剧本退出 Agent 运行时；遗留 HTTP 服务保留（ADR 0068/spec 057）。
- `WrenProjectAccess#resolve` 校验当前用户 MANAGE，从 DatasetScope/ConversationScopeRegistry 绑定单个草稿工程；`WrenProjectFilesystem` 只做路径和权限映射，委托 LocalFilesystem 执行文件操作。禁止穿越、宿主路径、符号链接和平台文件（含 `raw/docs/` 业务文档，只能由用户上传）；根目录搜索过滤平台目录，文件写入后标记草稿 DIRTY 并记入 `DraftChangeLog`（见第 14.1 节）。
- `OfficialWrenToolkit#execute` 将参数数组传给真实官方 CLI，强制 cwd/project/MDL/memory 路径；不开放任意 shell、profile 切换、连接参数或发布命令。query/dry-run 及需要执行的 Cube query 通过 `MdlPublishService#draftCliProfile` 在工作区锁内再次校验当前操作者的 MANAGE 权限，再按知识库数据集绑定连接；创建者、共享管理员与全局管理员遵循同一权限规则，排队期间撤权则拒绝执行，操作者身份不替换为库创建者；ProcessWrenCli 的 WREN_HOME 指向平台维护的 profiles，避免宿主账号的 active profile。返回退出码与原始输出；校验/编译/SQL 计划和 memory 均没有平台第二实现。 官方 build 退出码同步现有 ModelingWorkflowStore 页面记录，validate 成功单独不表示已编译；失败原样显示。query/dry-run 的临时连接绑定在调用后恢复平台工程文件。memory 索引位于知识库 runtime/modeling-memory，源示例仍在 knowledge/sql，派生二进制不进入发布。
- `ModelingHitlMiddleware#onActing` 不再拦截工程文件写入（ADR0111，修改进入草稿、发布前统一审阅），只对会生成种子样例的 memory index 等遗留写工具请求确认；显式 --no-seed 的索引仅更新派生产物。建模不允许 memory store（见第 14.2 节）。预览检查路径、内容和精确替换，不运行平台业务语义规则。Agent 写入后按官方技能执行 validate/build/dry-plan/dry-run，技术失败进入原生循环修复；build 不代替显式发布。历史 patch_file/批量卡只由兼容 HTTP 预览处理。

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

### 3.1 问数（ADR 0069 / spec 058）

`DataAgentConfig#dataAgentBootstrap` 对主 data-agent 挂载安装版官方 skills，使用原生 HarnessAgent/ReActAgent。
`WrenQueryContextMiddleware#onSystemPrompt` 仅给出鉴权用户可用的项目 ID/名称与发布状态，不注入草稿、业务规则或自研 SQL 剧本。
`DataToolkitRegistrar#register` 只注册平台通用结果工具；`WrenScopedToolkitMiddleware` 按本轮 RuntimeContext 复制 Toolkit，并通过 `Toolkit#registerMcpClient` 注册绑定当前已发布 project 的 MCP client。Agent 加载官方 usage，通过 listTools 发现官方 schema，
再以原始 arguments 调用 get_instructions/get_context/describe_schema/recall_queries/run_sql/query_cube 等官方工具。
只有官方已提供的工具可调用；问数服务不使用 --allow-write，store_query 不对 Agent 开放。

`WrenQueryAccess#resolve` 检查 ownerId、DatasetScope 与当前会话范围并要求已发布版本。
`WrenInstanceRegistry#spawn` 使用 `WrenPublishedProject#prepare` 的运行工程与发布时锁定的 profile，继续复用框架 MCP ClientWrapper。

ADR 0072 / spec 061：`ProcessWrenCli#run` 与 `WrenInstanceRegistry#spawnProject` 共用
`WrenProcessEnvironment#configure`，为平台启动的子进程加载随 JAR 打包的限行兼容资源。
只对 wrenai 0.15.0 仍直接追加 LIMIT 的 MySQL/Doris 连接器生效：SQLGlot 定位最外层行数，
取原 LIMIT 与执行上限的较小值，保留 OFFSET、排序、内层限制和其它 SQL 原文。官方技能、
MDL、传给 Wren 的逻辑 SQL、N+1 截断协议及示例不修改；安装目录不打补丁。升级官方版本时重验并删除兼容资源。
`WrenInstanceRegistry#listTools` 按实例缓存成功发现的官方 schema，发布失效/进程重建时丢弃；
权限仍在每次工具调用校验，不缓存业务结果。`WrenQueryContextMiddleware#onSystemPrompt` 提醒复用
当前会话同知识库/发布版本有效的技能和元数据，仅在需要时诊断；官方按问题召回继续执行。
SELECT/WITH 只读检查保留；LIMIT/OFFSET 遵循官方参数，strict_mode 将数据查询限制在声明模型边界。
平台没有物理直查回退、Cube 路由、指标选择器、领域问题状态机或自研召回排序。

### 3.2 建模

上传/关联数据 → 基础模型发布 → 可选文档与问题 → 官方建模技能 → 原生草稿编辑 → 官方校验/构建/查询检查 → 显式发布。
`ModelingToolkitRegistrar#registerOfficialTools` 仅注册原生文件工具、受限 delete_file 和官方 wren_cli。
`WrenProjectAccess#resolve` 将 ownerId 与唯一知识库绑定到草稿工程；业务文档在 `raw/docs/` 由 `BusinessDocumentService` 管理。
普通/ref_sql 模型、关系、视图、Cube 和 knowledge 按同版本官方契约维护，不构建本体/KG，不修改官方 skill。
禁止越界路径、链接、宿主 shell、连接覆盖及平台配置写入。

`ModelingHitlMiddleware#onActing` 对工程文件写入直接放行（ADR0111），遗留写工具仍使用原生 RequireUserConfirmEvent/ConfirmResult。
同批合法写操作一次展示并可一次授权；失败操作各自返回错误，不把一个文件的失败复制给其他调用。
write_file/edit_file/delete_file 与会生成种子样例的 memory index 需写权限（`models/`、`knowledge/questions/` 为技术写入不弹卡）；--no-seed 的派生索引重建不需人确认；建模不允许 memory store。
通用授权不是第二轮业务审阅，技术校验与修复由 Agent 调用官方工具；没有问题必填或必验条件。

### 3.3 发布与 MDL

`MdlPublishService#publish` 从工程 YAML 组装官方输入，执行官方 strict validate/build 与 view/ref_sql/Cube 查询检查，再复制快照并提升版本。
官方 target/mdl.json 是引擎事实；不调用 RefSqlMaterializer，不物理化 ref_sql，不禁止官方允许的资产依赖。
ref_sql 的内容和依赖是否可执行由官方引擎判断。任何检查失败保留旧发布快照，官方 build 不自动替换在线版本。
模型目录与 MDL 可视化属于只读投影，元数据旧实体与旧 HTTP 为兼容保留，不参与主 Agent 的语义决策。

---

## 4. 问数工具链

### 4.1 原始官方调用与通用产物

`WrenScopedMcpClient#listTools` 返回原始官方工具 schema，AgentScope 将其转换为原生 `McpTool`；`WrenScopedMcpClient#callTool` 原样透传官方参数与输出。共享 Agent Toolkit 不保存用户或 project 绑定。
run_sql/query_cube 成功结果可经通用 CSV 产物交给 run_python/render_chart。get_instructions、list_knowledge、
recall_queries、describe_model 与 describe_cube 均由官方 MCP 提供，平台不再提供第二套知识读取或召回工具。
问题范围、时间粒度、JOIN、DISTINCT、视图与 Cube 的选择遵循官方技能，不由平台正则路由。

### 4.2 点赞与官方 memory

外层可选 answer_question 仅标注用于最终答案的业务查询，不传入官方 arguments；诊断查询省略。
服务端成功结果附 WREN_QUERY_RECEIPT，将真实 groupId/question/SQL/limit 与执行记录关联。
`AnswerQueryMemory#candidates` 从服务器会话中选取当轮成功且标注的查询，排除失败、无标注和其他 owner 的回执。
一条 SQL 只回答子问题时标注该子问题，避免局部 SQL 冒充完整用户问题。点赞是用户认可最终结果，不证明算法绝对正确。

`AnswerQueryMemory#feedback` 只在 UP 后用官方 memory store 写入 knowledge/sql；DOWN 仅记反馈，失败可重试，不自动修订语义资产。

语义建模页的 SQL 问答对模块由 `SqlQaExampleService` 统一读取两类官方 Markdown 源：建模工作区
`knowledge/sql` 与当前用户知识库的点赞源 `runtime/liked-examples/knowledge/sql`。建模源修改后须重新发布，
点赞源修改后重建官方 memory index；接口按 owner/group 隔离并限制文件名。视图资产路径由
`MdlWorkspaceReader#read` 返回实际存在的 `sql.yml`，缺失时回退 `metadata.yml`，避免页面读取派生的不存在文件。
问答对删除沿用相同的来源隔离；点赞来源删除后重新执行官方 memory index。
SQL 原样保存，不二次添加 LIMIT。`WrenPublishedProject#prepare` 合并发布源码与隔离的点赞源码，
`WrenPublishedProject#indexed` 按源码摘要调用官方 memory index --no-seed，索引改变时不复用旧模型/示例内容。

建模问答只来自用户逐题确认（第 14.2 节）：确认后由 `MdlQuestionStore#decide` 写入草稿 `knowledge/sql`，页面显式发布后进入问数工程；问题列表和 schema 索引本身不代表存在问答。`WrenPublishedProject#mergeExamples` 保留已发布文件名，为点赞来源生成不冲突的派生文件名（默认 `liked-` 前缀），原始 Markdown 内容不变；只同步派生目录，避免两类中文问题都生成 `query.md` 时覆盖。每次部署修正派生文件并清除不再由来源提供的副本，索引仍由官方实现负责。

`AnswerQueryMemory#candidates` 优先匹配调用 ID；历史回合缺 ID 时按业务问题、工具类型与实际 SQL 核对所有候选，不因同题的首条 SQL 不同而漏存。有调用但内容不匹配、诊断、失败及其他用户知识库回执均排除；完全缺失调用输入时才采用服务端成功回执。显式再次点赞可重试此前空候选，已保存状态仍保持幂等。

问数工具展示由 `ToolCallBlock`/`ToolInspector` 解析外层调用的 `tool_name` 显示中文操作名；`WrenToolResult` 展示官方 `nl_query/sql_query` 问答卡、查询行表格及模型/Cube结构，并保留空值、截断提示、SQL回执和产物说明。原始响应可展开复制，JSON字符串只按 JSON 语义解码，不修改 SQL 转义；SSE 与存储原文不变。参见 spec 063。
线上 recall_queries/get_context 使用该同一工程的官方 memory；无平台评分、中文词项算法或可信等级扩展。
CLI/依赖/多语言模型不可用时明确返回错误；普通 SQL 查询仍可执行。

### 4.3 保留的数据分析能力




ADR0087/spec075：前端 `isWrenTool` 同时识别官方原生名称与兼容网关，`parseWrenResult` 解析查询回执、JSON及附件说明；解析失败不视为历史截断。真实结果截断以官方字段为准。`WrenInstanceRegistry#callAuthorized` / `doCall` 记录 queueMs、prepareMs、executeMs 和结果字符数，`OfficialWrenToolkit#execute` 记录CLI耗时，用于区分等待与执行；日志不额外记录SQL或行值。无选库时 `WrenScopedToolkitMiddleware#onAgent` 继续普通聊天，不因Reactive空完成跳过执行。基础建模按一批关联文件集中validate/build，不再要求执行已禁止的Cube命令；官方技能原文不变。

DataAgentToolkit 的 render_chart 与沙箱 run_python 继续复用现有 ArtifactStore、SandboxManager 和 AgentScope 执行。
MCP 返回的 columns/rows 与截断信息保持原样；CSV 是通用传输，不引入 SQL 编译、指标口径或领域规划。
历史 WrenToolkit/SqlExampleRecall 源码保留迁移期兼容测试，主 data-agent 不注册这些领域工具；随后的问数技能只引用官方 Wren 工具名。

---

## 5. 数据集与知识库

> 当前语义事实为 workspace 工程 YAML 与 published 官方快照。运行问数工程合并发布 knowledge 和点赞 knowledge/sql；不从平台关系/Cube/术语实体重新构造线上语义。业务文档上传只保存资料，不触发旧 DocEnhanceService；增强在官方对话中进行。历史服务/API 保留兼容，不是主 Agent 流程。

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

元数据走 JPA；当前 application.yml 默认 MySQL，h2 profile 使用 `~/.agentscope-dataagent/db.*` 文件库。切换其他 JDBC 数据库需匹配驱动、初始化 SQL 和方言；上传物理数据仍落独立的数据集 MySQL。

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

ADR 0078 / spec 067：`DatasetUploadProperties` 统一大小、文件数量、解析规模和并发策略，前端通过 `DatasetController#uploadPolicy` 读取；`DatasetUploadAdmission#acquire` 限制单进程在途文件、同库同名重复和上传编号复用。`DatasetUploadIntake` 将内容暂存磁盘，超限/取消清理；导入以有限在途批次写入，失败回滚物理表及元数据。浏览器 `uploadQueue` 调度文件，服务端满额 429，不构建持久等待队列。`DatasetController#uploadStatuses` 校验库管理权限，仅返回当前上传者的状态；数据导入成功与基础模型发布结果分别反馈，支持部分成功和发布待处理。

### 5.3 外部数据源关联

`ExternalDataSourceController`（`/api/datasources`）管理用户级 JDBC 连接；
`DatasetService#associateTables` 把外部库已有表关联进知识库：`DataSourceIntrospector`
读取列元数据/表注释，可选通过 `DatasetSampling#read` 每表一次 LIMIT 1000 有界读取，在内存提取每列最多20个观察值，再经
`SchemaGenerationService` 生成 AI 描述。`origin=datasource`，删除时不 drop 物理表。

Wren 知识库当前只允许关联同一个 MySQL 外部数据源，拒绝与上传表混合、跨多个外部源或关联
PostgreSQL。`DatasetService#prepareAssociatedTables` 在任何 repository/registry 写入前完成整批表名、
重复项、已有关联、字段元数据与描述预检；结构准备失败时整批不写入，采样失败只记录 FAILED，不阻断结构关联。全部准备成功后再统一
持久化并注册，Controller 最后只触发一次基础 MDL 发布。

ADR 0080/spec 068：关联不再执行 COUNT(*) 或逐列 DISTINCT。外部表 API rowCount 为 null（包括历史外部记录），UI 显示总行数未统计；上传文件保留准确行数。摘要存入 DatasetEntity.sampleSummaryJson，对应可空 LONGTEXT 列 sample_summary_json，包含时间、范围、NULL 数、截断后的样本内不同值数和状态。MySQL 投影每值最多257字符，内存保留256字符并标记截断，二进制列不取内容。每次采样30秒语句超时、最多1000行，无失败全表回退。上传沿用导入中的每列最多10个非空样例，不新增数据库查询，未知NULL数/基数以-1表示。

`DatasetService#refreshSample` 提供 MANAGE 受限的 POST /api/datasets/{id}/sample-refresh，先鉴权，再取得数据集悲观锁并复核权限，采样完成后再次复核；源采样关闭时拒绝刷新。失败保留旧摘要，成功标记草稿待发布。新资料在下一次草稿读取或 reconcile 时生成，不直接改变已发布版本。

`DatasetSampleDocuments#sync` 生成官方目录内 数据库中的结构化采样摘要；不生成 knowledge/rules/data-sampling.md 或 get_instructions 采样注入；平台文件禁止原生或兼容写入口覆盖，不修改安装版 skills 或 target/mdl.json。`MdlSeeder#reconcile` 在发布前同步资料；`WrenProjectAccess#resolve` 在当前授权库的工作区锁内同步。列 description 保存简短样例提示，业务含义充分确认后才由官方建模流程写 [enum]，样本不证明完整枚举或关系唯一性。

`WrenProjectContextMiddleware#onSystemPrompt` 每次构造上下文时提供通用约束及最多6000字符摘要，完整资料按需读取；不会用“首次已发送”进程标记丢失恢复会话上下文。`WrenQueryContextMiddleware#onSystemPrompt` 仅注入通用约束，官方 get_instructions 读取已发布的采样规则和限长摘要（24000字符）；不注入草稿资料。提示约束不能保证模型必然遵循，也不是数据库端成本硬限制。必要的准确聚合和业务验证仍通过官方 Wren 运行。

### 5.4 历史台账与兼容接口

历史关系服务、术语口径台账与问题确认兼容接口已退出主流程，原说明见 [docs/archive/architecture-legacy.md](docs/archive/architecture-legacy.md)。

### 5.6 数据集组 → WrenAI 语义层发布流（M1/M2/M3 已实施）

产品形态（ADR 0029）：**数据集组 == wren project**。表结构变化立即自动生成并发布基础 MDL；
语义建模页、文档增强和对话建模在此基础上完善关系、View、术语与业务规则。

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
`confirmRelation`/`rejectRelation`/`addManualRelation`；旧 Cube API 返回 410）、
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
`官方 describe_model` / `官方 run_sql` / `官方 recall_queries`；`WrenInstanceRegistry` 管理 per-group
`wren serve mcp` 实例池（按需 spawn、空闲回收、发布后 invalidate、传输失败重建）；
`WrenProfileHome` 托管连接档案；`MdlCatalog` + `DataDynamicContextMiddleware` 仅渲染轻量逻辑目录；
旧物理直查工具与 JDBC connector 已删除。
**M4 已落地**（ADR 0021）——外部数据源组发布链路：`MdlPublishService#selectWrenSource`
发布期解析组连接目标（全上传 → 默认 profile；单一 MySQL 外部源 → `ext-<id>`；混合/多源/
源已删/非 MySQL → 结构化中文错误拒绝发布）、`WrenSourceProber`/`JdbcWrenSourceProber`
发布期连通性预检（information_schema 验证表存在，连接超时 8s，失败拒绝并报表名）、
`WrenProfileHome#ensureAll` profiles.yml 幂等全量重建（发布路径重渲染，单一事实源是数据库）、
`published/wren-source.properties` 快照连接钉入 + `WrenInstanceRegistry#resolveSnapshotProfile`
spawn 读取（缺失回落默认，M3 存量组零迁移）、`官方 run_sql` 入口 LIMIT/OFFSET 硬门（历史实现，已由 ADR0069 起改为遵循官方 LIMIT/OFFSET）
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
视图小节同步改口径「工程 views/ 文件，官方 run_sql 可直接按视图名查询」）；
`ModelingToolkit#listBusinessRules` 切文件源，`DocEnhanceService` 退出建模工具链；
DocEnhance 规则采纳双写（见 5.7）。守卫：`MdlSuggestionServiceTest`（幂等确认/交换重确认
替换不重复/拒绝不动文件/复合键生长）、`MdlWorkspaceReaderTest#readKnowledgeRules*`、
`ModelingToolkitTest`（listBusinessRules 读文件 + 外组拒绝 + 退役守卫）、
`DataDynamicContextMiddlewareTest`（标题与空源回归）。

**对话式建模入口（ADR 0069/spec 058）**：modeling-agent 是通用 AgentScope 执行器。DataAgentConfig#MODELING_SCRIPT 只声明官方技能、当前工程、中文、权限和发布边界；WrenProjectContextMiddleware#onSystemPrompt 仅注入项目身份和资料位置，不注入平台建模状态/阶段。WrenSkillsLocator 挂载安装版官方技能，技能不可用时经 wren_cli skills list/get 读取官方内容。文件预览由 SemanticModelingController#workspacePreview 分派原生 write_file/edit_file 与历史 patch_file/批量卡；确认卡展示实际前后内容与 diff，不宣称写入前已完成官方语义检查。ChatController#confirmModeling 复用原生 ConfirmResult 恢复工具执行。模型、关系、视图、SQL 定义模型与 Cube 由官方契约和技能指导，平台不强制资产推荐路由。最终发布是页面显式动作，草稿 build 不更新线上版本；原生 edit_file 展示实际变更内容。

以下遗留 HTTP 能力及历史 ADR 说明不代表当前 Agent 注册工具或固定步骤：


**语义视图（specs/011 M2 → specs/019 文件化，ADR 0027/0033；定位回归见 specs/035/ADR 0043）**：复杂
口径（跨表 JOIN/窗口/CTE/复合过滤）的 agent 首选载体（对齐官方 enrich-context sink 决策树）——建模
对话经 `create_view` 写 views/<name>/ 双文件（HITL 闸门 + scratch 三道预检 validate+build+dry-plan），
方言绑定风险由「工具描述函数对照表 + `requireViewSql` 黑名单附替代写法 + 发布链逐视图 dry-run」三层
机制化解；轻量口径仍可经 `write_file` 手工写入。事实源为工作区
`views/<name>/{metadata.yml,sql.yml}`（`statement` 块标量承载 SQL，`create_view` 渲染 `|-` 字面块；
建模对话经 `create_view` 写入并过 HITL 三重闸）。视图名与 model/cube 名经 `sanitizeIdentifier` 归一后
互斥；wren 引擎 `queryable_names` 含视图名，`官方 run_sql` 可直接按视图名查询。概览注入
`DatasetService#semanticViewsText` 渲染工程 `views/` 文件，每视图在名称与描述下附一行折叠、
超 400 字截断的 `SQL: <statement>`（上游 `_describe_view` 对齐：SQL 原文即视图的 schema，
问数模型免 describe 即得输出列；specs/020，ADR 0034）（`[KNOWLEDGE_BASE_OVERVIEW]`
「语义视图」小节；specs/019 §7 起以工作区文件为事实源；specs/037 起 REST 写侧与对话写侧同权——
`createView` 等 CRUD 在工作区锁内镜像写/删对应工程文件（文件写先于 DB 落库，
绝不单边写，ADR 0045））；
语义建模页视图卡片与术语只读卡片（管理在「语义配置」页）不变。

**派生模型（ref_sql 模型，specs/034 → specs/035 降级人工载体，ADR 0043）**：手工精修载体——
`models/<name>/{metadata.yml, ref_sql.sql}` 双文件（文件优先于 metadata.yml 内联 `ref_sql` 键），
模型不指物理表而是一段 SQL，走 sqlglot 直查路径、无视图那种 DuckDB 规划期函数绑定，用于承载
view 函数表达力覆盖不到的极端口径；官方实验证伪「引擎自解析内层逻辑名」假设——ref_sql 体内
裸名按连接默认库直通解析（MySQL 1146），故发布链在 `context build` 后把 staging
`target/mdl.json` 的 `refSql` 物化为物理全限定名（`RefSqlMaterializer`：词法提取 FROM/JOIN
引用、CTE/字符串/注释掩码、已限定名跳过；未解析引用报 error 中止发布），工作区文件保持逻辑名。
当前 Agent 可按官方技能生成 SQL 定义模型，使用原生文件工具维护；不再强制 create_view 路由。旧 propose_derived_model/create_view 领域工具不注册。读取器 `MdlWorkspaceReader` 解析
refSql/refSqlPath（models/、views/ 缺 metadata.yml 的静默跳过已改 error issue）；`MdlView`
单列 `derivedModels`（`MdlModelView.refSqlPath` 指向 ref_sql.sql）；发布链逐派生模型
`dry-run -s 'SELECT * FROM "<name>"'` 兜底；`mdl.json` 带 `derived` 标记。语义建模页「表与
关系」后保留「派生模型」tab 作为手工资产只读展示面（AssetYamlBrowser 右栏渲染 ref_sql.sql）。

**发布语义与状态机（specs/019 M2，发布链 v2）**：

- 事实源唯一：语义资产以工作区工程文件为准，HITL 确认写入即生效；发布 = 全量校验 + 快照 + 版本。
  BASELINE/SEMANTIC 双模式与 DRAFT 草稿已退役（DB 语义实体停止作为事实源）；specs/037 起读侧同源
  （模型/视图页签同读工作区）且 REST 写侧（视图 CRUD）同步镜像工作区文件，
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
- 业务规则与知识读取统一使用官方 get_instructions/list_knowledge；历史知识库不迁移，由上线前清理。

### 5.7 文档驱动语义增强（specs/014，ADR 0028）

当前 ADR 0068 流程：语义文档经 `DatasetGroupController#uploadKnowledge` 保存，上传不再自动调用
`DocEnhanceService#triggerAnalyzeQuietly`，避免旧版分析与通用建模 Agent 并行。助手通过当前工程 raw 资料和官方 enrich-context 技能完成增强。
以下为旧版手动兼容流程：增强失败只把任务置为 FAILED，不回滚文档。手动重跑和审阅端点位于
`/api/dataset-groups/{id}/modeling/enhance`，所有入口先以 ownerId + groupId 校验租户归属。

`DocEnhanceService#buildPrompt` 把当前表结构、关系、View、术语和组级业务规则与文档原文
同时交给模型；模型逐条返回 `NEW` / `PARTIAL` / `CONFLICT` / `COVERED`。`COVERED` 不落提案，
其余保存为 `DocEnhanceProposalEntity`，类型为 `TERM` / `BUSINESS_RULE` / `RELATIONSHIP` /
`CUBE` / `VIEW` / `MANUAL_FIX`。表和列引用在服务端重新解析；无法安全解析的 payload 降级为
`MANUAL_FIX`。同一 ownerId + groupId 下，PENDING 或 ADOPTED 指纹用于抑制重复提案。

采纳必须由用户在 `SemanticModelingPage` 的“文档语义增强”卡片逐条执行：Term、Relationship、
View 复用 `MdlSuggestionService` 的既有写入路径，Business Rule 由
`DocEnhanceService#createBusinessRule` **双写**（specs/019 §7）：先经 `MdlWorkspaceService`
写组工作区 `knowledge/rules/<sanitizeIdentifier(name)>.md`（问数生效面；同名文件自动追加
`_2.._99` 后缀，不覆盖既有规则），再保存 `SemanticBusinessRuleEntity` 审阅台账（同名校验、
ADOPTED 状态）；文件写入失败则整个采纳中止，DB 不留已采纳行。`CONFLICT` 与 `MANUAL_FIX`
只展示，不允许直接采纳。Relationship、View 采纳后标记 MDL DIRTY 并立即执行
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

### 建模流程快照接口

`ModelingWorkflowController#snapshot` 提供 `GET /api/dataset-groups/{groupId}/modeling/workflow`：principal → modeling-agent RUN 鉴权 → DatasetScope → ownerId 校验，文件/JPA 调用位于 boundedElastic。scope 缺失、会话范围之外或他人知识库均拒绝。工程校验状态是平台文件，agent 不能通过 write_file/patch_file 写入。

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

### 7.0 通用建模工程权限边界（ADR 0068）

建模仍免容器沙箱，但不能使用任意宿主文件和 shell。通用文件工具每次按用户/单知识库范围解析工程；原生技能读取复用 Harness，不允许借工具参数跨工程或选择全局连接。ModelingHitlMiddleware 管写权限确认，官方技能管 Grill/Auto-pilot 的业务澄清，二者不合并。官方 build 修改草稿 target；线上快照仅经显式平台发布更新。官方 memory store 的标准 NL/SQL 文件经发布后由 MdlCatalog#confirmedExamples 读取，问数复用现有官方召回链，不再要求平台专用 verified/validation_id 标记；问数反馈保存策略仍遵循 ADR 0065/0066。

### 7.1 会话隔离与并发

- `gateKey = MsgContext.canonicalKey()`，由 (channelId, userId, agentId, conversationId) 决定——
  每用户每会话互相不可见的实现。
- chatui 通道默认 `DmScope.PER_ACCOUNT_CHANNEL_PEER`：conversationId 流入 `MsgContext.group`，
  实现 ChatGPT 式多会话。
- `HarnessGateway#withGatedTurn/withGatedStream`：同一 gateKey 同一时刻仅一个 turn，以 using 在完成、错误和取消时关闭 lease；交互准入繁忙明确提示，不静默丢弃。
- `contextKeyToSessionKey`：gateKey→sessionKey 内存映射；启动时 `restorePersistedMainSessions`
  从持久化会话存储恢复（平台库 `session_registry` 表，受 SessionResetPolicy 新鲜度过滤）。

ADR 0078：`ChatController#stream/send` 在问数执行前经 `ChatCapacity#execute` 分配单进程/每用户执行或等待名额，建模豁免；SSE capacity 通知 queued/running。等待不创建沙箱、不占工作线程，支持等待和执行超时。排队后执行仍复核当前知识库权限。`ChatCapacity.Lease#operation` 使 Python 真实退出前保留预算。其他通道不经过 ChatController 的该准入。资源门锁不替代租户隔离，也不是多副本全局预算。

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
- ADR 0078：默认最多 16 容器；满额按 LRU 驱逐无活动 lease 的空闲容器，跨 key 驱逐移出 compute 以避免死锁。Docker exec 用 setsid 进程组支持取消，Python 的 timeout 使用 foreground；沙箱镜像需同步更新。控制记录通过 `UserControlFilesystem` 按当前用户隔离，避免读取框架记录时批量启动沙箱。
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

### 7.5 会话历史、附件与沙箱生命周期（ADR 0060、spec 052）

- 展示：数据库 `session_history/session_message` 提供列表与消息分页；索引器从本地引擎日志补齐，列表不访问 Docker。
- 续聊：引擎日志及工作区持久状态继续保留，数据库展示投影不替代引擎上下文。
- 附件：`session_artifact` 元数据加持久目录，所有者通过请求头认证读取；沙箱回收不影响已保存文件，删除或重置会话会清理附件。
- 执行：按用户和 agent 懒创建，活动租约保护回收；每次 Python 独立运行目录，最近输入有限恢复；容器资源和数量受限。
- 维护：容量保护、分段锁和分批孤立文件补偿已实施；正常历史保留任务见 ADR 0061，默认关闭并先试运行；多实例协调和对象存储尚未实施。部署参数与备份范围见 [会话与附件部署说明](docs/reference/session-history-and-artifacts.md)。

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

### 知识库共享（ADR 0077）

`KnowledgeBaseAccessService#requireQuery/requireManage` 统一 QUERY/使用与 MANAGE/管理权限；创建者和当前全局管理员拥有管理权限。列表先按 ACL 过滤再分页，成员变更与审计同事务提交。QUERY 只能获取查询卡片和调用已发布工程，草稿、原始数据预览、建模、发布、问答对管理及成员管理需要 MANAGE。

`ConversationScopeRegistry` 按用户和会话持久化最近选择，并按用户消息保存实际范围快照。`ChatController#resolveGroupScope` 支持普通问答零/单/多/全选，建模保持单库；`ChatTurnScope#accept` 在框架会话锁内复核并接受范围。`WrenQueryAccess` 查询共享库而不替换操作者；官方 MCP 调用与工具发现经 `WrenInstanceRegistry#callAuthorized/listToolsAuthorized` 在排队结束后再次授权。

知识库模型和数据归创建者，会话、历史、沙箱、附件及反馈状态归操作者。点赞 SQL 样例进入该库的官方 memory，保存和索引通过 `WrenPublishedProject#locked` 按知识库串行执行；不把草稿混入已发布查询工程。当前官方 Wren 建模、问数语义边界不变。

`KnowledgeBaseOperations` 跟踪运行操作、导入表及 CLI 进程，删除时停止相关操作；`KnowledgeBaseDeletionService` 持久化删除日志并支持重试。ADR 0078 在此基础上增加上传准入和 ChatCapacity，而非来源截至 32c6d292 时的取消/忙提示。`WrenInstanceRegistry#withGate` 将工具发现和查询统一为每库公平有时限门锁，保留排队后授权检查；信号量限制 MCP 实例，关闭失败保留名额并重试清理。此预算不含所有 CLI 短进程。

- **认证**：`SecurityConfig`（WebFlux Security）：`POST /api/auth/login`、actuator health/info、`/api/webhook/**` 公开；
  其余 `/api/**` 需 JWT（`JwtAuthFilter` 校验 Bearer 或 `?token=` 查询参数，后者供 `<img src>` 使用）。CORS 放开（含 Vite dev 5173）。
- **前端会话**：`SessionGate` 在受保护页面挂载前通过 `auth.me` 校验缓存令牌；`auth.apiFetch` 统一处理同源 API 的 401，只有请求使用的令牌仍为当前令牌时才清除登录状态，显示“登录状态已失效，请重新登录”。登录接口及外部资源不触发退出；403 和网络故障保留会话，首次校验网络失败提供重试。
- **授权**：`web/share/AgentAclService` + `AgentAccessGuard`：每个 agent 按 Tier（RUN/EDIT/…）鉴权，
  Controller 入口统一 `guard.require(userId, agentId, Tier.X)`。
- **agent 目录**：`web/catalog/AgentCatalogService` 合并全局与用户可见自定义定义，复用 Agent ACL；当前仍有创建/克隆兼容 API，详见 docs/reference/agent-definition.md。主建模/问数的固定工具装配不自动套用于所有自定义 Agent。
- **审计**：`web/audit/AgentActivityStore`（RUN_SESSION 等事件，`AgentActivityController` 查询）。
- **用量**：`web/usage/UsageStore`（每 turn 记录 userId/agentId/耗时，admin 端 `/api/admin/usage/...` 聚合）。
- **AI 草稿**：`web/ai/AgentDraftService`（聊天式生成 agent 定义，`/api/agents/draft`），语义建模页 AI 建议（`dataset/MdlSuggestionService`，经 `chatBlockingModeling` 转录进 `logs/LLM-modeling.log`）也复用它；直调模型时以与 DebugLoggingMiddleware 相同的 role/type/text 结构把输入输出转录进对应文件（见 2.3）。

---

## 11. 前端结构

> 当前 SemanticModelingPage 使用四个显性入口：数据与资料、对话建模、检查与发布、查看语义模型。移除主页面独立关系推断/文档增强/DB 视图编辑；可选问题使用可增加行数的表格。聊天占主体宽度，文档紧凑显示保存状态；资产只读浏览包含模型、关系、ref_sql、视图、Cube、knowledge 和 MDL/发布快照。

ADR 0063：建模页面移除 Cube 标签、推荐、编辑和图谱卡片，展示模型、关系、视图、规则与 MDL；问题录入/验证保持可选。

**当前交互（specs/054）**：SemanticModelingPage 直接提供对话，QuestionIntakeForm 为可展开的可选表格；验证与发布面板说明问题状态不阻碍发布。ChatPanel 最终答案展示 AnswerFeedback 的醒目点赞/点踩、选择态和持久化反馈结果，不逐条确认工具 SQL，不阻塞下一轮问数。SSE `done.answerId` 取自原生 `AgentResultEvent` 的结果消息 ID，刷新后使用历史消息 ID 恢复反馈；`sessionKey` 仍是 conversationId。内部答案查询声明工具不展示在用户轨迹中。具体存储及权限见第 3 章。

`ModelingHitlCard` 对 `write_file.files_json` 展示一个业务方案卡，业务说明优先、逐文件完整内容折叠且复用 `ReadableCode`；按钮一次采纳整套方案或退回调整。批量卡不调用单文件 preview 接口，不提供未预检的直接编辑；调整由 agent 重新提交完整方案。单文件卡保留既有预检和编辑。采纳仅写入草稿，卡片明确引导后续问题验证、结果确认和发布，不把文档定义说成人员已验收。SSE 协议不变。

建模问题入口使用 `QuestionIntakeForm` 可增删行表格；`ModelingQuestionsPanel` 将问题 SQL 修正、模型修复提议与移出验收分开，并在操作后刷新流程快照，提供发布入口。人工编辑只操作问题实例，模型修复仍由现有建模对话和 HITL 执行，详见第 3 章与 specs/051。

React 18 + TypeScript + Vite SPA（`frontend/`），构建产物进 `classpath:/static/` 由后端托管。

- **页面**：
  - 聊天：`ChatPage`（SSE 消费 `api/chat.ts`，`ChatPanel`/`ToolCallBlock`/`EChartsBlock`/`PythonArtifactsPanel`/`Markdown` 渲染），`OntologyChatPage`
  - 配置：`configure/`（DatasetsPage、DatasetDetailPage、DatasetGroupPage（左侧导航：文件/知识图谱（已停用，历史展示）/树结构目录/语义建模；独立「关系说明文档」视图已删除，上传统一为建模页「上传语义文档」，specs/028。第五 Tab「语义建模」= 独立全屏路由 `/configure/modeling/:groupId`；`AppShell` 隐藏全局会话侧栏。specs/041 / ADR 0050 将聊天置于建模主区域，右侧为问题清单及阶段引导，顶部固定“查看模型”切换资产工作区；旧资产 URL 与选中位置保留。模型/派生模型/视图复用 `AssetYamlBrowser`，MDL 页提供当前结构图及草稿/发布工程快照。文档语义增强提案移入术语与规则详情）、SemanticConfigPage、SkillsPage、SubagentsPage、ToolsPage、ChannelsPage、SettingsPage）
  - 管理：`admin/`（Overview、Agents、Users、Channels、Instances、Sessions、Usage、Config、Debug）
  - 其他：Login、Profile、Workspace、Usage、Appearance、UserBindings
- **MDL 可视化与确认**：`MdlPublishPanel`（YAML diff + 验证/发布）、`MdlGraphView`（G6 只读 ERD）与 `ModelingHitlCard`（写工具确认/调整；文件写工具 `write_file`/`patch_file` 走专用变更卡——服务端预检结论、行级 diff 折叠、全文/替换文本编辑、重新预检，specs/019 §5，行级 diff 由 `utils/diff.ts` 自实现）；`MdlGraphView` 和关系确认卡复用 `ReadOnlyRelationGraph`，保持只读语义。
- **API 层**：`frontend/src/api/*.ts` 一一对应后端控制器（chat/datasets/datasources/knowledgeGraph/semantic/semanticModeling/ontology/sessions/…）。
- **工作区**：`WorkspaceFileTree` + `WorkspaceEditor` 经 `AgentWorkspaceController` 读写用户沙箱文件。

---

### 当前建模工作台（ADR 0069、0075、0076）

`SemanticModelingPage` 组织建模对话、资料、语义模型三区（spec092 §7）。建模对话页以对话为中心：每轮回复下方列出 AI 建模结果，待确定的口径以对话内卡片出现，右侧试问验证口径，底部草稿栏统一发布。文档修改后自动排队一条文档更新请求。助手按官方技能直接修改草稿，发布为显式操作，官方工程校验失败保留旧快照。问数始终使用已发布工程。

语义资产展示普通/ref_sql 模型、关系、视图、Cube、knowledge 及 MDL/工程快照；SQL 问答对区由 SqlQaExamplesPanel 管理建模与点赞来源，修改/删除遵循来源生命周期，详情见第 4.2 节及 ADR 0075/0076。工具返回通过 WrenToolResult 中文结构化展示，原文可查看。

### 历史引导界面与兼容接口（ADR 0050—0055）

以下保留历史演进及兼容组件说明；其中阶段剧本、问题必填/逐题审阅、自动文档分析等已经被上述当前流程取代，不得依据本节恢复旧 Agent 注册或发布门禁。

ADR 0054 / spec 044：`MdlPublishService#validateQuestion` 在工作区锁内复用 `MdlWorkspaceReader#read`，将问题 SQL 与当时全部视图定义目录写入 `MdlQuestionStore.Receipt.evidence`。确认与拒绝保留原快照，旧凭据缺失快照时提示重验，不用当前定义冒充历史依据；视图变化仍使旧凭据过期。`ModelingQuestionsPanel` 就地展示定义 SQL、确认人/时间，直接应用确认接口返回的 Review，并用服务端 workflow 展示下一步。建模与验证清单复用 `QuestionIntakeForm`；显式提交后通过原生 SSE 发送一次，并经原生 HITL 后才成为已保存 YAML。

ADR 0053：原生拒绝可能保留历史 ToolUseBlock 的 ASKING 状态，已处理边界以非 suspended ToolResultBlock 为准。`ModelingHitlMiddleware#onActing` 排除已处理调用后继续委托原生 acting/reasoning；`ChatController#findAskingTools` 不恢复或再次确认这些旧调用。

`ModelingChatPanel#restorePendingProposal` 在消息流与确认恢复流结束后回读原生 currentSession，把已持久化的 ASKING 提案显示到当前回复；部分恢复流只含 done 时仍可继续确认。回读不执行或批准工具，也不创建另一套确认状态机。

ADR 0052 / spec 043：预检失败卡片可“让助手修正”，`ChatController#confirmModeling` 接收仅拒绝可携带的 feedback（最多 4000 字符），仍通过原生 ConfirmResult 拒绝当前调用。框架确认恢复不保存入站文本，因此 `ModelingHitlMiddleware#onAgent` 在本次 RuntimeContext 暂存反馈，`ModelingHitlMiddleware#onReasoning` 仅在匹配的原生 DENIED 结果生成后把反馈加入会话和模型输入，避免跨调用共享。新提案仍须 HITL 与写前门禁。`BusinessDocumentGuide` 使用紧凑状态栏与原生 dialog 全文预览，查看文档不挤占聊天高度；进度栏 300px，助手回复占满可用宽度。

ADR 0051 / spec 042：`BusinessDocumentGuide` 在建模主区顶部建议先上传业务文档，再提出分析问题，无文档仍可直接对话。`SemanticModelingPage#onUploadSemanticDoc` 按真实请求显示进度、成功文件名或错误，成功后更新已保存内容；刷新通过 GroupDetail.knowledge 恢复资料状态。文档保存与异步口径分析分别显示，全文在弹窗预览；更新上传替换当前内容。顶部显著“查看语义模型”入口标注模型/关系/视图/MDL 范围。

spec 041 / ADR 0050：`SemanticModelingPage` 的对话建模阶段以聊天为主区域，右侧显示同一批问题与下一步。顶部保留数据准备、对话建模、验证确认、发布导航，以及固定“查看模型”入口。模型/派生模型/视图/术语/MDL 沿用资产 URL，在独立详情工作区浏览，返回不卸载聊天或待确认 HITL。窄屏按上下顺序展示。

spec 045 / ADR 0055：无已登记问题、无待确认原生 HITL 时，页面先展示业务文档入口和批量问题表单，隐藏聊天及重复清单入口。显式提交后展示聊天并发送一次批量请求；恢复已有问题或待确认 HITL 时直接展示聊天。新增问题仅从表单进入，对话用于口径澄清和修正。助手通过原生 HITL 保存 `knowledge/questions/*.yml`，完善 SQL，并据此创建或复用视图。已登记清单仅展示实际保存的 YAML，修改和移除通过对话预填提交。移除采用受保护 YAML 的 `archived=true` 归档，`MdlQuestionStore#readQuestions` 忽略归档项，保留原文件与审计记录；已确认示例仍按当前有效问题过滤，不把归档问题作为发布示例。

`ModelingWorkflowService#snapshot` 将所有问题计入不完整、待验证、待审阅进度，所有未确认项进入发布阻断；`ModelingQuestionsPanel` 批量验证全部完整待验问题，各结果仍逐项人工确认。服务端重新核验所有未归档问题及工程结构；忽略历史分类，内容未变化的旧确认哈希兼容有效。模型/问题变化会使旧结果失效。

`ModelSnapshotPanel` 在 MDL 详情展示草稿和已发布工程文件及差异；上方 `MdlGraphView` 是当前草稿的结构可视化，工程 YAML 明确标注为工程定义，不冒充编译 JSON。`MdlPublishPanel` 展示业务变更和全部问题确认，技术差异折叠。阶段接口及问题查询/验证/确认接口沿用 `api/modelingWorkflow.ts`、`api/modelingQuestions.ts`，无 SSE 变更。问数始终使用已发布版本，HITL 文件修改确认与业务结果确认分开。


## 12. 配置与启动

> Standalone 构建使用根目录 mvn test / mvn package -DskipTests，前端在 frontend/ 运行 npm run build。requirements-wren.txt 固定 wrenai 0.15.0/core 0.8.0 及 memory-onnx；服务实际 CLI 环境必须安装相同依赖。多语言模型首次下载需要网络，可用官方缓存，不自研词项降级。

### 12.1 关键配置（`application.yml`，`dataagent.*` 前缀 / `DATAAGENT_*` 环境变量）

平台增量带入 `dataagent.seed-demo-users`（默认 false，显式启用 Java 本地管理员初始化，不替代 SQL 初始化开关）和 `dataagent.cors.allowed-origins`（默认 `*`，部署时指定允许来源）。JWT 密钥拒绝空值、开发占位值及不足 32 UTF-8 字节的短密钥。本轮不覆盖现有 application.yml 或部署 profile。

| 配置 | 默认 | 说明 |
|---|---|---|
| `dataagent.jwt.secret` | dev 占位 | 生产必须覆盖（≥32 字符） |
| `dataagent.workspace` | JVM cwd | 运行态工作目录 |
| `dataagent.agent.name` | `data-agent` | 自动生成 `agentscope.json` 时的 agent 名；系统提示词默认用内置常量，覆盖用 `DATAAGENT_AGENT_SYS_PROMPT`（见 4.5） |
| `dataagent.agent.subagents-enabled` | false | 子代理编排（harness `agent_spawn` / `agent_send` / `task_*`）。关闭可省下每轮固定约 6.2k 字符的 `## Subagents` 提示词段与相应工具 schema；**不影响网关、会话路由与聊天**（`buildSubagentEntries` 不读该开关），内置定义也不删（ADR 0009） |
| `dataagent.openai.*` | DeepSeek 默认值 | 主模型（任意 OpenAI 兼容端点） |
| `dataagent.openai.stream` | `true` | `DATAAGENT_OPENAI_STREAM` 可切换模型流式/非流式请求以定位上游 EOF；前端 SSE 协议不变，关闭流式不表示连接问题已解决 |
| `dataagent.dashscope.*` | — | 兜底模型 |
| `dataagent.sandbox.image` | `agentscope/dataagent-sandbox:latest` | 沙箱镜像（`docker/sandbox.Dockerfile` 构建） |
| `dataagent.sandbox.max-containers` | `16` | 单进程容器上限，满额驱逐空闲实例；环境变量 DATAAGENT_SANDBOX_MAX_CONTAINERS |
| `dataagent.upload.max-file-size` / `max-selection-size` / `max-files` | `50MB` / `100MB` / `10` | 上传单文件、整批及数量上限 |
| `dataagent.upload.max-rows` / `max-columns` / `max-cells` | `100000` / `100` / `2000000` | 解析规模上限 |
| `dataagent.upload.concurrent-files` | `8` | 单进程在途文件准入，允许 1—32 |
| `dataagent.chat.max-active` / `max-active-per-user` | `16` / `2` | 问数全局/每用户执行数 |
| `dataagent.chat.max-waiting` / `max-waiting-per-user` | `16` / `1` | 全局/每用户等待数 |
| `dataagent.chat.wait-seconds` / `execution-seconds` | `30` / `600` | 等待/执行超时 |
| `dataagent.wren.max-instances` / `call-wait-seconds` | `16` / `30` | MCP 实例数与每库门锁等待秒数 |
| `dataagent.openai.http-version` | `HTTP_1_1` | 默认模型协议，环境变量 DATAAGENT_OPENAI_HTTP_VERSION |
| `dataagent.dataset.datasource.*` | 本地 MySQL `data_agent` 库 | **上传数据集物理表所在库** |
| `dataagent.wren.*` | `wren` CLI / `~/.agentscope/dataagent/mdl` / `mysql` / 180s / 1800s | WrenAI CLI 与 MDL 产物根（拼装/validate/build/发布快照，specs/010 M2，见 5.6）；`profile`/`instance-idle-seconds` 供运行期 `serve mcp` 实例池用（M3，见 4.6/ADR 0020）。M4 起 `profile` 是默认回落值——外部数据源组的实际连接由快照内 `wren-source.properties` 钉入（`ext-<datasourceId>`，发布期解析，见 4.6/ADR 0021）。`executable` 默认值按平台探测：Linux 先查 `~/.local/bin`/`/usr/local/bin`/`/usr/bin` 再回落 PATH（`pip install` 后零配置），Windows 需显式指向 venv `wren.exe`；`skills-dir` 指向 wrenai 包内官方 skills 根（specs/022、ADR 0035，空值从 executable 位置推断，失效仅 warn 降级 CLI 通道）；CLI 与 serve mcp 两处 spawn 失败统一回跨平台安装引导（`WrenProperties#resolveExecutable`/`unavailableMessage`），集群部署前置见 `docs/guides/cluster-deploy.md` |
| `dataagent.analytics.enabled` | false | 预置分析库（tenant_storage_utilization / project_info） |
| `dataagent.expose-app-db` | false | 是否把平台元数据库暴露为数据源 |
| `dataagent.session.redis.*` | 关闭 | 分布式会话（HA 必需；多副本另需 sticky LB，见 7.3） |
| `spring.datasource.*` | 本地 MySQL `dataagent_platform` | 平台元数据（JPA）；h2 profile 切文件 H2，其他数据库需同步驱动和初始化 SQL |

### Wren 记忆依赖

`requirements-wren.txt` 固定官方 CLI 与 memory-onnx 依赖。在配置的 Wren Python 环境安装该文件；Wren 默认自动选择多语言 ONNX/lancedb，首次使用需要下载模型。可由进程环境设置 WREN_MEMORY_BACKEND=lancedb、WREN_EMBEDDING_BACKEND=onnx、WREN_EMBEDDING_MODEL=paraphrase-multilingual-MiniLM-L12-v2；实际后端以 memory status 为准，依赖缺失时即使设置 lancedb 也会降级。应用不修改外部 Python 环境，不自动安装依赖。

### 12.2 构建与运行

```bash
# 构建（含前端：自动安装 Node → npm build → 后端打包）
mvn package -DskipTests

# 格式门禁（CI 必过）
mvn spotless:check

# 沙箱镜像（一次性）
docker build -f docker/sandbox.Dockerfile -t agentscope/dataagent-sandbox:latest .

# 运行
java -jar target/agentscope-dataagent-*-exec.jar   # → http://localhost:8080
```

H2 种子账号：`bob/bob`、`alice/alice`（`data-h2.sql`，幂等 MERGE）。

根 pom.xml 的编译基线为 Java 17，本项目不依赖旧多模块 `-pl agentscope-examples/...` 路径。框架改动后先安装配套 harness/OpenAI 扩展，再构建本项目；默认 Maven 流程会构建前端，已有前端构建时可使用 skip.npm/skip.installnodenpm 参数跳过。保留原 Boot exec JAR，同时按 ADR0080 生成 dataagent-<version>.tar.gz，包含 bin/conf/lib。公共默认配置及 h2 profile 保留；私有 dev/prod 的 YAML/properties 同时由 Maven resources 和 jar 插件排除，分发仅带无凭据样例。dataagent.sh 通过共享启停锁、PID/启动时间/JAR 身份管理单实例，启动前用 check-wren.py 校验固定 Wren 依赖，再等待 HTTP 健康；停止不依赖 Wren。数据位于版本父目录 data/，日志位于当前版本 log/。发布默认重建前端，可选 frontend/build.ps1 只是提前构建包装。步骤及脚本环境变量见 [单机发布](docs/guides/single-host-release.md)。部署步骤见 [并发与发布说明](docs/guides/platform-concurrency-and-release.md)。

SQL 演示账号初始化与 Java 管理员初始化是独立开关；`dataagent.seed-demo-users` 默认 false，不代表禁用 SQL 初始化。生产环境须明确设置初始化策略和数据库配置，不能根据旧文档假设默认 H2 或自动创建管理员。

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

> ADR 0081 新入口：WrenQueryAccess（权限选择）、WrenPublishedProject（发布部署/官方索引）、WrenQueryContextMiddleware（项目目录）、WrenScopedToolkitMiddleware（调用作用域 Toolkit）、WrenScopedMcpClient（原始 MCP 透传）、WrenInstanceRegistry#listTools（原生发现）。验收 fixtures 位于 docs/fixtures/wren-native/，30 个固定 SQL/标准结果用于执行对照，不能等同真实 LLM 准确率。

| 关注点 | 类（锚点） |
|---|---|
| 应用入口 | `web/DataAgentApp` |
| Spring 装配/模型 | `web/config/DataAgentConfig`（`builderBootstrap`、`openaiModel`、`dashscopeModel`） |
| 安全 | `web/config/SecurityConfig`（`JwtAuthFilter`） |
| Bootstrap 三阶段 | `runtime/DataAgentBootstrap`（`Builder.build`、`applyFileEntry`） |
| 网关/沙箱注入/并发门/announce | `runtime/gateway/HarnessGateway`（`runStream`、`attachUserSandboxContext`、`setSandboxlessAgents`、`withGatedStream`、`tryDispatchAnnounce`） |
| 聊天端点/SSE/HITL 恢复 | `web/api/ChatController`（`stream`、`confirmModeling`、`currentSession`、`buildInbound`、`toAgentFrame`、`toToolFrame`） |
| 动态上下文注入 | `runtime/session/DataDynamicContextMiddleware`（`onSystemPrompt`；M3 按组分流读 `MdlCatalog`） |
| Wren 问数工具 | `tools/data/WrenToolkit`（`wrenDescribeModel`、`wrenRunSql`）与 `tools/data/DataAgentToolkit`（`retrieveEvidence`、`renderChart`） |
| Python 沙箱工具 | `tools/data/RunPythonTool`（`runPython`） |
| 图表生成 | `tools/data/ChartBuilder`（`build`、`applyMarkLine`） |
| 工具注册 | `tools/data/DataToolkitRegistrar` |
| 数据源注册表/种子 | `tools/data/InMemoryDataSourceRegistry`、`tools/data/DataToolkitConfig` |
| 数据集生命周期 | `dataset/DatasetService`（`ingest`、`associateTables`、`rebuildRegistry`、`relationshipsText`、`semanticTermsText`、`semanticViewsText`、`relationsFor`） |
| 文件解析/导入 | `dataset/DatasetImportService`、`dataset/parser/{ExcelParser,CsvParser,TypeInferrer}` |
| AI 字段描述 | `dataset/parser/SchemaGenerationService` |
| 外部库内省 | `dataset/DataSourceIntrospector`、`dataset/TableProvisioner` |
| 语义建模（M1） | `dataset/MdlSuggestionService`（`refreshRelations`、`confirmRelation`、`addManualRelation`、`suggestCubes`、`createView` 及 specs/037 工作区镜像写、`persistConfirmedRelation`）、`web/api/SemanticModelingController` |
| 当前建模与通用 HITL | `ModelingToolkitRegistrar#registerOfficialTools`、`OfficialWrenToolkit#execute`、`WrenProjectAccess#resolve`、`WrenProjectFilesystem`、`ModelingHitlMiddleware`；前端 ModelingChatPanel/ModelingHitlCard。旧 ModelingToolkit 仅兼容源码，不是当前注册工具链 |
| 文档语义增强（specs/014） | `dataset/DocEnhanceService`（`triggerAnalyzeQuietly`、`adopt`）、`web/api/SemanticModelingController`（enhance 端点）、`web/persistence/jpa/{DocEnhanceTaskEntity,DocEnhanceProposalEntity,SemanticBusinessRuleEntity}`、前端 `pages/configure/SemanticModelingPage` |
| MDL 拼装/发布（M2 → specs/019 v2） | `dataset/MdlPublishService`（`preview`、`validate`、`publish`、`deleteArtifacts`、`view` 只读视图）、`dataset/{MdlSeeder,MdlWorkspaceService,MdlWorkspaceReader,WrenTypeNormalizer}`（播种/工作区/读面）、`dataset/{WrenCli,WrenProperties}`、前端 `components/{MdlPublishPanel,MdlGraphView}` |
| Wren 运行期（M3） | `tools/data/WrenToolkit`（`wrenRunSql`）、`runtime/wren/{WrenQueryGateway,WrenInstanceRegistry}`（`call`、`invalidate`、`resolveSnapshotProfile`）、`dataset/{WrenProfileHome,MdlCatalog}` |
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

## 14. 建模与问数的当前运行约束

本章只写稳定的结构与边界；具体数值（上限、批量、文案）以代码常量和测试为准。决策原因见 [ADR 索引](docs/adr/README.md)。

### 14.1 建模 Agent

| 方面 | 当前行为 | 入口 |
|---|---|---|
| 技能 | 同版本官方技能，默认 Auto-pilot；挂载后关闭 CLI skills 通道；不加载 Cube 提案参考 | `DataAgentConfig.mountWrenSkills`、`OfficialWrenToolkit#command`、`ModelingHitlMiddleware#onActing` |
| 文件范围 | `cubes/`、`views/` 不读不写；`models/<模型>/` 只允许官方文件；`knowledge/sql/` 不可写；平台文件与路径逃逸拒绝 | `WrenProjectAccess#path` |
| 文件写入 | YAML 先做语法检查，失败不落盘；可整体替换已有文件；所有工程文件写入直接进入草稿，不再弹出确认卡（ADR0111）；每次写入记入 `DraftChangeLog`（来源、用户、时间）；自动建模轮次在没有业务文档时拒绝写 `knowledge/rules/`，推断只能写 `knowledge/caveats/` | `WrenProjectFilesystem#write/edit/delete/rulesGuard`、`ModelingHitlMiddleware#onActing` |
| CLI 执行 | 参数、Cube、memory store、时间门禁在工程锁前检查；未编译时先自动 `context build`；单轮 query 上限 `MAX_QUERIES_PER_TURN`；超时结束进程树 | `OfficialWrenToolkit#execute`、`ProcessWrenCli#destroyTree` |
| 草稿与建模结果 | 修改先进草稿，不单独审阅（spec092 §7）。每轮回复下方显示"AI 建模结果"：`DraftItemService` 把本轮写入（`DraftChangeLog` 中该轮开始后的文件）与已发布快照的差异拆成条目——模型（表/列说明的前后对比）、关系（按 name）、业务语义（rules/glossary/metrics 的 Markdown 条目）、注意事项（caveats 条目），依据为来源小字；可逐条编辑，关系、业务语义、注意事项可逐条删除，模型只能编辑；无法拆分的整段内容按整段显示。底部草稿栏汇总全部条目、待你确定数和新示例数，发布前自动检查，仍有待确定项时提示并可定位或跳过；可放弃全部草稿；助手运行中不可发布或编辑；多人共用一份草稿 | `DraftItemService#items/updateModel/updateRelationship/updateBullet`、`DraftReviewService#review/discard`、`ModelingActivity`、`DraftReviewController`、前端 `TurnResultCard`、`DraftItemsList`、`DraftBar` |
| 需要你确定的部分 | 能确定的直接写入；只有会改变统计结果的歧义才经 `request_user_confirmation` 提问（2–4 个选项、首项推荐，每轮最多 3 题，待确定上限 10）。问题存于 `.platform/confirmations/`，不发布、问数不可见；助手空闲时以对话内卡片逐题展示（选项、自己写、不采用、稍后），最后一题后一起交还，不弹窗；去掉括号说明、空白与标点后相同的问题视为重复；只有实际创建的问题计入每轮上限，选项名多于规则原文时多出的（如"其他"）被忽略；外部表的候选关系也经此由用户确定后写入 `relationships.yml`，关系问题（`topic=relationship`）另有每轮 3 个名额；答复以 `purpose=confirm` 交还助手写入规则或列描述；未确定不生效、不阻止发布 | `ModelingConfirmationTool`、`ModelingConfirmationService`、前端 `ConfirmationCards` |
| 轮次用途 | 页面随请求发送 `purpose`：`auto` 自动建模、`document` 文档更新、`question` 问题处理、`confirm` 确认答复，无用途为用户对话；`ChatController#stream` 写入 `ConversationScopeRegistry#putModelingPurpose`，`buildInbound` 放入 `ModelingTurnPurpose`，用于修改来源、外部表查询许可与规则写入约束 | `ModelingTurnPurpose`、`ChatController#trackModeling` |
| 业务文档 | 每库多份，存于草稿 `raw/docs/<名称>.md`，随快照发布；新增、替换（旧版本存 `.platform/docs-history/`）、删除均为草稿修改；只能由用户上传，助手只读；修改后页面自动排队一条 `document` 请求，连续修改合并为一条；问数不直接读取文档 | `BusinessDocumentService`、前端 `BusinessDocumentsPanel` |
| 入口与增量建模 | 采样与时间配置完成后（`?start=1`）先提示上传业务文档，用户点"开始自动建模"才发送 `purpose=auto` 请求；上传数据弹窗不再附文档；已发布过的库新增表时，运行上下文列出"本次新增的表"，助手只处理新表 | `SemanticModelingPage#startModeling`、`WrenProjectContextMiddleware#newTables` |
| SQL 词法 | 单条只读 SELECT/WITH，拒绝笛卡尔积写法，允许官方 LIMIT/OFFSET；建模、问数、问题验证共用 | `OfficialWrenToolkit#requireReadOnly` |
| 运行上下文 | 授权工程、服务时间、编译状态、按表来源的探查边界（上传表有限探查，外部表禁止）、外部受限表时间策略 | `WrenProjectContextMiddleware#onSystemPrompt` |
| 外部表探查硬约束 | 基础建模轮次不查询非上传表；只有 `purpose=question` 的问题生成、重试、修复轮次可以查询；问题轮次内仍禁止 MIN/MAX，且外部表返回空结果后同轮换日期重试直接拒绝（极值限制只针对时间列，MAX(属性列) 这类分组带出属性不受限）；该检查在时间门禁之前，被拒的查询不调用门禁模型；`dry-plan` 只编译不读数据，不过时间门禁。受限事实表的时间条件写法由平台按采样识别的列格式（`temporal_columns.format`）与数据源生成并注入建模、问数上下文：不补零文本用 `STR_TO_DATE`/`TO_TIMESTAMP` 转换后 `>=`/`<`，补零文本、原生类型与数字日期键直接比较，不用 LIKE；门禁放行结果在进程内按知识库、规则与 SQL 复用（1 小时、500 条），同一 SQL 在试运行、确认正确与问数间判断一致 | `OfficialWrenToolkit#guardExternalProbe`、`isEmptyResult` |
| 建模页与入口 | 左侧导航"语义建模"打开知识库列表；页头一行（返回、版本、助手状态、标签建模对话/资料/语义模型、试问、去问数）。建模对话页以对话为主，连续工具步骤完成后折叠为一行；"试问"为按需打开的右侧栏（只在用户点击时打开）；底部草稿栏为一行，"查看全部"向上展开抽屉，可发布时按钮高亮；对话在用户位于底部时随内容（含异步加载的结果卡）自动跟随；助手空闲且无待确定问题时，对话末尾显示"下一步"卡（试问、检查并发布，发布后去问数）；页面请求按序排队，助手忙时提示排队数；提交问题不跳页。试问：推荐 3 个问题（`QuestionSuggestionService#MAX_SUGGESTIONS`）或自由提问，以 `purpose=question` 让助手写 `knowledge/questions/<ID>.yml`，助手空闲后平台自动试运行；"确认正确"写入草稿 `knowledge/sql/<ID>.md`（source: user-confirmed），发布后可召回；"不正确，告诉助手"转给对话。试运行与确认正确都过时间门禁；推荐问题在用户打开试问时才生成，要求单指标、至多一个维度的简单问题 | `ModelingHubPage`、`SemanticModelingPage`、`TrialPanel`、`MdlQuestionService#validate/decide` |

### 14.2 用户问题验证与问答召回

问答对只来自用户确认和问数点赞，基础建模不生成。流程：

1. 自动建模轮结束、流结束并从服务端会话恢复确认状态后仍没有待处理的确认卡（`ModelingChatPanel#finishTurn` → `onTurnDone(awaitingConfirmation)`；批量确认中途的 `done` 不触发；页面还有排队的请求，如"按新文档更新口径"，时推迟到该请求完成后）且编译成功 → `SemanticModelingPage` 弹出 `VerifyQuestionsPrompt`。表单预填平台推荐问题（`QuestionSuggestionService#suggest`，接口 `GET .../modeling/questions/suggestions`，只生成问题文本，失败返回空；受限表的日期优先取采样中时间列的观察范围 `sampledRanges`，外部表无需探查也能落在有数据的区间），可编辑、删除、新增；批量上限见 `QuestionIntakeForm.MAX_QUESTIONS`。
2. 提交后页面立即切到"检查与发布"，未写入的问题显示"生成 SQL 中"；请求仍排在运行中的轮次或待确认卡之后时显示排队原因和"去处理确认卡"入口（`ModelingChatPanel#onActivityChange` 的 `AssistantState`）；Agent 为每题生成并试运行 SQL，写入 `knowledge/questions/<ID>.yml`，`ModelingChatPanel` 在该文件写入成功时即刷新列表。
3. `ModelingQuestionsPanel` 左列表右明细；确认按钮在明细中，不要求先试运行（spec091）。试运行可选：`MdlQuestionService#validate`（先过时间门禁）→ `MdlPublishService#validateQuestion`。
4. 确认 → `MdlQuestionService#decide`（时间门禁）→ `MdlQuestionStore#decide`（只读/笛卡尔积检查与资产覆盖，写 `knowledge/sql/<ID>.md`，frontmatter `source: user-confirmed`；官方索引只把 `source` 转成标签、忽略 `tags` 列表，召回结果因此显示 `source:user-confirmed`）；确认只绑定问题与 SQL 哈希，模型变化不使其失效。撤销、删除时移除问答。
5. 发布时 `MdlPublishService#dryPlanConfirmedQuestions` 对已确认 SQL 做官方 `dry-plan`：失败的题不进入快照，经 `MdlQuestionStore#revokeConfirmation` 退回并显示原因，不阻止模型发布。
6. 发布快照 → 问数 `WrenPublishedProject#prepare` 合并点赞问答并索引 → `recall_queries` 召回。

### 14.3 SQL 时间门禁

- 外部数据源事实表的时间窗口保存在平台数据库，不属于 MDL，保存即生效；上传表不设门禁（`FactTableWindowService`、`FactQueryWindowPolicy#rules`）。
- `SqlWindowGate` 把原始 SQL 与登记配置交给无工具 LLM 逐表判断，`parse` 按逐表明细重算结论；未登记表与 `query_cube` 不覆盖。
- 建模 CLI、问数 MCP（`WrenScopedMcpClient#callTool`）、问题验证共用 `FactQueryWindowPolicy#requireAllowed`；拒绝后本轮阻止后续 SQL；判断写入 `logs/sql-window-gate.log`。

### 14.4 问数提示

- `WrenQueryContextMiddleware`：每轮注入服务时间、授权库与时间策略；`dateRules` 只在所选库登记了受限事实表时要求"缺日期先问"；范围超过表的最大天数（如整年）时直接请用户给出不超上限的范围，不提出拆分多次查询再汇总的方案。
- `DataAgentConfig.QUERY_EFFICIENCY_RULES`：已写月日即视为给了日期；统计结果必须来自 SQL 返回值；排名只按主体分组。

### 14.5 发布后问数准备与可观测性

- 发布成功后，`WrenQueryPreparation` 在事务提交后后台准备索引并做一次只读召回预热；`QueryPreparationController#status` 供发布页显示准备状态（进程内建议值）。
- `logs/execution-timing.log` 记录工具、索引、Wren 队列、门禁与 `wren_cli.*` 分段耗时；嵌套耗时不可相加。
