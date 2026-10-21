
# agentscope-dataagent 架构与流程详解

> 本文档面向开发者与 AI 编码助手，基于当前代码实际实现梳理（非上游 agentscope 示例的原始版本）。
> 核心流程：**通过 Wren 进行语义建模 + 问数**——数据集组即 wren project，表结构变化自动播种并发布
> 基础 MDL；建模助手（modeling-agent）经对话完善关系/模型口径/明细视图/业务规则后发布；问数助手（data-agent）
> 在已发布语义模型上发现并透传官方 MCP（`wren_list_tools` / `wren_call_tool`），
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

> 当前主 Agent 装配以 ADR 0069 为准：建模与问数均挂载同版本官方 Wren 技能；问数注册 WrenMcpToolkit，系统上下文使用 WrenQueryContextMiddleware，仅列出授权发布项目。下述非主 data-agent 的平台能力继续保留。

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
   - middleware: 主 data-agent 使用 WrenQueryContextMiddleware 注入本轮授权发布工程目录；
     modeling-agent 使用 WrenProjectContextMiddleware 注入单库工程身份与资料位置。
     DataDynamicContextMiddleware 仅为非主问数兼容装配保留，不能恢复平台语义路由。
   - stateStore: AgentStateConfig#durableAgentStateStore复用框架MysqlAgentStateStore，使用平台库；H2开发使用JsonFileAgentStateStore，自定义Bean优先（ADR0084）
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

- `DataAgentToolkit` — 仅提供服务端图表工具（`render_chart`）；`renderChart`从框架RuntimeContext取得当前请求用户写入ChartOptionEntity#ownerId，缺身份拒绝生成。ChartController#get按chartId/ownerId查找并精确复核字符串，其他用户（含管理员/共享库创建者）及旧NULL归属记录返回404，成功响应private/no-store；不扩大到知识库共享范围。已有MySQL表升级见docs/migrations/0083-chart-owner-mysql.sql，不回填历史归属（ADR0083）；
- `WrenMcpToolkit` — 官方 MCP schema 发现与原始调用透传（`wren_list_tools` / `wren_call_tool`），
  依赖 `WrenQueryGateway`（`WrenInstanceRegistry` 的接口）与当前发布工程；
- `RunPythonTool(new SandboxBackedFilesystem())` — 沙箱 Python 执行工具。
  此处用独立代理实例即可：每轮调用的真实沙箱由 SandboxLifecycleMiddleware 绑定在
  RuntimeContext 上，工具调用拿到的就是同一个上下文。
- `ModelingToolkitRegistrar#registerOfficialTools` 向 modeling-agent 挂载 AgentScope 原生 FilesystemTool（list_files/read_file/write_file/edit_file/grep_files/glob_files）与 OfficialWrenToolkit 的单一 wren_cli 工具。旧 ModelingToolkit 不再注册，set/get_enrich_context_mode、关系/视图/问题专用工具与阶段剧本退出 Agent 运行时；遗留 HTTP 服务保留（ADR 0068/spec 057）。
- `WrenProjectAccess#resolve` 校验当前用户 MANAGE，从 DatasetScope/ConversationScopeRegistry 绑定单个草稿工程；`WrenProjectFilesystem` 只做路径和权限映射，委托 LocalFilesystem 执行文件操作。禁止穿越、宿主路径、符号链接和平台文件，Cube 按官方契约支持；业务文档同步到 raw/business-document.md，用户不能修改该映射文件。根目录搜索过滤平台目录，文件写入后标记草稿 DIRTY。
- `OfficialWrenToolkit#execute` 将参数数组传给真实官方 CLI，强制 cwd/project/MDL/memory 路径；不开放任意 shell、profile 切换、连接参数或发布命令。query/dry-run 及需要执行的 Cube query 通过 `MdlPublishService#draftCliProfile` 在工作区锁内再次校验当前操作者的 MANAGE 权限，再按知识库数据集绑定连接；创建者、共享管理员与全局管理员遵循同一权限规则，排队期间撤权则拒绝执行，操作者身份不替换为库创建者；ProcessWrenCli 的 WREN_HOME 指向平台维护的 profiles，避免宿主账号的 active profile。返回退出码与原始输出；校验/编译/SQL 计划和 memory 均没有平台第二实现。 官方 build 退出码同步现有 ModelingWorkflowStore 页面记录，validate 成功单独不表示已编译；失败原样显示。query/dry-run 的临时连接绑定在调用后恢复平台工程文件。memory 索引位于知识库 runtime/modeling-memory，源示例仍在 knowledge/sql，派生二进制不进入发布。
- `ModelingHitlMiddleware#onActing` 对 write_file/edit_file、wren_cli memory store 和会生成源码样例的 memory index 请求通用写确认；显式 --no-seed 的索引仅更新派生产物。预览检查路径、内容和精确替换，不运行平台业务语义规则。Agent 写入后按官方技能执行 validate/build/dry-plan/dry-run，技术失败进入原生循环修复；build 不代替显式发布。历史 patch_file/批量卡只由兼容 HTTP 预览处理。

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
`DataToolkitRegistrar#register` 注册 `WrenMcpToolkit`。Agent 加载官方 usage，通过 listTools 发现官方 schema，
再以原始 arguments 调用 get_instructions/get_context/describe_schema/recall_queries/run_sql/query_cube 等官方工具。
只有官方已提供的工具可调用；问数服务不使用 --allow-write，store_query 不对 Agent 开放。

