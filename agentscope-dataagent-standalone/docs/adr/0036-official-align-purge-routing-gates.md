# ADR 0036 — 问数/建模全面对齐 wren 官方形态，撤确定性路由门

日期：2026-10-04 · 状态：Accepted · 取代：ADR 0032 的问数路由门部分（发布闸部分仍有效）

## 背景

红海库（99701d70）问数实证：用户问「9月6日访问量TOP10报表」，Cube 因建模缺
`time_dimensions` 无法带日期过滤（引擎正确报错），agent 降级 `wren_run_sql` 直查基础模型时，
被 `WrenToolkit#preferredViewRouteError` 视图方言门拦截。该门以「问题文本 + SQL」与视图
名/描述的 4 字汉字 n-gram 交集 ≥2 判定「明显匹配」——本问题词汇（报表访问/访问用户）与
视图名（报表访问用户活跃度）天然重叠，agent 改写措辞 4 次全部被拦；而该视图只有
「项目名称/活跃度分层」两列，语义上根本不覆盖此问题。三条路（cube/基础模型/视图）全堵死，
整会话死锁。

## 决策

整个建模与问数阶段**完全参照 wrenAI 官方形态**，平台不再自研路由护栏（用户拍板「官方是
什么样就必须什么样」）：

1. 删除 `WrenToolkit#preferredViewRouteError` 及其配套（`viewMatchScore`/`hanNgrams`/
   `referencesLogicalObject`）。
2. 数据 agent 剧本（`DEFAULT_AGENT_SYS_PROMPT`）、动态目录说明
   （`DataDynamicContextMiddleware`）、`sql-analysis` 技能与 `wren_run_sql`/`wren_query_cube`
   工具描述中的强制路由措辞（「必须…不得改写」「不得从基础模型重建同等语义」「View 必须
   直接出现在 FROM 中」）全部改为官方式 **prefer 软引导**（与官方 usage 技能 Aggregation
   decision tree 同构：聚合问题先 `cube list/describe` 自查覆盖，覆盖则 preferred cube
   query——理由 lower error rate；不覆盖则 raw SQL；错误走两层诊断修复，无拦截）。
3. 保留与官方一致或属平台基础设施的约束：SELECT/WITH 与禁物理表名（官方 SQL rules 同款）、
   LIMIT/OFFSET 工具契约、反摸底/计数一致写作纪律（提示词层指引，不拦截）、建模期
   MODELING_SCRIPT 硬约束（与官方 enrich-context「只增不改/每次编辑立即 validate/高爆炸
   半径强制问人」同向，HITL 是其平台化强化）、发布闸（validate --strict → build →
   逐视图 dry-run → 逐 cube --sql-only）与多租户校验。

## 理由（trade-off）

- 官方形态是「软引导 + 错误回喂重写」，永不产生确定性死锁；代价是 LLM 可能绕开语义资产
  造成口径漂移。平台 v1 用「绝不漂移」换「可能卡死」，本次死锁实证卡死代价更高——漂移
  可事后治理（建模期 enrich 校准视图描述），死锁则直接终结会话。
- n-gram 词面匹配的结构性缺陷无法用补丁消除（列覆盖检查、逃生阀都是新的自研复杂度，
  用户已明确否决继续自研发挥）。
- 语义资产复用的正确激励是资产质量本身：视图/Cube 描述写清口径与适用问题（官方
  enrich-context 生成的描述自带「直接用视图名查询统计数据」），模型自然 prefer。

## 被否方案

- 门上加逃生阀（同会话拦截 N 次后放行）——仍是确定性拦截，且把复杂度推给调试；
- 门上加列覆盖检查（SQL 引用列 ⊆ 视图输出列才拦）——需解析 SQL 标识符并暴露视图输出
  列，自研负担与误判面继续扩大；
- 仅软化 prompt 保留硬门——prompt 与门行为不一致，模型更困惑。

## 影响面

- `WrenToolkit`（约 -90 行）、`DataAgentConfig`、`DataDynamicContextMiddleware`、
  `sql-analysis/SKILL.md` 及四个测试文件（gate 用例删除、断言改 prefer 措辞并加
  doesNotContain 官方对齐守卫）。
- 行为变化：视图不覆盖但词面相似的问题不再被拦截，agent 可自由降级逻辑 SQL；语义漂移
  风险回归官方水位，由建模期描述质量与 sql-analysis 反模式清单对冲。
- specs/018/ADR 0032 的发布期防线（方言黑名单、逐对象 dry-run）不受影响。

## 验证

全量 `mvn test`、`npm run build` 全绿；守卫测试断言四个事实源均不含强制路由措辞。
