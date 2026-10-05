# ADR 0032: 视图方言门禁、发布前 dry-run 与守卫逃生门

- 状态：已采纳（实施规格 specs/018）
- 日期：2026-10-03
- 来源：LLM.log 问答死锁事故复盘 + 三轮 wren CLI 探针实证

## 背景

一次问数会话的完整死锁链：建模侧 `create_view` 写入 MySQL 方言视图 SQL
（`DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)`）→ `context validate --strict` 与
`context build` 均不解析视图 statement 的函数有效性，坏视图通过发布 → 问数侧守卫
（`WrenToolkit#preferredViewRouteError`）强制模型引用该视图，执行报
`Invalid function 'date_sub'` → 基础模型回退被守卫第二道拦截 → 视图+模型 JOIN 被
同一道拦截 → `wren_describe_model` 不认视图名 → 模型 11 次尝试全部失败后放弃。

三轮探针实证（target/dialect-probe2-result.txt、view-func-probe-result.txt）确立两个
此前未知（且与既有认知相反）的引擎行为：

1. **wren 双路径方言行为**：模型直查 SQL 经 sqlglot transpile、不做函数绑定（MySQL
   函数全部放行，dry-plan 实测 exit 0）；视图 statement 被展开为 CTE 注入每个生成查询后
   做 DuckDB 规划期函数绑定。方言约束只存在于视图 statement——因此防线应聚焦视图写入与
   发布链路，而非模型直查路径。
2. **`context validate --strict`/`context build` 均不校验视图 statement 函数有效性**
   （推翻此前「validate 内含 view SQL 校验、写错发布即失败」的认知）；`dry-run -s
   "SELECT * FROM <view>"` 读 `target/mdl.json`（须先 build）才能在规划期拦截。

## 决策

1. **D1 黑名单实证化，只拦「函数」不拦词**：`MdlSuggestionService#requireViewSql` 追加
   14 个 DuckDB planner 实证不可用函数的黑名单（YEAR、DATE_SUB、DATE_ADD、ADDDATE、
   SUBDATE、CURDATE、CURTIME、STR_TO_DATE、GROUP_CONCAT、DATEDIFF、TIMESTAMPDIFF、IF、
   LAST_DAY、UNIX_TIMESTAMP），词边界 + `(?i)` + 后随 `(` 匹配（防误伤 `year` 列名），
   命中即 400 并附中文改写建议（DATE_SUB → `col - INTERVAL 30 DAY`、YEAR →
   `DATE_FORMAT(col, '%Y')`、IF → `CASE WHEN`、GROUP_CONCAT → `STRING_AGG` 等）。
   黑名单按 MySQL/Doris 双方言设计（平台数据源仅此两类，函数集高度重合）；YEAR 失败而
   DATE_FORMAT 可用的实证结果说明黑名单不能凭直觉推导，只能来自探针。
2. **D2 发布前逐视图 dry-run 试跑**：`validate()` 在 validate --strict 后追加
   `context build` + 逐视图 `dry-run -s "SELECT * FROM \"<view>\""`；`doPublish()` 在
   build 成功后同样试跑；任一失败解析为 error issue（source=wren，前缀「视图「x」试跑
   失败」），发布中止保留旧快照（延续 ADR 0019 D5 的失败语义）。
3. **D3 守卫逃生门 = SQL 注释标记**：SQL 含 `/* view-fallback */` 时
   `preferredViewRouteError` 整体跳过。选注释标记而非开关参数：对模型零参数负担、语义
   自文档化、不进引擎（wren 会剥离注释）、且只在模型显式声明降级意图时生效。
4. **D4 删除守卫第二道拦截**：原「引用视图后不得再 FROM/JOIN 其他逻辑模型」一刀切拒绝
   改为放行——视图健康时「视图 JOIN 基础模型补维度」是合法查询（wren 原生支持），视图
   损坏时它是模型唯一活路；语义漂移风险由提示词与第一道拦截（未引用视图时强制）继续
   把守。第一道拦截保留不动。
5. **D5 执行失败附降级指引**：`wrenRunSql` 失败且 SQL 引用了某已发布视图、错误含
   `Invalid function`/`SQL_PLANNING` 特征时，错误文本附加指引：可用基础逻辑模型重建
   等效查询并加 `/* view-fallback */` 标记绕过路由强制，同时建议告知用户 `update_view`
   修复并重新发布。坏视图从「死锁终点」变为「有出口的降级路径」。
6. **D6 describe_model 兼认视图名**：model_names 逐个先查逻辑模型、再查已发布视图；
   命中视图渲染名称/描述/完整 SQL（视图无列元数据，SQL 即口径）。
7. **D7 关联图与待确认 UI 修正**：`MdlModelView` 增 `tableName`（ModelSpec 已有、
   view() 此前漏下发）；MdlGraphView 与 HITL 卡迷你图节点改两行「物理表名 + 数据集中文
   名」、边 label 改关联字段对（复合键「、」连接），连接类型移入 tooltip；HITL 卡视觉
   强化（加粗主色边框、左侧色条），ModelingChatPanel 待确认时输入区上方加醒目横条。

## 理由与权衡

- 黑名单 vs 通用 transpile：不引入 sqlglot 等转译层——14 函数黑名单覆盖实证全部风险，
  转译层会带来新依赖与语义漂移风险；黑名单外的新方言函数由 D2 dry-run 兜底（发布前
  真实规划期拦截），双层防御。
- dry-run 代价：每视图一次子进程（发布低频、视图数少），换取「发布即可用」的硬保证；
  与 validate --strict 同在 staging 目录跑，行为与发布后运行时一致。
- 逃生门安全性：`/* view-fallback */` 只是绕过路由守卫，不绕过 EXPLICIT_LIMIT、
  SELECT-only、租户/权限校验——安全边界不变。
- 删第二道拦截的残留风险：视图（已聚合）JOIN 明细模型可能语义错。权衡：死锁（用户
  完全无答案）比偶发语义偏差（答案仍可解释）危害更大，且 D1/D2 已在源头消灭坏视图。
- 明确不决策项：Doris 数据源连接能力（本次仅作黑名单设计输入）、wren 引擎侧方言适配、
  视图列级元数据推导。

## 影响面

- 修改：`MdlSuggestionService`（requireViewSql 黑名单）、`ModelingToolkit`
  （create_view/update_view 描述方言声明）、`MdlPublishService`（validate/doPublish
  dry-run、MdlModelView.tableName）、`WrenToolkit`（守卫逃生门、删第二道拦截、失败
  指引、describe 视图）、前端 `semanticModeling.ts`/`MdlGraphView`/
  `ReadOnlyRelationGraph`/`ModelingHitlCard`/`ModelingChatPanel`。
- 测试：MdlSuggestionServiceTest（黑名单/词边界/大小写）、MdlPublishServiceTest
  （dry-run 失败中止发布）、WrenToolkitTest（守卫改写 + 逃生门 + describe 视图）。
- 文档：ARCHITECTURE_zh.md 问数工具链与语义建模章节、specs/018。
