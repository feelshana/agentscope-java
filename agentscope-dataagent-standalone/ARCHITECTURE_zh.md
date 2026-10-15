
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

**多租户模型**：所有数据资产按 `ownerId` 隔离；数据集组织为「知识库（DatasetGroup）→ 数据集（Dataset）→ 物理表」三级；
运行时通过 `DatasetScope(ownerId, groupIds?)` 把租户与知识库过滤注入每个 agent turn。

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
   - middleware: DataDynamicContextMiddleware（每轮重建 [DATA_SOURCES_OVERVIEW]
     与 [KNOWLEDGE_BASE_OVERVIEW] 追加到 system prompt；数据源概览只渲染可查询的
     已发布逻辑模型/视图 轻量目录（不含物理名称），字段与关系详情由
     官方 describe_model 按需获取，见 4.6）
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
- `WrenProjectAccess#resolve` 从鉴权用户和 DatasetScope/ConversationScopeRegistry 绑定单个草稿工程；`WrenProjectFilesystem` 仅做路径和权限映射，委托 LocalFilesystem 执行原生文件操作。禁止穿越、宿主路径、符号链接、平台文件及 Cube；业务文档同步到 raw/business-document.md，用户不能修改该映射文件。根目录搜索过滤平台目录，文件写入后标记草稿 DIRTY。
- `OfficialWrenToolkit#execute` 将参数数组传给真实官方 CLI，强制 cwd/project/MDL/memory 路径；不开放任意 shell、profile 切换、连接参数或发布命令。query/dry-run 通过 `MdlPublishService#draftCliProfile` 绑定当前知识库连接；ProcessWrenCli 的 WREN_HOME 指向平台维护的 profiles，避免宿主账号的 active profile。返回退出码与原始输出；校验/编译/SQL 计划和 memory 均没有平台第二实现。 官方 build 退出码同步现有 ModelingWorkflowStore 页面记录，validate 成功单独不表示已编译；失败原样显示。query/dry-run 的临时连接绑定在调用后恢复平台工程文件。memory 索引位于知识库 runtime/modeling-memory，源示例仍在 knowledge/sql，派生二进制不进入发布。
- `ModelingHitlMiddleware#onActing` 对 write_file/edit_file 与 wren_cli memory store 请求原生权限确认；通用预览只检查路径、内容和精确替换，不预先运行 YAML 语义规则或 scratch 三重闸。Agent 写入后按官方技能执行 context validate/build/dry-plan/dry-run，技术失败进入原生循环修复。context build 和 memory index 不请求写权限卡；memory index 强制 --no-seed，避免未授权生成示例。历史 patch_file/批量卡继续由遗留 HTTP 预览处理。

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
SQL 原样保存，不二次添加 LIMIT。`WrenPublishedProject#prepare` 合并发布源码与隔离的点赞源码，
`WrenPublishedProject#indexed` 按源码摘要调用官方 memory index --no-seed，索引改变时不复用旧模型/示例内容。

建模推荐问答按官方技能成功验证后，由 `wren_cli memory store` 经原生写授权保存到草稿 `knowledge/sql`，页面显式发布后进入问数工程；问题列表和 schema 索引本身不代表存在问答。`WrenPublishedProject#mergeExamples` 保留已发布文件名，为点赞来源生成不冲突的派生文件名（默认 `liked-` 前缀），原始 Markdown 内容不变；只同步派生目录，避免两类中文问题都生成 `query.md` 时覆盖。每次部署修正派生文件并清除不再由来源提供的副本，索引仍由官方实现负责。

`AnswerQueryMemory#candidates` 优先匹配调用 ID；历史回合缺 ID 时按业务问题、工具类型与实际 SQL 核对所有候选，不因同题的首条 SQL 不同而漏存。有调用但内容不匹配、诊断、失败及其他用户知识库回执均排除；完全缺失调用输入时才采用服务端成功回执。显式再次点赞可重试此前空候选，已保存状态仍保持幂等。

问数工具展示由 `ToolCallBlock`/`ToolInspector` 解析外层调用的 `tool_name` 显示中文操作名；`WrenToolResult` 展示官方 `nl_query/sql_query` 问答卡、查询行表格及模型/Cube结构，并保留空值、截断提示、SQL回执和产物说明。原始响应可展开复制，JSON字符串只按 JSON 语义解码，不修改 SQL 转义；SSE 与存储原文不变。参见 spec 063。
线上 recall_queries/get_context 使用该同一工程的官方 memory；无平台评分、中文词项算法或可信等级扩展。
CLI/依赖/多语言模型不可用时明确返回错误；普通 SQL 查询仍可执行。

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
spawn 读取（缺失回落默认，M3 存量组零迁移）、`官方 run_sql` 入口 LIMIT/OFFSET 硬门
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

**对话式建模入口（ADR 0068/spec 057）**：modeling-agent 是通用 AgentScope 执行器。DataAgentConfig#MODELING_SCRIPT 只声明官方技能、当前工程、中文、权限和发布边界；WrenProjectContextMiddleware#onSystemPrompt 仅注入项目身份和资料位置，不注入平台建模状态/阶段。WrenSkillsLocator 挂载安装版官方技能，技能不可用时经 wren_cli skills list/get 读取官方内容。文件预览由 SemanticModelingController#workspacePreview 分派原生 write_file/edit_file 与历史 patch_file/批量卡；确认卡展示实际前后内容与 diff，不宣称写入前已完成官方语义检查。ChatController#confirmModeling 复用原生 ConfirmResult 恢复工具执行。模型、关系、视图和 SQL 定义模型的内容由官方技能指导生成，平台不强制资产推荐路由；Cube 仍禁用。最终发布是页面显式动作，草稿 build 不更新线上版本。过程任务行和 SSE 协议保留，新增原生 edit_file 内容展示。

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

