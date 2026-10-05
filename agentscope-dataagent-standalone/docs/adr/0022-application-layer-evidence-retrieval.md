# ADR 0022: retrieve_evidence 下沉应用层的关键词召回（简单 RAG）

日期：2026-09-29
状态：已接受（specs/011，已实施）

## 背景

`retrieve_evidence` 自 TC 式工具链引入以来是伪检索：`DataAgentToolkit#retrieveEvidence`
调用 `DatasetService#relationshipsText`（知识文档全文渲染，本是
`[KNOWLEDGE_BASE_OVERVIEW]` 预注入的复用），`query` 参数完全不参与过滤——query 非空
返回全部文档全文，为空则「没有相关内容」，二选一。2026-09-28 会话（`logs/LLM.log`，
M4 落地后首个真实问数）实证其失败形态：用户问「咪咕视频考核指标完成情况」，模型
4 次调 `retrieve_evidence`（query 各不相同），因知识库尚未录入考核目标文档全部落空；
且返回内容对查询零区分度——文档一旦就位并增长，「全量或无」也无法定位到回答问题的
那一段。

评估过的现成方案均不可行（当日源码调研）：

- **AgentScope `io.agentscope.core.rag` 包全家 `@Deprecated(forRemoval=true, since 2.0.0)`**
  （Knowledge / KnowledgeRetrievalTools / SimpleKnowledge 与 Bailian/Dify/Haystack/RAGFlow
  四集成）——官方 Javadoc 指引「integrate retrieval at the application layer」，
  框架层 RAG 正在撤出，新代码不应再依赖；
- **BailianKnowledge**：`addDocuments` 抛 UnsupportedOperationException（文档上传必须
  阿里云控制台手工操作）、单实例绑一个 indexId、鉴权要阿里云 AK——与「知识文档经
  `PUT /api/dataset-groups/{id}/knowledge` 多租户 API 自动上传」的平台形态正交；
- **自建向量召回**：dashscope 扩展无 Embedding 实现（仅存于已废弃的 rag-simple 模块），
  需引入 embedding 依赖与向量存储——对当前规模是过度设计。

规模事实：知识文档上限 `MAX_KNOWLEDGE_CHARS=20000` 字/库（一组一份），内容是
markdown 结构化短文（考核口径、表关系说明、业务规则）。

## 决策

1. **应用层关键词召回，零新依赖**：新增 package-private 纯函数类 `KnowledgeEvidence`
   （chunks / terms / score / isShortDoc）+ `DatasetContextProvider#evidenceFor` +
   `DatasetService#evidenceFor`，`retrieveEvidence` 改调它。框架 rag 包废弃正是官方
   指定的路径；关键词召回对 ≤2 万字结构化短文档够用。
2. **markdown 标题感知切分**：≤`WHOLE_DOC_CHARS`（600 字）整篇单 chunk；否则按
   `#{1,6}` 标题切分（chunk 保留标题，作出处与打分高信号）；无标题按空行分段；单段
   超 `MAX_CHUNK_CHARS`（800 字）硬切。标题是天然语义边界，不需要语义模型。
3. **CJK bigram 词项 + 标题加权打分**：query 归一化为小写 ASCII `[a-z0-9]{2,}` token
   + CJK bigram（单字保留 unigram）——免中文分词依赖；打分正文命中 +2、标题命中
   再 +3。返回 top-3，每片段带【知识库「组名」 › 章节】出处前缀。
4. **短文档整篇注入（免关键词门）**：归一化后 ≤600 字的文档（`isShortDoc`，与整篇
   单 chunk 同一把尺）无论词项是否命中都整篇返回——短文档（典型考核口径便签）被
   词表 miss（查询「量子物理」、文档「考核目标」）的代价大于全量返回的噪音；关键词
   命中片段仍按得分优先，短文档 0 分时作兜底填充（「宁可多给，不可漏答」）。
5. **稳定失败契约**：未命中文案「知识库中没有与 '…' 相关的内容。」保持 byte 级不变
   （仅无文档或长文档无命中时触发）——LLM 已适配该失败形态，
   `DataAgentToolkitTest#retrieveEvidenceKeepsNotFoundWording` 锁定。
6. **向量召回后置为单方法替换路径**：规模化（多库 × 长文档 × 语义歧义）出现时仅替换
   `DatasetService#evidenceFor` 一个方法（chunks/score 换 embedding 相似度），接口、
   工具契约与失败文案零改动。

## 后果

- 正面：`retrieve_evidence` 成为真检索（query 参与过滤、片段带出处、按相关度排序、
  短文档零 miss）；零新依赖、零新服务；`[KNOWLEDGE_BASE_OVERVIEW]` 全文预注入（全局
  视野）与本召回（按需定位）数据同源、各司其职。
- 负面/权衡：关键词召回无语义泛化（「用户流失」召回不了「churn」）；短文档免门意味着
  与查询零相关的短便签也占据 top-3 名额（噪音换召回，接受）；`terms.isEmpty()` 早退
  移除后，无词项查询（纯标点）也会触发短文档兜底返回。
- 明确不做：向量召回 / embedding / 向量存储引入（后置，见决策 6）；Bailian 等外部
  知识库对接（无 API 上传，运营模式不匹配）；跨知识库语义合并；片段高亮与相关度
  分数透出。
- 遗留观察：真实语料下 bigram 噪音命中率与 top-3 截断的召回率，待上线后从 LLM.log
  观察。

## 关联

- specs/011（实施规格）；ADR 0007（prompt 分层——预注入与检索同源并存）；ADR 0018 D3
  （知识库范围：文档类 RAG 留平台）
- 框架源码：`agentscope-core/.../rag/Knowledge.java`（@Deprecated forRemoval）、
  `agentscope-extensions-rag-bailian/.../BailianKnowledge.java#addDocuments`
- 诊断记录：`logs/LLM.log`（2026-09-28 会话，4 次 retrieve_evidence 全落空）