`WrenQueryAccess#resolve` 检查 ownerId、DatasetScope 与当前会话范围并要求已发布版本。
实际可执行JAR中，后台线程的默认ContextClassLoader可能看不到嵌套依赖中的MCP JSON SPI。`WrenInstanceRegistry#createClient`在客户端创建/初始化范围内临时使用应用类加载器，finally恢复调用线程原值；不依赖主线程预热，不全局替换线程类加载器。实际JAR验证见spec075及20261019模拟BI报告。

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

ADR0085/spec074：`WrenInstanceRegistry#doCall`对官方MySQL兼容明确断连诊断进行一次实例重建和只读重试，
仅覆盖run_sql/dry_run/query_cube；不改原始参数、技能、MDL或发布版本。`WrenConnectionFailure#matches`
同时校验官方错误前缀、工具名、错误码和连接诊断，SQL/字段/账号权限错误正常返回，不因失败普遍重启。
同库门锁内重建、重试前及重新获取实例后复核授权；两次调用共享等待预算，初始化仍受原上限保护。
恢复失败后同库30秒冷却，工具发现也不能绕过冷却启动进程；成功立即解除，不主动探测、不无限重试。
传输失败仍失效特定实例；关闭失败保留预算待清理。正常请求无额外数据库往返，故障路径增加一次冷启动。


### 3.2 建模

上传/关联数据 → 基础模型发布 → 可选文档与问题 → 官方建模技能 → 原生草稿编辑 → 官方校验/构建/查询检查 → 显式发布。
`ModelingToolkitRegistrar#registerOfficialTools` 仅注册原生文件工具、受限 delete_file 和官方 wren_cli。
`WrenProjectAccess#resolve` 将 ownerId 与唯一知识库绑定到草稿工程，映射 raw/business-document.md。
普通/ref_sql 模型、关系、视图、Cube 和 knowledge 按同版本官方契约维护，不构建本体/KG，不修改官方 skill。
禁止越界路径、链接、宿主 shell、连接覆盖及平台配置写入。

`ModelingHitlMiddleware#onActing` 执行通用权限预检并使用原生 RequireUserConfirmEvent/ConfirmResult。
同批合法写操作一次展示并可一次授权；失败操作各自返回错误，不把一个文件的失败复制给其他调用。
write_file/edit_file/delete_file、memory store 和会生成源码样例的 memory index 需写权限；--no-seed 的派生索引重建不需人确认。
通用授权不是第二轮业务审阅，技术校验与修复由 Agent 调用官方工具；没有问题必填或必验条件。

### 3.3 发布与 MDL

`MdlPublishService#publish` 从工程 YAML 组装官方输入，执行官方 strict validate/build 与 view/ref_sql/Cube 查询检查，再复制快照并提升版本。
官方 target/mdl.json 是引擎事实；不调用 RefSqlMaterializer，不物理化 ref_sql，不禁止官方允许的资产依赖。
ref_sql 的内容和依赖是否可执行由官方引擎判断。任何检查失败保留旧发布快照，官方 build 不自动替换在线版本。
模型目录与 MDL 可视化属于只读投影，元数据旧实体与旧 HTTP 为兼容保留，不参与主 Agent 的语义决策。

---

## 4. 问数工具链

### 4.1 原始官方调用与通用产物

`WrenMcpToolkit#listTools` 返回原始官方工具 schema；`WrenMcpToolkit#callTool` 原样透传参数与官方输出。
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
SqlExampleDocument结构化解析frontmatter，仅更新nl/sql并做精确往返校验，同步正文SQL代码块，保留其他字段值/无关正文，不压平SQL空白。SqlQaExampleService#update使用工作区锁和临时文件原子替换；点赞编辑/删除另在现有发布组锁内完成，失败还原原始源文件，再由WrenPublishedProject#restoreIndex恢复投影与官方索引。WrenPublishedProject#indexed在重建前清除旧成功标记，恢复失败返回明确错误，后续成功准备前不视为生效。坏文件在列表标记INVALID、不可编辑且不覆盖；YAML排版/注释可能规范化，非逐字节保留头部。详见ADR0083/spec072。
SQL 原样保存，不二次添加 LIMIT。`WrenPublishedProject#prepare` 合并发布源码与隔离的点赞源码，
`WrenPublishedProject#indexed` 按源码摘要调用官方 memory index --no-seed，索引改变时不复用旧模型/示例内容。

