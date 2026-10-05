# ADR 0027: 语义视图与口径落库（term 保存即生效 + view 发布生效双轨）

日期：2026-10-01 ｜ 状态：已接受 ｜ 关联规格：docs/specs/011-semantic-enrich-and-views.md

## 背景

999 库事故（ADR 0026 同源）的另一条根因：建模对话中用户口述的业务口径（「高频用户 =
访问次数 > 5 的用户」）只留在会话记忆里，MDL 与术语表均无落点——下轮会话口径漂移、
跨会话不可复现。同期实证 wren 引擎对 views 双通道支持（`views/<name>/` 工件 validate/build
全绿，`queryable_names = model_names | view_names`，探针 target/wren-probe），而平台
`MdlPublishService#renderFiles` 只渲染 project/models/relationships/cubes，建模助手
（ADR 0024）九工具无术语与 view 入口。

## 决策

1. **D1 双落点路由看内容形态、不看来源**。`MODELING_SCRIPT` 剧本第 6 步给出路由决策树：
   纯词义声明 → `create_term`（保存即生效）；单表聚合指标 → term 保底 + `create_cube`；
   HAVING/窗口/多表 JOIN → term 保底 + `create_view`。文档上传与对话口述走同一剧本同一
   路由（文档内容本就经 `DataDynamicContextMiddleware` 全文注入 SystemMessage，无独立管线）。
   硬约束「口径必须落库，禁止只记会话记忆」（ADR 0024 D7 工具层强制精神的延续）。
2. **D2 term 不进 MDL、保存即生效**。官方 MDL schema v5 无术语段（术语属知识层而非模型层），
   因此 term 创建/更新/删除不触发 MDL 置脏、不触发重建，生效路径 = 下轮 SystemMessage 重建时
   `DatasetService#semanticTermsText()` 注入 `[KNOWLEDGE_BASE_OVERVIEW]`——零发布、即时。
   view/cube 相反：不发布不可查（wren 引擎冻结发布期 manifest），走既有 DIRTY→发布状态机。
3. **D3 view 形态校验从宽、命名互斥从严**。`requireViewSql` 只做单条 SELECT/WITH 白名单
   （行注释剥离、尾分号剥除、内部分号拒绝）——不做 SQL AST/表级越权分析：view 由知识库
   owner 本人维护并仅供本组问数使用，威胁模型与 WrenAI 官方一致；SQL 长度上限 4000。
   发布期 `buildViews` 将视图名经 `sanitizeIdentifier` 归一后与 model/cube 名互斥检查，
   冲突报 error issue 阻断发布（wren `queryable_names` 扁平命名空间，重名行为未定义）。
4. **D4 渲染锁定探针黄金形状**。`views/<name>/metadata.yml`（name + properties.description）
   与 `sql.yml`（`statement: >-` 折叠块）按探针产物逐字节对齐；不做 `base_object: view`
   的 cube 挂载（探针虽通，但 cube 已有自己的模型挂载语义，混挂徒增排障面）。
5. **D5 概览注入只列 PUBLISHED**。`semanticViewsText` 过滤掉 DRAFT——DRAFT 视图不可查，
   注入会诱导 LLM 生成按视图名查询的失败 SQL；DRAFT 可见性由建模对话
   `list_modeling_state` 与语义建模页承担。term 维持全局字典（方案 A，specs/011 拍板：
   租户隔离后置，`scope` 自由标签过渡）。
6. **D6 三入口同轮落地**。建模对话（`create_view`/`update_view`/`delete_view` + 术语三工具，
   更新/删除带硬性确认门）、语义建模页（视图卡片可编辑 + 术语只读卡片，管理归
   「语义配置」页）、REST（`/modeling/views` 三端点，租户校验 + `markMdlDirty`）同批交付，
   与 ADR 0026 D4 同一纪律。

## 理由与权衡

- term「保存即生效」与 view「发布生效」的分裂是刻意的：两者的生效介质不同（提示词 vs
  编译产物），强行统一（比如 term 也走发布）会让词义修正被发布闸阻塞；反之把 term 硬塞进
  MDL 则破坏与官方 schema 的对齐。
- 代价：`create_term` 同名重复直接 409 报错引导去「语义配置」页，助手不重试（无 update_term，
  避免对话内半编辑态）；存量语义术语无租户隔离，跨知识库词义冲突靠 `scope` 标签人工区分。
- 明确不做：SQL 表级越权分析（`checkCrossTable` 只覆盖 `DataAgentToolkit` 直查通道，wren
  通道由引擎按 MDL 模型边界约束）、view 物化（wren 引擎查询期展开）、cube base_object 挂
  view、MDL 原生知识/规则段映射。

## 影响面

- `MdlSuggestionService`（术语/view CRUD + `requireViewSql`）、`MdlPublishService`
  （`buildViews`/`renderView`/`renderViewSql`/`markPublished` views 段/`mdl.json` views 数组）、
  `ModelingToolkit`（六新工具 + `renderState` 视图清单）、`DataAgentConfig`（`MODELING_SCRIPT`
  硬约束 5 + 步骤 6）、`DatasetService`/`DatasetContextProvider`（`semanticViewsText`）、
  `DataDynamicContextMiddleware`（概览语义视图小节）、`SemanticModelingController`
  （views 三端点 + overview 扩展）、前端 `api/semanticModeling.ts`/`SemanticModelingPage`。
- 新表 `dataagent_semantic_view`（`ddl-auto=update` 自动建）。
- 测试守卫：`MdlSuggestionServiceTest`（SQL 形态/查重/404）、`MdlPublishServiceTest`
  （黄金形状断言/命名冲突）、`ModelingToolkitTest`（十五工具剧本守卫 + 工具委托）、
  `SemanticModelingControllerTest`（端点委托/越权 404）、`DatasetServiceEvidenceTest`/
  `DatasetServiceMdlDirtyTest`（构造链）。

## 关联

- ADR 0024（D3 agent 写实体、D7 剧本硬约束）、ADR 0026（D4 三入口同轮落地纪律同源）、
  ADR 0019（发布状态机）、ADR 0020（queryable_names/发布即重建实例）
- specs/011-semantic-enrich-and-views.md（实施规格与用户三项拍板）
- 探针证据：target/wren-probe/viewproj/（views 工件黄金形状与 dry_plan 三场景）
