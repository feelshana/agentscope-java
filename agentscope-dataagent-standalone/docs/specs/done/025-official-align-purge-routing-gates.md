# specs/025 — 问数/建模全面对齐 wren 官方形态，撤自研路由门

## 背景

LLM.log 实证（红海库 99701d70）：用户问「9月6日访问量TOP10报表」，agent 先撞 Cube 缺
`time_dimensions`（建模质量问题，引擎正确报错），降级 `wren_run_sql` 直查基础模型时被
`WrenToolkit#preferredViewRouteError` 视图方言门拦截——4 字汉字 n-gram 交集 ≥2 即判
「与视图明显匹配」，而问题词汇（报表访问/访问用户）与视图名（报表访问用户活跃度）天然
重叠，agent 改写措辞 4 次全被拦；视图实际只有「项目名称/活跃度分层」两列，语义上根本
不覆盖该问题。三条路全堵死，会话卡死。

用户拍板：**整个建模与问数阶段完全参照 wrenAI 官方形态，不再自研发挥**。

## 官方形态对照（usage SKILL.md 实读）

官方问数是**纯决策树软引导**，无任何确定性路由门：

- Aggregation decision tree：聚合问题 → `cube list` → `cube describe` 自查覆盖 →
  覆盖则 **prefer** cube query（理由：lower error rate）→ 不覆盖则 raw SQL。
- 官方无「视图路由门」：v5 语义层的 view 不是问数路由对象，usage 全篇无视图强制。
- 官方 SQL rules：「Target MDL model names, not database tables」「Write dialect-neutral
  SQL」——与平台现有约定一致。
- 官方 Things to avoid：「Do not guess model or column names」——与平台一致。
- 错误处理：两层诊断（dry-plan 失败=语义层 / 执行失败=DB 层）+ Fix ONE issue at a time，
  无拦截。

官方建模（enrich-context）：「只增不改」「每次 MDL 编辑立即 validate」「新
cube/view/relationship 高爆炸半径强制问人」——平台 MODELING_SCRIPT 硬约束与之同向
（HITL 比官方 prompt 自律更严），**保留**。

## 拆除清单（自研硬门 + prompt 禁令）

1. `WrenToolkit#preferredViewRouteError` + `viewMatchScore` + `hanNgrams` +
   `referencesLogicalObject`（仅 gate 使用）与 `wrenRunSql` 内调用点：**整体删除**。
2. `WrenToolkit` wren_run_sql 描述（L132 附近）：「必须只按该视图名查询…也不得再 JOIN
   其他逻辑模型」→ 官方式软引导（视图覆盖问题时优先按视图名查，视图口径已经建模审阅）。
3. `WrenToolkit` wren_query_cube 描述（L224 附近）：「必须使用本工具，不得改写为手工聚合
   SQL」→ 官方式（聚合问题先 `wren_cube_list/describe` 自查覆盖，覆盖则优先本工具）。
4. `DataAgentConfig` 数据 agent 剧本第 3 条：「严格按以下顺序路由…必须…不得…」→
   官方式决策树（prefer 语义资产，agent 自查覆盖，不覆盖走逻辑 SQL）。
5. `DataDynamicContextMiddleware` L206-211 路由段 + L286-287 语义视图段：
   同上软化为 prefer 引导。
6. `sql-analysis` SKILL.md：L20 四段决策树的「必须/禁止/不得」措辞、L33「View 必须直接
   出现在 FROM 中」、L67 反模式行 → 官方式决策树措辞。

## 保留清单（官方一致或平台基础设施，非「发挥」）

- SELECT/WITH 限定、禁物理表名（官方 SQL rules 同款）。
- 禁显式 LIMIT/OFFSET：平台工具契约（`limit` 参数 + 引擎统一 append），官方 CLI 无此
  参数形态；属接口设计非语义门。
- 反摸底/计数一致/维表去重等 sql-analysis 写作纪律：提示词层指引，不拦截查询，无死锁
  风险（ADR 0008 的 handbook 层）。
- MODELING_SCRIPT 硬约束（只增不改/写工具纪律/列名不猜/播种只追加/validate 纪律）：
  与官方 enrich-context 同向，HITL 是其平台化强化。
- HITL 写闸、批量关系多选卡（specs/024）、播种、发布闸（validate--strict/build/
  dry-run）、多租户隔离：基础设施，官方无对应物但产品必需。

## 验收

- G1：同一问题（含与视图名词汇重叠的非视图问题）不再被任何确定性规则拦截，agent 可
  自由降级逻辑 SQL。
- G2：剧本/工具描述/技能文档中不再出现「不得从基础模型重建同等语义」「必须…不得改写」
  类强制路由措辞；语义资产引导均为 prefer 措辞。
- G3：WrenToolkitTest 的 gate 用例删除/改写为直通行为；四处测试守卫更新后全量
  mvn test 与 npm build 全绿。

## 排除项

- 不引入官方 memory recall/store（平台会话结构不同，另行评估）。
- 不改建模工具面与 HITL（specs/024 刚交付，用户明确要求的交互）。
- specs/018/ADR 0032 的发布闸（方言黑名单/逐对象 dry-run）是建模期质量防线，不动。
