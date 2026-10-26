# AGENTS.md — agentscope-dataagent

> AI 编码助手在本仓库工作的「宪法」，每次对话自动生效。只写**当前有效**的规则；历史演变见 ADR 索引。
> 动手前先读 [ARCHITECTURE_zh.md](ARCHITECTURE_zh.md) 相关章节；新功能先写 spec，架构取舍写 ADR。

## 1. 项目定位

基于 AgentScope（HarnessAgent + ReActAgent）的多租户数据分析 Agent 平台，核心流程是**通过 Wren 语义建模 + 问数**（数据集组即 Wren project）：

- 问数助手 `data-agent`：在授权的已发布工程上使用官方 Wren usage 技能；`WrenScopedToolkitMiddleware` 为每次调用注册受权限约束的官方 MCP 工具（`run_sql`、`recall_queries`、`get_instructions` 等，只读），配合 `render_chart` / `run_python`。
- 建模助手 `modeling-agent`：通用 AgentScope Agent，使用同版本官方 Wren skills、原生文件工具和受权限约束的 `wren_cli`；工程 YAML 是唯一事实源，页面显式发布后才用于问数。
- Wren-only：没有物理 SQL 直查回退；知识图谱链路已停用，不要向 KG 方向新增功能。

## 2. 文档地图

| 位置 | 内容 | 何时读/写 |
|---|---|---|
| [ARCHITECTURE_zh.md](ARCHITECTURE_zh.md) | 当前实现：装配、主流程、工具链、数据模型；第 14 章为建模与问数的当前运行约束 | 改代码前读；改主流程/装配/工具链/数据模型后同步更新 |
| [docs/adr/README.md](docs/adr/README.md) | ADR 索引与"当前有效主线" | 做架构取舍时追加新 ADR（只增不改） |
| [docs/specs/README.md](docs/specs/README.md) | spec 索引；顶层为进行中，`done/` 为历史 | 中大功能先写 spec，确认后再写代码 |
| [docs/guides/](docs/guides/) | 部署、发布、并发与容量 | 运维与发布操作 |
| [docs/reference/](docs/reference/) | Agent 定义、会话历史与附件等专题参考 | 查专题细节 |
| [docs/ai/](docs/ai/) | 多 Agent 共享记忆（当前任务、决策、会话日志） | 每次开始/阶段完成时按 shared-memory 流程读写 |
| [docs/test-reports/](docs/test-reports/) | 日期命名的验证与复盘报告 | 记录真实验收结果 |
| `docs/migrations/`、`docs/fixtures/`、`docs/samples/` | SQL 迁移、验收脚本、样例业务资料 | 按需使用 |
| [docs/archive/](docs/archive/) | 历史资料（旧评审、旧计划、导入分支、原型） | **不作为当前依据** |

## 3. 技术栈

- 后端：Java 17 + Spring Boot 4.0.4 WebFlux + Spring Data JPA；JDBC 等阻塞调用必须 `subscribeOn(Schedulers.boundedElastic())`，禁止阻塞事件循环。
- 前端：React 18 + TypeScript + Vite（`frontend/`，构建进 `classpath:/static/`）。
- 存储：默认 MySQL 平台库（`h2` profile 切换文件 H2）；上传物理表在独立的数据集 MySQL；可选 Redis 会话协调。
- 构建：standalone 项目，根 `pom.xml` 直接构建，依赖同仓库 harness/core/OpenAI 扩展；格式门禁为 Spotless。框架变更后先在上层仓库执行 `mvn -pl agentscope-harness,agentscope-extensions/agentscope-extensions-model/agentscope-extensions-model-openai -am install -DskipTests`。

## 4. 分层纪律（最重要）

1. 业务代码只写在四个包：`web/`（API、权限、审计）、`tools/data/`（问数与建模工具）、`dataset/`（数据资产）、`runtime/`（装配、网关、通道）。
2. ReAct 循环、沙箱生命周期、会话路由、工具反射执行一律复用 `agentscope-core` / `agentscope-harness`，**禁止在业务层重新实现**。
3. 不确定框架内部行为时先查 harness/core 源码，禁止臆测框架 API。

## 5. 硬性规则

### 后端

