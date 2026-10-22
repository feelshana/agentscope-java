# Agent 定义与当前装配

更新：2026-10-18。本文依据 DataAgentConfig、DataAgentBootstrap、AgentCatalogService、DataToolkitRegistrar 和 ModelingToolkitRegistrar；开发约束见 [AGENTS.md](../../AGENTS.md)，主流程见 [架构文档](../../ARCHITECTURE_zh.md)。

## 内置 Agent

| Agent | 运行方式 | 工具与数据边界 |
| --- | --- | --- |
| data-agent | HarnessAgent/ReActAgent，挂载安装版官方 Wren skills | 门禁通过后通过调用作用域 Toolkit 原生注册官方 MCP，配合 `run_python` / `render_chart` |
| modeling-agent | 通用 AgentScope Agent，单知识库、宿主受限文件系统，无分析 Docker 沙箱 | 原生文件工具及 `wren_cli`；当前用户需库 MANAGE，工程归属由库 owner 决定 |

两者使用同版本官方技能和 CLI/MCP。普通模型、ref_sql、关系、视图、knowledge 按官方契约；Cube 暂不进入主流程（ADR0085）；平台不实现第二套 SQL/指标/召回路由、不修改官方 MDL。旧 WrenToolkit/ModelingToolkit 源码及兼容 HTTP 服务不代表仍在主 Agent 上注册。

建模文档和问题可选，文件修改走 ModelingHitlMiddleware 通用写确认；官方校验/编译不代替页面显式发布。业务文档映射 raw/business-document.md，平台文件、宿主路径、跨库工程、任意 shell 与 profile 切换受限。问数仅用本轮有效 QUERY 范围，工具排队后复核撤权。

## 文件配置与代码装配

`DataAgentBootstrap.DEFAULT_CONFIG_PATH` 当前为 `~/.agentscope/dataagent/agentscope.json`，不是 workspace 下的 `.agentscope/agentscope.json`，也不是 `dataagent-standalone` 目录。`dataagent.workspace` 指运行工作区，与配置文件位置分开。

`DataAgentConfig#ensureAgentscopeConfig` 负责首次缺失配置的生成，既有文件不会被无条件覆盖。`DataAgentBootstrap.Builder` 支持读取配置、`configureAgent` 定制、注册预构建 Agent 和 `skipConfigFile`；这是框架扩展方式，不能绕过平台租户、权限或工具边界。具体 JSON 字段以当前 Config 模型为准，勿照搬旧版本示例。

主装配由 `DataAgentConfig#builderBootstrap` 完成：模型、工作区、提示词、技能、通用中间件、沙箱及会话网关。`DataToolkitRegistrar#registerDataToolkit` 注册图表/Python 等通用工具，`WrenScopedToolkitMiddleware#onModelCall` 按本轮单库权限原生注册官方 MCP；`WrenScopedMcpClient#callTool` 在查询触达 Wren 前执行已发布 MDL 驱动的 SQL 性能边界。`ModelingToolkitRegistrar#registerOfficialTools` 独立装配建模工具。不要在新入口重新注册旧领域工具。

子 Agent 定义可保留，`dataagent.agent.subagents-enabled=false` 默认关闭编排工具；定义存在不代表已启用并发执行，也不代表替换主问数/建模流程。

## 目录 API 与自定义 Agent 兼容面

当前代码仍有用户自定义 Agent 的目录、持久化、克隆与共享能力，不能仅凭旧文档说它不存在，也不能假定它自动获得内置 Wren 工具链：

| API | 当前控制器与含义 |
| --- | --- |
| `GET /api/agents`、`GET /api/agents/{id}` | AgentCatalogController，按可见性和 ACL 返回目录/详情 |
| `POST /api/agents`、`PUT/DELETE /api/agents/{id}` | 用户自定义定义创建/更新/删除，鉴权按具体操作执行 |
| `POST /api/agents/{id}/clone` | AgentCloneController，按权限克隆 |
| `/api/agents/{agentId}/skills`、`/tools`、`/workspace` | 技能、工具和工作区管理，分别受 Agent 权限控制 |

`AgentCatalogService` 合并全局定义与当前用户可见的自定义定义；自定义运行注册键使用 `uca-` 前缀。可见/共享 Agent 不等于能访问对方的私有会话、附件或数据源。新能力须复用已有目录和 ACL，不重建第二套注册系统。

## 每轮上下文与容量

