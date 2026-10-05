# ADR 0038 — 废弃上传自动知识图谱构建；关系说明文档视图下线并入建模页

日期：2026-10-04 · 状态：Accepted

## 背景

`DatasetGroupController#uploadKnowledge` 自 specs/014 起在上传文档后静默触发
`KnowledgeGraphService#triggerBuild`（LLM GraphRAG：文档分块 → 抽取实体/关系三元组 → 落图）。
实际使用中该链路价值不稳定：抽取质量依赖文档质量与模型，图谱结构可读性一般，且每次上传
都全量重置重建（LLM 成本随上传频次线性增长）。用户拍板废弃「上传即建图」；知识图谱 tab
今后展示什么另行规划（本 ADR 不涉及）。

同期交互收敛：同一份文档原有三个入口（左侧「关系说明文档」独立视图、文件视图上传卡、
语义建模页「上传文档增强」按钮），上传与审阅分散在两个页面，认知负担高。

## 决策

1. `uploadKnowledge` 删除 `triggerBuild` 静默块；`DatasetGroupController` 不再依赖
   `KnowledgeGraphService`。上传语义文档只做两件事：`saveKnowledge`（每轮注入问数
   SystemMessage）+ `DocEnhanceService#triggerAnalyzeQuietly`（差异分析提案）。
2. **知识图谱能力保留**：手动构建端点（`KnowledgeGraphController`）、`KgBuildConfigModal`、
   `KnowledgeGraphView` 展示、`semanticContext` 查询链路全部不动——只切断自动触发，
   不删能力。
3. 「关系说明文档」独立视图（`DocDetailView`）删除；上传统一为语义建模页「上传语义文档」
   （引导条按钮 + 文档语义增强卡头部两处入口，复用同一 `uploadKnowledge`），上传后页面
   原地出现增强任务，形成「上传 → 分析 → 审阅」闭环。

## 理由（trade-off）

- 建图成本是每次上传全额支付，而图谱消费（问数/溯源）没有稳定场景；先止损，等展示形态
  定稿后再按需恢复触发点（恢复点只剩一处：`uploadKnowledge` 加回一行）。
- 与 specs/014 的语义增强对比：增强提案走「差异分析 + 人审采纳」，产出直接进问数链路
  （术语/规则/Cube/视图），是文档价值的显式兑现；KG 三元组则被动躺在图库里等查询——
  保留前者、废弃后者的自动触发是价值密度选择。
- 上传入口收敛到建模页：文档的唯一消费场景就是语义完善（注入 + 增强），入口放在消费地
  消除「上传在 A 页、分析在 B 页」的割裂。

## 被否方案

- 连 `KnowledgeGraphService` 与手动构建端点一起删除——图谱 tab 尚未定稿，保留能力给
  后续设计留选择空间；
- 图谱 tab 暂时隐藏/删除——导航入口保留现状，等展示形态定稿再动（用户明示「暂且不定」）；
- 上传建图改为可配置开关——为不确定的价值增加配置面，违背 YAGNI。

## 影响面

- `DatasetGroupController`（构造器 5 参 → 4 参，`DatasetGroupControllerTest` 同步）；
- 前端 `DatasetGroupPage`（doc 视图/上传卡/隐藏 input 删除）、`SemanticModelingPage`
  （上传语义文档入口 + tab underline 导航行 + 建模总览卡删除）、`api/datasets.ts`
  （`getKnowledge`/`KnowledgeDoc` 孤儿删除）、`DocDetailView.tsx` 删除；
- 后端 `PUT /{id}/knowledge` 端点契约不变。

## 验证

`mvn test` 全绿、`npm run build` 绿（specs/028 G1-G5）。