建模推荐问答按官方技能成功验证后，由 `wren_cli memory store` 经原生写授权保存到草稿 `knowledge/sql`，页面显式发布后进入问数工程；问题列表和 schema 索引本身不代表存在问答。`WrenPublishedProject#mergeExamples` 保留已发布文件名，为点赞来源生成不冲突的派生文件名（默认 `liked-` 前缀），原始 Markdown 内容不变；只同步派生目录，避免两类中文问题都生成 `query.md` 时覆盖。每次部署修正派生文件并清除不再由来源提供的副本，索引仍由官方实现负责。

`AnswerQueryMemory#candidates` 优先匹配调用 ID；历史回合缺 ID 时按业务问题、工具类型与实际 SQL 核对所有候选，不因同题的首条 SQL 不同而漏存。有调用但内容不匹配、诊断、失败及其他用户知识库回执均排除；完全缺失调用输入时才采用服务端成功回执。显式再次点赞可重试此前空候选，已保存状态仍保持幂等。

问数工具展示由 `ToolCallBlock`/`ToolInspector` 解析外层调用的 `tool_name` 显示中文操作名；`WrenToolResult` 展示官方 `nl_query/sql_query` 问答卡、查询行表格及模型/Cube结构，并保留空值、截断提示、SQL回执和产物说明。原始响应可展开复制，JSON字符串只按 JSON 语义解码，不修改 SQL 转义；SSE 与存储原文不变。参见 spec 063。
`ToolInspector` 右侧详情按 spec 071 使用“结果/输入/原始内容”页签，支持放大、窄屏覆盖和键盘切换；SQL表格只展开已返回行，Python文件按调用分组，失败时保留部分文件警告，原生图表复用ECharts。展示解析不触发重跑或改变权限；无回执显示“结果未确认”，不补造耗时、版本及调用导航。聊天内工具块和建模页面保持原实现。
线上 recall_queries/get_context 使用该同一工程的官方 memory；无平台评分、中文词项算法或可信等级扩展。
CLI/依赖/多语言模型不可用时明确返回错误；普通 SQL 查询仍可执行。

Wren耗时诊断（spec076）：`WrenTiming`只记录固定阶段、随机trace、库ID及毫秒，不增加问题/SQL内容。`ProcessWrenCli#run`拆分准入、启动、进程执行和输出读取；`WrenPublishedProject#indexed`拆分锁等待、工程准备、指纹、重建及后端检查；`AnswerQueryMemory#feedback`拆分保存和发布索引。建模工具补工作区锁等待，MCP实例注册器补门锁等待、实例获取和RPC。发布包`bin/profile-wren.py`按需使用单调时钟跟踪官方Python调用边界，不修改安装文件、不挂到默认请求；深度跟踪有开销且嵌套耗时不可直接相加。见[专项报告](docs/test-reports/20261019-wren-phase-timing.md)。这些计时不改变授权、取消、索引一致性或保存完成语义。

### 4.3 保留的数据分析能力

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

`DatasetSampleDocuments#sync` 生成官方目录内 raw/data-samples.md 与 knowledge/rules/data-sampling.md；平台文件禁止原生或兼容写入口覆盖，不修改安装版 skills 或 target/mdl.json。`MdlSeeder#reconcile` 在发布前同步资料；`WrenProjectAccess#resolve` 在当前授权库的工作区锁内同步。列 description 保存简短样例提示，业务含义充分确认后才由官方建模流程写 [enum]，样本不证明完整枚举或关系唯一性。

`WrenProjectContextMiddleware#onSystemPrompt` 每次构造上下文时提供通用约束及最多6000字符摘要，完整资料按需读取；不会用“首次已发送”进程标记丢失恢复会话上下文。`WrenQueryContextMiddleware#onSystemPrompt` 仅注入通用约束，官方 get_instructions 读取已发布的采样规则和限长摘要（24000字符）；不注入草稿资料。提示约束不能保证模型必然遵循，也不是数据库端成本硬限制。必要的准确聚合和业务验证仍通过官方 Wren 运行。

### 5.4 历史关系服务与兼容台账（ADR 0046）

`DatasetRelationEntity`、`MdlSuggestionService` 和旧关系审阅 HTTP 接口保留兼容源码，
不再是当前 Agent 的关系生成通道。当前关系以工程 `relationships.yml` 为事实源，由官方技能
指导原生文件工具，经通用 HITL 写入草稿；发布后才用于问数。旧推断/确认策略见 ADR 0044、0046
及 Git 历史，不把旧 `decide_relation` 等领域工具恢复到主 Agent。

### 5.5 历史术语与口径台账（specs/011，ADR 0027）