聊天 API 为 `/api/agents/{agentId}/chat/stream` 和 `/send`，请求含 message、可选 sessionKey、groupIds、allGroups。sessionKey 在聊天协议中是 conversationId，不是存储会话键。

有效选库范围保存到 ConversationScopeRegistry 并注入 DatasetScope；运行身份保持请求用户。知识库 QUERY/MANAGE 与 Agent 自身 Tier 权限分别检查。共享点赞资料归库，个人历史/反馈/附件归当前用户。

ChatCapacity 限制 ChatController 问数的执行、排队和时限，建模及其他通道不计入该预算；每用户沙箱和 MCP 实例预算也是单进程。SSE capacity 显示 queued/running，401 通过前端 apiFetch/SessionGate 返回登录页。配置和配套依赖/镜像发布见 [运维补充](../guides/platform-concurrency-and-release.md)。

## 修改与核对入口

装配修改同步本文件、AGENTS.md、ARCHITECTURE_zh.md 和 README；配置修改同步配置表。来源自 10 月 9 日的提交覆盖、保留当前实现的差异和 ADR/spec 映射见 [集成核对表](../archive/source-branch-integration-audit-20261015.md)。


## 当前语义建模（ADR0094）

采样完成后进入现有建模工作台，使用同版本官方 generate-mdl、enrich-context、usage、onboarding；默认 Auto-pilot，不维护 enrich-context-lite 或第二个深度 Agent。模型及基础关系的技术性修改按当前授权执行，其它高影响变更遵循现有确认机制。当前基础建模 CLI 的 Cube 命令限制保留。校验、编译与页面显式发布按当前主流程执行。


## 当前事实表检查（ADR0097/spec083）

时间配置是独立数据库资产，通过知识库页面管理，与建模/问数状态和发布无关。SQL 执行前 FactQueryWindowPolicy#requireAllowed 调用 SqlWindowGate#check，CLI/MCP 复用，不读 MDL 或视图定义。仅逐表检查已登记受限事实表，返回 allowed/tables/reason。拒绝后停止同轮 SQL 重试并提示用户，旧 QueryBoundary 正则已删除。query_cube 未纳入本次 SQL 门禁。

## 采样后自动建模准备（ADR0098/spec084）

采样字段说明模型同时返回表角色，独立保存到数据库摘要。页面仅为待配置事实表弹时间窗口，维度表跳过，UNKNOWN行内确认。配置完成后才挂载建模Agent并消费一次性启动参数；失败/关闭不启动，刷新重新检查。建模系统上下文只读当前授权库的数据库窗口策略，执行工具仍独立复核。


## 动态策略边界（ADR0099/spec085）

具体窗口仅保存在平台数据库并运行时读取，不复制到MDL描述/knowledge规则；MDL只写通用小窗口建议，真正业务时间口径保留。建模可自行选择有限样例日期，无需确认，仍走工具门禁。保存与复用SQL须符合已确认业务过滤，不新增未经确认口径。logs/sql-window-gate.log记录实际门禁判断/跳过/缓存/同轮阻止，主Agent预判不等于门禁执行；query_cube不在本次覆盖内。


## 问数准备（ADR0100/spec086）

发布后平台异步准备官方索引，问数按当前源指纹复用，失败按需重试。无参数get_instructions成功结果可复用同一MCP实例缓存，授权和排队撤权仍执行。字段与口径已知时减少重复describe，不猜测未知字段；SQL日期门禁及只读边界不变。准备状态和分段耗时见execution-timing.log，真实加速需服务加载后验收。


实际查询MCP会在发布后的后台执行固定文本的只读召回预热，结果不进入用户上下文；问数仍按实际问题正常召回。发布页准备状态为进程内建议状态，实例回收后失效。查询权限、日期门禁和官方执行方式保持，候选版本原子切换尚未实现（ADR0101/spec087）。


## 问数日期优先约束（ADR0102/spec088）

受限事实表分析缺少日期先询问，禁止MIN/MAX或日期排序探查；这一要求优先于探索、技能加载和召回。每轮注入服务当前时间与时区，相对日期与补充日期使用Agent原有理解和历史。结构/规则解释与不受限表查询不强制日期。
建模自拟样例生成前参考逐表配置，问题与SQL的范围、指标及已确认过滤一致；不缩短用户问题、历史问答或固定业务周期。实际SQL成功但为空可接受，build/dry-plan不能冒充执行。拒绝后同轮停止机制保留：用户范围问题请用户确认，技术样例/可选探查停止验证，检查异常说明未执行；不增加重试。