1. **多租户与共享**：资源归属用 `ownerId`；知识库可见性经 `KnowledgeBaseAccessService` 的 QUERY/MANAGE 校验。`DatasetScope` 表示当前请求用户及本轮选库，不能把共享库 owner 当运行用户；null scope 不得扩大权限；共享写入受权限与资源锁约束，排队后复核撤权。
2. **新 HTTP 端点**：参照 `ChatController` —— `(String) auth.getPrincipal()` 取 userId → `AgentAccessGuard.require(userId, agentId, Tier.X)` → 需要时记审计/用量；数据集类接口在服务层再做 owner 校验（参照 `DatasetService#get`）。
3. **查询边界**：语义查询只经官方 MCP/CLI，只读、遵守 strict_mode 已声明模型边界，允许官方 LIMIT/OFFSET，不改写官方 MDL；禁止物理 SQL 回退或平台业务路由。`WrenQueryAccess` 校验本轮范围和 QUERY，调用前及排队后复核撤权。
4. **建模写面**：只通过原生 `write_file`/`edit_file` 和 `wren_cli` 修改当前用户有 MANAGE 的单库草稿；禁写平台文件、禁止路径逃逸、任意 shell 与 profile 切换。基础建模维护模型、ref_sql、关系与 knowledge；Cube/View 不读不写；问答对不由建模生成。
5. **上传数据集物理表**：一律 `ds_` 前缀落在数据集 MySQL。
6. **知识库范围**：每轮有效授权范围同时写 `ConversationScopeRegistry` 和 RuntimeContext 的 `DatasetScope`，问数仅零/单库、建模限定单库，多库/全选拒绝；历史多库重新选择；`done.sessionKey` 是 conversationId。
7. **语言**：代码注释/Javadoc 用英文；面向 LLM 的文本（`@Tool` 描述、系统提示词、SKILL.md、图表标题、返回给模型的错误信息）用简体中文。
8. **Spring 注入**：用构造器或 `@Bean` 方法参数；多构造器时只给完整构造器标 `@Autowired`；本类内定义的 Bean 不得字段自注入。

### 前端

1. 函数组件 + TS 严格类型；新页面先看 `frontend/src/pages/` 同类页与 `components/` 复用件；不引入新 UI 组件库/图表库（图表统一 ECharts）。
2. API 调用统一走 `frontend/src/api/*.ts` 的 `apiFetch`；SSE 协议（`capacity`、`tool_call/tool_result/token/done/error`）改动必须前后端同步。
3. `done` 帧的 `sessionKey` 是 conversationId，不是存储层 sessionKey。

### 文档

1. 改主流程/装配/工具链/数据模型 → 同步更新 ARCHITECTURE_zh.md 对应章节，引用代码用「类名#方法名」，禁止写行号。
2. 架构取舍按第 7 节判断表写 ADR 并更新 [ADR 索引](docs/adr/README.md)；ADR 正文只增不改，被取代时只改状态行。
3. 改 `application.yml` 配置项 → 同步 README 配置表与 ARCHITECTURE_zh.md 第 12 章。
4. 中大功能 → 先写 `docs/specs/NNN-<name>.md`（模板 `000-template.md`），确认后再写代码；完成并验收后移入 `docs/specs/done/`。
5. 本文件只写当前有效规则，不按 ADR 逐段追加历史；过期内容直接改写或删除。
6. 规模上限（`DocsConsistencyTest` 检查）：AGENTS.md ≤ 150 行；`docs/ai/CURRENT_TASK.md` ≤ 80 行；ARCHITECTURE_zh.md 单章 ≤ 250 行。超出时先合并或删减，不提高上限。

## 6. 当前有效边界（只列稳定边界，细节见 ARCHITECTURE_zh.md 第 14 章）

- 建模：官方技能 Auto-pilot，不改官方技能文件；Cube/View 不读不写；问答对不由建模生成；SQL 只读且禁止笛卡尔积；单轮查询有硬上限。
- 建模变更治理：修改直接进入草稿，在每轮"AI 建模结果"中逐条查看、编辑，草稿栏统一发布，不逐项确认（ADR0111、spec092 §7）；无业务文档的自动建模不写业务规则；业务文档多份、只由用户上传。
- 问答：只来自用户逐题确认与问数点赞，发布后可被召回。
- 时间门禁：外部数据源事实表窗口存平台数据库，不写入 MDL；建模、问数、问题验证共用逐表检查；上传表不设门禁。
- 问数：只有登记了受限事实表的库才"缺日期先问"；统计必须来自 SQL 返回值。
- 工程整合：模型切换为全局管理员手动操作，每轮及辅助调用固定快照；不自动切公网；会话使用框架持久存储。SQL 问题投影仅在问题列表按修订流程维护，不绕过确认。
- 容量默认值均为单进程预算，不能描述为分布式全局容量。

