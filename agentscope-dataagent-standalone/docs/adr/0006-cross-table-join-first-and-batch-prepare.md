# ADR 0006: 跨表查询「JOIN 优先、禁止结果搬运」+ prepare_data_context 批量取表

- 状态：已采纳
- 日期：2026-09-22
- 证据：`logs/LLM-extract.log`（本项目同题 5 轮 LLM 调用全量日志）+ `logs/tcdataAgetn的过程`（TC-DataAgent 页面同题过程），对比场景「有哪些领导访问了，分别访问了哪些模块，哪些报表」（同题同数据）

## 背景

与 TC-DataAgent 同题同数据对比，发现两条过程性差距（最终答案质量不差，差距在稳健性与效率）：

1. **跨表筛选用「结果搬运」而非 JOIN。** 本项目先查出 58 个领导账号，再把 58 个账号字面量
   硬编码进 `IN (...)` 复制进 2 条 SQL，继而又把筛出的 9 个活跃账号复制进另外 2 条 SQL。
   风险是正确性而非仅效率：领导清单 `row_limit=100`，若超过 100 人会**静默截断漏数**；
   58→9 的链式复制任一环出错即污染最终答案；且 IN 列表每轮随历史重发，浪费 token。
   TC 的做法是单条 SQL 内 `JOIN ... ON c.user_account = u.account_name AND u.position='领导'`，
   筛选条件永不离库；并用 CTE 先对维表去重，显式防止同账号多项目行导致的 JOIN 扇出、SUM 膨胀。
2. **schema 获取被「逐步探测」规则拖累。** sql-analysis 技能反模式明确写着
   「❌ 一次性对所有表调用 prepare_data_context」「每次只探查当前步骤必需的表」，
   于是 agent 只 prepare 了 1 张表，另 2 张直接相关的表盲写列名（本次靠同会话前文的
   查询历史兜底才未翻车）。TC 一次 `prepare_data_context` 取回全部 3 张相关表。

根因均不在模型能力，而在引导缺失：系统提示词「数据源规则」与 sql-analysis SKILL.md
全文没有任何跨表 JOIN 约定，且 prepare 工具签名只支持单表。

## 决策

1. **JOIN 优先、禁止结果搬运。** 在系统提示词「数据源规则」（`DataAgentConfig` 的
   `dataagent.agent.sys-prompt` 默认值）新增规则：涉及多张表的筛选/关联，优先在一条
   SQL 内用 `JOIN`/`CTE` 完成；禁止把上一步查询结果作为字面量复制进 `IN (...)`。
   在 sql-analysis SKILL.md 步骤 3 增加 JOIN 约定：多表关联时先用 CTE 对维表（如用户表）
   按关联键去重，再 JOIN 明细表，防止扇出导致聚合值膨胀。
2. **prepare_data_context 支持批量取表。** 工具参数由单表（`table` + `source_id`）扩展为
   支持表数组，一次调用返回多张相关表的 schema；`DataAgentToolkit` 保持逐表可见性校验
   （`visible(scope)`）不变。sql-analysis 第 2 步相应改为「与问题直接相关的 1–3 张表
   一次性 prepare」，删除/改写「逐步探测、每次只探查当前步骤必需的表」及对应反模式。

## 理由与权衡

- 筛选条件留在库内，消除 row_limit 截断导致的静默漏数与链式复制污染，这是正确性收益。
- 减少轮次与 token：同题场景可由 5 轮 LLM / 7 次工具调用收敛到约 3 轮 / 4 次。
- CTE 去重约定把 TC 显式推理出的「防扇出」经验固化为 prompt 规则，不依赖模型临场发挥。
- 代价：批量 prepare 单次返回变长（多表 schema）；仍受 `toolResultEviction` 4000 字符
  驱逐约束，批量返回需注意精简（只回列名+描述，不回大体积样例值）。

## 被否决的替代方案

- 保持单表 prepare、仅靠 prompt 要求多查几次：轮次更多，且仍受「逐步探测」反模式语义牵制。
- 允许 IN 列表搬运但提高 row_limit 上限：治标不治本，token 浪费与复制污染依旧。
- 引入 write_answer 收口工具 / 查询结果 CSV 产物化：本次对比确认有价值（P1/P2），
  但不属于本次决策范围，另行评估。

## 适用与失效条件

适用于所有上传数据集同库（`ds_` 前缀单库）内的跨表查询——`checkCrossTable` 的越权校验
天然允许同可见范围内的多表 JOIN。若未来引入跨库/跨数据源联邦查询，JOIN 优先规则
需按连接器能力重新评估。