`SemanticTermController` 和组级术语接口保留业务术语维护能力；历史 DB 规则、视图及提案台账
不重建当前线上语义。问数使用发布工程的官方 knowledge；建模读取草稿资料并按官方技能维护
`knowledge/`。修改草稿规则、模型、视图或 Cube 都不能代替显式发布。旧即时注入策略仅见历史 ADR。

### 5.6 数据集组 → WrenAI 语义层发布流（M1/M2/M3 已实施）

当前行为依据 ADR 0069、0074、0077；本节替代已退役的领域工具、MDL 物化和逐题发布门禁说明。
一个知识库对应一个 Wren project：

```
上传 / 关联 / 删除数据集 → BaselineMdlService#publishAfterDatasetChange
页面显式发布           → MdlPublishService#publish
  → 每库发布锁 → 工作区锁 → MdlSeeder#reconcile
  → 单来源连接选择 / 外部表连通性预检 / profiles 重建
  → 工程读取与结构检查 → staging → 官方 validate / build
  → SQL 对象实际查询检查 / Cube SQL 转换检查
  → prepared 快照 → 替换 published 与平台清单 → 更新版本与发布状态
```

- `MdlPublishService#doPublishLocked` 不以可选问题的逐题确认作为门禁；技术检查失败仍阻止发布。
- 官方编译 `target/mdl.json` 原样保留，不使用旧 `RefSqlMaterializer` 改写为平台语义。
  ref_sql、视图和 Cube 服从安装版官方契约；编译成功仍不证明指标口径正确。
- 自动基础发布与显式发布复用同一发布实现。上传新增/删除表会对账工程并触发基础发布，
  不能据此承诺原有复杂语义在数据结构变化后自动正确。修改资料及建模文件后应重新检查、显式发布。
- 发布锁按库串行；自动请求的 `requestTick` 合并仅跳过已被后续成功快照覆盖的变更，失败不记覆盖。
  这是单 JVM 保证，不是分布式发布协调。锁顺序保持发布锁再工作区锁。
- 发布选择全上传默认连接或同一外部 MySQL 兼容连接，以 `wren-source.properties` 钉入快照。
  混合来源、多个外部连接或已删除连接被拒绝；LIMIT/OFFSET 按 ADR 0072 的版本限定兼容支持。
- 首次失败进入 FAILED；已有快照的失败保留旧版本并标记 DIRTY；空库回到 NONE。
  快照保留不保证被删除/变更的物理源仍可查询。Windows 快照目录切换仍有已记录的偶发失败。
- 发布成功失效已有 Wren 实例，下次请求按发布工程准备 MCP 与索引。ADR0085对明确MySQL兼容断连增加一次重建/只读重试及失败冷却；未知诊断保守返回，不将该恢复范围写成所有故障均自动恢复。

### 5.7 文档驱动语义增强（specs/014，ADR 0028）

当前文档入口为 `DatasetGroupController#uploadKnowledge` → `DatasetService#saveKnowledge`。
上传替换当前业务资料并标记草稿变化，不自动启动 `DocEnhanceService` 的旧分析任务。助手读取
当前工程 `raw/business-document.md` 和采样资料，经官方技能解释业务含义、原生文件 HITL 写入
工程；用户也可直接在建模对话提供规则。没有文档/预设问题仍可建模，坏数据和口径冲突应明确说明。

旧文档提案的分析/采纳接口及实体作为兼容源码保留，历史设计见 ADR 0028、0033、0068；
不将旧 `MODELING_SCRIPT` 阶段工具、提案双写或规则保存即影响线上问数作为当前流程。

### 历史问题确认与兼容接口

`MdlQuestionStore` 和可选问题验证/确认接口保留旧记录及凭据读取能力；问题文件、工程哈希、
验证快照与历史审阅不能被当前数据冒充。旧强制 MODEL/VIEW/CUBE/EXAMPLE 声明及逐题确认
发布门禁已被 ADR 0069 取代。当前问题可选，发布以官方工程检查为准；人员仍须核对业务答案。
历史流程见 ADR 0054—0056，不在此重复维护另一份发布规则。

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

- 对话上下文：`AgentStateConfig#durableAgentStateStore`默认提供框架MySQL实现，保存到平台DataSource当前库的`dataagent_agent_state`；每用户/会话的`agent_state`为最新JSON快照，正常每轮读取、结束保存，版本校验复用框架。联合主键`session_id/state_key/item_index`，身份列使用binary排序规则。建模 HITL 的
  `replyId` 和 `ToolCallState.ASKING` 同样保存在该状态中，刷新后由 session 探测接口恢复卡片。