## 7. 每个任务的工作流（Codex 与 Claude 通用）

**开工时**先用一两句话报告：变更类型、本次文档要求（spec 要/不要、ADR 要/不要，以及理由）。用户可当场纠正。

| 变更类型 | spec | ADR | 必须同步 |
|---|---|---|---|
| 修 bug、调提示词、加校验、小优化（**默认归入此类**） | 不写 | 不写 | 测试；受影响的 ARCHITECTURE 描述；提交说明 |
| 新功能或改变用户流程，跨模块且需要验收标准 | 先写，用户确认后再写代码 | 一般不写 | 完成并验收后移入 `docs/specs/done/`，更新 spec 索引 |
| 多方案取舍且影响长期；改变系统边界、产品方向、安全/多租户、数据模型；推翻旧 ADR | 视规模 | 写（模板 `docs/adr/0000-template.md`） | 旧 ADR 只改"状态"行为"已被 ADR-xxxx 取代"；更新 ADR 索引主线 |
| 改 `application.yml` 配置项 | — | — | README 配置表、ARCHITECTURE 第 12 章 |

- **需求调整**：进行中的 spec 直接修改并说明变化；已完成的功能被推翻时，写新 spec，必要时写新 ADR 取代旧决策，不改旧 ADR 正文。
- **规则落地**：同一条规则被违反两次，就改成代码约束或测试，不在文档里加强语气。
- **文档分层**：文档写结构、边界与原因；易变的数值和细节写在代码常量与测试里。
- **完成时**：运行第 8 节验证命令（含文档一致性测试 `DocsConsistencyTest`），执行共享记忆 Checkpoint，在最终回复中说明改了什么、验证结果、未验证的部分。
- **定期清理**：用户说"整理文档"或一个里程碑结束时，按 [doc-maintenance](.agents/skills/doc-maintenance/SKILL.md) 清理。

## 8. 验证命令（提交前必须全绿）

```bash
mvn spotless:check
mvn test
mvn package -DskipTests
cd frontend && npm run build
```

真实 LLM / Docker / Wren 相关测试为条件启用，跳过时须在报告中注明；真实 Agent 行为以重启后的日志验收为准，不能以提示词断言代替。

## 9. 测试约定

- 后端测试在 `src/test/java/...` 镜像被测包结构；工具、解析器、安全相关改动必须补 JUnit 用例。
- 多租户/越权逻辑（WrenQueryAccess、WrenProjectAccess、DatasetScope、QUERY/MANAGE、ACL）变更必须覆盖「看不到别人的数据」及排队撤权。

## 10. 提交约定

Conventional Commits：`feat|fix|refactor|docs|test|chore|perf(<scope>): 摘要`，scope ∈ `web|runtime|tools|dataset|frontend|marketplace|docs`。正文写：动机 → 方案要点 → 影响面 → 已执行的验证命令。

## 11. 共享记忆协作

- 项目级 Skill：[shared-memory](.agents/skills/shared-memory/SKILL.md)；共享文件在 [docs/ai/](docs/ai/)，多个账号使用同一份项目文件。
- 开始新任务前读本文件与 `docs/ai/` 下 PROJECT_CONTEXT、CURRENT_TASK、DECISIONS、SESSION_LOG，再核对 Git 状态和代码。
- 重要阶段完成后执行 Checkpoint：更新 CURRENT_TASK（只保留当前任务）、DECISIONS，追加 SESSION_LOG。
- "继续任务"优先恢复 CURRENT_TASK 的未完成事项；"保存进度/交接任务"按 Skill 流程执行。
- 建议、待确认方案和历史测试必须标明状态；不自动提交 Git，不覆盖或回滚他人未提交修改，不记录密钥和令牌。
