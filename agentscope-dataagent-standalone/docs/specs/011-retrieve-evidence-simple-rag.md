# spec 011: retrieve_evidence 应用层关键词召回（简单 RAG）

> 状态：已实施（2026-09-29；实施先于文档，当日由 LLM.log 分析驱动，架构取舍见 ADR 0022）。

## 背景与目标

`retrieve_evidence` 原为伪检索（query 不参与过滤：非空返回全部知识文档全文、空则
「没有相关内容」）。2026-09-28 会话 4 次调用全部落空且返回零区分度（ADR 0022 背景）。
目标：query 真正参与过滤、返回带出处的 top-3 片段；复杂方案（向量召回 / Bailian）
明确后置——用户拍板「先做相对简单的 RAG」。

## 方案概述

同日两步交付：

**第一步 关键词召回**：`DataAgentToolkit#retrieveEvidence` 改调新增的
`DatasetContextProvider#evidenceFor(ownerId, groupIds, query, 3)`（null = 未命中，
调用方保持自己的 not-found 文案）；`DatasetService#evidenceFor` 遍历 owner 可见组
（组过滤语义同 `relationshipsText`，`onlyGroups` 收窄）→ `KnowledgeEvidence` 纯函数
三段式：

- `chunks`：≤600 字整篇单 chunk / markdown 标题切分（chunk 保留标题）/ 无标题空行
  分段 / 单段 >800 字硬切
- `terms`：小写 ASCII `[a-z0-9]{2,}` + CJK bigram（单字 unigram）
- `score`：正文命中 +2 / 标题命中再 +3，score>0 收集按分降序取 top-limit

**第二步 短文档整篇注入**：`KnowledgeEvidence#isShortDoc`（归一化后 ≤600 字）免
关键词门直接收集（`score > 0 || wholeDoc`）；命中片段仍按得分优先，短文档 0 分作
兜底；`terms.isEmpty()` 早退移除（无词项查询也可见短文档）。

渲染契约：片段前缀 `【知识库「组名」 › 章节】\n正文`，片段间空行分隔；工具外层包
`## 检索结果\n\n`；@Tool 描述声明「篇幅较短的知识文档会整篇返回，不做关键词过滤」。
ARCHITECTURE_zh.md 第 4.1 节工具表已同步。

## 影响面

- 后端：`KnowledgeEvidence`（新增纯函数类）、`DatasetContextProvider#evidenceFor`
  （接口新方法）、`DatasetService#evidenceFor`（实现）、
  `DataAgentToolkit#retrieveEvidence`（改调用 + 描述更新）
- 前端：无（工具结果仍是 markdown，走既有 tool_result 帧）
- 数据：无 schema 变更（读 `DatasetKnowledgeEntity`）
- 文档：ARCHITECTURE_zh.md 4.1；ADR 0022；本 spec

## 验收标准（Given-When-Then）

1. Given 文档 >600 字含「## 付费用户口径」节，When query 含该节词项（如「付费用户
   考核目标」），Then 返回该节片段且带【知识库「名」 › 付费用户口径】出处
2. Given 文档 >600 字且 query 与全文零词项交集，Then `evidenceFor` 返回 null → 工具
   输出「知识库中没有与 '…' 相关的内容。」（byte 级不变）
3. Given 文档 ≤600 字且 query 词项与文档零交集（如查「量子物理」、文档是考核口径），
   Then 仍整篇返回，出处带文档 leading 标题
4. Given 同 owner 一长库（有命中片段）一短库（零交集便签），When limit=1，Then 只
   返回长库命中片段（关键词命中 > 短文档兜底）
5. Given query 提取不出任何词项（纯标点）且存在短文档，Then 短文档仍返回
6. Given `onlyGroups` 限定 g2，Then g1 的文档不可见（组过滤隔离）
7. Given ownerId 为 null / query 空白 / limit≤0，Then 返回 null

## 不做的事

- 不做向量召回 / embedding / 向量存储（后置：替换 `evidenceFor` 单方法即可，ADR 0022 D6）
- 不对接 Bailian / Dify / RAGFlow（无 API 上传，ADR 0022）
- 不改 `[KNOWLEDGE_BASE_OVERVIEW]` 全文预注入（与本召回同源并存，各司其职）
- 不改知识文档上传链路与 `MAX_KNOWLEDGE_CHARS` 上限
- 不透出相关度分数 / 片段高亮（模型只需带出处文本）

## 测试要求

- `KnowledgeEvidenceTest`（9）：chunks（短整篇/标题切分/空行分段/超长硬切）、terms
  （ASCII+CJK 混合/单字 unigram）、score 权重（body 2×2 + title 3 = 7）、空输入、
  `isShortDoc` 边界（600/601/CRLF 归一化计量）
- `DatasetServiceEvidenceTest`（8）：命中含出处、长文档无关 query null、短文档免门
  整篇、命中优先于兜底、无词项查询短文档可见、`onlyGroups` 隔离、无文档组跳过、
  null owner / 空 query / limit≤0
- `DataAgentToolkitTest`：mock provider 命中返回 `## 检索结果` + 片段；null 保持
  未找到文案（`retrieveEvidenceKeepsNotFoundWording`）
- 多租户：`evidenceFor` 只遍历 `findByOwnerIdOrderByCreatedAtDesc(ownerId)`——
  ownerId 维度隔离与 `relationshipsText` 同一数据面，`onlyGroups` 过滤有用例锁定

## 关联

- ADR 0022（架构取舍）
- 同批改动：specs/012（技能 wren 通道分流修补，同源 LLM.log 分析）
