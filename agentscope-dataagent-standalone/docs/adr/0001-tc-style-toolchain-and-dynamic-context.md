# ADR 0001: 问数工具链采用 TC 式「上下文预注入 + 四工具」而非「工具逐步发现」

- 状态：已采纳
- 日期：2026（重写工具链时）

## 背景

上游 agentscope 示例的问数工具是 `list_data_sources` / `describe_table` / `run_sql_preview`：
模型需要先调 `list_data_sources` 发现数据源，再 `describe_table` 探 schema，再查询。
每次问答至少 2-3 轮工具调用才能拿到 schema，且 `describe_table` 实时查库（COUNT + 采样 5 行），
慢且上下文噪音大。

## 决策

改为腾讯 TC-DataAgent 模式：

1. `DataDynamicContextMiddleware` 在每轮 reasoning 前，按 `DatasetScope` 重建
   `[DATA_SOURCES_OVERVIEW]`（数据源+表名+AI 描述）与 `[KNOWLEDGE_BASE_OVERVIEW]`
   （知识文档+语义术语），直接追加进 system prompt。
2. 工具集缩减为四个：`prepare_data_context`（查元数据确认列细节，不查库）、
   `query_structured_data`（SELECT/WITH 只读查询）、`retrieve_evidence`（知识检索）、
   `render_chart`（服务端自动推断图表类型）。
3. 字段级描述在数据集导入/关联时由 AI 一次性生成（`SchemaGenerationService`）并存入
   `columnSchemaJson`，运行时零数据库开销。

## 理由与权衡

- 省 token、省轮次：典型问答从「3 次工具调用才开始查数」变为「首次调用即正式查询」。
- 描述质量更高：AI 生成的业务描述 + 低基数维度值样例，比裸 schema 更能防止列名幻觉。
- 代价：prompt 变长（每轮注入概览）；数据集变更需重建 registry（已通过
  `DatasetService` 在各变更点 `registry.add/remove` 解决）。

## 被否决的替代方案

- 保留 describe_table 实时探查：慢、上下文噪音大，且与 AI 预生成描述重复。
- RAG 向量检索 schema：引入向量库成本高，当前规模（每用户数十表）全量注入更简更准。

## 适用与失效条件

适用于单用户可见表量级在数十张以内。若未来单租户表数超过 prompt 承载能力，
需重新评估为「概览注入 + 按需检索」混合模式。
