> 后续交付：spec128–131已统一纳入[本地检查点](20261112-architecture-checkpoint.md)，并补当前完整验证。本报告保留原阶段的测量范围与提交状态。

> spec130将通用计时类迁入runtime，相关源码链接已跟随迁移；本报告数字仍为原阶段实测。

# 测试分层与旧链路清理验证（2026-11-12）

本轮按[spec129](../specs/129-test-lanes-and-legacy-cleanup.md)在codex/architecture-phase1实施，保留阶段1全部未提交修改；基线5479d152。原开发启动、打包和人工部署入口不变，未提交/push、合并或部署，GitHub首次CI尚待推送后验证。

## 已完成与价值

- 新增[scripts/check.py](../../scripts/check.py)：后端类/方法选测、单元通道、前端文件/前缀及完整检查。后端日常检查跳过npm，避免为后端小改重复构建前端；类/方法/文件/前缀错误明确失败。
- 13个上下文/持久化类标记integration，外部环境检查标记external；两个混合类仅标记外部方法，本地进程/资源回归仍进单元通道。原Maven test和npm test默认范围不变，CI不按标签过滤。
- H2DemoSeedTest从完整应用改为文件H2/JPA切片，新增实际JDBC URL断言；bob/alice/admin与密码断言保留。MarketContributionServiceTest用真实JPA、事务及临时文件，Bootstrap只替身cwd、沙箱注册表隔离；提交/审批/拒绝/权限/校验7场景保留。JpaPersistenceIntegrationTest保留完整应用装配4场景；原12项没有删减。
- 删除5个旧测试类及对应无生产调用链路：RefSqlMaterializerTest(17)、KnowledgeEvidenceTest(9)、DatasetServiceEvidenceTest(8)、WrenToolkitTest(38)、WrenMcpToolkitTest(2)，合计74项。配套移除4个生产文件、DatasetContextProvider/DatasetService.evidenceFor及无引用TenantTablesFixture。生产装配与调用搜索无引用、当前问数由WrenScopedMcpClient承担；此次删除减少维护负担，旧74项仅约0.189秒，不是主要提速来源。
- 3个仍有价值的条件集成类（OfficialWrenCliIntegrationTest、WrenQueryMemoryIntegrationTest、WrenLiveQueryEvaluationTest）迁至当前原生MCP。有效CSV引号、换行、前导零、false/0边界迁为QueryDataFilesTest新增1项。KGServiceTest、SchemaTripleExtractorTest及历史数据/开关本轮保留。
- CI增加15项Python工具逻辑/日志单元检查；另有6项报告脚本回归。其余需要MySQL/Docker/分析环境的Python专项仍独立验收，未把122项Python全部宣称已运行。
- AGENTS与[CI指引](../guides/dataagent-ci.md)明确日常按改动选测、合并/发布前完整clean verify；不再要求每个小改机械重复全量和打包。

## 本次实测

Windows、Java17.0.14；完整验证使用当前非忽略源码的独立副本，npm ci不触碰正在使用的开发目录node_modules。依赖来自阶段1已按同checkout构建的本地Maven仓库；本轮没有再次执行整个框架测试。

| 检查 | 结果/范围 | 耗时 |
| --- | --- | --- |
| 重点后端回归 | 64项通过，含恢复12项、原生MCP、问答记忆、CSV及装配规则 | Maven48.093秒，含源码重编译 |
| `python scripts/check.py backend --unit` | 137类、1015项通过，0跳过，13个integration类均未运行，无npm目标 | Maven42.967秒，已有编译缓存 |
| `backend --tests QueryDataFilesTest` | CLI实际调用Maven，4项通过，无npm目标 | Maven26.996秒，含迁移夹具重编译 |
| 前端选测auth-session.test.mjs | 仅根前缀13项通过 | Node报告约0.222秒，不含Vite编译 |
| 无效选择 | 类/方法/文件退出2，未知前缀退出1 | 调用前校验 |
| `clean verify -Dfrontend.npm.install.arguments=ci` | 后端163类1077项：1056通过、0失败/错误、21条件跳过；前端22文件×三个前缀，每前缀105项；Spotless、TypeScript/Vite、JAR/exec JAR/tar通过 | Maven3分49秒，含新安装与全部构建 |
| Python独立逻辑 / CI报告脚本 | 15 / 6项通过 | 0.190 / 0.176秒 |
| 报告门禁与工作流语法 | 完整XML汇总通过，三个必跑类4/1/7均执行且无跳过；actionlint通过 | 非业务耗时基准 |

