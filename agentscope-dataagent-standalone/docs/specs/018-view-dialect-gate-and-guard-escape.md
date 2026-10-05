# spec 018: 视图方言门禁、发布试跑与守卫逃生门

> 状态更新（2026-10-05）：本 spec 的载体定位经历一轮回归——ADR 0042 一度把复杂口径
> 改走 ref_sql 派生模型（views/ 降级只读），ADR 0043/specs/035 又回归 view-first：
> 跨表 JOIN/窗口/CTE 类口径一律走 `create_view` 命名视图（对齐官方 sink 决策树），
> ref_sql 降级为纯人工编辑载体。本文的创建期黑名单（已升级为「函数对照表 + 替代
> 写法」三层机制之「堵」）与发布前 dry-run 防线（三层机制之「保」）继续生效。

## 背景与目标

LLM.log（975 行）记录了一次问答会话死锁：建模会话经 `create_view` 生成了 MySQL 方言视图 SQL（`DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)`），`context validate --strict` 与 `context build` 均不解析视图 statement 的函数有效性，坏视图一路发布；随后问数侧守卫强制模型引用该视图（执行报 `Invalid function 'date_sub'`），又拦截基础模型回退与视图+模型 JOIN，`wren_describe_model` 也不认视图名——模型 11 次尝试全部失败后放弃。

三轮 CLI 探针实证（target/dialect-probe2-result.txt、view-func-probe-result.txt）：

- wren 双路径方言行为：模型直查 SQL 走 sqlglot transpile 不做函数绑定（MySQL 函数全放行）；视图 statement 被展开为 CTE 注入每个生成查询后做 DuckDB 规划期函数绑定——**方言约束只存在于视图 statement**。
- `wren dry-run -s "SELECT * FROM <view>"` 能在发布前拦截坏视图（须先 `context build`，dry-run 读 `target/mdl.json`）。
- 视图函数兼容黑名单（DuckDB planner 实证）：不可用 14 个 = YEAR、DATE_SUB、DATE_ADD、ADDDATE、SUBDATE、CURDATE、CURTIME、STR_TO_DATE、GROUP_CONCAT、DATEDIFF、TIMESTAMPDIFF、IF、LAST_DAY、UNIX_TIMESTAMP；可用 = `日期 ± INTERVAL n unit`、NOW()、CURRENT_DATE、DATE_FORMAT、SUBSTRING_INDEX、IFNULL、FROM_UNIXTIME。注意 YEAR 失败而 DATE_FORMAT 可用——黑名单必须实证化，不能凭直觉。

平台数据源按用户约束只考虑 MySQL 与 Doris（两者函数集高度重合，黑名单同时覆盖）。目标：坏视图在创建时被拒、在发布前被拦；存量坏视图在问数侧有逃生门；建模关联图信息正确（节点=物理表名+中文名、边=关联字段）；对话建模待确认卡视觉突出。

## 方案概述

四道防线补齐 + 前端展示修正：

1. **创建时门禁**（MdlSuggestionService#requireViewSql）：SELECT/WITH 校验后追加函数黑名单检查——词边界正则（`(?i)\b函数名\s*\(`，防误伤列名）、命中即 400 拒绝，错误消息中文并给出改写建议（如 DATE_SUB → `col - INTERVAL 30 DAY`、YEAR → `DATE_FORMAT(col, '%Y')`、IF → `CASE WHEN`、GROUP_CONCAT → `STRING_AGG`）。
2. **工具描述方言声明**（ModelingToolkit create_view/update_view）：@Tool description 声明"视图 SQL 由 wren 内置 DuckDB 引擎执行，禁止 MySQL/Doris 专有函数"并列出改写指引。
3. **发布前试跑**（MdlPublishService）：`validate()` 在 `validate --strict` 后追加 `context build` + 逐视图 `dry-run -s 'SELECT * FROM "<view>"'`；`doPublish()` 在 build 成功后同样逐视图 dry-run；失败解析为 error issue（来源 wren），发布中止保留旧快照。
4. **守卫逃生门与放宽**（WrenToolkit）：
   - `preferredViewRouteError`：SQL 含 `/* view-fallback */` 注释标记时整体跳过（逃生门——视图损坏时模型可显式降级）；
   - 删除第二道拦截（引用视图后不得再 JOIN 其他逻辑模型）——视图健康时视图+基础模型 JOIN 是合法的补维度手段，损坏时它是唯一活路；
   - `wrenRunSql` 执行失败且 SQL 引用了某已发布视图、错误含 `Invalid function`/`SQL_PLANNING` 时，附加降级指引（加 view-fallback 标记 + 建议用户 update_view 修复并重新发布）；
   - `wrenDescribeModel` 支持视图名：model_names 逐个先查逻辑模型、再查已发布视图，命中视图则渲染名称/描述/完整 SQL。
