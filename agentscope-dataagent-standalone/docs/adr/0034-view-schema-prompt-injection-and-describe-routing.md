# ADR 0034: 视图 schema 经 prompt 注入暴露，describe 保持逻辑模型专属并对错路由给引导文案

- 状态：已采纳（实施规格 specs/020）
- 日期：2026-10-04
- 来源：2026-10-04 LLM.log 死锁诊断（知识库 8addb559）+ 上游 WrenAI 0.15.0 源码对照（mcp_server.py / memory/schema_indexer.py）

## 背景

问数 LLM 在 `[KNOWLEDGE_BASE_OVERVIEW]`「语义视图」小节只能看到视图名+一行描述（specs/019 §7
切工程文件源后的形态），不知道视图输出哪些列。想看结构时唯一确定性入口是
`WrenToolkit#wrenDescribeModel`，其白名单只含已发布逻辑模型——这与上游 wrenai
`mcp_server.py#describe_model` 行为完全一致（官方同样只认 models，视图名报
`Model 'X' not found`）。实证死锁链：LLM 对已发布视图「用户活跃度分层」与合法模型混合 describe
→ 整批拒绝且报错不带名字、不引导替代工具 → 反复换名重试 → 转而从基础模型写 SQL → 被视图守卫
（ADR 0032）拦截 → 最终放弃视图语义。上游不死锁靠的是平台未搬走的三条互补通道：
`describe_schema` 输出视图 statement 全文（`_describe_view`：SQL 原文即 schema）、memory
语义检索把视图按 `item_type="view"` 索引、官方工作流 prompt 显式分工（schema 走
describe_schema/get_mdl）。平台采用 push 模式预注入（ADR 0020），没有 pull 型兜底通道。

## 决策

1. **D1 describe_model 白名单不扩权**：视图与 Cube 继续不属于 describe 的服务对象（与上游
   一致）。理由：视图无独立列清单（statement 是自由 SQL，解析推断输出列有编造风险）；Cube
   成员目录已在注入小节与 describe 输出中随基础模型给出。
2. **D2 视图 schema 的暴露通道是注入端 statement 渲染**：`DatasetService#semanticViewsText`
   对每个视图在名称+描述之下渲染一行折叠为单行、超长截断的 `SQL: <statement>`，对齐上游
   `_describe_view`「输出 SQL 原文即最佳 schema 描述」的设计。数据源为工程 `views/` 文件
   （`MdlWorkspaceReader.WorkspaceView#sql`），无新 IO。
3. **D3 describe 校验失败按命中资产类型给工具路由引导**：名字命中已发布 views →
   「X 是已发布视图而非逻辑模型，请直接用 wren_run_sql 按视图名查询」；命中已发布 cubes →
   「X 是 Cube 而非逻辑模型，请用 wren_query_cube 查询」；均未命中维持原文案。错误带命中名字
   （名字来自调用方入参，无租户泄漏面），整批拒绝语义不变。

## 理由与权衡

- 为何「注入 statement」而非「扩权 describe」：平台是 push 预注入架构，问数 LLM 看不到 pull 型
  工具面之外的世界；视图数量级为每库个位数，每视图一行折叠 SQL 的 token 成本可控且一次性付清，
  换来 LLM 免工具调用即获得输出列——上游 memory 索引也只存 statement 前 200 字符，证明截断
  statement 是官方认可的 schema 载体。
- 为何「引导文案」而非「静默通过」：上游官方工作流 prompt 明确写了分工（schema 用
  describe_schema），平台无此兜底，错误文案必须自带路由（TC 风格确定性引导，参照 specs/010 M3
  「guided error」先例）。
- 备选被拒：新增 `wren_describe_views` 工具（注入已携带 statement 后冗余，且扩大工具面）；
  describe 输出视图推断列（需要 SQL 输出列解析器，复杂且有错列风险）。
- 已知边界（明确不修）：`semanticViewsText` 读工程文件含 DRAFT 视图，存在「注入了但 run_sql
  查不到」的发布前窗口期——specs/019 §7 既定决策，收紧另立 spec。

## 影响面

- 修改：`WrenToolkit`（describe 校验分支与错误文案）、`DatasetService#semanticViewsText`
  （视图行渲染）。
- 测试：`WrenToolkitTest`（视图/Cube/混合批次引导）、视图渲染静态方法测试、
  `DataDynamicContextMiddlewareTest`（注入含 SQL 行契约）。
- 文档：ARCHITECTURE_zh.md 4.6 工具表与 5.6「语义视图」小节、specs/020。
