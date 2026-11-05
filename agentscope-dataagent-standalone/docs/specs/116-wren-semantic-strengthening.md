# spec116：让 Wren 承担语义——升级 0.17.1、指标、取值与同义词、基准评测

> 状态：暂缓（2026-11-05 用户决定先不做）；草案保留，重启时再确认"待确认"各项。决策见 [ADR0136](../adr/0136-metrics-as-cubes-and-wren-0-17.md)（提议）。

## 背景与目标

继续使用 Wren（2026-11-05 用户决定），但现在的语义大多停留在"写给模型看的文字"（业务规则），引擎真正执行的只有名称翻译、查询边界和计算字段，所以 Wren 显得没有作用。本 spec 把能交给引擎的语义交给引擎，把引擎做不到的部分结构化后经 Wren 的工具送到模型面前，并建立基准评测，用数字证明每一步是否有效。

阻碍：指标（Cube）卡在 Wren 0.15 读取中文 Cube 元数据的 UTF-8 问题（ADR0128 第 8 条），0.16.0 已修复，当前最新为 0.17.1（2026-10-30）。

受益人：问数用户（同一指标多次问答案一致、过滤条件更准），数据开发（改动效果可量化）。

## 方案概述

分四部分，按顺序实施；第 1 部分是其余部分的前提。

**1. 升级 Wren 0.15.0 → 0.17.1 并回归**
- 本地与 66 的 Python 环境、同版本官方技能（`generate-mdl`、`enrich-context`、`usage`）一并升级；对比技能正文差异，更新平台对官方流程的调整说明。
- 回归：建模（validate、build、dry-plan）、发布（编译、EXPLAIN、检索索引）、问数（`run_sql`、`query_cube`、`recall_queries`、`get_instructions`）、时间门禁、Windows 中文路径与文件编码、MySQL 8 与 Doris。
- 0.17.0 的 Ossie 改名、MCP SDK 版本锁定逐项确认不影响我们。

**2. 指标成为一等语义资产（Cube）**
- 资产轮次开放 `cubes/`：数据开发用业务语言说"销售额怎么算"，或问数优化发现"同一指标被反复计算"时，助手起草 Cube（度量、维度、时间维度，`base_object` 为逻辑模型）。
- 问数优化可提议 `kind=metric`（ADR0133 只排除 View，指标不在排除之列）。
- 核查与验证沿用查询模板的流程：描述按小节写；验证用 `cube query --sql-only` 展开 SQL，人工确认，不执行；发布前逐项核查（ADR0128）。
- 时间门禁：Cube 依赖受限事实表时，`query_cube` 必须带范围合规的 `time_dimension`，否则拒绝（spec111 验收 13）。
- 问数：官方 usage 技能已优先 `query_cube`；问题命中某个指标时，平台在上下文提示该指标名，要求用 `query_cube`，或照抄其度量表达式。
- 已有规则里的指标定义：提供"转为指标"的页面操作，由数据开发逐条确认后起草 Cube，规则原文保留并注明"已由指标 X 取代"。

**3. 取值与同义词进入 MDL，经 Wren 工具送达**
- 列的 properties 增加两个平台约定字段（引擎忽略，`get_mdl`、`describe_model` 可读）：
  - `values`：低基数文本列的完整取值。上传表在上传时由平台直连统计（同 ADR0135 的日期范围）；外部表只用采样中出现的值，并标注"采样取值，非完整枚举"。
  - `synonyms`：列和模型的同义词，由建模助手起草、用户在页面维护；`naming.md` 只保留跨表的复杂术语。
- 问数时平台用问题中的词匹配取值与同义词，把命中项（"华东 → 演示客户.region = '华东'"）写进本轮上下文，不改写 SQL，不做路由。

**4. 基准评测**
- 基准问题集 = 已确认问答 + 问数优化中的问题（带已确认 SQL）。
- "评测"操作：对每题按当前草稿重新生成 SQL（复用试问轮次，只用 dry-plan），与已确认 SQL 比较 dry-plan 展开后的结构（表、关联、过滤、分组、聚合、指标），分为一致、不一致、生成失败，给出准确率和逐题差异。
- 发布前展示"本次改动后基准准确率"及变差的题目；不阻止发布。
- 执行结果比较默认关闭；是否对上传表开放执行，作为待确认项（见"待确认"）。