本次后端总数变化为1150−74+1=1077；不是覆盖率提升，也没有隐藏21项条件跳过。原1150项的基线与逐类保留理由见[盘点](20261112-test-class-inventory.md)。本轮完整XML与日志在忽略目录.runtime/phase2-ci-validation及.runtime/phase2-full.log，不能与主目录选测后的旧XML混合统计。

收尾文档一致性5项、1174条相对链接、538个Java/pom/测试入口与完整验证副本逐字节一致性、git diff --check通过。后续只更新了报告和共享记忆，未改变已验证源码。

## 耗时比较与开发感知

| 范围 | 阶段1 | 本轮 | 解读 |
| --- | --- | --- | --- |
| H2DemoSeedTest（1项） | 4.172秒 | 1.211秒 | 真实文件H2/JPA切片 |
| MarketContributionServiceTest（7项） | 4.834秒 | 1.205秒 | 保留真实数据库事务及文件 |
| JpaPersistenceIntegrationTest（4项） | 2.964秒 | 2.305秒 | 仍完整应用；差异含运行波动 |
| 全部Java类报告累计 | 86.885秒 | 63.165秒 | 含上述优化和缓存/负载波动，非稳定性能承诺 |
| 全量clean verify | 约3分42秒 | 约3分49秒 | 安装、编译、前端及打包主导，不宣称完整构建变快 |

日常只改后端时可以只选相关类，无需执行前端；涉及数据库/HTTP/装配再追加相关integration。只改前端时选相关文件，类型和产品构建仍用npm run build。跨模块风险较高时运行单元通道；合并/发布前仍全量。一次样本不能保证以后固定耗时，不能靠删掉有效场景换速度。

## 限制与后续

21项跳过仍是LLM、Wren、MySQL、Docker或分析依赖条件；迁移后的3个真实Wren/模型测试已编译，但本轮未实际运行其外部场景。Python检查使用模拟环境，输出不代表真实依赖已验收。IDE直接JUnit不自动继承Surefire的测试HOME，宜委托Maven或设置独立测试user.home；正常应用不要加测试参数。

首轮发现H2切片缺Jackson配置、旧接口残留和迁移测试编译/格式问题，均修正后通过上述完整构建。未启动/重启服务、连接生产或验证线上行为。当前大类职责拆分、依赖契约、数据库迁移/备份恢复仍属后续治理，本轮完成不代表整个项目架构已无风险。

## 本轮完整后端逐类运行记录

下表来自单次干净完整报告；每类现行断言与保留价值沿用[原逐类清单](20261112-test-class-inventory.md)，上述5个已删除类除外，迁移和收窄差异已在前文列明。`integration`为上下文/持久化通道；`external`可能仅标类中方法。耗时包含该类初始化。

