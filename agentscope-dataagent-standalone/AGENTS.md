# AGENTS.md — agentscope-dataagent

> 本文件是 AI 编码助手在本仓库工作的「宪法」。每次对话自动生效。
> 系统详解见 [ARCHITECTURE_zh.md](ARCHITECTURE_zh.md)（动手前先读相关章节）；
> 架构取舍见 [docs/adr/](docs/adr/)；新功能先写 [docs/specs/](docs/specs/) 中的一页纸需求。

## 项目定位

基于 AgentScope（HarnessAgent + ReActAgent）的多租户数据分析 Agent 平台。
核心流程是**通过 Wren 进行语义建模 + 问数**（数据集组 == wren project，ADR 0029）：

- 问数助手 `data-agent`：在授权的已发布工程上使用官方 Wren skills，通过
  `WrenMcpToolkit` 的 `wren_list_tools` / `wren_call_tool` 发现并调用官方 MCP，
  配合 `render_chart` / `run_python`。`WrenQueryContextMiddleware` 只提供轻量发布目录；
  不注册旧 `WrenToolkit`、`retrieve_evidence` 或平台自定义 SQL/Cube/召回路由。
- 建模助手 `modeling-agent`：使用同版本官方 Wren skills、原生文件工具与受权限约束的
  `wren_cli`；通用文件写授权、工程 YAML、官方校验/编译/查询和页面显式发布构成主流程。
  旧 `ModelingToolkit` 和阶段工具保留兼容源码，退出主 Agent 运行时。
- Wren-only：不存在物理 SQL 直查回退；知识图谱构建链路已停用（ADR 0040），
  不要向 KG 方向新增功能。

## 技术栈

- 后端：Java 17 编译基线（根 pom.xml）+ Spring Boot 4.0.4 WebFlux + Spring Data JPA；JDBC 等阻塞调用必须 `subscribeOn(Schedulers.boundedElastic())`，禁止阻塞事件循环
- 前端：React 18 + TypeScript + Vite（`frontend/`，构建进 `classpath:/static/`）
- 存储：当前 application.yml 默认 MySQL 平台库，`h2` profile 切换文件 H2；其他 JDBC 后端须明确配置驱动、SQL 初始化和方言。上传物理表在独立的数据集 MySQL；可选 Redis 会话协调
- 构建：本 IDEA 项目为 standalone，根 pom.xml 直接构建；通过 Maven 依赖同仓库的 harness/core/OpenAI 扩展，框架变更需先构建安装对应依赖。格式门禁为 Spotless

## 分层纪律（最重要）

1. 业务代码只写在本模块四个包：`web/`（API+权限+审计）、`tools/data/`（问数与建模工具）、`dataset/`（数据资产）、`runtime/`（装配+网关+通道）。
2. ReAct 循环、沙箱生命周期、会话路由、工具反射执行一律复用 `agentscope-core` / `agentscope-harness`，**禁止在业务层重新实现**。
3. 不确定框架内部行为时，先 SearchCodebase 查 harness/core 源码，禁止臆测框架 API。

## 硬性规则

### 后端

