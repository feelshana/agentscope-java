# 011 — 语义增强对话化：文档/对话口径落库（enrich-context 平台化）与业务视图

日期：2026-10-01 ｜ 状态：待确认 ｜ 关联：specs/010、specs/013、ADR 0024/0026、新增 ADR 0027

## 背景

「高频用户」事故暴露两层断裂：用户在建模对话中定义的口径只进 modeling-agent 记忆（agent×userId 隔离，问数不可见），问数 LLM 即兴解释导致口径漂移。对照 WrenAI enrich-context 官方剧本（四 sink 路由：MDL YAML / cubes/ / knowledge/rules/ / knowledge/sql/，无 sink 不收工）与本平台探针实证（wren-core 引擎 view 双通道支持：run_sql 直接查 view 展开 CTE、cube 可建在 view 上），平台缺三块：

1. **建模助手无术语/视图工具**：语义术语通道（SemanticTerm → [KNOWLEDGE_BASE_OVERVIEW]）已存在但建模对话够不着；HAVING/窗口级口径（cube 度量表达不了）无 view 落点。
2. **发布链路不渲染 views/**：MdlPublishService 仅渲染 project/models/relationships/cubes。
3. **剧本无增强指引**：MODELING_SCRIPT 无「从文档提取业务语义」步骤、无口径路由决策树、无「口径禁止只记对话记忆」硬规则；建模对话 SystemMessage 已注入文档全文与术语段（DataDynamicContextMiddleware 对所有 agent 生效），只差引导与工具。

## 方案决策（已确认）

- **术语与 MDL 发布完全解耦**（对齐官方：Manifest schema 无术语段，v5 术语映射落 knowledge/rules/ 由 context instructions 注入 LLM）：术语保存写 dataagent_semantic_term 即生效，不触发 MDL 重建/发布；DataDynamicContextMiddleware 每轮重建 SystemMessage 时注入。cube/view 才是引擎工件，走发布流程。
- **术语保持全局字典（方案 A）**：create_term 的 scope 默认填 groupId 仅作溯源标注，注入不过滤；按组绑定/租户隔离延后（用户确认）。

### M1 — 建模对话术语与口径路由（止血）

- **ModelingToolkit 新增 3 工具**（对齐 create_cube 模式，@Tool 描述中文，DatasetScope+RuntimeContext 租户上下文）：
  - `create_term(term, explanation, synonyms?, scope?)` — 落语义术语字典（复用 SemanticTermRepository，scope 默认填 groupId 便于溯源）；term 已存在时报错引导用 update 语义（术语更新走语义配置页）
  - `list_terms()` — 列出现有术语（供对话中查重与复述）
  - `delete_term(termId)` — 删除需先向用户复述确认
- **MODELING_SCRIPT 增强**（DataAgentConfig）：
  - 新增硬规则：「口径约定必须落库（create_term / create_cube / create_view），禁止只记在对话或记忆中；每轮收尾复述本会话已落库清单」
  - 新增口径路由决策树（enrich-context cube_proposals 平台化）：聚合型命名指标（ARR/DAU/去重计数）→ cube；纯行级表达式 → 提示无法落（平台无计算列），引导并入口径维度；HAVING/窗口/CTE 级口径（如 高频用户=访问次数>N）→ view；业务词定义（VIP/活跃用户）→ term
  - 剧本第 5 步（Cube 提案）后插入「文档增强」步骤：从 SystemMessage [KNOWLEDGE_BASE_OVERVIEW] 的文档全文提取枚举含义/单位/NULL 语义/业务术语/指标口径（enrich-context gap catalog 十类的平台子集：#1 枚举、#2 单位、#3 NULL、#6 同义词、#7 时间约定 + 命名指标），逐条向用户提案（一次一题、必带推荐答案与落点），同意后调对应工具落库
  - **口径双落点判定**（界定规则与来源无关，只看内容形态）：词义声明（「X 是什么」）→ 落 term（注入即生效，保底）；命名度量（会被反复聚合出数、口径含 HAVING/窗口/跨表，现场写 SQL 易漂移）→ 先落 term 保底，再追加 view/cube 提案（固化成引擎工件，需发布）；术语与 MDL 发布解耦，view/cube 才走发布

### M2 — 业务视图全链路（views）

- **新实体** `SemanticViewEntity`（表 `dataagent_semantic_view`，结构对齐 SemanticCubeEntity）：id、groupId、name（组内唯一）、statement（TEXT，一条 SELECT SQL）、description、status(DRAFT/PUBLISHED)、ownerId、createdAt/updatedAt；新 `SemanticViewRepository`。
- **MdlSuggestionService 新增** createView/listViews/deleteView（与 cube 同套状态与校验：statement 必须 SELECT/WITH 开头、组内查重、ownerId 越权拒绝）。
- **ModelingToolkit 新增 3 工具**：`create_view(name, baseDescription?)`——LLM 起草 SQL 经模型调用参数传入（statement 参数）；`list_views()`；`delete_view(viewId)`（复述确认）。create_view 描述含硬约束：「statement 只能是单条 SELECT/WITH 查询；只能引用 list_modeling_state 快照中的物理列名与关联列；高爆炸半径动作必须先获用户同意」。
- **MdlPublishService 渲染**（对齐官方 v5 工件格式，探针已验证）：每视图两个文件 `views/<name>/metadata.yml`（name + properties.description）与 `views/<name>/sql.yml`（statement）；validate 沿用 `wren context validate --strict`（官方自带 view SQL dry-plan，SQL 写错发布直接失败，零新增校验代码）；build 产物 mdl.json 自动携带 views。**顺带修复 CubeSpec.description 渲染缺口**（renderCube 补 properties.description）。
- **问数侧消费**：
  - `DataDynamicContextMiddleware` 概览的 Cube 清单段后追加「视图清单」：名称 + 口径描述 + 「可用 wren_run_sql 直接按表名查询」提示（含视图的组才渲染）
  - `WrenToolkit` run_sql 工具描述补一句「已发布语义视图是可查询的逻辑表，按视图名直接 SELECT」
- **REST**（web/api，对齐 modeling 端点风格 + AgentAccessGuard 鉴权）：`GET/POST/DELETE /api/dataset-groups/{groupId}/modeling/views`（页面展示与删除用；对话工具与服务层共用 MdlSuggestionService）。

### M3 — 前端展示（语义建模页）

- SemanticModelingPage form tab 新增「业务视图」卡片（对齐 Cube 卡片）：列表展示 name/description/status/所属基表；展开可看 SQL；删除按钮；顶部提示「对话中可让建模助手创建视图」。
- 业务术语卡片：form tab 顶部追加只读术语列表（复用 listSemanticTerms，展示 term/explanation/synonyms + 跳转语义配置页链接）——对话中新建的术语立即可见。
- `frontend/src/api/semanticModeling.ts` 补 listViews/createView/deleteView。

## 影响面

后端：ModelingToolkit、MdlSuggestionService、MdlPublishService、DataDynamicContextMiddleware、WrenToolkit、DataAgentConfig(MODELING_SCRIPT)、新实体/仓储/web 端点、（术语路由不改 SemanticTermController）。前端：SemanticModelingPage、semanticModeling.ts。文档：ARCHITECTURE_zh.md §5.6/§6、ADR 0027、README 配置表无变化。

## 验收（GWT）

1. Given 建模对话中用户定义「高频用户=近30天访问次数>5的用户」，When 助手提案落 term 且用户同意，Then GET /api/semantic-terms 可见，且新问数会话 SystemMessage 业务术语段包含该条。
2. Given 用户定义 HAVING 级口径「高频用户数」，When 助手调 create_view（用户确认 SQL），Then 语义建模页视图卡片出现该视图。
3. Given 视图已创建，When 用户发布 MDL，Then 发布产物含 views/<name>/metadata.yml 与 sql.yml，wren context validate --strict 通过（view SQL dry-plan）。
4. Given 视图已发布，When 问数「高频用户有多少个」，Then wren_run_sql 按 `SELECT COUNT(*) FROM <view>` 返回正确结果（引擎探针已证）。
5. Given 用户上传文档含「VIP=消费≥10000」等定义，When 用户要求按文档补全语义，Then 助手逐条提案（术语/口径/指标）并给出落点，同意后落库。
6. Given cube 配置了 description，When 发布，Then mdl.json 与前端展示携带该 description（缺口修复）。
7. Given 用户 B 访问用户 A 的组，Then 视图接口与建模工具均不可见 A 的视图（DatasetScope 过滤）。

## 不做的事

- 文档向量化/嵌入式检索（文档仍全文预注入，规则提取靠 LLM）
- 语义术语按组隔离改造（保持全局字典，scope 仅作标注；风险已知：全量注入，量大需另行治理）
- 视图编辑（只增不改 + 删除重建，对齐 Universal Rule 1）
- auto-pilot 免确认模式（保持对话确认制）
- NL→SQL 查询配方库（knowledge/sql 对应物，延后）
- 前端新建视图表单（视图创建只走对话，页面只展示/删除）

## 测试要求

- MdlPublishServiceTest：views 渲染 golden（metadata.yml/sql.yml 全文断言，对齐 distinctCountMeasureRendersCountDistinct 模式）；视图 SQL 非 SELECT 拒绝；CubeSpec description 断言；视图名组内重复拒绝。
- ModelingToolkitTest：create_term/create_view 参数透传 + 描述契约（含「只能引用快照列名」「高爆炸半径须确认」字样），对齐 createCubePreservesDistinctCountAgg 模式。
- MdlSuggestionService 视图越权用例（看不到别人的视图）。
- 中间件概览渲染测试：视图清单段含名称与查询提示；无视图组不渲染。
- 前端 `npm run build` 全绿；后端 `mvn test` 全绿（Spotless 门禁）。
