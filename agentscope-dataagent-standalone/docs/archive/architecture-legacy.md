# 架构历史说明（已退出主流程）

> 从 ARCHITECTURE_zh.md 移出的历史章节，仅供追溯，不作为当前实现依据。

### 5.4 历史关系服务与兼容台账（ADR 0046）

本节保留旧服务与数据结构说明，当前 Agent 不注册这些领域写工具。关系的当前事实源为官方工程 YAML，建模按第 3.2 节执行。

**候选生成（唯一自动通道：LLM）**：表间关系候选全部由 `MdlSuggestionService#suggestRelationsLlm`
产生（`refreshRelations` 触发，两步语义判定——先按表描述/表行数/示例数据判定各表是事实表还是
维表，再只提议事实→维方向的关联：维表被引用列不一定是主键、业务键关联合法；事实表↔事实表、
维表↔维表严禁推荐；解析层仅当提案恰好维侧为主键形态列而事实侧非时做方向反转修复，其余原方向
保留、永不丢弃，人工兜底）。知识文档不再走正则解析为 DOC 边——文档内容仍经
[KNOWLEDGE_BASE_OVERVIEW] 注入，由建模对话的 LLM 自行据此提议关系；建模页人工录入
（`addManualRelation`）是第二个非自动入口。原规则推断层（`RelationInferenceService` 的
SAME_COLUMN/SUFFIX 推断与 specs/036 XOR 定向、`SchemaRelationInferrer` 图谱现场推断）已整体
退役删除：主键形态机械过滤会误杀非主键业务键关联（订单表.商品编码 ↔ 商品表.商品编码），
语义等价但写法不同（`user_id` vs `用户id`）的场景规则无法桥接——两能力都只有 LLM 能覆盖；
上传/关联/保存文档入口不再触发任何关系重建。

**候选管理（specs/010 M1）**：DB `DatasetRelationEntity` 是候选建议队列（PENDING/CONFIRMED/
REJECTED，specs/013 起支持复合键 `source_columns`/`target_columns`——发布时
`MdlPublishService#buildRelations` 逐列校验渲染 `a.c1 = b.c1 AND a.c2 = b.c2`）；记录永不自动
删除（无重建即无清理），`joinType`（MANY_TO_ONE 等）由唯一性探测补齐
（`MdlSuggestionService#probeJoinTypes`，COUNT DISTINCT vs 行数比对，绝不猜测）。
`DatasetGroupEntity` 的 `mdlState/mdlVersion/mdlPublishedAt` 为发布流程预留。

`DatasetService#relationsFor(owner, table)` 把命中边渲染为可读行 + 建议 JOIN 片段；当前无生产调用方（为 specs/010 建议服务预留）。

**关系候选 → MDL relationships 的现行通路（specs/019 YAML-first → specs/036 一次即生效）**：
LLM 建议候选（唯一自动通道，见上）+ joinType 唯一性探测构成候选池；建模对话经
`decide_relation` / `decide_relations` HITL 确认后直接写工程 `relationships.yml`（幂等 upsert，
DB 边仅保留候选建议队列，见 2.5 与 5.6）；确认一次即生效（specs/036：确认即写入并附
`context validate --strict` 结论，之后不复述不二次确认）。知识图谱 Tab 的图节点按数据集列
schema 现场构造、边只来自持久化关系（LLM/历史 DOC 行/manual），见 `DatasetGroupController#graph`。
见 ADR 0018 D2/D6/D9、ADR 0044 与 ADR 0046。

### 5.5 历史术语与口径台账（specs/011，ADR 0027）

以下实体和接口属于兼容面；当前运行知识与业务规则以官方 knowledge 源码及发布工程为准，不从台账重建线上语义。

`SemanticTermController`（`/api/semantic-terms`）维护全局业务词典；
`DatasetService#semanticTermsText()` 渲染进 `[KNOWLEDGE_BASE_OVERVIEW]` 的「业务术语」小节。

**建模对话口径入口（specs/011 M1 → specs/019 调整）**：术语管理走「语义配置」页 REST
（`SemanticTermController`，`/api/semantic-terms`）；建模对话保留只读 `list_terms`
（`ModelingToolkit#listTerms`，读全局词典），写入类工具 `create_term` / `delete_term` 已随
specs/019 结构化写面退役。原路由决策树在 YAML-first 形态下的对应落点：

| 口径形态 | 落点 | 生效方式 |
|---|---|---|
| 纯词义声明（"活跃用户=30天内登录过"） | 术语页登记（全局词典） | 保存即生效，下轮 SystemMessage 重建即注入 |
| 业务规则/口径约束 | `write_file` 写 `knowledge/rules/<name>.md` | 文件即生效（`MdlWorkspaceReader#readKnowledgeRules` 注入） |
| 单表聚合指标 | `write_file` 写 `cubes/<name>/metadata.yml` | 发布后可查（HITL 确认 + 三重闸） |
| HAVING/窗口/多表 JOIN | `write_file` 写 `views/<name>/` | 发布后可按视图名查询 |

术语不触发 MDL 重建（官方 MDL schema 无术语段，属知识层而非模型层）。术语租户隔离按方案 A
后置：全局字典 + `scope` 自由标签标注知识库。

### 历史问题确认与兼容接口

本节描述旧问题验证/审阅台账，不是当前 Agent 的发布前置流程。当前 `MdlPublishService#doPublishLocked` 明确问题可选，官方工程校验仍执行；不要求逐题平台确认。

`MdlQuestionStore` 以工作区问题文件为事实源；执行凭据存放在平台拥有的 `.platform/questions/`，绑定问题哈希、语义文件哈希和验证 ID。模型、规则或问题修改后状态为 STALE，需要重新执行和确认。确认凭据不由模型提交，过期凭据、失败执行、截断结果不可确认。

spec 046 / ADR 0056：新写入的问题必须声明 `modeling.strategy`（MODEL/VIEW/CUBE/EXAMPLE）、业务理由及逻辑资产引用。`ModelingToolkit#validateProposal` 校验声明，`MdlQuestionStore#coverage` 校验资产存在与 SQL 引用，`MdlPublishService#validateQuestion`、人员确认与发布均检查覆盖状态；这不替代 Wren 执行和人员口径确认。历史问题缺少声明时按历史 SQL 示例兼容，新增与修改声明纳入问题指纹。问题 Review 的 published 状态从同知识库 published 快照推导，区分已发布可问数与未发布草稿。前端使用编号列表和选中详情；第三步展示发布条件、阻塞事项及第四步发布入口。写入卡复用 Prism 展示完整内容和放大阅读，编辑内容可一次预检并提交原生 HITL，前端同步锁防重复提交。

`MdlPublishService#doPublish` 在工作区锁内检查所有未归档问题是否 CONFIRMED，失败保留上一份可用 published 快照。未配置问题的现有知识库继续兼容原发布流程。已确认的问题生成 Wren 官方 `knowledge/sql/*.md` 格式（frontmatter 的 `nl/sql/source`），发布副本只保留当前有效的确认示例，历史手写且未确认的 SQL 示例需要迁移为问题后重新验收。

文件写入、确认和发布共用每组工作区锁；发布与草稿执行额外使用既有发布互斥锁，顺序为发布锁再工作区锁。知识库 ownerId 和 DatasetScope 在服务层校验，HTTP 接口还经 AgentAccessGuard；阻塞执行位于 boundedElastic。当前凭据绑定语义版本，不代表数据永久不变，日后数据更新仍通过当前 Wren 查询获取结果。
