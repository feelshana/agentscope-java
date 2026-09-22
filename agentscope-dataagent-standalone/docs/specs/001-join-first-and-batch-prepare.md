# spec 001: 跨表查询 JOIN 优先 + prepare_data_context 批量取表

## 背景与目标

与 TC-DataAgent 同题对比（ADR 0006）发现：跨表筛选时 agent 把上一步查询结果（58 个账号）
字面量复制进 `IN (...)`，存在 row_limit 静默截断与链式复制污染风险；且 `prepare_data_context`
仅支持单表，叠加 sql-analysis 的「逐步探测」反模式，导致直接相关的多张表只 prepare 了一张、
其余盲写列名。本 spec 落地 ADR 0006 的两项决策：引导模型「JOIN 优先、禁止结果搬运」，
并让 prepare 工具支持一次批量取多张表。

## 方案概述

三处改动，均为 prompt/工具层，不动主流程：

1. **系统提示词**（`DataAgentConfig` 的 `dataagent.agent.sys-prompt` 默认值「数据源规则」段）新增：
   - 涉及多张表的筛选/关联，优先在一条 SQL 内用 `JOIN`/`CTE` 完成；
     禁止把上一步查询结果作为字面量复制进 `IN (...)`。
   - 多表 JOIN 时先用 CTE 对维表按关联键去重再 JOIN 明细表，防止扇出导致聚合值膨胀。
   - 「工作流程」第 3 步的 prepare 描述改为「一次性确认直接相关的 1–3 张表」。
2. **sql-analysis SKILL.md**（`shared/agents/data-agent/skills/sql-analysis/SKILL.md`）：
   - 步骤 2：「逐步探测，不要一次全查」改为「与问题直接相关的 1–3 张表一次性 prepare」；
     删除「每次只探查当前步骤必需的表」。
   - 步骤 3 的 SQL 约定追加：JOIN 优先、禁止 IN 搬运、维表 CTE 去重防扇出。
   - 反模式：删除「❌ 一次性对所有表调用 prepare_data_context」，
     新增「❌ 把上一步查询结果字面量复制进 IN (...) 做跨表筛选——改用 JOIN/CTE」。
3. **prepare_data_context 批量**（`DataAgentToolkit.prepareDataContext`）：
   - 新增可选参数 `tables`：数组，元素为 `{source_id, table}`，上限 5 张；
     与原 `source_id` + `table` 单表参数并存（传了 `tables` 则以 `tables` 为准）。
   - 逐条独立校验可见性（沿用 `resolveScoped`），失败条目在输出中内联报错、
     不影响其余条目返回；输出按表拼接为多个 `## <label>` 小节。
   - 修正工具描述与签名不符的问题（描述已写「1-3 张」但签名只支持单表）。
   - 批量返回注意精简：只回列名/类型/描述，不回大体积样例值（受 toolResultEviction
     4000 字符驱逐约束）。

无需同步 ARCHITECTURE_zh.md 主流程；仅需更新其「问数工具链」一节中
prepare_data_context 的一行描述。

## 影响面

- 后端：`DataAgentConfig`（sys-prompt 默认值字符串）、`DataAgentToolkit.prepareDataContext`
  （参数与输出拼接）、`DataAgentToolkitTest`
- 前端：无
- 数据：无
- 文档：`shared/agents/data-agent/skills/sql-analysis/SKILL.md`、
  ARCHITECTURE_zh.md「问数工具链」一节一行、实施后把 ADR 0006 状态改为「已采纳」

## 验收标准（Given-When-Then，可测试）

1. Given 用户可见 3 张数据集表，When 一次调用 `prepare_data_context` 传入含 3 个
   `{source_id, table}` 的 `tables` 数组，Then 一次调用返回全部 3 张表的字段信息小节。
2. Given `tables` 数组中混入一个他人（不可见）的 source_id，When 调用，
   Then 该条目位置返回「未知或不 permitted」错误文本，其余可见条目正常返回，
   且不泄露该表的任何列信息。
3. Given 旧的单表参数调用（`source_id` + `table`，无 `tables`），When 调用，
   Then 输出与改动前一致（现有单表测试全部通过）。
4. Given 默认 `dataagent.agent.sys-prompt`（未被配置覆盖），When 应用启动装配，
   Then prompt 文本包含「禁止把上一步查询结果作为字面量复制进 IN」与「JOIN」规则
   （用单测断言字符串包含）。
5. Given `tables` 数组超过 5 张，When 调用，Then 返回错误提示要求精简到 5 张以内，
   不部分执行。

## 不做的事（明确排除项）

- 不改 `query_structured_data` 的结果格式，不做查询结果 CSV 产物化（ADR 0006 注明另行评估）。
- 不新增 `write_answer` 收口工具。
- 不改 `checkCrossTable`、`visible` 等可见性/越权校验逻辑本身。
- 不改 `retrieve_evidence` / `render_chart` / `run_python`。
- 不改 chart-rendering、python-analysis 两个技能。

## 测试要求

- `DataAgentToolkitTest` 新增：批量 3 表成功、批量含越权条目（看不到别人的数据，
  内联报错不影响其余）、批量超 5 张报错、单表参数兼容共 4 个用例。
- 新增 sys-prompt 默认值断言测试（放 `DataAgentConfigTest` 或并入现有 web 配置测试）：
  包含「IN (」「JOIN」「CTE」关键规则文本。
- 验证命令：`mvn spotless:check`、`mvn -Dtest=DataAgentToolkitTest test`。

## 关联

- ADR：[0006-cross-table-join-first-and-batch-prepare](../adr/0006-cross-table-join-first-and-batch-prepare.md)
- 证据：`logs/LLM.log`、`logs/tcdataAgetn的过程`（同题「领导访问分析」对比）