- `ddl-auto=update/create/create-drop`初始化表而不删除旧状态；生产validate/none须先执行[0084迁移](docs/migrations/0084-agent-state-mysql.sql)，缺表/列或非binary身份列明确启动失败，不回落内存。H2开发用框架JSON文件，路径为`${user.home}/.agentscope-dataagent/state/h2-<数据库URL哈希>/`；Windows文件名大小写遵循底层文件系统，平台身份仍使用既有稳定内部ID，不改为用户名。
- `ReActAgent#activateSlotForContext/saveStateToSession`按本轮userId/sessionId读写，网关启动只恢复路由，登录/打开页面不加载所有会话。当前权限和选库继续经原链复核。只恢复已保存的状态，不自动从展示历史重建已丢失状态，也不承诺续跑重启前未完成工具。
- `SessionAgentManager#resetSession/removeSession`在移除旧路由前删除该用户旧sessionId的全部状态键，删除失败保留登记供重试；自动维护同路。展示历史依旧独立保存；长快照整体读取/更新仍有成本，快照表需纳入数据库备份，详见spec073/ADR0084。
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

### 7.4 长时记忆与 Wren 向量记忆的区别

当前有三类不同状态，不把其中一类的持久化当作另一类已完成：

| 状态 | 当前载体与作用 | 边界 |
|---|---|---|
| 模型对话状态 | AgentStateStore 的消息、工具和待确认状态 | 默认平台MySQL最新快照，H2开发文件存储；已丢失旧状态不能靠展示历史自动恢复，见7.2及ADR0084 |
| Harness工作区记忆 | MEMORY.md、memory日账本、合并进度；框架flush/maintenance中间件 | 问数按用户沙箱，容器回收会丢可写层；建模本地文件命名空间按库，但DataAgentConfig装配时disableWorkspaceContext，不自动注入旧MEMORY.md |
| Wren业务查询记忆 | 工程knowledge/sql的NL-SQL源码及ONNX/LanceDB衍生索引 | 建模样例需发布；点赞样例在运行资料中合并。按库召回，技术查询成功不证明口径正确，见4.2 |

框架记忆由通用Harness管线提供，不另建平台记忆系统；同用户的聊天历史/偏好不能扩大本轮
数据查询权限。ONNX模型、索引准备、热召回、SQL执行和LLM耗时分别观察；发布或样例变化可导致
索引重新准备，空闲实例回收后也可能重新加载模型。框架能力和历史调研不替代当前装配与重启实测。

---

## 8. 共享层

首发业务主链使用内置问数与建模助手，官方Wren技能按安装版挂载；子Agent默认关闭。
Agent目录仍保留用户创建/克隆的兼容API与管理能力，见docs/agent-definition.md，不能将其写成
所有自定义Agent均已具备主助手的固定工具与权限装配。`AgentSkillsController`提供受权限约束的
技能维护；共享内容投影到用户沙箱，具体启动与覆盖规则如下。

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

