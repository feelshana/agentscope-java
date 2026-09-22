# ADR 0004: 知识图谱采用双通道抽取（schema 走确定性规则，文档走 LLM）

- 状态：已采纳
- 日期：2026（知识图谱功能落地时）

## 背景

知识图谱用于 Schema Linking：把业务词/指标名映射到 table.column、口径与 JOIN 提示。
构建来源有两类：数据集 schema（结构化）与知识文档（非结构化）。

## 决策

1. 数据集 schema → `SchemaTripleExtractor`：确定性规则生成三元组（表/字段/主外键等），
   不调用 LLM。
2. 知识文档 → LLM 抽取实体与关系（固定 SYSTEM_PROMPT + 本体约束，JSON 输出，失败重试一次）。
3. 构建按「单元」异步执行：知识文档分块 + 每数据集一单元，boundedElastic 并发，
   单元级成败落库，任务终态 COMPLETED / PARTIAL_FAILED；同组并发构建由 JVM 锁拒绝。
4. 查询侧 `semanticContext(ownerId, term)`：实体模糊匹配 → 沿关系边展开 →
   经「包含字段」边把字段解析为 table.column，返回口径事实与 JOIN 提示。
5. 另有一条轻量确定性关系推断 `RelationInferenceService`（SAME_COLUMN 0.7 /
   SUFFIX 0.6 / 知识文档正则 DOC 0.9），在数据集变更时整组重建，供 JOIN 建议与图展示。

## 理由与权衡

- schema 抽取零 LLM 成本、结果可重现、无幻觉；LLM 只处理它擅长的非结构化文本。
- 整组重建（而非增量）实现简单，规模小（单组数十表）时可接受。
- 代价：知识文档更新需手动触发重建；实体匹配是字符串包含级，未做向量/同义词归一。

## 被否决的替代方案

- 全部 LLM 抽取：成本高、schema 部分结果不稳定，且 schema 信息本来就是结构化的。
- 引入图数据库（Neo4j）：当前查询模式（按组全量扫边）用 JPA 足够，不增运维负担。

## 适用与失效条件

适用于单组实体千级以内、以 schema linking 为主的使用方式。若需要复杂图遍历
（多跳路径推理）或百万级实体，应评估专用图存储。