1. **多租户与显式共享**：资源归属用 `ownerId`；知识库可见性经 `KnowledgeBaseAccessService` 的 QUERY/MANAGE 校验，创建者与全局管理员天然管理。`DatasetScope` 表示当前请求用户及本轮选库，不能把共享库 owner 当运行用户。历史、沙箱、附件和反馈仍私有；库内点赞资料按库串行更新。null scope 不得扩大到其他用户资源。共享写入必须受权限及相应资源锁约束。
2. **新 HTTP 端点**：参照 `ChatController` 模式 —— `(String) auth.getPrincipal()` 取 userId → `AgentAccessGuard.require(userId, agentId, Tier.X)` 鉴权 → 需要时记审计/用量。数据集类接口在服务层再做 ownerId 校验（参照 `DatasetService#get`）。
3. **工具与查询边界**：`@Tool`/`@ToolParam` 描述用中文，入参校验；租户上下文由框架注入。语义查询只经官方 MCP/CLI，保持只读与 strict_mode 已声明模型边界，允许官方 SQL 的 LIMIT/OFFSET，不改写官方 MDL。`WrenQueryAccess` 校验本轮范围和 QUERY，实际调用前及排队后复核撤权；工具发现同样受门锁及授权约束。禁止恢复物理 SQL 回退或增加平台业务路由。
4. **建模写面**：工程 YAML 为唯一事实源；使用原生 `write_file`/`edit_file` 并经 `ModelingHitlMiddleware` 通用文件写确认。`WrenProjectAccess` 校验 MANAGE 并绑定单库工程；禁写 `wren_project.yml` 等平台文件，禁止路径逃逸和任意 shell/profile 切换。普通/ref_sql 模型、关系、视图、Cube、knowledge 按官方契约支持；编译成功不代替页面显式发布。
5. **上传数据集物理表**：一律 `ds_` 前缀落在数据集 MySQL，不得散落多库（跨数据集 JOIN 依赖同库）。
6. **知识库范围**：每轮零/单/多/全选的有效授权范围同时写 `ConversationScopeRegistry` 和 RuntimeContext 的 `DatasetScope`，建模限定单库；不能因 null/空选择扩大权限。带外调用通过会话范围恢复上下文；`done.sessionKey` 是 conversationId。
7. **代码注释/Javadoc 用英文**（跟随现状）；**面向 LLM 的文本用简体中文**（@Tool 描述、系统提示词、SKILL.md、图表标题/轴标签、返回给模型的错误信息）。两者不得混用。
8. Spring Bean 注入用构造器或 `@Bean` 方法参数；本类内定义的 Bean 不得字段 `@Autowired` 自注入（`DataAgentConfig` 的 Model 循环依赖教训）。

### 前端

1. 函数组件 + TS 严格类型；新页面先看 `frontend/src/pages/` 同类页与 `components/` 复用件；禁止引入新 UI 组件库/图表库（图表统一 ECharts）。
2. API 调用统一走 `frontend/src/api/*.ts` 的 `apiFetch`，由统一入口适配部署前缀；同源 API 401 只清理当前被拒令牌，dev返回登录页、prod提示从BI重新进入，403/网络错误不退出。认证配置加载失败不展示密码框。SSE 协议（含 `capacity`、`tool_call/tool_result/token/done/error`）改动必须前后端同步。
3. `done` 帧的 `sessionKey` 是 conversationId 而非存储层 sessionKey，前端不得混用（会话分裂教训，见 ChatController 注释）。

### 文档

1. 改主流程/装配/工具链/数据模型 → 同步更新 `ARCHITECTURE_zh.md` 对应章节；引用代码用「类名+方法名」，**禁止写行号**。
2. 做架构取舍 → 在 `docs/adr/` 追加一条（只增不改）。
3. 改 `application.yml` 配置项 → 同步 README 配置表与 `ARCHITECTURE_zh.md` 第 12 章。
4. 中大功能 → 先写 `docs/specs/NNN-<name>.md`（模板见 `000-template.md`），计划确认后再写代码。

## 验证命令（提交前必须全绿）

```bash
mvn test
mvn package -DskipTests
cd frontend && npm run build
```

## 测试约定

- 后端测试在 `src/test/java/...` 镜像被测包结构；工具/解析器/安全相关改动必须补 JUnit 用例（参照 `WrenToolkitTest`、`ModelingToolkitTest`、`MdlPublishServiceTest`、`WebhookSignatureTest`）。
- 多租户/越权类逻辑（WrenQueryAccess、WrenProjectAccess、DatasetScope、QUERY/MANAGE、visible、ACL）变更必须覆盖「看不到别人的数据」及排队撤权。

## 提交约定

Conventional Commits：`feat|fix|refactor|docs|test|chore|perf(<scope>): 摘要`，
scope ∈ `web|runtime|tools|dataset|frontend|marketplace|docs`。
正文写：动机（为什么改）→ 方案要点 → 影响面 → 已执行的验证命令。

