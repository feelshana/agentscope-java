# spec 010: Cube 度量支持去重计数（DISTINCT_COUNT）

## 背景与目标

知识库「999」建模事故：建模助手提案并获用户确认的度量口径为「访问用户数 = COUNT(DISTINCT user_id)」，但 `create_cube` 的 agg 参数仅有五选一枚举（SUM/AVG/COUNT/MAX/MIN），无法表达 DISTINCT，LLM 静默降级为 `COUNT(user_id)`（行计数），发布后查询返回 40/30/30（正确应为去重 4/3/3，10 倍虚高）。9-30 另一知识库建模同样发生降级——凡建「用户数/去重数」类指标必然复现。wren-core 引擎侧已探针验证支持 `COUNT(DISTINCT col)` 表达式（编译直通）。

目标：度量口径新增封闭枚举值 `DISTINCT_COUNT`（编译为 `COUNT(DISTINCT col)`），打通「建模工具 → 发布校验/渲染 → 前端表单」三处，并在建模工具描述中加「复述口径必须与实参一致」的硬约束，杜绝确认口径与落库口径静默不一致。

## 方案概述

1. **发布渲染**（`MdlPublishService`）：
   - `AGG_FUNCTIONS` 白名单加入 `DISTINCT_COUNT`；
   - `measureExpression` 无 CASE 分支：`DISTINCT_COUNT` 拼为 `COUNT(DISTINCT <col>)`；有 CASE 分支与 COUNT 同型（`COUNT(DISTINCT CASE WHEN <filter> THEN <col> END)`，不带 ELSE——NULL 被忽略，语义正确）；
   - 成员类型判定：`DISTINCT_COUNT` 与 COUNT 一律 BIGINT；
   - 校验：`DISTINCT_COUNT` 且列名为空时发布校验报错（提示「去重计数必须指定列」，区别于 COUNT 的 `COUNT(*)` 特例）。
2. **建模工具**（`ModelingToolkit`）：
   - `create_cube` / `update_cube` 的 agg 描述扩为六选一，注明 `DISTINCT_COUNT`＝去重计数（COUNT(DISTINCT col)），常用于「用户数/活跃用户数」类指标；
   - 描述追加硬约束：「向用户复述的度量口径必须与传入 agg 严格一致；不得在提案中写出工具无法表达的口径（如 COUNT(DISTINCT)），也不得在未说明的情况下降级口径」。
3. **前端表单**（`SemanticModelingPage`）：
   - `AGG_OPTIONS` 增加 `DISTINCT_COUNT`（下拉自动带出）；CASE 口径预览文案对 DISTINCT_COUNT 渲染为 `COUNT(DISTINCT CASE WHEN ...)`；
   - `parseMeasure` 对存量数据 agg 缺省行为不变（回退 SUM）。
4. **同步文档**：`ARCHITECTURE_zh.md` 语义建模/Cube 度量小节补充 DISTINCT_COUNT 说明。

数据流不变：agg 值仍存于 `measures_json`（JSON 文本，向后兼容，无 JPA 迁移），发布期经 `cubeMembers` 校验并渲染进 `cubes/<name>/metadata.yml` 的 expression。

## 影响面

- 后端：`dataset/MdlPublishService`（AGG_FUNCTIONS、cubeMembers 校验、measureExpression、类型判定）、`tools/data/ModelingToolkit`（create_cube/update_cube 描述）
- 前端：`pages/configure/SemanticModelingPage.tsx`（AGG_OPTIONS、CASE 预览文案）
- 数据：无迁移（measures_json 为 JSON 文本）
- 文档：ARCHITECTURE_zh.md 对应小节
- 存量数据：已有 Cube 不受影响；本库「999」的「访问日志分析」在能力落地后经 `update_cube` 改 DISTINCT_COUNT 并重新发布（运营动作，不属代码验收）

## 验收标准（Given-When-Then）

1. Given 度量 `{"name":"访问用户数","column":"user_id","agg":"DISTINCT_COUNT"}`，When 发布 MDL，Then `cubes/<name>/metadata.yml` 中该度量 expression 为 `COUNT(DISTINCT user_id)` 且 type 为 BIGINT。
2. Given agg=`DISTINCT_COUNT` 且列名为空，When 发布校验，Then 校验失败并提示去重计数必须指定列。
3. Given agg=`DISTINCT_COUNT` 且带 caseFilter，When 发布 MDL，Then expression 为 `COUNT(DISTINCT CASE WHEN <filter> THEN <col> END)`。
4. Given 建模会话调用 `create_cube` 传 DISTINCT_COUNT，When 落库，Then `measures_json` 原样保留该 agg 值。
5. Given 打开语义建模页编辑度量，When 展开聚合函数下拉，Then 可选 DISTINCT_COUNT，选择后保存回读不丢失。
6. Given `create_cube`/`update_cube` 工具描述，Then 包含「复述口径必须与实参一致 / 不得静默降级」约束文本。

## 不做的事（明确排除项）

- 不做自由 SQL 表达式度量（比率、嵌套聚合等）——仍维持封闭枚举，扩能力走后续 spec；
- 不做 hierarchies、filtered measure（MDL 原生 CASE WHEN 进 expression）等 WrenAI 高级特性；
- 不做已发布 Cube 的自动迁移或批量改写——存量度量定义保持原样；
- 不改 wren 通道（WrenToolkit）与 text2sql 直查通道的查询侧行为。

## 测试要求

- `MdlPublishServiceTest`：验收 1-3 各一用例（渲染 COUNT(DISTINCT)、无列报错、CASE 组合），并覆盖既有 agg 五选一回归；
- `ModelingToolkitTest`：`create_cube`/`update_cube` 传 DISTINCT_COUNT 的落库用例（验收 4）、工具描述约束文本断言（验收 6）；
- 前端 `npm run build` 通过（验收 5 属人工验收 + build 冒烟）；
- 不涉及多租户/越权逻辑变更，无需新增隔离用例。

## 关联

- ADR：实施后补一条——度量口径采用封闭枚举（DISTINCT_COUNT）而非自由表达式字符串（表达力 vs 校验安全/LLM 约束的取舍）；
- 事故证据：logs/LLM-modeling.log（提案 COUNT(DISTINCT user_id) 与落库 agg=COUNT 的对照）。