- **例行任务首发边界（ADR0082/spec070）**：用户10.17确认只保留占位入口。ScheduledTasksPage不请求旧任务数据，SecurityConfig在/api/**规则前拒绝全部/api/scheduled-tasks及子路径，所有角色均不能读取/修改旧任务。保留旧表和记录，未实现owner与调度投递，后续开放须重新设计并验收。
- **认证（ADR0081/spec069）**：`AuthSettings`在prod强制关闭密码登录、启用BI免登，Java演示种子关闭；SQL演示初始化在prod配置设never。`AuthController#biLogin`接受前端共享密钥AES-GCM ticket，`BiTicketCodec#decode`校验协议及60秒/未来30秒时间窗；`BiLoginService#login`按用户名复用或自动创建普通用户，通过JPA消费记录唯一插入防重放，用户创建与消费独立事务，失败须使用新ticket。前端持钥可伪造任意用户名（含管理员）的限制由用户明确接受，不能描述为可信BI身份验证。管理员仍按本地roles_csv；不接BI后端、不同步退出。
- **公开入口**：`SecurityConfig`（WebFlux Security）：GET `/api/auth/options`、POST `/api/auth/bi-login`与`/api/auth/login`、actuator health/info、`/api/webhook/**`公开到控制器；关闭的入口由控制器拒绝，认证响应no-store、iframe限同源；
  其余 `/api/**` 需 JWT（`JwtAuthFilter` 校验 Bearer 或 `?token=` 查询参数，后者供 `<img src>` 使用）。CORS 放开（含 Vite dev 5173）。
- **前端会话**：`AuthBootstrap`先获取入口配置，失败可重试且不显示密码框；`SessionGate`挂载业务页面前通过`auth.me`校验sessionStorage令牌，旧localStorage凭据不迁移。`auth.apiFetch`处理同源当前凭据401，403/网络故障不退出。JWT默认1800秒固定到期，刷新不续期、已有SSE不定时截断。生产失效页提示从BI重新进入，dev保留密码页。`BiEntryPage`仅在显式/bi-entry接受同源父窗口握手，校验JWT后整页重载，避免切换用户残留组件与模块缓存；凭据不进URL，关闭页面不撤销JWT。部署前缀由Vite BASE_URL统一至Router/API；见[BI对接](docs/integration/bi-login.md)。
- **授权**：`web/share/AgentAclService` + `AgentAccessGuard`：每个 agent 按 Tier（RUN/EDIT/…）鉴权，
  Controller 入口统一 `guard.require(userId, agentId, Tier.X)`。
- **agent 目录**：`web/catalog/AgentCatalogService` 合并全局与用户可见自定义定义，复用 Agent ACL；当前仍有创建/克隆兼容 API，详见 docs/agent-definition.md。主建模/问数的固定工具装配不自动套用于所有自定义 Agent。
- **审计**：`web/audit/AgentActivityStore`（RUN_SESSION 等事件，`AgentActivityController` 查询）。
- **用量**：`web/usage/UsageStore`（每 turn 记录 userId/agentId/耗时，admin 端 `/api/admin/usage/...` 聚合）。
- **AI 草稿**：`web/ai/AgentDraftService`（聊天式生成 agent 定义，`/api/agents/draft`），语义建模页 AI 建议（`dataset/MdlSuggestionService`，经 `chatBlockingModeling` 转录进 `logs/LLM-modeling.log`）也复用它；直调模型时以与 DebugLoggingMiddleware 相同的 role/type/text 结构把输入输出转录进对应文件（见 2.3）。

---

## 11. 前端结构

React 18 + TypeScript + Vite SPA，构建到 `classpath:/static/`。`AppShell` 提供全局导航；
`SessionsSidebar` 按原型组织品牌、导航、实际历史及账号区；`ShellNavigationContext` 与
`ShellNavigationToggle` 在窄屏打开单份侧栏，支持关闭、Escape、焦点循环及正文 inert。
`KnowledgeBreadcrumb` 统一顶部路径栏；独立语义建模路由隐藏会话侧栏和展开按钮。
业务 HTTP 调用经 `apiFetch` 适配部署前缀及认证；
SSE 由 `api/chat.ts` 消费，`done.sessionKey` 是 conversationId。

- 普通问答：`ChatPage` / `ChatPanel`，本轮零/单/多/全选；`ToolCallBlock` 展示轨迹，
  点开由 `ToolInspector` 展示当前调用；`EChartsBlock` / `PythonArtifactsPanel` / `Markdown`
  展示图表与附件，`AnswerFeedback` 只在用户点赞后保存成功业务查询。
- 知识库：`DatasetsPage`、`DatasetGroupPage`、`DatasetDetailPage` 提供列表、上传/关联、
  数据预览及成员授权；QUERY 与 MANAGE 的管理入口不同，后端仍复核。
- 数据源：`DatasetsPage` 进入独立 `/configure/datasources` 的 `DataSourcesPage`，
  按既有原型展示连接列表和实际检测结果；`DataSourceFormModal` 保留检测后保存、加密、采样
  与编辑时密码留空保留，`DataSourceDetailModal` 提供连接概况及按需库表字段浏览。
  `DataSourceDialog` 使用原生模态框管理键盘焦点；窄屏由全局导航抽屉提供入口，也可通过面包屑返回知识库。
- 业务术语：`SemanticConfigPage` 保留可管理知识库范围和组级 API，提供选择、搜索、表格、
  分页及轻量编辑弹窗；切库取消旧读取，失败保留草稿，术语不会自动改写已发布工程。
- 更多：`MorePage` 在 `/more` 提供既有 `/workspace` 文件浏览入口，工作区面包屑经“更多”返回。
- 建模：`SemanticModelingPage` 分资料与可选问题、对话建模、检查与发布、语义资产。
  `ModelingChatPanel` / `ModelingHitlCard` 复用原生确认及实际文件预览，不把确认写入当作业务验收。
  `MdlPublishPanel` 提供工程检查与显式发布；`ModelSnapshotPanel` / `MdlGraphView` 浏览草稿及发布结构。
- 当前资产包括普通/ref_sql 模型、关系、视图、Cube、knowledge、MDL 与工程快照；
  `SqlQaExamplesPanel` 管理建模和点赞两种来源，来源生命周期见第4.2节及ADR0075/0076。
- `ModelingChatPanel#restorePendingProposal` 只恢复已持久化待确认卡；回读不执行或批准工具。
  拒绝反馈经原生 ConfirmResult 进入匹配会话，不创建第二套确认状态机。
- 例行任务按原型提供页面和表头，但按 ADR0082/spec070 仅占位，不调用任务 API。
  本轮局部 UI 范围见 spec078/079；知识库卡片主体、聊天和建模主体、其他管理页沿用现有实现。
  开发密码与生产 BI 入口行为见第10章。

旧四阶段引导、问题必填/逐题确认、自动文档增强卡、Cube 隐藏等已被当前流程取代；
历史演进查 ADR0050—0063。当前依据为 ADR0069、0075—0078，不复制旧 UI 流程作为现状。

---

## 12. 配置与启动

> Standalone 构建使用根目录 mvn test / mvn package -DskipTests，前端在 frontend/ 运行 npm run build。requirements-wren.txt 固定 wrenai 0.15.0/core 0.8.0 及 memory-onnx；服务实际 CLI 环境必须安装相同依赖。多语言模型首次下载需要网络，可用官方缓存，不自研词项降级。

### 12.1 关键配置（`application.yml`，`dataagent.*` 前缀 / `DATAAGENT_*` 环境变量）

认证新增`dataagent.jwt.ttl-seconds`（默认1800）、`dataagent.auth.password-login-enabled`（默认true，prod强制false）、`dataagent.auth.bi.enabled`（默认false，prod强制true）、`dataagent.auth.bi.shared-key`（启用时必填32字节Base64，与JWT密钥独立）。环境变量为DATAAGENT_JWT_TTL_SECONDS、DATAAGENT_PASSWORD_LOGIN_ENABLED、DATAAGENT_BI_LOGIN_ENABLED、DATAAGENT_BI_SHARED_KEY。私有dev/prod文件Git忽略且不进入发布包。同域/dataagent/构建使用DATAAGENT_BASE_PATH，配置反向代理剥离前缀、关闭流式缓冲。新增消费表及管理员SQL见docs/migrations/0081-bi-login-mysql.sql；ddl-auto=validate须先迁移。对接示例不携带正式密钥。

平台增量带入 `dataagent.seed-demo-users`（默认 false，显式启用 Java 本地管理员初始化，不替代 SQL 初始化开关）和 `dataagent.cors.allowed-origins`（默认 `*`，部署时指定允许来源）。JWT 密钥拒绝空值、开发占位值及不足 32 UTF-8 字节的短密钥。本轮不覆盖现有 application.yml 或部署 profile。

| 配置 | 默认 | 说明 |
|---|---|---|
| `dataagent.jwt.secret` | dev 占位 | 生产必须覆盖（≥32 字符） |
| `dataagent.workspace` | JVM cwd | 运行态工作目录 |
| AgentStateStore（自动装配） | 平台MySQL，H2开发JSON文件 | AgentStateConfig#durableAgentStateStore共用spring.datasource和既有DDL策略；生产先0084迁移，自定义Bean优先；旧Redis块不自动切换存储 |
| `dataagent.agent.name` | `data-agent` | 自动生成 `agentscope.json` 时的 agent 名；系统提示词默认用内置常量，覆盖用 `DATAAGENT_AGENT_SYS_PROMPT`（见 4.5） |
| `dataagent.agent.subagents-enabled` | false | 子代理编排（harness `agent_spawn` / `agent_send` / `task_*`）。关闭可省下每轮固定约 6.2k 字符的 `## Subagents` 提示词段与相应工具 schema；**不影响网关、会话路由与聊天**（`buildSubagentEntries` 不读该开关），内置定义也不删（ADR 0009） |
| `dataagent.openai.*` | DeepSeek 默认值 | 主模型（任意 OpenAI 兼容端点） |
| `dataagent.openai.stream` | `true` | `DATAAGENT_OPENAI_STREAM` 可切换模型流式/非流式请求以定位上游 EOF；前端 SSE 协议不变，关闭流式不表示连接问题已解决 |
| `dataagent.dashscope.*` | — | 兜底模型 |
| `dataagent.sandbox.image` | `agentscope/dataagent-sandbox:latest` | 沙箱镜像（`docker/sandbox.Dockerfile` 构建） |
| `dataagent.sandbox.max-containers` | `16` | 单进程容器上限，满额驱逐空闲实例；环境变量 DATAAGENT_SANDBOX_MAX_CONTAINERS |
| `dataagent.upload.max-file-size` / `max-selection-size` / `max-files` | `50MB` / `100MB` / `10` | 上传单文件、整批及数量上限 |
| `dataagent.upload.max-rows` / `max-columns` / `max-cells` | `500000` / `100` / `10000000` | application.yml 解析上限；外置配置可调整，不代表容量验收结论 |
| `dataagent.upload.concurrent-files` | `8` | 单进程在途文件准入，允许 1—32 |
| `dataagent.chat.max-active` / `max-active-per-user` | `16` / `2` | 问数全局/每用户执行数 |
| `dataagent.chat.max-waiting` / `max-waiting-per-user` | `16` / `1` | 全局/每用户等待数 |
| `dataagent.chat.wait-seconds` / `execution-seconds` | `30` / `600` | 等待/执行超时 |
| `dataagent.wren.max-instances` / `call-wait-seconds` | `16` / `30` | MCP 实例数与每库门锁等待秒数 |
| `dataagent.openai.http-version` | `HTTP_1_1` | 默认模型协议，环境变量 DATAAGENT_OPENAI_HTTP_VERSION |
| `dataagent.dataset.datasource.*` | 本地 MySQL `data_agent` 库 | **上传数据集物理表所在库** |
| `dataagent.wren.*` | `wren` CLI / `~/.agentscope/dataagent/mdl` / `mysql` / 180s / 1800s | WrenAI CLI 与 MDL 产物根（拼装/validate/build/发布快照，specs/010 M2，见 5.6）；`profile`/`instance-idle-seconds` 供运行期 `serve mcp` 实例池用（M3，见 4.6/ADR 0020）。M4 起 `profile` 是默认回落值——外部数据源组的实际连接由快照内 `wren-source.properties` 钉入（`ext-<datasourceId>`，发布期解析，见 4.6/ADR 0021）。`executable` 默认值按平台探测：Linux 先查 `~/.local/bin`/`/usr/local/bin`/`/usr/bin` 再回落 PATH（`pip install` 后零配置），Windows 需显式指向 venv `wren.exe`；`skills-dir` 指向 wrenai 包内官方 skills 根（specs/022、ADR 0035，空值从 executable 位置推断，失效仅 warn 降级 CLI 通道）；CLI 与 serve mcp 两处 spawn 失败统一回跨平台安装引导（`WrenProperties#resolveExecutable`/`unavailableMessage`），集群部署前置见 `docs/cluster-deploy.md` |
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

根 pom.xml 的编译基线为 Java 17，本项目不依赖旧多模块 `-pl agentscope-examples/...` 路径。框架改动后先安装配套 harness/OpenAI 扩展，再构建本项目；默认 Maven 流程会构建前端，已有前端构建时可使用 skip.npm/skip.installnodenpm 参数跳过。保留原 Boot exec JAR，同时按 ADR0080 生成 dataagent-<version>.tar.gz，包含 bin/conf/lib。公共默认配置及 h2 profile 保留；私有 dev/prod 的 YAML/properties 同时由 Maven resources 和 jar 插件排除，分发仅带无凭据样例。dataagent.sh 通过共享启停锁、PID/启动时间/JAR 身份管理单实例，启动前用 check-wren.py 校验固定 Wren 依赖，再等待 HTTP 健康；停止不依赖 Wren。数据位于版本父目录 data/，日志位于当前版本 logs/。发布默认重建前端，可选 frontend/build.ps1 只是提前构建包装。步骤及脚本环境变量见 [单机发布](docs/single-host-release.md)。部署步骤见 [并发与发布说明](docs/platform-concurrency-and-release.md)。

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

> ADR 0069 新入口：WrenQueryAccess（权限选择）、WrenPublishedProject（发布部署/官方索引）、WrenQueryContextMiddleware（项目目录）、WrenMcpToolkit（原始 MCP 透传）、WrenInstanceRegistry#listTools（原生发现）。验收 fixtures 位于 docs/fixtures/wren-native/，30 个固定 SQL/标准结果用于执行对照，不能等同真实 LLM 准确率。

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


## 2026-10-08 补充：会话历史、附件与沙箱生命周期

当前实现以 [ADR 0060](docs/adr/0060-durable-session-history-and-artifact-lifecycle.md) 和 [spec 052](docs/specs/052-durable-session-history-and-artifacts.md) 为准；涉及旧章节中的历史读沙箱、查询 CSV 立即上传和镜像级孤儿清理描述时，由本节替代。

- 展示：数据库 `session_history/session_message` 提供列表与消息分页；索引器从本地引擎日志补齐，列表不访问 Docker。
- 续聊：引擎日志及工作区持久状态继续保留，数据库展示投影不替代引擎上下文。
- 附件：`session_artifact` 元数据加持久目录，所有者通过请求头认证读取；沙箱回收不影响已保存文件，删除或重置会话会清理附件。
- 执行：按用户和 agent 懒创建，活动租约保护回收；每次 Python 独立运行目录，最近输入有限恢复；容器资源和数量受限。
- 维护：容量保护、分段锁和分批孤立文件补偿已实施；正常历史保留任务见 ADR 0061，默认关闭并先试运行；多实例协调和对象存储尚未实施。

部署限制、完整参数、备份范围和验证边界见 [会话与附件部署说明](docs/session-history-and-artifacts.md)。

### 模拟BI联调入口（spec075 / ADR0086）

`docs/integration/bi-simulator*`是独立静态工作台和iframe宿主，复用正式`bi-login.mjs`及真实prod后端，不新增模拟认证接口或产品菜单。本地Node仅提供loopback静态服务和SSE流式代理；云端用Nginx同源子路径。未知用户仍自动创建普通用户，角色由平台库决定；每标签页保存身份，刷新复用固定到期JWT，切账号显式重新免登。私有配置外置；此入口不作为可信SSO的新机制，既有前端持钥边界不变。见[运行与部署说明](docs/integration/bi-simulator.md)。
