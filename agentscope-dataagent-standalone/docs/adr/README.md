# 架构决策记录（ADR）索引

ADR 记录"为什么这样做"。规则：

- **只增不改**：已接受的 ADR 不修改决策内容；新决策取代旧决策时，新增一条 ADR 并写明取代范围。
- 2026-10-21 文档重组时，ADR 中指向已迁移文档的相对链接做了机械路径更新，决策内容未改。
- 当前代码的实际行为以 [ARCHITECTURE_zh.md](../../ARCHITECTURE_zh.md) 为准，尤其是第 14 章"建模与问数的当前运行约束"。

## 当前有效主线（按主题）

| 主题 | 当前有效 ADR | 要点 |
|---|---|---|
| 总体路线 | [0069](0069-wren-native-modeling-and-query-agents.md)、[0068](0068-generic-modeling-agent-with-official-wren.md) | 建模与问数统一使用同版本官方 Wren skills/CLI/MCP；建模 Agent 是通用 Agent，平台只提供执行环境与服务 |
| 知识库共享与权限 | [0077](0077-shared-knowledge-official-wren-integration.md) | QUERY/MANAGE、操作者范围与官方 Wren 入口集成 |
| 建模技能与模式 | [0094](0094-official-enrich-context-and-technical-modeling-confirmation.md)、[0103](0103-source-tiered-probing-and-row-based-gate-verdict.md) | 官方 enrich-context Auto-pilot；技能挂载后关闭 CLI skills 通道 |
| 建模探查与查询 | [0104](0104-upload-probe-scope-and-sql-side-aggregation.md)、[0106](0106-modeling-query-cap-cartesian-ban-and-cli-timeout-cleanup.md) | 上传表两类有限探查；单轮 10 条 query；禁止笛卡尔积 |
| 建模文件边界 | [0107](0107-basic-modeling-closes-cube-and-view-files.md)、[0108](0108-yaml-write-guard-model-file-layout-and-cli-timing.md)、[0110](0110-platform-handles-build-overwrite-and-conditional-date-rules.md) | Cube/View 不读不写；YAML 语法检查；models 目录限制；整文件替换；自动补编译 |
| 问答对来源 | [0109](0109-user-confirmed-questions-replace-generated-qa-pairs.md) | 只来自用户逐题确认与问数点赞；建模不生成 |
| 建模变更治理 | [0111](0111-draft-changes-reviewed-before-publish.md) | 修改直接进入草稿，发布前统一审阅；取消逐项确认卡；无文档的自动建模不写规则 |
| SQL 时间门禁 | [0097](0097-llm-per-table-sql-window.md)、[0098](0098-post-sampling-fact-window-confirmation.md)、[0099](0099-runtime-window-policy-and-verifiable-sql-gate.md)、[0103](0103-source-tiered-probing-and-row-based-gate-verdict.md)、[0112](0112-rule-first-sql-window-gate.md)、[0113](0113-mysql8-or-doris-sources-and-literal-date-ranges.md) | 数据库独立配置；逐表检查，普通写法由规则判断、其余交 LLM；日期范围要求写成常量；明细重算结论；上传表不设门禁 |
| 问数日期与统计 | [0102](0102-query-date-clarification-and-modeling-samples.md)、[0104](0104-upload-probe-scope-and-sql-side-aggregation.md)、[0105](0105-build-state-context-and-ranking-granularity.md)、[0110](0110-platform-handles-build-overwrite-and-conditional-date-rules.md) | 仅受限表缺日期先问；统计来自 SQL；排名按主体分组 |
| 问数准备与性能 | [0100](0100-published-query-index-preparation.md)、[0101](0101-reuse-published-mcp-for-recall-warmup.md) | 发布后后台索引、召回预热、分段计时 |
| 工程整合、单库与管理员 | [0114](0114-native-query-and-engineering-integration.md) | 原生建模问数优先；BI、私有图表、持久状态、人工模型切换与受限恢复 |
| 会话与附件 | [0060](0060-durable-session-history-and-artifact-lifecycle.md)、[0061](0061-configurable-history-retention.md) | 持久历史、附件生命周期、保留策略 |

