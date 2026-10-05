# ADR 0040 — 知识图谱构建链路整体停用；核心流程定位为 Wren 语义建模 + 问数

日期：2026-10-05 · 状态：Accepted

## 背景

ADR 0038 废弃了「上传自动建图」，但当时保留手动构建端点与前端展示（「图谱展示形态待定」）。
之后的实际使用确认：手动建图同样没有稳定消费场景——跨表关系与 Schema Linking 已由 Wren
语义层完整承接（关系候选经 `decide_relation` HITL 确认写入 `relationships.yml`、口径经
Cube/View 固化、`wren_describe_model` 按需返回关系），KG 三元组的抽取质量与查询价值均不及
语义层路径。用户拍板：平台不再提炼知识图谱的关系，核心流程收敛为「通过 Wren 进行语义建模 + 问数」。

## 决策

1. 知识图谱构建链路整体停用：不再执行 `triggerBuild` / `semanticContext`，产品流程与
   架构文档不再将其列为现行能力（ARCHITECTURE_zh.md 第 6 章改写为停用说明）。
2. 代码与数据表保留不删：`KnowledgeGraphService` / `KnowledgeGraphController` /
   `knowledge_graph_*` JPA 表 / 前端 `KnowledgeGraphView` / `KgBuildConfigModal` 保持
   现状，仅供历史数据查看，不提供删除迁移。
3. 关系职责唯一归属 Wren 语义层：推断/LLM 建议 → 建模对话 HITL 确认 →
   `relationships.yml`（specs/019 通路，见 ARCHITECTURE_zh.md 5.4）。
4. 架构文档同步重构：第 3 章改为「Wren 语义建模与问数」双主流程（新增 3.2 语义建模链路，
   建模助手 modeling-agent 端到端），第 1 章定位与分层图突出语义建模 + 问数双核心。

## 理由（trade-off）

- 维护与认知面收敛：Schema Linking、跨表 JOIN 提示、口径溯源只保留一个事实源（语义层），
  不再需要解释「图库与语义层各管什么」。
- 保留代码而非物理删除：删除需连带清理 10+ JPA 实体、前端组件、Controller 与测试，收益
  仅是代码库整洁；若未来 GraphRAG 形态定稿可低成本复活（同 ADR 0038 的保留逻辑）。

## 被否方案

- 物理删除 KG 模块——图谱展示形态仍未定稿，保留复活空间；
- 继续维持「手动构建可用」——没有消费场景的能力只积累维护与认知成本；
- 文档仅删不述——停用状态与职责去向需要在架构文档中显式可查，避免后来者误把 KG 当现行链路。

## 影响面

- 文档：ARCHITECTURE_zh.md（文档头、第 1 章定位与分层图、第 3 章重构、第 6 章停用说明、
  2.3 转录去向、5.1 概念模型、5.4 关系通路现行化、第 10/11/13 章索引）。代码零改动。

## 验证

文档一致性：全文「知识图谱」出现点均为停用口径并指向第 6 章；目录与章节锚点同步；
旧章题「一次提问的完整链路」全文零残留。
