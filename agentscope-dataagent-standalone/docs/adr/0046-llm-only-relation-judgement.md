# ADR 0046: 表间关系判定全 LLM——规则推断层退役

- 状态：已采纳
- 日期：2026-10-05
- 来源：用户拍板（关系的判断全部通过 AI，不再通过规则）+ ADR 0044 两层判定的运行结论

## 背景

ADR 0044 之后关系判定分两层：规则层（`RelationInferenceService` 的 SAME_COLUMN/SUFFIX 推断 +
specs/036 XOR 事实↔维定向，上传/关联/保存文档三入口触发 `reinferGroup` 选择性重建；
`SchemaRelationInferrer` 为知识图谱 Tab 现场推断规则边）与 LLM 层
（`MdlSuggestionService#suggestRelationsLlm` 两步语义判定）。实际运行暴露规则层的根本局限：
主键形态（首列或 `<表>_id`）机械过滤会误杀非主键业务键关联（订单表.商品编码 ↔ 商品表.商品编码），
语义等价但写法不同的列（`user_id` vs `用户id`）规则永远无法桥接——这两类恰好只有 LLM 能覆盖。
规则层的确定性收益（零成本、稳定可重复）在 LLM 层一次批量建议已足够覆盖，双层并存徒增维护面与
行为解释成本（同一批表两条通道产出不一致时用户无从判断哪条可信）。

## 决策

1. **规则层整体退役**：删除 `RelationInferenceService`（含 `reinferGroup` 选择性重建与知识文档
   正则解析 DOC 边）与 `SchemaRelationInferrer`（图谱现场规则边）。`DatasetService` 的上传
   （`ingest`）/外部表关联（`associateTables`）/保存知识文档（`saveKnowledge`）三个入口不再触发
   关系重建；`MdlSuggestionService#refreshRelations` 只保留 LLM 建议（merge 跳过已知对）与
   joinType 唯一性探测。
2. **知识图谱 Tab 改持久化边**：`DatasetGroupController#graph` 的节点照旧按数据集列 schema 现场
   构造，边只来自持久化关系（LLM 建议候选、历史 DOC 行、manual 手工）——图上每条边都是 AI 产出
   或人工确认，规则推断边（`column-heuristic`）成为历史。
3. **候选管理只删不建**：DB `DatasetRelationEntity` 维持候选建议队列语义（PENDING/CONFIRMED/
   REJECTED），任何入口不再批量重建或清除候选；历史 `inferred` 来源旧行向后兼容保留（读取端不
   迁移）。知识文档的语义不再被正则硬解析为关系边——文档内容仍经 `[KNOWLEDGE_BASE_OVERVIEW]`
   注入，由建模对话的 LLM 自行据此提议。
4. **保留项**：joinType 唯一性探测（`probeJoinTypes`）是基数测量不是关系判断，保留；
   `suggestRelationsLlm` 的「先判事实/维表、再只提事实→维、解析仅反向修复永不丢弃」两步法
   （ADR 0044 D2）不变；建模页人工录入（`addManualRelation`）保留为非自动入口。

## 理由与权衡

- 规则层能做的（同名列/后缀匹配）LLM 都能做且做得更好；规则层做不到的（业务键、写法桥接、表
  角色语义判定）只有 LLM 能做。双层并行时两层结果不一致还要解释哪层可信——单层消除这类歧义。
- 代价：LLM 不可用时（`agentDraftService.modelAvailable()` 为 false）不再有候选兜底产出；新表
  入库后关系候选要等一次 LLM 刷新（`refreshRelations`/建模对话）而非上传即得。可接受——建模
  本身就是在建模对话里进行的。
- 人工记录保护语义从「重建时保留」简化为「永不自动删除」，更简单也更保守。

## 影响面

- 删除：`dataset/RelationInferenceService`、`dataset/SchemaRelationInferrer` 及其测试
  （`RelationInferenceServiceTest`、`SchemaRelationInferrerTest`）。
- 修改：`DatasetService`（三个入口摘除 reinferGroup）、`MdlSuggestionService`
  （refreshRelations 去 reinferGroup）、`DatasetGroupController`（graph 端点内联节点构造、边只取
  持久化关系）、`GraphDto`/`DatasetRelationEntity` 注释口径、`ARCHITECTURE_zh.md` 第 1/5/13 章。
- 测试：`MdlSuggestionServiceTest`/`DatasetServiceEvidenceTest`/`DatasetServiceMdlDirtyTest`/
  `DatasetServiceSourceCombinationTest` 摘除 relationInference mock 与 verify。