知识图谱链路已停用（[0040](0040-kg-build-chain-retired-wren-semantic-core.md)），不要向该方向新增功能。

## 全部 ADR

"后续取代"列由文本中"取代/替代 ADRxxxx"的表述自动识别，可能不全；以较新 ADR 的"状态"段为准。

| 编号 | 标题 | 状态 | 后续取代 |
|---|---|---|---|
| [0001](0001-tc-style-toolchain-and-dynamic-context.md) | 问数工具链采用 TC 式「上下文预注入 + 四工具」而非「工具逐步发现」 | 已采纳 |  |
| [0002](0002-dataset-storage-and-tenant-isolation.md) | 上传数据集统一落单库 ds_ 前缀表 + 应用层越权校验 | 已采纳 |  |
| [0003](0003-per-user-sandbox-external-injection.md) | 每 (userId, agentId) 长存沙箱 + 网关 externalSandbox 注入，多副本需 sticky LB | 已采纳 |  |
| [0004](0004-knowledge-graph-dual-channel-extraction.md) | 知识图谱采用双通道抽取（schema 走确定性规则，文档走 LLM） | 已采纳 |  |
| [0005](0005-marketplace-shared-layer-and-invalidation.md) | 能力市场=「审批物化到共享层 + 沙箱失效重建」，市场源可插拔 | 已采纳 |  |
| [0006](0006-cross-table-join-first-and-batch-prepare.md) | 跨表查询「JOIN 优先、禁止结果搬运」+ prepare_data_context 批量取表 | 已采纳 |  |
| [0007](0007-prompt-layering-and-shared-single-source.md) | 提示词三层分工 + `shared/` 单一事实源与 seeder 版本化 | 已采纳 |  |
| [0008](0008-anti-probe-queries-and-count-consistency.md) | 禁「摸底查询」硬门上移 + 回答计数一致性自检 | 已采纳（2026-09-22 实施，见 [spec 002](../specs/done/002-anti-probe- |  |
| [0009](0009-subagent-orchestration-off-by-default.md) | 子代理编排默认关闭（可配置开关） | 已采纳（2026-09-22 实施，见 [spec 003](../specs/done/003-subagent-or |  |
| [0010](0010-llm-log-readable-transcript.md) | `LLM.log` 改为人工可读的消息转录（只记 role / type / text） | 已采纳（2026-09-22 实施，见 [spec 004](../specs/done/004-llm-log-rea |  |
| [0011](0011-session-isolation-and-batch-delete.md) | 会话隔离与批量删除 | 已采纳（从 data-agent-merge 合并） |  |
| [0012](0012-security-and-stability-fixes.md) | 安全修复与稳定性增强 | 已采纳（从 data-agent-merge 合并） |  |
| [0013](0013-tc-landing-page-and-semantic-config.md) | TC 式落地页与语义配置页 | 已采纳（从 data-agent-merge 合并） |  |
| [0014](0014-sandbox-session-isolation.md) | 沙箱会话目录隔离 | 已采纳（从 data-agent-merge 合并） |  |
| [0015](0015-scheduled-tasks-ui-and-crud.md) | 例行任务（Scheduled Tasks） | 已采纳 |  |
| [0016](0016-dataset-groups-list-n-plus-1-fix.md) | 知识库列表接口 N+1 查询优化 | 已采纳 |  |
| [0017](0017-datasource-modal-ui-redesign.md) | ADR-0017: 数据源管理弹窗界面优化 | 已接受 (2026-09-23) |  |
| [0018](0018-wren-semantic-layer-mcp-integration.md) | WrenAI 语义层集成（问数执行换轨与 cube 发布流） | 已采纳（实施规格 specs/010） | 部分或全部被 [ADR0029](0029-wren-only-query-and-auto-baseline-mdl.md) 取代 |
| [0019](0019-mdl-publish-and-dirty-marking.md) | MDL 拼装、验证与发布 + 脏标记（specs/010 M2） | 已采纳（实施规格 specs/010 M2） |  |
| [0020](0020-wren-runtime-and-prompt-split.md) | Wren 运行期接入——查询工具、实例池与预注入分流（specs/010 M3） | 已采纳（实施规格 specs/010 M3） |  |
| [0021](0021-runtime-dynamic-wren-profiles.md) | 运行期动态 wren 连接档案（外部数据源组发布） | 已接受（specs/010 M4） |  |
| [0022](0022-application-layer-evidence-retrieval.md) | retrieve_evidence 下沉应用层的关键词召回（简单 RAG） | 已接受（specs/011，已实施） |  |
| [0023](0023-wren-instance-call-serialization.md) | wren 实例调用按组串行化（MCP stdio transport 非线程安全） | 已接受（已实施） |  |
| [0024](0024-conversational-mdl-modeling-and-readonly-visualization.md) | 对话式语义建模与 MDL 只读可视化 | 已接受（待实施，规格见 specs/013） |  |
| [0025](0025-sandboxless-modeling-agent.md) | 元数据型 agent 免沙箱（建模助手脱离 Docker） | 已接受（已实施） |  |
| [0026](0026-cube-measure-distinct-count-closed-enum.md) | Cube 度量去重计数采用封闭枚举 DISTINCT_COUNT |  |  |
| [0027](0027-semantic-views-and-term-persistence.md) | 语义视图与口径落库（term 保存即生效 + view 发布生效双轨） |  |  |
| [0028](0028-document-driven-semantic-enrichment.md) | 文档驱动语义差异分析与人工采纳 | Accepted |  |
| [0029](0029-wren-only-query-and-auto-baseline-mdl.md) | Wren 单一问数通道与基础 MDL 自动发布 | Accepted |  |
| [0030](0030-wren-query-result-file-handoff.md) | Wren 查询结果到 Python 的文件级数据交接 | 已采纳（实施见 specs/016） | 部分或全部被 [ADR0060](0060-durable-session-history-and-artifact-lifecycle.md) 取代 |
| [0031](0031-agentscope-native-modeling-hitl.md) | 采用 AgentScope 原生中断与恢复实现建模 HITL | 已被 ADR-0111 取代（2026-10-21） | [0111](0111-draft-changes-reviewed-before-publish.md) |
| [0032](0032-view-dialect-gate-and-guard-escape.md) | 视图方言门禁、发布前 dry-run 与守卫逃生门 | 已采纳（实施规格 specs/018） | 部分或全部被 [ADR0036](0036-official-align-purge-routing-gates.md)、[ADR0043](0043-view-first-ref-sql-manual-route.md) 取代 |
| [0033](0033-yaml-first-semantic-modeling.md) | 语义资产 YAML-first（文件唯一事实源与三项拍板） | 已采纳（实施规格 specs/019） |  |
| [0034](0034-view-schema-prompt-injection-and-describe-routing.md) | 视图 schema 经 prompt 注入暴露，describe 保持逻辑模型专属并对错路由给引导文案 | 已采纳（实施规格 specs/020） |  |
| [0035](0035-modeling-knowledge-single-source-wren-skills.md) | 建模知识单一事实源上移 wren 官方 skills —— 干净 HarnessAgent 复用官方指引 | 已采纳 |  |
| [0036](0036-official-align-purge-routing-gates.md) | ADR 0036 — 问数/建模全面对齐 wren 官方形态，撤确定性路由门 |  |  |
| [0037](0037-semantic-maturity-signal-and-modeling-page-tabs.md) | ADR 0037 — 语义完善度做纯计算信号，建模页按资产分 tab + 对话建模居中大窗 |  |  |
| [0038](0038-drop-auto-kg-build-on-upload.md) | ADR 0038 — 废弃上传自动知识图谱构建；关系说明文档视图下线并入建模页 |  |  |
| [0039](0039-modeling-memory-group-scope.md) | ADR 0039 — 建模助手长时记忆按知识库隔离（自定义 NamespaceFactory） |  |  |
| [0040](0040-kg-build-chain-retired-wren-semantic-core.md) | ADR 0040 — 知识图谱构建链路整体停用；核心流程定位为 Wren 语义建模 + 问数 |  |  |
| [0041](0041-semantic-modeling-page-workbench.md) | ADR 0041 — 语义建模页工作台化：资产独立展示与对话驱动更新发布 |  |  |
| [0042](0042-ref-sql-derived-model-route.md) | ADR 0042 — 复杂口径走 ref_sql 派生模型：平台侧零改写、发布期逐模型试跑 |  |  |
| [0043](0043-view-first-ref-sql-manual-route.md) | ADR 0043 — 回归官方 sink 分工：view 为 agent 首选载体，ref_sql 降级人工载体 + 发布链物化 |  |  |
| [0044](0044-fact-dim-relation-semantics-and-one-shot-confirm.md) | 关系候选只建事实↔维与确认一次即生效 | 已采纳（实施规格 specs/036） |  |
| [0045](0045-rest-write-side-workspace-mirroring.md) | 语义资产写侧收口——REST 同步工作区与刷新链路 | 已采纳（实施规格 specs/037） |  |
| [0046](0046-llm-only-relation-judgement.md) | 表间关系判定全 LLM——规则推断层退役 | 已采纳 |  |
| [0047](0047-lazy-sandbox-and-orphan-cleanup.md) | 懒加载沙箱 + 启动时孤儿容器清理 | 已接受（已实施） | 部分或全部被 [ADR0060](0060-durable-session-history-and-artifact-lifecycle.md) 取代 |
| [0048](0048-question-driven-modeling-and-verified-examples.md) | 问题驱动建模、Wren 实查验收与已确认示例 | 已接受（用户已授权编写方案并开发） | 部分或全部被 [ADR0055](0055-question-first-unified-acceptance.md) 取代 |
| [0049](0049-guided-modeling-workflow.md) | 统一建模阶段与用户操作引导 | 已接受（用户已授权 ADR、spec 和开发） | 部分或全部被 [ADR0055](0055-question-first-unified-acceptance.md) 取代 |
| [0050](0050-conversation-first-multi-question-workbench.md) | 以对话为主的多问题建模工作台 | Accepted | 部分或全部被 [ADR0055](0055-question-first-unified-acceptance.md)、[ADR0057](0057-wren-guided-modeling-and-published-query-context.md) 取代 |
| [0051](0051-document-first-modeling-entry.md) | 业务文档优先引导与明确上传反馈 | Accepted |  |
| [0052](0052-modeling-preview-repair-feedback.md) | 建模预检失败反馈与对话空间 | 已接受 |  |
| [0053](0053-native-hitl-resolved-call-boundary.md) | 原生 HITL 的已处理调用边界 | 已接受 |  |
| [0054](0054-question-review-evidence-snapshot.md) | 验收依据随验证凭据保存 | Accepted |  |
| [0055](0055-question-first-unified-acceptance.md) | 问题优先入口与统一验收 | Accepted |  |
| [0056](0056-question-asset-coverage-and-review-journey.md) | 问题资产覆盖与建模审阅交互 | Accepted |  |
| [0057](0057-wren-guided-modeling-and-published-query-context.md) | 基于 Wren 的业务方案审阅与已发布问数上下文 | Accepted（用户于 2026-10-08 确认；实现以 specs 047–050 为准） | 部分或全部被 [ADR0058](0058-recommended-questions-and-draft-asset-acceptance.md) 取代 |
| [0058](0058-recommended-questions-and-draft-asset-acceptance.md) | 推荐问题、草稿资产依赖与业务确认边界 | Accepted（用户通过本轮讨论明确要求同步文档） |  |
| [0059](0059-query-correction-and-explicit-model-repair.md) | 查询修正与显式模型修复分离 | Accepted |  |
| [0060](0060-durable-session-history-and-artifact-lifecycle.md) | 持久会话历史、附件与受限沙箱生命周期 | 已接受（已实施；运行验收范围见 spec 052） |  |
| [0060](0060-preflight-before-business-plan-confirmation.md) | 预检先于业务方案确认 | Accepted |  |
| [0061](0061-configurable-history-retention.md) | 可配置的历史会话保留策略 | 已接受（默认关闭） |  |
| [0062](0062-wren-modeling-and-answer-feedback-memory.md) | Wren 建模流程与点赞后查询记忆 | Accepted（已实现并通过构建与自动化验证） |  |
| [0063](0063-sql-only-modeling-and-relevant-example-recall.md) | 统一 Wren SQL 与相关示例召回 | Accepted（用户已授权开发） | 部分或全部被 [ADR0069](0069-wren-native-modeling-and-query-agents.md) 取代 |
| [0064](0064-platform-query-agent-rules.md) | 问数助手统一加载平台运行规则 | Accepted（用户已授权文档和开发） |  |
| [0065](0065-liked-business-query-examples.md) | 点赞后保存成功业务查询样例 | Accepted（用户已授权文档和开发） |  |
| [0066](0066-official-cli-isolated-memory.md) | 知识库示例存储与召回统一委托官方 Wren CLI | Accepted（用户已授权文档和开发） |  |
| [0067](0067-example-feedback-status.md) | 明确展示样例保存与召回结果 | Accepted（用户已授权文档和开发） |  |
| [0068](0068-generic-modeling-agent-with-official-wren.md) | 建模 Agent 采用通用执行能力与官方 Wren | Accepted（2026-10-11 用户确认文档修改与代码开发；实施方案见 spec 057） |  |
| [0069](0069-wren-native-modeling-and-query-agents.md) | 建模与问数统一使用官方 Wren 能力 | Accepted（2026-10-11 用户确认按完整计划开始实施；阶段进度见实施计划和共享记忆） | 部分或全部被 [ADR0081](0081-native-wren-tools-and-answer-evidence.md) 取代 |
| [0070](0070-chat-capacity-and-queuing.md) | 问答并发容量控制与排队机制 | 已接受（已实施） |  |
| [0070](0070-official-mcp-query-receipts-and-autopilot-confirmation.md) | 0070-official-mcp-query-receipts-and-autopilot-confirmation.md | Accepted |  |
| [0071](0071-modeling-session-pacing.md) | 0071-modeling-session-pacing.md | Accepted |  |
| [0072](0072-wren-mysql-limit-compatibility-and-discovery-cache.md) | Wren MySQL 限行兼容与工具发现复用 | Accepted（用户要求优先修复 LIMIT，再减少冗余调用） |  |
| [0073](0073-official-wren-vector-memory.md) | 使用官方 Wren 向量记忆后端 | Accepted |  |
| [0074](0074-official-wren-only-query-tools.md) | 问数 Agent 仅使用官方 Wren 能力 | Accepted |  |
| [0075](0075-sql-qa-management-and-view-path.md) | 语义建模中的 SQL 问答对管理与视图文件路径 | 已接受（2026-10-15） |  |
| [0076](0076-sql-qa-delete-and-folded-frontmatter.md) | SQL 问答对删除与折叠 SQL 兼容 | 已接受（2026-10-15） |  |
| [0077](0077-shared-knowledge-official-wren-integration.md) | 共享权限与官方 Wren 入口集成 | 已接受，2026-10-15。用户确认以 data-agent-multiAgent-Mdl-lyf（32c6d292） |  |
| [0078](0078-platform-capacity-followup-integration.md) | 上传与问答并发后续集成及来源文档边界 | 已接受，用户要求继续合入来源分支昨天两次提交；实现与验证状态见 CURRENT_TASK。 |  |
| [0079](0079-source-history-and-current-documentation.md) | 来源提交覆盖核对与当前文档事实源 | 已接受，本轮仅核对与文档同步，不新增业务实现。 | 部分或全部被 [ADR0080](0080-bounded-sampling-and-wren-context.md) 取代 |
| [0080](0080-bounded-sampling-and-wren-context.md) | 有界采样与官方 Wren 上下文复用 | Accepted |  |
| [0080](0080-single-host-release-and-regression-tests.md) | 单机发布补充与来源回归测试 | 已接受，用户明确授权按核对建议实施。 |  |
| [0081](0081-native-wren-tools-and-answer-evidence.md) | 官方工具直接调用与业务结果证据 | Accepted（2026-10-16 用户确认三项方案并授权实施；实现状态见spec069） |  |
| [0082](0082-mdl-first-sampling-context.md) | 0082-mdl-first-sampling-context.md | Accepted（2026-10-17） |  |
| [0083](0083-time-bounded-analytical-queries.md) | 时间字段模型的聚合与 DISTINCT 必须带时间条件 | 已接受 | 部分或全部被 [ADR0088](0088-explicit-views-and-question-date-gate.md) 取代 |
| [0084](0084-mdl-driven-temporal-boundary.md) | 基于 MDL 字段语义的事实表时间边界 | 已接受 | 部分或全部被 [ADR0088](0088-explicit-views-and-question-date-gate.md) 取代 |
| [0085](0085-separate-cube-modeling-stage.md) | 将 Cube 从基础语义建模中隔离 | 已接受 |  |
| [0086](0086-disable-modeling-probes.md) | 禁用语义建模阶段的自动探测查询 | 已接受 |  |
| [0087](0087-native-tool-display-and-query-observability.md) | 原生工具展示与查询耗时可观测性 | Accepted（2026-10-17 用户要求对照日志整体优化） |  |
| [0088](0088-explicit-views-and-question-date-gate.md) | 显式视图授权、问题日期门禁与逐步计时 | 已接受（2026-10-18，用户确认实施）。 | 部分或全部被 [ADR0089](0089-lightweight-date-gate-and-native-history.md) 取代 |
| [0089](0089-lightweight-date-gate-and-native-history.md) | 轻量日期门禁，复用原生历史与确认 | 已接受，2026-10-18 用户要求精简后确认执行。取代 ADR0088 中独立视图授权记录、日期继承状态及历史资产处 |  |
| [0090](0090-query-latency-cancellation-and-date-resolution.md) | 问数耗时观测、取消释放与日期解析 | Accepted |  |
| [0094](0094-official-enrich-context-and-technical-modeling-confirmation.md) | 官方 enrich-context 与基础技术变更自动确认 | 已接受，2026-10-19 |  |
| [0097](0097-llm-per-table-sql-window.md) | 统一 SQL 逐表时间窗口门禁与文档收敛 | 已接受，2026-10-19。合并并取代 ADR0091、0092、0095、0096 的日期边界方案；按用户明确要求删 |  |
| [0098](0098-post-sampling-fact-window-confirmation.md) | 采样后先确认事实表时间范围，再启动语义建模 | 已接受，2026-10-19。补充ADR0097的采样后交互，数据库配置独立性及SQL门禁不变。实现使用“现有字段说明L |  |
| [0099](0099-runtime-window-policy-and-verifiable-sql-gate.md) | 动态窗口只作运行策略，门禁结果独立留证 | 已接受，2026-10-20。补充ADR0097/0098，并按用户最新确认替代“建模样例日期必须询问用户”的提示限制。 |  |
| [0100](0100-published-query-index-preparation.md) | 发布后提前准备官方问数索引 | 接受 |  |
| [0101](0101-reuse-published-mcp-for-recall-warmup.md) | 在实际查询MCP预热召回并公开准备状态 | 接受 |  |
| [0102](0102-query-date-clarification-and-modeling-samples.md) | 问数日期优先澄清与建模样例约束 | 已接受，2026-10-20。关联 spec088；补充 ADR0097/0099，保留其数据库配置来源、逐表检查及拒绝 | 部分或全部被 [ADR0103](0103-source-tiered-probing-and-row-based-gate-verdict.md) 取代 |
| [0103](0103-source-tiered-probing-and-row-based-gate-verdict.md) | 按表来源分层探查、门禁以逐表明细为准与 CLI 契约对齐 | 已接受，2026-10-20。关联 spec089；补充 ADR0098/0099/0102，替代 ADR0102 中“ |  |
| [0104](0104-upload-probe-scope-and-sql-side-aggregation.md) | 上传表探查按用途限定、全表验证的唯一性与问数统计在 SQL 中完成 | 已接受，2026-10-20。收窄 ADR0103 的上传表探查范围；其余 ADR0103 决策保持不变。 |  |
| [0105](0105-build-state-context-and-ranking-granularity.md) | 建模上下文注入编译状态、CLI 日期输出说明与问数排名粒度 | 已接受，2026-10-20。补充 ADR0104。 |  |
| [0106](0106-modeling-query-cap-cartesian-ban-and-cli-timeout-cleanup.md) | 建模查询单轮硬上限、禁止笛卡尔积、超时进程树清理与屏蔽 Cube 参考 | 已接受，2026-10-20。补充 ADR0104/0105。ADR0104 中"探查只用软约束、不新增计数"的部分由本 |  |
| [0107](0107-basic-modeling-closes-cube-and-view-files.md) | 基础语义建模对 Cube/View 文件既不读也不写，自动建模消息与探查边界一致 | 已接受，2026-10-20。补充 ADR0085/0088/0106。ADR0085、0088 中"已有 Cube/V |  |
| [0108](0108-yaml-write-guard-model-file-layout-and-cli-timing.md) | YAML 写入语法检查、models 目录文件限制、年份规则提级与 wren_cli 分段计时 | 已接受，2026-10-20。补充 ADR0105/0106。 |  |
| [0109](0109-user-confirmed-questions-replace-generated-qa-pairs.md) | 问答对只来自用户确认，基础建模不生成问答 | 已接受，2026-10-20。实施 spec090。补充 ADR0069/0103，取代 MODELING_SCRIPT |  |
| [0110](0110-platform-handles-build-overwrite-and-conditional-date-rules.md) | 自动补编译、允许整文件替换、日期优先规则按受限表条件注入 | 已接受，2026-10-20。补充 ADR0102/0105/0108。 |  |
| [0111](0111-draft-changes-reviewed-before-publish.md) | 建模修改直接进入草稿，发布前统一审阅，取消逐项确认卡 | 已接受，2026-10-21。取代 ADR0031，修改 ADR0069/0094 确认条款。 |  |
| [0112](0112-rule-first-sql-window-gate.md) | SQL 时间门禁规则优先，模型只判断规则无法确定的 SQL | 已接受，2026-10-25。补充 ADR0097/0103：ADR0097 中"检查器仅将原始 SQL 交给无工具 LLM"改为规则优先、模型兜底；登记配置 |  |
| [0113](0113-mysql8-or-doris-sources-and-literal-date-ranges.md) | 外部数据源限定 MySQL 8.0+ 或 Doris，SQL 用 MySQL 8.0 语法与日期常量范围 | 已接受，2026-10-25。补充 ADR0021（外部源只发布 MySQL）与 ADR0112（规则优先的时间门禁）。 |  |

| [0114](0114-native-query-and-engineering-integration.md) | 单库原生问数与工程能力整合 | 已接受，2026-10-26；组合验收见 spec094 |  |
