# 功能需求（spec）索引

spec 是一页纸的需求与验收标准，写代码之前先写（模板：[000-template.md](000-template.md)）。

- **进行中**：放在本目录顶层，实施完成并经真实验收后移入 `done/`。
- **done/**：已实施、已被后续 spec/ADR 取代或已关闭的历史 spec，状态列取自文件本身的记录。
- 架构取舍写 ADR（[../adr/README.md](../adr/README.md)），当前行为以 [ARCHITECTURE_zh.md](../../ARCHITECTURE_zh.md) 为准。

## 进行中

| 编号 | 标题 | 状态 |
|---|---|---|
| [090](090-user-confirmed-modeling-questions.md) | spec090：基础建模不生成问答对，用户问题逐题确认后保存 | 已实施并验收；交互由 spec091 调整 |
| [091](091-modeling-interaction-flow.md) | spec091：建模全流程交互重设计（推荐问题预填、问题直接确认、流程连贯） | 已实施，待真实验收 |
| [092](092-draft-and-review-modeling-workflow.md) | spec092：建模改为"草稿 + 发布前审阅"，多业务文档，入口与增量建模 | 已实施，待真实验收 |
| [093](093-field-screening-before-modeling.md) | spec093：建模前字段整理（筛选纳入字段、补充说明） | 已实施，待真实验收 |
| [095](095-web-search-for-query.md) | spec095：问数助手联网搜索（DeepSeek 官方搜索 API） | 已实施，待真实验收 |

## 已完成或已关闭（done/）

| 编号 | 标题 | 文件中记录的状态 |
|---|---|---|
| [001](done/001-join-first-and-batch-prepare.md) | spec 001: 跨表查询 JOIN 优先 + prepare_data_context 批量取表 | — |
| [002](done/002-anti-probe-and-count-consistency.md) | spec 002: 禁「摸底查询」硬门上移 + 回答计数一致性自检 | — |
| [003](done/003-subagent-orchestration-off-by-default.md) | spec 003: 子代理编排默认关闭（`dataagent.agent.subagents-enabled`） | — |
| [004](done/004-llm-log-readable-transcript.md) | spec 004: `LLM.log` 人工可读转录（只含 role / type / text） | — |
| [005](done/005-session-isolation-and-batch-delete.md) | spec 005: 会话隔离与批量删除 | — |
| [006](done/006-security-and-stability-fixes.md) | spec 006: 安全修复与稳定性增强 | — |
| [007](done/007-tc-landing-page-and-semantic-config.md) | spec 007: TC 式落地页与语义配置页 | — |
| [008](done/008-sandbox-session-isolation.md) | spec 008: 沙箱会话目录隔离 | — |
| [009](done/009-scheduled-tasks-ui-and-crud.md) | spec 009: 例行任务（Scheduled Tasks） | — |
| [010](done/010-cube-distinct-count-measure.md) | spec 010: Cube 度量支持去重计数（DISTINCT_COUNT） | — |
| [010](done/010-semantic-modeling-and-wren-publish.md) | spec 010: 语义建模与 WrenAI 发布流（Semantic Modeling & Wren Publish） | — |
| [011](done/011-retrieve-evidence-simple-rag.md) | spec 011: retrieve_evidence 应用层关键词召回（简单 RAG） | 已实施（2026-09-29；实施先于文档，当日由 LLM.log 分析驱动，架构取舍见 ADR 0 |
| [011](done/011-semantic-enrich-and-views.md) | 011 — 语义增强对话化：文档/对话口径落库（enrich-context 平台化）与业务视图 | 待确认 |
| [012](done/012-skill-wren-routing-branch.md) | spec 012: 技能文档补齐 wren 通道分流分支 | 已实施（2026-09-29 修补；实施先于文档）。 |
| [013](done/013-conversational-mdl-modeling.md) | spec 013: 对话式语义建模与 MDL 只读可视化 | 已批准（ADR 0024），待实施。合并自「对话式建模 M1-M3 评估」与「cube 建模增强 |
| [014](done/014-doc-enhance-term-proposals.md) | spec 014: 文档驱动语义差异分析与多类型提案 | 已实施（2026-10-02） |
| [015](done/015-wren-only-auto-baseline-and-readable-modeling-ui.md) | spec 015: Wren 单通道、基础 MDL 自动发布与建模页业务化展示 | 已实施（2026-10-02） |
| [016](done/016-wren-query-result-file-handoff.md) | spec 016: Wren 查询结果到 Python 的文件级数据交接 | — |
| [017](done/017-modeling-hitl-and-query-routing.md) | spec 017: 建模 HITL 与问数路由优化 | — |
| [018](done/018-view-dialect-gate-and-guard-escape.md) | spec 018: 视图方言门禁、发布试跑与守卫逃生门 | — |
| [019](done/019-yaml-first-semantic-modeling.md) | spec 019: 语义建模 YAML-first 全量官方化 | — |
| [020](done/020-view-schema-discovery.md) | spec 020: 视图 schema 发现对齐（prompt 注入 statement + describe 错路由引导） | 已实施（2026-10-04）。诊断实证见 2026-10-04 LLM.log（知识库 8addb |
| [021](done/021-modeling-task-row-ui.md) | spec 021: 建模对话任务行化（Claude Code 式过程呈现） | 已实施（2026-10-04）。用户需求：语义建模过程参考 Qoder / Claude Code— |
| [022](done/022-wren-official-skills-mount.md) | spec 022: 建模知识上移 wren 官方 skills——原生技能仓库挂载与 MODELING_SCRIPT 协议层瘦身 | — |
| [023](done/023-write-gate-dry-plan.md) | 023 - write_file 预检追加全工程 dry-plan 门（specs/022 后续） | — |
| [024](done/024-batch-relation-confirm.md) | specs/024 — 关系决策批量多选确认（decide_relations） | — |
| [025](done/025-official-align-purge-routing-gates.md) | specs/025 — 问数/建模全面对齐 wren 官方形态，撤自研路由门 | — |
| [026](done/026-term-scope-binding.md) | specs/026 — 业务术语绑定知识库（撤全局共享） | 已确认（用户拍板「术语也是和知识库绑定，不全局共享」，推翻 specs/011 时的「方案 A 全局 |
| [027](done/027-modeling-page-tabs-chat.md) | specs/027 — 语义建模页信息架构重组：完善度标示 + 资产分 tab + 对话建模主入口 | 已实施（交互形态经用户确认：对话窗=仅居中大窗，drawer 保留为可选） |
| [028](done/028-semantic-doc-upload-and-tab-nav.md) | specs/028 — 关系说明文档下线，上传语义文档并入建模页 + tab 导航重构 + 废弃上传自动建图 | 已实施（mvn test 360 绿 + npm build 绿；G1-G4 代码面达成，运行时点检 |
| [029](done/029-modeling-memory-group-isolation.md) | specs/029 — 建模助手长时记忆按知识库（DatasetGroup）隔离 | 已实施并运行时验证通过（2026-10-04 重启后 4444/红海两库点检 G2/G3/G4 全绿 |
| [030](done/030-modeling-workbench.md) | spec 030: 语义建模页工作台化 | — |
| [034](done/034-ref-sql-derived-model.md) | spec 034: ref_sql 派生模型——复杂口径的一等载体 | — |
| [035](done/035-view-first-ref-sql-manual-route.md) | spec 035: 回归官方 sink 分工——view 为 agent 首选载体，ref_sql 降级人工载体 | — |
| [036](done/036-fact-dim-relations-and-concise-review.md) | spec 036: 关系只生成事实↔维、提案/候选精简、确认一次即生效 | — |
| [037](done/037-cube-view-consistency-and-refresh.md) | spec 037: Cube/视图「页签计数×内容」同源、REST 写侧同步工作区、刷新链路补全 | — |
| [038](done/038-lazy-sandbox-and-orphan-cleanup.md) | spec 038: 懒加载沙箱 + 启动时孤儿容器清理 | — |
| [039](done/039-question-driven-modeling.md) | spec 039：对话建模的常用问题与业务验收闭环 | — |
| [040](done/040-guided-modeling-workflow.md) | spec 040：引导式语义建模工作台 | — |
| [041](done/041-conversation-first-multi-question-workbench.md) | spec 041：对话主工作区与多问题建模 | 已实现；验证见 docs/archive/validation/2026-10-07-convers |
| [042](done/042-visible-business-document-entry.md) | spec 042：显著业务文档入口与上传反馈 | 已实现，验证见 docs/archive/validation/2026-10-07-busines |
| [043](done/043-modeling-repair-and-chat-space.md) | spec 043：建模提案修正与对话空间 | — |
| [044](done/044-question-review-evidence-and-actions.md) | spec 044：问题验收依据与清晰操作闭环 | — |
| [045](done/045-question-first-modeling.md) | spec 045：先提交问题，再对话建模，统一结果确认 | — |
| [046](done/046-modeling-assets-and-review-journey.md) | spec 046：问题资产落点与清晰的建模审阅流程 | — |
| [047](done/047-modeling-background-and-question-table.md) | spec 047: 建模业务背景与批量问题表格 | — |
| [048](done/048-business-plan-review-and-candidate-workspace.md) | spec 048: 关键业务口径确认与隔离候选工程 | — |
| [049](done/049-modeling-acceptance-and-version-publication.md) | spec 049: 业务结果验收与完整版本发布 | — |
| [050](done/050-published-context-recall-and-cube-routing.md) | spec 050: 已发布上下文、示例召回与 Cube 问数 | — |
| [051](done/051-query-correction-and-model-repair.md) | spec 051：明确的查询修正与模型修复 | — |
| [052](done/052-durable-session-history-and-artifacts.md) | spec 052: 持久会话历史与附件生命周期 | — |
| [052](done/052-preflight-and-batched-business-plan.md) | spec 052：技术自动修复与一次业务方案确认 | — |
| [053](done/053-configurable-history-retention.md) | spec 053: 可配置的历史会话保留任务 | — |
| [054](done/054-wren-modeling-and-answer-feedback-memory.md) | spec 054：可选预设问题与最终答案点赞记忆 | 已实现并通过构建与自动化验证（2026-10-09）。ADR 0062 为本规范依据；与旧 spec |
| [055](done/055-sql-only-modeling-and-example-recall.md) | spec 055：取消 Cube 与加强问数示例召回 | 代码实现与本轮验证完成（2026-10-10）。用户自行删除旧数据，本轮不迁移、不删除旧资产。 |
| [056](done/056-platform-rules-and-liked-business-memory.md) | spec 056：统一问数规则与点赞业务样例 | — |
| [057](done/057-wren-official-capability-boundary.md) | 057-wren-official-capability-boundary.md | — |
| [058](done/058-wren-native-modeling-and-query-agents.md) | spec 058：官方 Wren 建模与问数的通用 Agent 集成 | 用户已确认，实施中。详细任务和阶段验收见 [实施计划](../../archive/plans/05 |
| [059](done/059-chat-capacity-and-queuing.md) | Spec 059: 问答并发容量控制与排队 | 已实施 |
| [059](done/059-official-mcp-query-receipts-and-feedback.md) | 059-official-mcp-query-receipts-and-feedback.md | 实施中 |
| [060](done/060-modeling-session-pacing.md) | 060-modeling-session-pacing.md | 实施中 |
| [061](done/061-wren-limit-and-call-efficiency.md) | 061：Wren LIMIT 修复与调用精简 | — |
| [062](done/062-official-wren-vector-memory.md) | Spec 062：官方 Wren 向量记忆检索 | — |
| [063](done/063-query-memory-and-tool-presentation.md) | Spec 063：两类问答召回与问数工具展示 | — |
| [064](done/064-official-wren-only-query-tools.md) | spec 064：清理问数 Agent 的项目自定义工具 | — |
| [065](done/065-sql-qa-management-and-view-file.md) | SQL 问答对管理与视图文件读取 | — |
| [066](done/066-platform-increment-integration.md) | 平台增量合入分析 | 已完成适用增量移植和验证，变更留在当前分支工作区，未提交 Git。对应 ADR 0077。 |
| [067](done/067-platform-followup-integration.md) | spec 067：平台并发与失败处理后续集成 | — |
| [068](done/068-bounded-sampling-and-wren-context.md) | 068：有界采样与 Wren 样本复用 | — |
| [068](done/068-single-host-release.md) | spec 068：单机发布与来源回归补齐 | — |
| [069](done/069-native-wren-tools-and-answer-evidence.md) | spec 069：官方工具直接调用与建模、问数证据 | — |
| [070](done/070-mdl-first-sampling-context.md) | 070-mdl-first-sampling-context.md | — |
| [071](done/071-time-bounded-analytical-queries.md) | 071 时间字段查询边界 | — |
| [072](done/072-mdl-driven-temporal-boundary.md) | 072 基于 MDL 的事实模型时间边界 | — |
| [073](done/073-separate-cube-modeling-stage.md) | 073 基础语义建模与 Cube 分阶段 | — |
| [074](done/074-disable-modeling-probes.md) | 074 禁用建模自动探测 | — |
| [075](done/075-native-wren-display-and-efficiency.md) | spec 075：原生Wren展示与调用效率 | — |
| [076](done/076-explicit-views-and-question-date-gate.md) | spec076：显式视图、问数日期门禁与耗时记录 | — |
| [077](done/077-query-latency-cancellation-and-date-resolution.md) | Spec 077：问数耗时、取消恢复与月日日期解析 | — |
| [078](done/078-sql-boundary-only-date-interception.md) | spec078：SQL 执行前日期条件拦截 | — |
| [080](done/080-official-modeling-and-technical-confirmation.md) | spec080：官方建模流程与技术变更自动确认 | — |
| [081](done/081-autopilot-fact-query-window.md) | 081 — Auto-pilot 与事实表小时间窗口 | — |
| [082](done/082-external-fact-table-query-window.md) | 外部事实表问数时间范围配置 | — |
| [083](done/083-llm-per-table-sql-window.md) | spec083：统一逐表 SQL 时间窗口门禁 | — |
| [084](done/084-post-sampling-fact-window-confirmation.md) | spec084：采样后事实表时间范围确认 | — |
| [085](done/085-runtime-window-policy-and-gate-verification.md) | spec085：动态时间策略隔离与工具门禁验收 | — |
| [086](done/086-query-preparation-latency.md) | spec086：问数索引准备与分段耗时 | — |
| [087](done/087-mcp-recall-warmup-and-preparation-status.md) | spec087：实际MCP召回预热与准备状态 | — |
| [088](done/088-query-date-clarification-and-modeling-samples.md) | spec088：问数日期优先澄清与建模样例约束 | — |
| [089](done/089-source-tiered-probing-and-cli-contract.md) | spec089：按表来源分层探查、门禁明细为准与 CLI 契约对齐 | — |

| [094](done/094-native-boundary-engineering-integration.md) | 原生建模问数与工程能力整合 | 已实施并完成隔离组合验收，2026-10-26 |

- [096 BI人员与公共库](done/096-bi-directory-and-public-knowledge.md)（本地验收完成，现场BI待验）