## 影响面

- 后端：Wren 版本与技能目录；`WrenProjectAccess`（资产轮次开放 `cubes/`）、`OfficialWrenToolkit`（`cube query --sql-only`）、`SemanticAssetService`、`AssetProposalStore`（`metric`）、`ModelingTurnRules`；`WrenScopedMcpClient`（`query_cube` 门禁）；`DatasetService`（取值统计）、问数上下文中间件（取值与同义词命中）；新增基准评测服务与接口。
- 前端：公共视图页增加指标；问数优化方案表支持指标；列编辑支持同义词；新增评测结果面板与发布前提示。
- 数据：不新增数据库表；取值、同义词写入 MDL YAML；评测结果存工程 `.platform/benchmark.json`。
- 文档：ADR0136；ARCHITECTURE 第 14 章；AGENTS 第 5、6 节；部署指南（Wren 升级步骤）。

## 验收标准（Given-When-Then）

1. Given 升级到 0.17.1，When 跑完回归清单，Then 建模、发布、问数、时间门禁与升级前行为一致，中文模型名的 Cube 能 validate、build、`cube query --sql-only`。
2. Given 数据开发说"净销售额 = 实付减退款，按下单时间统计"，When 资产轮次起草，Then 生成 Cube，`cube query --sql-only` 展开的 SQL 由人确认后才能发布。
3. Given 已发布净销售额指标，When 问数"上月各地区净销售额"，Then 问数助手使用 `query_cube` 或照抄度量表达式；同一问题问 3 次，SQL 的度量部分一致。
4. Given 指标依赖受限事实表，When `query_cube` 不带合规的 `time_dimension`，Then 被门禁拒绝。
5. Given 上传含"地区"列的表，When 上传完成，Then 该列 properties 有完整 `values`；外部表只有采样取值并标注。
6. Given 问"华东金卡会员的订单数"，When 问数，Then 上下文提示"华东 → region""金卡会员 → member_level"，SQL 过滤列正确。
7. Given 基准集 10 题，When 点"评测"，Then 展示准确率和逐题差异；人为改坏一条规则后再评测，相关题目显示为不一致。
8. Given 发布前，When 基准准确率比上次低，Then 发布对话框列出变差的题目，仍允许发布。

## 不做的事

- 不替换 Wren，不自建查询引擎，不改写官方 MDL 语义；新增 properties 只作为说明，不影响引擎行为。
- 不做行级权限、预聚合、公共视图提炼（另立 spec）。
- 不让指标默认过滤自动生效于所有问题；Cube 是否默认使用仍按 `## Canonical tables` 规则（ADR0129）。
- 评测不执行 SQL（除非"待确认"中另行同意）。

## 待确认

- 实施前先验证：0.17.1 中 `get_mdl`、`describe_model` 是否原样返回列 properties 中的自定义字段；不返回时改由平台上下文提供。
- 实施前先验证：View 作为 Cube 的 `base_object`（spec111 P4）。
- 外部表取值：是否允许平台在关联时对低基数列做一次去重统计（目前外部表只采样，不在建模阶段查询）。
- 评测是否对上传表开放执行比较。

## 测试要求

- 单元：资产轮次 `cubes/` 写入边界（其他轮次拒绝）、`metric` 提议流转、`query_cube` 时间门禁、取值统计（含高基数列跳过）、同义词与取值匹配、评测结构比较。
- 多租户：评测与取值只读当前用户有权限的知识库；排队期间撤权时拒绝。
- 真实验收：在演示知识库按验收 1–8 实测，以重启后日志为准；66 升级后重跑新手任务与一次问数。
- 全量运行 AGENTS.md 第 8 节验证命令。

## 关联

- ADR：[ADR0136](../adr/0136-metrics-as-cubes-and-wren-0-17.md)（提议）；相关 ADR0106、ADR0107、ADR0128、ADR0129、ADR0133、ADR0135
- spec：[spec111](111-conversational-semantic-assets.md) P4、[spec114](114-query-coach.md)
- 业界参考：dbt MetricFlow、Cube.dev、Snowflake Cortex Analyst（同义词、样例取值、已验证查询）、Databricks Genie 基准测试