标题和正文使用中文，保留上述类型和 scope；标题简洁、末尾不加句号，建议不超过50字符。
正文空行后用编号简述实现、影响和验证，建议每行不超过72字符；不同主题拆分提交。
不兼容变更在页脚使用 `BREAKING CHANGE:` 说明迁移要求；仅关联实际存在的 Issue。
用户指定参考：[提交信息规范文章](https://blog.csdn.net/baidu_38225647/article/details/163135257)。

## 当前运行边界（ADR 0069、0074、0077、0078）

- modeling-agent 是通用 AgentScope Agent；建模流程、模式选择和语义规则来自安装版官方 Wren skills。运行时仅注册原生文件工具与受当前知识库权限约束的 wren_cli，不再注册旧 ModelingToolkit 或平台模式、关系、视图、问题阶段工具。
- 工程文件通过原生 write_file/edit_file 修改并经 ModelingHitlMiddleware 授权；memory store 同样确认。通用预览只检查权限和变更内容，技术校验/编译/查询由 Agent 调用官方 CLI 完成。历史 patch_file/批量预检接口仅为旧页面兼容，不恢复为新 Agent 工具链。
- 草稿文件限定当前用户有 MANAGE 的单知识库 project，归属路径由库 owner 决定；禁止宿主路径、跨工程、任意 shell、profile 切换与平台文件写入。业务文档映射 raw/business-document.md。官方 build 不能代替页面显式发布；KG 和物理查询回退禁用，Cube 按官方契约支持。
- 上传使用 DatasetUploadProperties/Admission/Intake 的单进程预算、磁盘暂存与失败清理。ChatCapacity 约束 ChatController 问数，建模与其他通道不计入；Wren 实例预算只覆盖 MCP，沙箱只驱逐无活动租约的实例。不得把单进程默认值描述成分布式全局容量或已完成负载验收。
- 框架变更后先在上层仓库执行 `mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests`，再构建 standalone。Docker 取消实测为 opt-in，条件跳过须记录；发布需同步新沙箱镜像。

## 项目共享记忆协作规范

### 当前官方 Wren 运行边界（ADR 0069 / spec 058）

- 用户已确认建模与问数都使用同版本官方 Wren skills/CLI/MCP，本项目只提供通用 AgentScope 执行和平台服务，不建设本体/KG，不自研领域流程或 SQL/Cube 路由。
- 历史 ADR 中 Cube 一律禁用、旧平台 SQL 流程和 SQL LIMIT/OFFSET 一律禁用等规则已被替代；当前保留只读查询、Wren strict_mode 已声明模型边界、租户/路径/profile 隔离和显式发布。
- 普通模型、ref_sql、关系、视图、Cube、knowledge 均按官方契约支持。官方技能不得修改，官方编译 target/mdl.json 不经平台语义改写；展示目录仅为衍生信息。
- 问题可选，点赞后用官方 memory 保存成功且用于答案的业务查询，点踩只记录反馈。保留通用文件写授权，不叠加平台业务审阅。
- 本 IDEA 项目是 standalone：使用根目录 mvn test / mvn package -DskipTests；前端命令在 frontend/ 执行。当前根 pom.xml Java 属性 17，具体能力依实际框架源码，不假设旧多模块命令有效。

- 项目级 Skill：[shared-memory](.agents/skills/shared-memory/SKILL.md)。共享文件位于 [docs/ai/](docs/ai/)，两个 Codex 账号使用同一份项目文件，不依赖账号专属 CODEX_HOME。
- 每次开始新的开发任务前，先读取本文件与 docs/ai/PROJECT_CONTEXT.md、CURRENT_TASK.md、DECISIONS.md、SESSION_LOG.md，再检查 Git 状态和相关代码，核对记忆与实际进度。
- 每完成一个重要开发阶段后，执行 shared-memory 的 Checkpoint：更新当前任务、修改文件、测试结果、未完成工作、阻塞及具体下一步；重要决定记录到 DECISIONS.md，简要记录追加到 SESSION_LOG.md。
- 用户要求“继续任务”时，优先恢复 CURRENT_TASK.md 的未完成事项；用户明确切换任务时保留旧任务为待续。
- 用户要求“保存进度”或“交接任务”时，读取并执行 shared-memory Skill 对应流程。
- 不得假设可以直接访问另一个账号的聊天记录；共享文件、实际代码和可核查的验证结果是交接依据。建议、待确认方案和历史测试必须标明状态，不能记作已实施或本次验证。
- 更新前重新读取并合并共享记忆，保留其他 Agent 的新增内容；不自动提交 Git，不覆盖、删除或回滚其他 Agent 的未提交修改，不记录密钥和令牌。