### 问题、确认凭据与发布门

`MdlQuestionStore` 以工作区问题文件为事实源；执行凭据存放在平台拥有的 `.platform/questions/`，绑定问题哈希、语义文件哈希和验证 ID。模型、规则或问题修改后状态为 STALE，需要重新执行和确认。确认凭据不由模型提交，过期凭据、失败执行、截断结果不可确认。

spec 046 / ADR 0056：新写入的问题必须声明 `modeling.strategy`（MODEL/VIEW/CUBE/EXAMPLE）、业务理由及逻辑资产引用。`ModelingToolkit#validateProposal` 校验声明，`MdlQuestionStore#coverage` 校验资产存在与 SQL 引用，`MdlPublishService#validateQuestion`、人员确认与发布均检查覆盖状态；这不替代 Wren 执行和人员口径确认。历史问题缺少声明时按历史 SQL 示例兼容，新增与修改声明纳入问题指纹。问题 Review 的 published 状态从同知识库 published 快照推导，区分已发布可问数与未发布草稿。前端使用编号列表和选中详情；第三步展示发布条件、阻塞事项及第四步发布入口。写入卡复用 Prism 展示完整内容和放大阅读，编辑内容可一次预检并提交原生 HITL，前端同步锁防重复提交。

`MdlPublishService#doPublish` 在工作区锁内检查所有未归档问题是否 CONFIRMED，失败保留上一份可用 published 快照。未配置问题的现有知识库继续兼容原发布流程。已确认的问题生成 Wren 官方 `knowledge/sql/*.md` 格式（frontmatter 的 `nl/sql/source`），发布副本只保留当前有效的确认示例，历史手写且未确认的 SQL 示例需要迁移为问题后重新验收。

文件写入、确认和发布共用每组工作区锁；发布与草稿执行额外使用既有发布互斥锁，顺序为发布锁再工作区锁。知识库 ownerId 和 DatasetScope 在服务层校验，HTTP 接口还经 AgentAccessGuard；阻塞执行位于 boundedElastic。当前凭据绑定语义版本，不代表数据永久不变，日后数据更新仍通过当前 Wren 查询获取结果。

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

### 引导式建模工作台

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
| `dataagent.dataset.datasource.*` | 本地 MySQL `data_agent` 库 | **上传数据集物理表所在库** |
| `dataagent.wren.*` | `wren` CLI / `~/.agentscope/dataagent/mdl` / `mysql` / 180s / 1800s | WrenAI CLI 与 MDL 产物根（拼装/validate/build/发布快照，specs/010 M2，见 5.6）；`profile`/`instance-idle-seconds` 供运行期 `serve mcp` 实例池用（M3，见 4.6/ADR 0020）。M4 起 `profile` 是默认回落值——外部数据源组的实际连接由快照内 `wren-source.properties` 钉入（`ext-<datasourceId>`，发布期解析，见 4.6/ADR 0021）。`executable` 默认值按平台探测：Linux 先查 `~/.local/bin`/`/usr/local/bin`/`/usr/bin` 再回落 PATH（`pip install` 后零配置），Windows 需显式指向 venv `wren.exe`；`skills-dir` 指向 wrenai 包内官方 skills 根（specs/022、ADR 0035，空值从 executable 位置推断，失效仅 warn 降级 CLI 通道）；CLI 与 serve mcp 两处 spawn 失败统一回跨平台安装引导（`WrenProperties#resolveExecutable`/`unavailableMessage`），集群部署前置见 `docs/cluster-deploy.md` |
| `dataagent.analytics.enabled` | false | 预置分析库（tenant_storage_utilization / project_info） |
| `dataagent.expose-app-db` | false | 是否把平台元数据库暴露为数据源 |
| `dataagent.session.redis.*` | 关闭 | 分布式会话（HA 必需；多副本另需 sticky LB，见 7.3） |
| `spring.datasource.*` | H2 文件库 | 平台元数据（JPA）；生产激活 `jdbc` profile 切 MySQL/PG |

### Wren 记忆依赖

`requirements-wren.txt` 固定官方 CLI 与 memory-onnx 依赖。在配置的 Wren Python 环境安装该文件；Wren 默认自动选择多语言 ONNX/lancedb，首次使用需要下载模型。可由进程环境设置 WREN_MEMORY_BACKEND=lancedb、WREN_EMBEDDING_BACKEND=onnx、WREN_EMBEDDING_MODEL=paraphrase-multilingual-MiniLM-L12-v2；实际后端以 memory status 为准，依赖缺失时即使设置 lancedb 也会降级。应用不修改外部 Python 环境，不自动安装依赖。

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
| 对话式建模与原生 HITL（specs/013/014/017/019/022） | `tools/data/ModelingToolkit`（二十工具，YAML-first 写面 `write_file`/`patch_file` + 统一 `decide_relation` + HITL 预检 `previewChange`）、`web/middleware/ModelingHitlMiddleware`、`tools/data/ModelingToolkitRegistrar`（单例 `toolkit()` 供 HTTP 预检复用同闸）、`web/api/SemanticModelingController`（`workspace/file`、`workspace/preview`）、`web/config/DataAgentConfig`（剧本 + 免沙箱装配 + 技能挂载 `mountWrenSkills`）、`dataset/WrenSkillsLocator`（官方 skills 目录解析）、前端 `components/{ModelingChatPanel,ModelingHitlCard,ReadOnlyRelationGraph}` + `utils/diff.ts` |
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
