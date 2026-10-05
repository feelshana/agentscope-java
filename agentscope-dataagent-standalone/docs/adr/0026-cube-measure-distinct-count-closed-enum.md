# ADR 0026: Cube 度量去重计数采用封闭枚举 DISTINCT_COUNT

日期：2026-10-01 ｜ 状态：已接受 ｜ 关联规格：docs/specs/010-cube-distinct-count-measure.md

## 背景

999 知识库建模事故：建模助手提案「访问用户数 = COUNT(DISTINCT user_id)」并获用户确认，
但 `ModelingToolkit#create_cube` 的 agg 参数仅有 SUM/AVG/COUNT/MAX/MIN 五选一，DISTINCT
无法表达，LLM 静默降级为 `COUNT(user_id)`（行计数），发布后「访问用户数」与「访问次数」
恒等（40/30/30，去重正确值应为 4/3/3）。9-30 另一知识库同样发生降级——凡建「用户数/
去重数」类指标必然复现。wren-core 引擎侧 `cube_query_to_sql` 对 `COUNT(DISTINCT col)`
表达式直通编译（探针实证），缺口纯在平台建模链路。

## 决策

1. **D1 封闭枚举而非自由表达式**。度量聚合在 `MdlPublishService#AGG_FUNCTIONS` 增加
   `DISTINCT_COUNT`，`measureExpression` 编译为 `COUNT(DISTINCT col)`（含 CASE 口径时为
   `COUNT(DISTINCT CASE WHEN ... THEN col END)`，无 ELSE——被过滤行落 NULL 不计数）。
   不开放自由 SQL 表达式度量（比率/嵌套聚合）：封闭枚举保证校验可判定、前端可枚举、
   LLM 参数面收敛（ADR 0024 D3 同一精神）；比率类需求将来以独立枚举扩展。
2. **D2 校验前移**。`DISTINCT_COUNT` 无列在 `cubeMembers` 本地校验直接拒绝（「去重计数
   （DISTINCT_COUNT），必须指定列」），先于「不存在的列」判定，避免误导向列名修复提示；
   度量类型与 COUNT 同为 BIGINT。
3. **D3 确认口径一致性硬约束**。`create_cube`/`update_cube` 工具描述追加「向用户复述的
   度量口径必须与传入 agg 严格一致——不得在提案中写出 agg 无法表达的口径，也不得在未
   向用户明确说明的情况下静默降级口径」。本次事故的半数损失来自确认环节（用户确认的
   是去重口径，落库是行计数）——工具表达力补齐 + 描述硬约束双保险。
4. **D4 三入口同轮落地**。建模对话（工具描述）、语义建模页表单（前端 `AGG_OPTIONS`
   下拉与 CASE 预览文案）、发布渲染（`measureExpression`）同批修改，杜绝「前端可配但
   发布拒绝」或反向的错位。

## 理由与权衡

- 命名取 `DISTINCT_COUNT` 而非 `COUNT_DISTINCT`/`DISTINCT`：与既有 agg 词法风格一致，
  降低 LLM 与 `COUNT` 混淆的概率。
- 代价：存量 `measures_json` 中语义本应是去重计数的 `COUNT(col)` 不做自动迁移（刻意——
  无法可靠区分「用户想要去重」与「用户就要行计数」，重写交由 `update_cube` 显式完成）；
  CASE 口径 + DISTINCT_COUNT 的语义依赖「NULL 不被 COUNT 计数」，已用测试锁定。
- 明确不做：自由 SQL 表达式度量、hierarchies、MDL 原生 filtered measure、存量批量重写。

## 影响面

- `MdlPublishService`（AGG_FUNCTIONS / cubeMembers / measureExpression / 类型判定）、
  `ModelingToolkit`（create_cube / update_cube 描述与 measures 参数示例）、前端
  `SemanticModelingPage`（AGG_OPTIONS / CASE 预览文案）。
- ARCHITECTURE_zh 5.6 同步；测试守卫：`MdlPublishServiceTest`（渲染 COUNT(DISTINCT) /
  无列拒绝 / CASE 组合 / measureExpression 语法）与 `ModelingToolkitTest`（落库保真 /
  描述契约断言）。
- 存量修复走运营动作：对已发布组用 `update_cube` 改 DISTINCT_COUNT 后在语义建模页重新
  发布（发布即重建该组 wren 实例，下轮查询生效）。

## 关联

- ADR 0024（D3 agent 写实体、D7 剧本硬约束执行层化——本次 D3 是其延续）、ADR 0018 D2
  （类型经 wren 归一化）、ADR 0020（发布即重建实例）
- specs/010-cube-distinct-count-measure.md（实施规格与验收标准）
- 事故证据：logs/LLM-modeling.log（提案 `COUNT(DISTINCT user_id)` 与落库 `agg=COUNT`
  的对照，用户回复 A 确认环节）