5. **前端**（语义建模页 + 对话建模）：
   - 后端 `MdlPublishService.MdlModelView` 增 `tableName` 字段（ModelSpec 已有，view() 漏下发）；
   - MdlGraphView 节点 label 改两行「物理表名 + 数据集中文名」，边 label 改为关联字段对（`user_id = user_account`，复合键用「、」连接），连接类型移入 tooltip/图例说明；
   - ModelingHitlCard relation 卡迷你图同步（节点表名+中文、边关联字段），卡片视觉强化（加粗主色边框、更醒目 badge、左侧色条）；
   - ModelingChatPanel 在 awaitingDecision 时于输入区上方显示醒目「待确认」横条；
   - 前端 MdlView 类型补 `views`（后端已返回、前端漏声明）与 `tableName`。

主流程变更：需同步 ARCHITECTURE_zh.md 问数工具链与语义建模相关章节。

## 影响面

- 后端：MdlSuggestionService（黑名单）、ModelingToolkit（工具描述）、MdlPublishService（validate/doPublish dry-run、MdlModelView.tableName）、WrenToolkit（守卫/逃生门/失败指引/describe 视图）
- 前端：semanticModeling.ts（类型）、MdlGraphView.tsx、ReadOnlyRelationGraph.tsx（节点尺寸/边 label）、ModelingHitlCard.tsx、ModelingChatPanel.tsx
- 数据：无（无新表无迁移）
- 文档：ARCHITECTURE_zh.md 对应章节、ADR 0032

## 验收标准（Given-When-Then，可测试）

1. Given 视图 SQL 含 `DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)`，When createView/updateView，Then 400 拒绝且消息含改写建议「INTERVAL」字样。
2. Given 视图 SQL 含列名 `year`（如 `SELECT year FROM t`），When createView，Then 通过（词边界不误伤）。
3. Given 草稿含视图且 staging 上 dry-run 返回 exit 1，When validate/publish，Then 结果 issues 含「视图「x」试跑失败」error 且发布中止。
4. Given 已发布坏视图、SQL 从基础模型重建且带 `/* view-fallback */`，When wren_run_sql，Then 不被守卫拦截，正常下发引擎。
5. Given SQL 引用视图且同时 JOIN 基础模型（无 fallback 标记），When wren_run_sql，Then 不再被第二道拦截拒绝。
6. Given SQL 引用视图执行返回 Invalid function，When wren_run_sql，Then 错误文本附加含「view-fallback」的降级指引。
7. Given 已发布视图 high_frequency_users，When wren_describe_model 传该视图名，Then 返回视图名/描述/SQL 而非「未知或无权访问」。
8. Given MDL 视图页有 2 模型 1 关系（user_id 关联），Then 图节点显示两行（物理表名+中文名）、边 label 显示 `user_id = ...` 字段对。
9. Given 对话建模出现待确认提案，Then HITL 卡带加粗主色边框与左侧色条，输入区上方显示「待确认」横条且输入禁用。

## 不做的事（明确排除项）

- 不扩大外部数据源类型支持（当前仅 MySQL；Doris 仅作为黑名单方言设计输入，不新增 Doris 连接能力）。
- 不修改 wren 引擎/CLI 本体，不引入 SQL transpile 预处理层（黑名单+dry-run 已覆盖实证风险）。
- 不做对话内发布动作；修复存量坏视图仍走 update_view + 页面发布。
- 不改 Cube/关系/术语的既有校验逻辑。

## 测试要求

- MdlSuggestionServiceTest：黑名单命中（DATE_SUB/GROUP_CONCAT/IF）、词边界不误伤（列名 year、my_date_sub）、大小写混合、updateView 同样拦截。
- MdlPublishServiceTest：validate 中 dry-run 失败产生 issue；publish 中 dry-run 失败中止发布（verify 快照未替换）。
- WrenToolkitTest：改写 matchingPublishedViewRejectsAdditionalLogicalModelJoin 为放行断言；新增 fallback 标记跳过守卫、视图执行失败附加指引、describe 视图名三个用例；保留既有租户隔离用例。
- 前端 npm run build 零错误。

## 关联

- ADR：0032-view-dialect-gate-and-guard-escape.md（实施后）
- 前序：specs/011（语义视图）、specs/013（对话建模）、specs/017（HITL 与问数路由）