| 测试类（当前源码） | 用例 | 跳过 | 秒 | 标签 |
| --- | ---: | ---: | ---: | --- |
| [DocsConsistencyTest](../../src/test/java/io/agentscope/dataagent/DocsConsistencyTest.java) | 5 | 0 | 0.096 | 本地 |
| [AnswerQueryMemoryTest](../../src/test/java/io/agentscope/dataagent/dataset/AnswerQueryMemoryTest.java) | 9 | 0 | 1.957 | 本地 |
| [AssetExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetExampleServiceTest.java) | 3 | 0 | 0.121 | 本地 |
| [AssetProposalStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetProposalStoreTest.java) | 3 | 0 | 0.223 | 本地 |
| [BaselineMdlServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BaselineMdlServiceTest.java) | 5 | 0 | 0.48 | 本地 |
| [BiExtraSyncStatusTest](../../src/test/java/io/agentscope/dataagent/dataset/BiExtraSyncStatusTest.java) | 4 | 0 | 0.19 | 本地 |
| [BiSyncStatusServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BiSyncStatusServiceTest.java) | 5 | 0 | 0.046 | 本地 |
| [BusinessDocumentContentTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessDocumentContentTest.java) | 1 | 0 | 0.042 | 本地 |
| [BusinessTermServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessTermServiceTest.java) | 10 | 0 | 0.481 | 本地 |
| [DataSourceEngineTest](../../src/test/java/io/agentscope/dataagent/dataset/DataSourceEngineTest.java) | 2 | 0 | 0.004 | 本地 |
| [DatasetImportLimitsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportLimitsTest.java) | 9 | 0 | 0.882 | 本地 |
| [DatasetImportRollbackTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportRollbackTest.java) | 4 | 0 | 0.276 | 本地 |
| [DatasetMetadataDraftSyncTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSyncTest.java) | 7 | 0 | 0.414 | 本地 |
| [DatasetParsingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetParsingTest.java) | 9 | 0 | 0.011 | 本地 |
| [DatasetSamplingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetSamplingTest.java) | 4 | 0 | 0.19 | 本地 |
| [DatasetServiceMdlDirtyTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceMdlDirtyTest.java) | 14 | 0 | 0.262 | 本地 |
| [DatasetServiceSourceCombinationTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceSourceCombinationTest.java) | 11 | 0 | 0.056 | 本地 |
| [DatasetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceTest.java) | 4 | 0 | 0.004 | 本地 |
| [DatasetUploadAdmissionTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadAdmissionTest.java) | 5 | 0 | 0.014 | 本地 |
| [DatasetUploadIntakeTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadIntakeTest.java) | 6 | 0 | 0.204 | 本地 |
| [DatasetUploadPolicyDefaultsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadPolicyDefaultsTest.java) | 2 | 0 | 0.134 | 本地 |
| [DemoDataServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoDataServiceTest.java) | 5 | 0 | 0.07 | 本地 |
| [DemoSampleFilesTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoSampleFilesTest.java) | 1 | 0 | 1.214 | 本地 |
| [DocEnhanceServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DocEnhanceServiceTest.java) | 7 | 0 | 0.415 | 本地 |
| [DraftItemServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftItemServiceTest.java) | 9 | 0 | 1.236 | 本地 |
| [DraftReviewServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftReviewServiceTest.java) | 8 | 0 | 0.609 | 本地 |
| [ExcelParserTest](../../src/test/java/io/agentscope/dataagent/dataset/ExcelParserTest.java) | 3 | 0 | 0.141 | 本地 |
| [ExternalDataSourcePolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicyTest.java) | 16 | 0 | 0.121 | 本地 |
| [FactQueryWindowPolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicyTest.java) | 8 | 0 | 0.129 | 本地 |
| [FactTableWindowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FactTableWindowServiceTest.java) | 8 | 0 | 0.023 | 本地 |
| [FieldScreeningServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FieldScreeningServiceTest.java) | 7 | 0 | 0.459 | 本地 |
| [KnowledgeBaseAccessServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessServiceTest.java) | 8 | 0 | 0.15 | 本地 |
| [KnowledgeBaseBrowseTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseBrowseTest.java) | 5 | 0 | 0.544 | 本地 |
| [KnowledgeGraphServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeGraphServiceTest.java) | 6 | 0 | 0.141 | 本地 |
| [MdlCatalogTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlCatalogTest.java) | 8 | 0 | 0.243 | 本地 |
| [MdlPublishServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishServiceTest.java) | 24 | 0 | 3.569 | 本地 |
| [MdlPublishWiringTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishWiringTest.java) | 1 | 0 | 0.331 | 本地 |
| [MdlQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionServiceTest.java) | 10 | 0 | 0.103 | 本地 |
| [MdlQuestionStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionStoreTest.java) | 31 | 0 | 2.668 | 本地 |
| [MdlSeederTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSeederTest.java) | 9 | 0 | 0.509 | 本地 |
| [MdlSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSuggestionServiceTest.java) | 29 | 0 | 0.428 | 本地 |
| [MdlWorkspaceReaderTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlWorkspaceReaderTest.java) | 13 | 0 | 0.428 | 本地 |
| [ModelingConfirmationServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingConfirmationServiceTest.java) | 6 | 0 | 0.365 | 本地 |
| [ModelingHubServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingHubServiceTest.java) | 3 | 0 | 0.08 | 本地 |
| [ModelingRevisionTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingRevisionTest.java) | 2 | 0 | 2.53 | integration |
| [ModelingWorkflowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingWorkflowServiceTest.java) | 7 | 0 | 0.44 | 本地 |
| [ProcessWrenCliTest](../../src/test/java/io/agentscope/dataagent/dataset/ProcessWrenCliTest.java) | 4 | 0 | 0.357 | 本地 |
| [PublishedAssetWindowsTest](../../src/test/java/io/agentscope/dataagent/dataset/PublishedAssetWindowsTest.java) | 5 | 0 | 0.003 | 本地 |
| [QuestionDependenciesTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionDependenciesTest.java) | 2 | 0 | 0.141 | 本地 |
| [QuestionSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionSuggestionServiceTest.java) | 4 | 0 | 0.002 | 本地 |
| [RecommendedQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/RecommendedQuestionServiceTest.java) | 8 | 0 | 0.054 | 本地 |
| [SchemaTripleExtractorTest](../../src/test/java/io/agentscope/dataagent/dataset/SchemaTripleExtractorTest.java) | 1 | 0 | 0.002 | 本地 |
| [SemanticAssetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SemanticAssetServiceTest.java) | 21 | 0 | 0.816 | 本地 |
| [SharedDraftWrenAuthorizationTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedDraftWrenAuthorizationTest.java) | 12 | 0 | 0.361 | 本地 |
| [SharedOfficialWrenAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedOfficialWrenAccessTest.java) | 1 | 0 | 0.065 | 本地 |
| [SqlExampleDocumentTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleDocumentTest.java) | 14 | 0 | 0.02 | 本地 |
| [SqlExampleRecallTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleRecallTest.java) | 4 | 0 | 0.02 | 本地 |
| [SqlQaExampleMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleMemoryIntegrationTest.java) | 1 | 1 | 0.014 | external |
| [SqlQaExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleServiceTest.java) | 10 | 0 | 1.337 | 本地 |
| [SqlWindowAuditTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowAuditTest.java) | 1 | 0 | 0.005 | 本地 |
| [SqlWindowGateTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowGateTest.java) | 12 | 0 | 0.096 | 本地 |
| [SqlWindowRulesTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowRulesTest.java) | 6 | 0 | 0.004 | 本地 |
| [SyntheticDemoMysqlTest](../../src/test/java/io/agentscope/dataagent/dataset/SyntheticDemoMysqlTest.java) | 1 | 1 | 0.0 | external |
| [TableProvisionerTest](../../src/test/java/io/agentscope/dataagent/dataset/TableProvisionerTest.java) | 2 | 0 | 0.0 | 本地 |
| [TemporalConditionTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalConditionTest.java) | 4 | 0 | 0.035 | 本地 |
| [TemporalSamplesTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalSamplesTest.java) | 4 | 0 | 0.059 | 本地 |
| [WrenProfileHomeTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenProfileHomeTest.java) | 11 | 0 | 0.076 | 本地 |
| [WrenPropertiesResolveTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPropertiesResolveTest.java) | 6 | 0 | 0.027 | 本地 |
| [WrenPublishedProjectTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPublishedProjectTest.java) | 4 | 0 | 0.458 | 本地 |
| [WrenQueryAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryAccessTest.java) | 2 | 0 | 0.002 | 本地 |
| [WrenQueryMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryMemoryIntegrationTest.java) | 1 | 1 | 0.002 | external |
| [WrenQueryPreparationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryPreparationTest.java) | 4 | 0 | 0.094 | 本地 |
| [WrenSkillsLocatorTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenSkillsLocatorTest.java) | 8 | 0 | 0.093 | 本地 |
| [WrenTimingIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenTimingIntegrationTest.java) | 1 | 1 | 0.003 | external |
| [SchemaTableRoleTest](../../src/test/java/io/agentscope/dataagent/dataset/parser/SchemaTableRoleTest.java) | 2 | 0 | 0.034 | 本地 |
| [SubagentOrchestrationSwitchTest](../../src/test/java/io/agentscope/dataagent/runtime/SubagentOrchestrationSwitchTest.java) | 2 | 0 | 0.015 | 本地 |
| [WebhookInboundMapperTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundMapperTest.java) | 8 | 0 | 0.004 | 本地 |
| [WebhookSignatureTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignatureTest.java) | 8 | 0 | 0.023 | 本地 |
| [GatewayTurnLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/GatewayTurnLifecycleTest.java) | 3 | 0 | 0.16 | 本地 |
| [HarnessGatewaySandboxlessTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/HarnessGatewaySandboxlessTest.java) | 3 | 0 | 0.074 | 本地 |
| [ManagedModelHttpTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ManagedModelHttpTest.java) | 11 | 0 | 0.635 | 本地 |
| [ModelServiceCipherTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceCipherTest.java) | 2 | 0 | 0.028 | 本地 |
| [ModelServiceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistryTest.java) | 6 | 0 | 0.291 | 本地 |
| [SwitchingModelTest](../../src/test/java/io/agentscope/dataagent/runtime/model/SwitchingModelTest.java) | 2 | 0 | 0.002 | 本地 |
| [AnalysisSkillMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/AnalysisSkillMiddlewareTest.java) | 3 | 0 | 0.003 | 本地 |
| [DataDynamicContextMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/DataDynamicContextMiddlewareTest.java) | 12 | 0 | 0.013 | 本地 |
| [GroupScopedMemoryNamespaceTest](../../src/test/java/io/agentscope/dataagent/runtime/session/GroupScopedMemoryNamespaceTest.java) | 6 | 0 | 0.004 | 本地 |
| [ProjectFilesContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/ProjectFilesContextTest.java) | 2 | 0 | 0.052 | 本地 |
| [QuestionDateGateTest](../../src/test/java/io/agentscope/dataagent/runtime/session/QuestionDateGateTest.java) | 5 | 0 | 0.051 | 本地 |
| [RuntimeWindowContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/RuntimeWindowContextTest.java) | 7 | 0 | 0.081 | 本地 |
| [SessionStateLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/session/SessionStateLifecycleTest.java) | 4 | 0 | 0.11 | 本地 |
| [WrenSamplingContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenSamplingContextTest.java) | 9 | 0 | 0.115 | 本地 |
| [WrenScopedToolkitMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenScopedToolkitMiddlewareTest.java) | 4 | 0 | 0.065 | 本地 |
| [WrenConnectionRecoveryIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryIntegrationTest.java) | 2 | 2 | 0.003 | external |
| [WrenConnectionRecoveryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryTest.java) | 30 | 0 | 0.458 | 本地 |
| [WrenInstanceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistryTest.java) | 18 | 0 | 0.441 | 本地 |
| [WrenMcpClassLoaderTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMcpClassLoaderTest.java) | 2 | 0 | 0.014 | 本地 |
| [WrenMysqlLimitIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMysqlLimitIntegrationTest.java) | 1 | 1 | 0.001 | external |
| [WrenProcessEnvironmentTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenProcessEnvironmentTest.java) | 2 | 1 | 0.012 | external |
| [AnalysisScriptsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisScriptsTest.java) | 4 | 4 | 0.872 | external |
| [AnalysisSkillsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisSkillsTest.java) | 4 | 0 | 0.082 | 本地 |
| [ChartBuilderTest](../../src/test/java/io/agentscope/dataagent/tools/data/ChartBuilderTest.java) | 9 | 0 | 0.032 | 本地 |
| [DataAgentToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/DataAgentToolkitTest.java) | 4 | 0 | 0.003 | 本地 |
| [ModelingToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/ModelingToolkitTest.java) | 64 | 0 | 1.892 | 本地 |
| [OfficialWrenCliIntegrationTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenCliIntegrationTest.java) | 1 | 1 | 0.003 | external |
| [OfficialWrenToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkitTest.java) | 31 | 0 | 1.486 | 本地 |
| [QueryDataFilesTest](../../src/test/java/io/agentscope/dataagent/tools/data/QueryDataFilesTest.java) | 4 | 0 | 0.12 | 本地 |
| [RunPythonToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/RunPythonToolTest.java) | 5 | 0 | 0.004 | 本地 |
| [WebSearchToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/WebSearchToolTest.java) | 7 | 0 | 1.704 | 本地 |
| [WrenScopedMcpClientTest](../../src/test/java/io/agentscope/dataagent/tools/data/WrenScopedMcpClientTest.java) | 19 | 0 | 0.074 | 本地 |
| [AgentDraftServiceTest](../../src/test/java/io/agentscope/dataagent/web/ai/AgentDraftServiceTest.java) | 3 | 0 | 0.004 | 本地 |
| [AdminBiSyncControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminBiSyncControllerTest.java) | 3 | 0 | 0.728 | 本地 |
| [AdminHistoryHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminHistoryHttpTest.java) | 4 | 0 | 6.58 | integration |
| [AdminModelServiceControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminModelServiceControllerTest.java) | 3 | 0 | 0.201 | 本地 |
| [AnswerFeedbackControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AnswerFeedbackControllerTest.java) | 2 | 0 | 0.233 | 本地 |
| [BusinessTermControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/BusinessTermControllerTest.java) | 1 | 0 | 0.005 | 本地 |
| [ChartOwnershipHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/ChartOwnershipHttpTest.java) | 5 | 0 | 1.765 | integration |
| [ChatControllerAnalysisTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerAnalysisTest.java) | 4 | 0 | 0.039 | 本地 |
| [ChatControllerHitlTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerHitlTest.java) | 18 | 0 | 0.825 | 本地 |
| [ChatControllerInboundShapeTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerInboundShapeTest.java) | 2 | 0 | 0.003 | 本地 |
| [DatasetControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetControllerTest.java) | 9 | 0 | 0.155 | 本地 |
| [DatasetGroupControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetGroupControllerTest.java) | 7 | 0 | 0.176 | 本地 |
| [ExternalDataSourceConnectionTest](../../src/test/java/io/agentscope/dataagent/web/api/ExternalDataSourceConnectionTest.java) | 9 | 0 | 0.097 | 本地 |
| [QueryPreparationControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/QueryPreparationControllerTest.java) | 2 | 0 | 0.064 | 本地 |
| [RecommendedQuestionControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/RecommendedQuestionControllerTest.java) | 2 | 0 | 0.065 | 本地 |
| [SemanticModelingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingControllerTest.java) | 15 | 0 | 0.474 | 本地 |
| [SemanticModelingCubeRetirementTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingCubeRetirementTest.java) | 1 | 0 | 0.009 | 本地 |
| [UserBindingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/UserBindingControllerTest.java) | 2 | 0 | 0.016 | 本地 |
| [BiAccountKnowledgeHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAccountKnowledgeHttpTest.java) | 6 | 0 | 2.894 | integration |
| [BiAuthHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAuthHttpTest.java) | 3 | 0 | 1.426 | integration |
| [BiTicketCodecTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiTicketCodecTest.java) | 5 | 0 | 0.099 | 本地 |
| [JwtSessionExpiryTest](../../src/test/java/io/agentscope/dataagent/web/auth/JwtSessionExpiryTest.java) | 2 | 0 | 0.004 | 本地 |
| [ChatCapacityTest](../../src/test/java/io/agentscope/dataagent/web/chat/ChatCapacityTest.java) | 7 | 0 | 1.048 | 本地 |
| [AgentStateConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateConfigTest.java) | 6 | 0 | 0.396 | 本地 |
| [AgentStateMysqlIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateMysqlIntegrationTest.java) | 3 | 3 | 0.0 | external |
| [DataAgentConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/DataAgentConfigTest.java) | 20 | 0 | 0.096 | 本地 |
| [EmptyOrdersQueryFixtureTest](../../src/test/java/io/agentscope/dataagent/web/config/EmptyOrdersQueryFixtureTest.java) | 3 | 0 | 0.338 | 本地 |
| [QueryDateBehaviorLiveTest](../../src/test/java/io/agentscope/dataagent/web/config/QueryDateBehaviorLiveTest.java) | 1 | 1 | 0.0 | external |
| [SqlWindowLiveBoundaryTest](../../src/test/java/io/agentscope/dataagent/web/config/SqlWindowLiveBoundaryTest.java) | 1 | 1 | 0.0 | external |
| [WrenLiveModelingEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveModelingEvaluationTest.java) | 1 | 1 | 0.0 | external |
| [WrenLiveQueryEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveQueryEvaluationTest.java) | 1 | 1 | 0.0 | external |
| [MarketContributionServiceTest](../../src/test/java/io/agentscope/dataagent/web/marketplace/MarketContributionServiceTest.java) | 7 | 0 | 1.205 | integration |
| [DebugLoggingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddlewareTest.java) | 6 | 0 | 0.006 | 本地 |
| [ExecutionTimingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/observability/ExecutionTimingMiddlewareTest.java) | 1 | 0 | 0.009 | 本地 |
| [ModelingHitlMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/ModelingHitlMiddlewareTest.java) | 16 | 0 | 0.028 | 本地 |
| [OnboardingControllerTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingControllerTest.java) | 2 | 0 | 0.005 | 本地 |
| [OnboardingServiceTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingServiceTest.java) | 17 | 0 | 0.103 | 本地 |
| [AgentVisibilityQueryTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/AgentVisibilityQueryTest.java) | 1 | 0 | 0.937 | integration |
| [BiLoginPersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/BiLoginPersistenceTest.java) | 2 | 0 | 0.965 | integration |
| [DatasetGroupMdlBackfillTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/DatasetGroupMdlBackfillTest.java) | 4 | 0 | 0.111 | integration |
| [H2DemoSeedTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/H2DemoSeedTest.java) | 1 | 0 | 1.211 | integration |
| [JpaPersistenceIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceIntegrationTest.java) | 4 | 0 | 2.305 | integration |
| [JpaUserBindingStoreTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserBindingStoreTest.java) | 1 | 0 | 0.035 | integration |
| [ModelServicePersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/ModelServicePersistenceTest.java) | 1 | 0 | 0.823 | integration |
| [WorkspaceScaffolderTest](../../src/test/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolderTest.java) | 2 | 0 | 0.09 | 本地 |
| [ConversationScopeRegistryTest](../../src/test/java/io/agentscope/dataagent/web/session/ConversationScopeRegistryTest.java) | 5 | 0 | 0.091 | 本地 |
| [SessionTurnParserTest](../../src/test/java/io/agentscope/dataagent/web/session/SessionTurnParserTest.java) | 1 | 0 | 0.002 | 本地 |
| [RequestEventIsolationTest](../../src/test/java/io/agentscope/dataagent/web/toolbus/RequestEventIsolationTest.java) | 1 | 0 | 0.004 | 本地 |
| [SharedSkillContentTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedSkillContentTest.java) | 12 | 0 | 0.008 | 本地 |
| [SharedWorkspaceSeederTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeederTest.java) | 8 | 0 | 0.742 | 本地 |
| [UserControlFilesystemTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserControlFilesystemTest.java) | 1 | 0 | 0.014 | 本地 |
| [UserSandboxCapacityTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxCapacityTest.java) | 2 | 0 | 0.004 | 本地 |
| [UserSandboxDockerReadinessTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxDockerReadinessTest.java) | 15 | 1 | 0.587 | external |
