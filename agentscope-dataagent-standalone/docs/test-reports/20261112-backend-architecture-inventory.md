# 后端结构与平台库表全量目录（spec133）

此目录来自本批最终生产源码的JDK17 JavacTask语法树解析；解析379个文件、836个类型声明（含嵌套、record、匿名类型），方法与显式构造器数量见下表。代码行包含注释/空行/注解，长度只用于定位阅读热点。逐文件结构盘点不等于逐行逻辑审计；深入核查范围和结论见[工程分析报告](20261112-architecture-six-stage-acceptance.md)。

## 包目录

| 包 | 顶层类型/文件 |
| --- | --- |
| `dataset` | 96 |
| `dataset.parser` | 8 |
| `runtime` | 1 |
| `runtime.channel.webhook` | 7 |
| `runtime.config` | 10 |
| `runtime.context` | 1 |
| `runtime.gateway` | 1 |
| `runtime.marketplace` | 8 |
| `runtime.model` | 4 |
| `runtime.observability` | 1 |
| `runtime.outbound` | 4 |
| `runtime.session` | 30 |
| `runtime.session.tool` | 1 |
| `runtime.wren` | 4 |
| `tools.data` | 22 |
| `web` | 1 |
| `web.ai` | 2 |
| `web.api` | 46 |
| `web.artifact` | 2 |
| `web.audit` | 2 |
| `web.auth` | 7 |
| `web.binding` | 2 |
| `web.catalog` | 5 |
| `web.chat` | 2 |
| `web.config` | 4 |
| `web.identity` | 1 |
| `web.marketplace` | 6 |
| `web.middleware` | 3 |
| `web.onboarding` | 2 |
| `web.persistence.jpa` | 72 |
| `web.scaffold` | 1 |
| `web.session` | 7 |
| `web.share` | 3 |
| `web.template` | 2 |
| `web.toolbus` | 2 |
| `web.usage` | 1 |
| `web.util` | 1 |
| `web.workspace` | 7 |

## 逐文件职责归属与方法规模

归属以当前包表达，主类采用文件名；同文件嵌套DTO/record不强制拆成单文件。方法计数包含构造器、record显式构造器及匿名/嵌套类型方法，不含编译器生成方法。

| 生产文件 | 方法/构造器 | 最长方法（行） |
| --- | --- | --- |
| [dataset/AnswerQueryMemory.java](../../src/main/java/io/agentscope/dataagent/dataset/AnswerQueryMemory.java) | 32 | candidates（173） |
| [dataset/AssetExampleService.java](../../src/main/java/io/agentscope/dataagent/dataset/AssetExampleService.java) | 10 | input（36） |
| [dataset/AssetProposalStore.java](../../src/main/java/io/agentscope/dataagent/dataset/AssetProposalStore.java) | 8 | propose（52） |
| [dataset/AssetReviewStore.java](../../src/main/java/io/agentscope/dataagent/dataset/AssetReviewStore.java) | 16 | assets（34） |
| [dataset/AssetSqlGenerator.java](../../src/main/java/io/agentscope/dataagent/dataset/AssetSqlGenerator.java) | 5 | generate（42） |
| [dataset/BaselineMdlService.java](../../src/main/java/io/agentscope/dataagent/dataset/BaselineMdlService.java) | 6 | publishAfterDatasetChange（34） |
| [dataset/BiExtraSyncStatus.java](../../src/main/java/io/agentscope/dataagent/dataset/BiExtraSyncStatus.java) | 8 | project（113） |
| [dataset/BiSyncStatusService.java](../../src/main/java/io/agentscope/dataagent/dataset/BiSyncStatusService.java) | 12 | project（43） |
| [dataset/BusinessDocumentService.java](../../src/main/java/io/agentscope/dataagent/dataset/BusinessDocumentService.java) | 12 | list（31） |
| [dataset/BusinessTermService.java](../../src/main/java/io/agentscope/dataagent/dataset/BusinessTermService.java) | 16 | format（30） |
| [dataset/ChatTurnScope.java](../../src/main/java/io/agentscope/dataagent/dataset/ChatTurnScope.java) | 1 | accept（3） |
| [dataset/DataSourceIntrospector.java](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 13 | listSchemas（21） |
| [dataset/DatasetContextProvider.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetContextProvider.java) | 7 | authorizedGroupIds（4） |
| [dataset/DatasetDeletionCleanup.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetDeletionCleanup.java) | 10 | prepare（35） |
| [dataset/DatasetException.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetException.java) | 5 | <init>（4） |
| [dataset/DatasetGroupService.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 19 | updateGroup（28） |
| [dataset/DatasetImportService.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetImportService.java) | 24 | importFileForGroup（59） |
| [dataset/DatasetMetadataDraftSync.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSync.java) | 6 | update（99） |
| [dataset/DatasetSampleDocuments.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampleDocuments.java) | 3 | sync（16） |
| [dataset/DatasetSampling.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java) | 12 | collect（47） |
| [dataset/DatasetScope.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java) | 2 | <init>（3） |
| [dataset/DatasetService.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java) | 55 | ingest（122） |
| [dataset/DatasetStoreProperties.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java) | 3 | url（3） |
| [dataset/DatasetUploadAdmission.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadAdmission.java) | 16 | acquire（16） |
| [dataset/DatasetUploadIntake.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadIntake.java) | 17 | run（49） |
| [dataset/DatasetUploadProperties.java](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadProperties.java) | 15 | setMaxFileSize（4） |
| [dataset/DemoDataProperties.java](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataProperties.java) | 10 | isEnabled（3） |
| [dataset/DemoDataService.java](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java) | 17 | load（54） |
| [dataset/DocEnhanceService.java](../../src/main/java/io/agentscope/dataagent/dataset/DocEnhanceService.java) | 35 | buildPrompt（78） |
| [dataset/DraftChangeLog.java](../../src/main/java/io/agentscope/dataagent/dataset/DraftChangeLog.java) | 7 | record（20） |
| [dataset/DraftItemService.java](../../src/main/java/io/agentscope/dataagent/dataset/DraftItemService.java) | 39 | updateColumn（56） |
| [dataset/DraftReviewService.java](../../src/main/java/io/agentscope/dataagent/dataset/DraftReviewService.java) | 18 | edit（46） |
| [dataset/ExternalDataSourcePolicy.java](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java) | 5 | normalize（67） |
| [dataset/FactQueryWindowPolicy.java](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java) | 15 | requireAllowed（93） |
| [dataset/FactTableWindowService.java](../../src/main/java/io/agentscope/dataagent/dataset/FactTableWindowService.java) | 8 | save（64） |
| [dataset/FieldScreeningService.java](../../src/main/java/io/agentscope/dataagent/dataset/FieldScreeningService.java) | 16 | applyToDraft（67） |
| [dataset/GraphDto.java](../../src/main/java/io/agentscope/dataagent/dataset/GraphDto.java) | 0 | — |
| [dataset/Identifiers.java](../../src/main/java/io/agentscope/dataagent/dataset/Identifiers.java) | 5 | sanitize（24） |
| [dataset/JdbcWrenSourceProber.java](../../src/main/java/io/agentscope/dataagent/dataset/JdbcWrenSourceProber.java) | 2 | probe（44） |
| [dataset/KnowledgeBaseAccessService.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java) | 13 | page（30） |
| [dataset/KnowledgeBaseBrowseService.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseBrowseService.java) | 9 | browse（56） |
| [dataset/KnowledgeBaseDeletionService.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseDeletionService.java) | 8 | delete（111） |
| [dataset/KnowledgeBaseMembershipService.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseMembershipService.java) | 12 | filteredDirectory（45） |
| [dataset/KnowledgeBaseOperationAdvisor.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseOperationAdvisor.java) | 2 | knowledgeBaseOperationGate（77） |
| [dataset/KnowledgeBaseOperations.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseOperations.java) | 19 | recordImportedTable（22） |
| [dataset/KnowledgeGraphService.java](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeGraphService.java) | 23 | triggerBuild（83） |
| [dataset/MdlBuildChecks.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlBuildChecks.java) | 9 | dryRunCubes（33） |
| [dataset/MdlCatalog.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlCatalog.java) | 11 | dateGateContext（75） |
| [dataset/MdlManifestRenderer.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlManifestRenderer.java) | 3 | render（80） |
| [dataset/MdlPublicationStore.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublicationStore.java) | 12 | install（47） |
| [dataset/MdlPublishService.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java) | 55 | doPublishLocked（147） |
| [dataset/MdlQuestionService.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionService.java) | 18 | decide（34） |
| [dataset/MdlQuestionStore.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionStore.java) | 42 | coverage（59） |
| [dataset/MdlReadService.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlReadService.java) | 10 | view（84） |
| [dataset/MdlSeeder.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlSeeder.java) | 19 | reconcileExistingModel（126） |
| [dataset/MdlSuggestionService.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlSuggestionService.java) | 73 | addManualRelation（74） |
| [dataset/MdlTypes.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlTypes.java) | 1 | <init>（1） |
| [dataset/MdlWorkspaceReader.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java) | 20 | read（139） |
| [dataset/MdlWorkspaceService.java](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 19 | withWorkspaceTransactionLock（21） |
| [dataset/ModelTemporalMetadata.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelTemporalMetadata.java) | 2 | enrich（82） |
| [dataset/ModelingActivity.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingActivity.java) | 4 | end（4） |
| [dataset/ModelingConfirmationService.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingConfirmationService.java) | 13 | create（55） |
| [dataset/ModelingHubService.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingHubService.java) | 3 | summaries（26） |
| [dataset/ModelingPlanWriter.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingPlanWriter.java) | 5 | apply（106） |
| [dataset/ModelingTurnPurpose.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingTurnPurpose.java) | 5 | normalize（10） |
| [dataset/ModelingWorkflowService.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingWorkflowService.java) | 6 | derive（117） |
| [dataset/ModelingWorkflowStore.java](../../src/main/java/io/agentscope/dataagent/dataset/ModelingWorkflowStore.java) | 4 | record（20） |
| [dataset/ProcessWrenCli.java](../../src/main/java/io/agentscope/dataagent/dataset/ProcessWrenCli.java) | 6 | runProcess（81） |
| [dataset/PublicKnowledgeBaseService.java](../../src/main/java/io/agentscope/dataagent/dataset/PublicKnowledgeBaseService.java) | 4 | set（19） |
| [dataset/PublishedAssetWindows.java](../../src/main/java/io/agentscope/dataagent/dataset/PublishedAssetWindows.java) | 6 | derive（60） |
| [dataset/PublishedQueryProjectChanged.java](../../src/main/java/io/agentscope/dataagent/dataset/PublishedQueryProjectChanged.java) | 0 | — |
| [dataset/QuestionDependencies.java](../../src/main/java/io/agentscope/dataagent/dataset/QuestionDependencies.java) | 11 | read（66） |
| [dataset/QuestionSuggestionService.java](../../src/main/java/io/agentscope/dataagent/dataset/QuestionSuggestionService.java) | 7 | suggest（36） |
| [dataset/RecommendedQuestionService.java](../../src/main/java/io/agentscope/dataagent/dataset/RecommendedQuestionService.java) | 19 | generate（40） |
| [dataset/SchemaTripleExtractor.java](../../src/main/java/io/agentscope/dataagent/dataset/SchemaTripleExtractor.java) | 9 | extract（35） |
| [dataset/SemanticAssetService.java](../../src/main/java/io/agentscope/dataagent/dataset/SemanticAssetService.java) | 33 | verify（75） |
| [dataset/SqlExampleDocument.java](../../src/main/java/io/agentscope/dataagent/dataset/SqlExampleDocument.java) | 7 | parse（33） |
| [dataset/SqlExampleRecall.java](../../src/main/java/io/agentscope/dataagent/dataset/SqlExampleRecall.java) | 3 | published（23） |
| [dataset/SqlQaExampleService.java](../../src/main/java/io/agentscope/dataagent/dataset/SqlQaExampleService.java) | 14 | collect（46） |
| [dataset/SqlWindowGate.java](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java) | 7 | check（49） |
| [dataset/SqlWindowRules.java](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowRules.java) | 16 | evaluate（121） |
| [dataset/TableProvisioner.java](../../src/main/java/io/agentscope/dataagent/dataset/TableProvisioner.java) | 11 | bulkInsert（36） |
| [dataset/TemporalCondition.java](../../src/main/java/io/agentscope/dataagent/dataset/TemporalCondition.java) | 10 | describe（31） |
| [dataset/TemporalSamples.java](../../src/main/java/io/agentscope/dataagent/dataset/TemporalSamples.java) | 2 | add（16） |
| [dataset/UserDirectoryImportService.java](../../src/main/java/io/agentscope/dataagent/dataset/UserDirectoryImportService.java) | 3 | apply（41） |
| [dataset/WrenCli.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java) | 2 | ok（3） |
| [dataset/WrenProfileHome.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java) | 14 | renderAll（51） |
| [dataset/WrenProjectAccess.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenProjectAccess.java) | 14 | path（60） |
| [dataset/WrenProperties.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 19 | <init>（14） |
| [dataset/WrenPublishedProject.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java) | 18 | indexed（80） |
| [dataset/WrenQueryAccess.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java) | 3 | resolve（19） |
| [dataset/WrenQueryPreparation.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryPreparation.java) | 11 | prepare（85） |
| [dataset/WrenSkillsLocator.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenSkillsLocator.java) | 4 | candidates（30） |
| [dataset/WrenSourceProber.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenSourceProber.java) | 3 | success（3） |
| [dataset/WrenTiming.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenTiming.java) | 4 | measure（13） |
| [dataset/WrenTypeNormalizer.java](../../src/main/java/io/agentscope/dataagent/dataset/WrenTypeNormalizer.java) | 5 | normalize（61） |
| [dataset/parser/ColumnSchema.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 7 | <init>（19） |
| [dataset/parser/CsvParser.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/CsvParser.java) | 4 | readRecords（82） |
| [dataset/parser/DocxDescriptionExtractor.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/DocxDescriptionExtractor.java) | 2 | extract（16） |
| [dataset/parser/ExcelParser.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/ExcelParser.java) | 6 | parse（42） |
| [dataset/parser/FileParser.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/FileParser.java) | 2 | supports（1） |
| [dataset/parser/ParsedTable.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/ParsedTable.java) | 0 | — |
| [dataset/parser/SchemaGenerationService.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/SchemaGenerationService.java) | 15 | parseAiResponse（71） |
| [dataset/parser/TypeInferrer.java](../../src/main/java/io/agentscope/dataagent/dataset/parser/TypeInferrer.java) | 7 | infer（24） |
| [runtime/DataAgentBootstrap.java](../../src/main/java/io/agentscope/dataagent/runtime/DataAgentBootstrap.java) | 38 | build（173） |
| [runtime/channel/webhook/WebhookCallbackController.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookCallbackController.java) | 5 | inbound（64） |
| [runtime/channel/webhook/WebhookChannel.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookChannel.java) | 14 | dispatchAndDeliver（41） |
| [runtime/channel/webhook/WebhookChannelProperties.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookChannelProperties.java) | 8 | asStringList（11） |
| [runtime/channel/webhook/WebhookInboundMapper.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundMapper.java) | 2 | map（27） |
| [runtime/channel/webhook/WebhookInboundRequest.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundRequest.java) | 1 | effectiveReplyMode（6） |
| [runtime/channel/webhook/WebhookOutboundClient.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookOutboundClient.java) | 4 | deliver（57） |
| [runtime/channel/webhook/WebhookSignature.java](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignature.java) | 3 | hmacHex（16） |
| [runtime/config/AgentConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/AgentConfigEntry.java) | 43 | effectiveSkillRepositories（12） |
| [runtime/config/AgentscopeConfig.java](../../src/main/java/io/agentscope/dataagent/runtime/config/AgentscopeConfig.java) | 10 | getSchema（3） |
| [runtime/config/BindingConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/BindingConfigEntry.java) | 21 | toBinding（25） |
| [runtime/config/ChannelConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/ChannelConfigEntry.java) | 13 | toChannelConfig（26） |
| [runtime/config/ChannelTypeRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/ChannelTypeRegistry.java) | 4 | get（6） |
| [runtime/config/MarketplaceConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/MarketplaceConfigEntry.java) | 4 | setProperty（5） |
| [runtime/config/OntologyConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/OntologyConfigEntry.java) | 4 | getName（3） |
| [runtime/config/SessionLifecycleConfig.java](../../src/main/java/io/agentscope/dataagent/runtime/config/SessionLifecycleConfig.java) | 15 | pruneAfterMs（22） |
| [runtime/config/SkillRepositoryConfigEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/config/SkillRepositoryConfigEntry.java) | 14 | getType（3） |
| [runtime/config/SkillRepositorySupport.java](../../src/main/java/io/agentscope/dataagent/runtime/config/SkillRepositorySupport.java) | 5 | createGit（29） |
| [runtime/context/InvocationContextKeys.java](../../src/main/java/io/agentscope/dataagent/runtime/context/InvocationContextKeys.java) | 1 | <init>（1） |
| [runtime/gateway/HarnessGateway.java](../../src/main/java/io/agentscope/dataagent/runtime/gateway/HarnessGateway.java) | 40 | runStream（79） |
| [runtime/marketplace/DataAgentMarketplace.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/DataAgentMarketplace.java) | 7 | writable（3） |
| [runtime/marketplace/GitDataAgentMarketplace.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/GitDataAgentMarketplace.java) | 7 | fetch（15） |
| [runtime/marketplace/LocalApprovalMarketplace.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/LocalApprovalMarketplace.java) | 9 | fetch（40） |
| [runtime/marketplace/MarketSkillContent.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/MarketSkillContent.java) | 0 | — |
| [runtime/marketplace/MarketSkillSummary.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/MarketSkillSummary.java) | 0 | — |
| [runtime/marketplace/NacosDataAgentMarketplace.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/NacosDataAgentMarketplace.java) | 8 | <init>（47） |
| [runtime/marketplace/UserMarketplacePersistence.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/UserMarketplacePersistence.java) | 10 | loadAllForUser（16） |
| [runtime/marketplace/UserMarketplaceRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/marketplace/UserMarketplaceRegistry.java) | 12 | build（20） |
| [runtime/model/ManagedModelFactory.java](../../src/main/java/io/agentscope/dataagent/runtime/model/ManagedModelFactory.java) | 2 | create（24） |
| [runtime/model/ModelServiceCipher.java](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceCipher.java) | 4 | key（31） |
| [runtime/model/ModelServiceRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java) | 20 | probe（105） |
| [runtime/model/SwitchingModel.java](../../src/main/java/io/agentscope/dataagent/runtime/model/SwitchingModel.java) | 11 | <init>（22） |
| [runtime/observability/ExecutionTimingMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/observability/ExecutionTimingMiddleware.java) | 7 | onActing（51） |
| [runtime/outbound/OutboundController.java](../../src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundController.java) | 2 | send（40） |
| [runtime/outbound/OutboundRequest.java](../../src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundRequest.java) | 1 | <init>（10） |
| [runtime/outbound/OutboundService.java](../../src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundService.java) | 5 | send（51） |
| [runtime/outbound/OutboundTool.java](../../src/main/java/io/agentscope/dataagent/runtime/outbound/OutboundTool.java) | 2 | send（50） |
| [runtime/session/AgentManagerConfig.java](../../src/main/java/io/agentscope/dataagent/runtime/session/AgentManagerConfig.java) | 4 | <init>（17） |
| [runtime/session/AnalysisSkillMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/session/AnalysisSkillMiddleware.java) | 2 | instruction（25） |
| [runtime/session/CleanupPolicy.java](../../src/main/java/io/agentscope/dataagent/runtime/session/CleanupPolicy.java) | 0 | — |
| [runtime/session/CommandLane.java](../../src/main/java/io/agentscope/dataagent/runtime/session/CommandLane.java) | 0 | — |
| [runtime/session/ConversationScopeRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/session/ConversationScopeRegistry.java) | 13 | enrichTurns（40） |
| [runtime/session/DataDynamicContextMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/session/DataDynamicContextMiddleware.java) | 15 | onSystemPrompt（64） |
| [runtime/session/GroupScopedMemoryNamespace.java](../../src/main/java/io/agentscope/dataagent/runtime/session/GroupScopedMemoryNamespace.java) | 2 | getNamespace（22） |
| [runtime/session/HistoryResult.java](../../src/main/java/io/agentscope/dataagent/runtime/session/HistoryResult.java) | 0 | — |
| [runtime/session/ModelingTurnRules.java](../../src/main/java/io/agentscope/dataagent/runtime/session/ModelingTurnRules.java) | 4 | forPurpose（9） |
| [runtime/session/PendingCompletion.java](../../src/main/java/io/agentscope/dataagent/runtime/session/PendingCompletion.java) | 0 | — |
| [runtime/session/QuestionDateGate.java](../../src/main/java/io/agentscope/dataagent/runtime/session/QuestionDateGate.java) | 10 | onModelCall（54） |
| [runtime/session/SendResult.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SendResult.java) | 0 | — |
| [runtime/session/SessionAgentManager.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionAgentManager.java) | 47 | registerSession（97） |
| [runtime/session/SessionConstants.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionConstants.java) | 2 | resolveRequesterKey（6） |
| [runtime/session/SessionEntry.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionEntry.java) | 2 | <init>（28） |
| [runtime/session/SessionFreshness.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionFreshness.java) | 0 | — |
| [runtime/session/SessionFreshnessEvaluator.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionFreshnessEvaluator.java) | 3 | evaluate（29） |
| [runtime/session/SessionKind.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionKind.java) | 2 | <init>（3） |
| [runtime/session/SessionMaintenanceConfig.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionMaintenanceConfig.java) | 2 | disabled（3） |
| [runtime/session/SessionResetPolicy.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionResetPolicy.java) | 5 | <init>（9） |
| [runtime/session/SessionStore.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionStore.java) | 12 | toSessionEntry（17） |
| [runtime/session/SessionTurnParser.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionTurnParser.java) | 6 | parse（73） |
| [runtime/session/SessionView.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionView.java) | 1 | from（15） |
| [runtime/session/SpawnResult.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SpawnResult.java) | 0 | — |
| [runtime/session/SubagentRunRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/session/SubagentRunRegistry.java) | 4 | put（3） |
| [runtime/session/WrenProjectContextMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenProjectContextMiddleware.java) | 13 | onSystemPrompt（52） |
| [runtime/session/WrenQueryContextMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenQueryContextMiddleware.java) | 13 | onSystemPrompt（76） |
| [runtime/session/WrenQueryToolRegistrar.java](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenQueryToolRegistrar.java) | 2 | register（13） |
| [runtime/session/WrenScopedToolkitMiddleware.java](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenScopedToolkitMiddleware.java) | 10 | withTools（34） |
| [runtime/session/WrenToolForwarder.java](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenToolForwarder.java) | 7 | callAsync（8） |
| [runtime/session/tool/SessionsTool.java](../../src/main/java/io/agentscope/dataagent/runtime/session/tool/SessionsTool.java) | 11 | sessionsSpawn（146） |
| [runtime/wren/WrenConnectionFailure.java](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenConnectionFailure.java) | 3 | matches（5） |
| [runtime/wren/WrenInstanceRegistry.java](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistry.java) | 39 | invoke（63） |
| [runtime/wren/WrenProcessEnvironment.java](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenProcessEnvironment.java) | 3 | directory（16） |
| [runtime/wren/WrenQueryGateway.java](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java) | 11 | warmRecall（10） |
| [tools/data/AnalysisSkills.java](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisSkills.java) | 5 | files（27） |
| [tools/data/AnalysisToolkit.java](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisToolkit.java) | 1 | proposeAnalysis（49） |
| [tools/data/AnalysisTurn.java](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisTurn.java) | 2 | confirmed（3） |
| [tools/data/ChartBuilder.java](../../src/main/java/io/agentscope/dataagent/tools/data/ChartBuilder.java) | 41 | buildCategoryChart（135） |
| [tools/data/ChartRenderer.java](../../src/main/java/io/agentscope/dataagent/tools/data/ChartRenderer.java) | 1 | render（1） |
| [tools/data/DataAgentToolkit.java](../../src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | 4 | renderChart（57） |
| [tools/data/DataSource.java](../../src/main/java/io/agentscope/dataagent/tools/data/DataSource.java) | 1 | <init>（7） |
| [tools/data/DataSourceRegistry.java](../../src/main/java/io/agentscope/dataagent/tools/data/DataSourceRegistry.java) | 2 | list（1） |
| [tools/data/DataToolkitConfig.java](../../src/main/java/io/agentscope/dataagent/tools/data/DataToolkitConfig.java) | 2 | inMemoryDataSourceRegistry（32） |
| [tools/data/DataToolkitRegistrar.java](../../src/main/java/io/agentscope/dataagent/tools/data/DataToolkitRegistrar.java) | 7 | registerDataToolkit（35） |
| [tools/data/InMemoryDataSourceRegistry.java](../../src/main/java/io/agentscope/dataagent/tools/data/InMemoryDataSourceRegistry.java) | 5 | <init>（7） |
| [tools/data/ModelingConfirmationTool.java](../../src/main/java/io/agentscope/dataagent/tools/data/ModelingConfirmationTool.java) | 6 | request（48） |
| [tools/data/ModelingToolkit.java](../../src/main/java/io/agentscope/dataagent/tools/data/ModelingToolkit.java) | 64 | renderState（130） |
| [tools/data/ModelingToolkitRegistrar.java](../../src/main/java/io/agentscope/dataagent/tools/data/ModelingToolkitRegistrar.java) | 7 | <init>（52） |
| [tools/data/OfficialWrenToolkit.java](../../src/main/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkit.java) | 23 | command（199） |
| [tools/data/QueryDataFiles.java](../../src/main/java/io/agentscope/dataagent/tools/data/QueryDataFiles.java) | 7 | note（66） |
| [tools/data/RunPythonTool.java](../../src/main/java/io/agentscope/dataagent/tools/data/RunPythonTool.java) | 12 | executePython（170） |
| [tools/data/SemanticAssetProposalTool.java](../../src/main/java/io/agentscope/dataagent/tools/data/SemanticAssetProposalTool.java) | 2 | propose（63） |
| [tools/data/StubChartRenderer.java](../../src/main/java/io/agentscope/dataagent/tools/data/StubChartRenderer.java) | 1 | render（9） |
| [tools/data/WebSearchTool.java](../../src/main/java/io/agentscope/dataagent/tools/data/WebSearchTool.java) | 6 | webSearch（43） |
| [tools/data/WrenProjectFilesystem.java](../../src/main/java/io/agentscope/dataagent/tools/data/WrenProjectFilesystem.java) | 27 | preview（59） |
| [tools/data/WrenScopedMcpClient.java](../../src/main/java/io/agentscope/dataagent/tools/data/WrenScopedMcpClient.java) | 19 | callTool（136） |
| [web/DataAgentApp.java](../../src/main/java/io/agentscope/dataagent/web/DataAgentApp.java) | 1 | main（3） |
| [web/ai/AgentDraftController.java](../../src/main/java/io/agentscope/dataagent/web/ai/AgentDraftController.java) | 2 | draft（8） |
| [web/ai/AgentDraftService.java](../../src/main/java/io/agentscope/dataagent/web/ai/AgentDraftService.java) | 13 | callModelBlocking（39） |
| [web/api/AdminBiSyncController.java](../../src/main/java/io/agentscope/dataagent/web/api/AdminBiSyncController.java) | 4 | extra（12） |
| [web/api/AdminConversationController.java](../../src/main/java/io/agentscope/dataagent/web/api/AdminConversationController.java) | 5 | list（61） |
| [web/api/AdminHistoryOverviewController.java](../../src/main/java/io/agentscope/dataagent/web/api/AdminHistoryOverviewController.java) | 8 | export（47） |
| [web/api/AdminModelServiceController.java](../../src/main/java/io/agentscope/dataagent/web/api/AdminModelServiceController.java) | 10 | activate（10） |
| [web/api/AdminUserController.java](../../src/main/java/io/agentscope/dataagent/web/api/AdminUserController.java) | 12 | create（34） |
| [web/api/AgentActivityController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentActivityController.java) | 3 | list（37） |
| [web/api/AgentBindingController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentBindingController.java) | 10 | update（51） |
| [web/api/AgentCloneController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentCloneController.java) | 2 | clone（58） |
| [web/api/AgentSkillsController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentSkillsController.java) | 29 | installFromMarketplace（94） |
| [web/api/AgentToolsController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentToolsController.java) | 14 | validate（63） |
| [web/api/AgentWorkspaceController.java](../../src/main/java/io/agentscope/dataagent/web/api/AgentWorkspaceController.java) | 35 | readBinaryFile（79） |
| [web/api/AnalysisController.java](../../src/main/java/io/agentscope/dataagent/web/api/AnalysisController.java) | 2 | get（19） |
| [web/api/AnswerFeedbackController.java](../../src/main/java/io/agentscope/dataagent/web/api/AnswerFeedbackController.java) | 4 | feedback（22） |
| [web/api/ArtifactController.java](../../src/main/java/io/agentscope/dataagent/web/api/ArtifactController.java) | 2 | content（41） |
| [web/api/BindingPersistence.java](../../src/main/java/io/agentscope/dataagent/web/api/BindingPersistence.java) | 8 | reloadChannel（28） |
| [web/api/BusinessTermController.java](../../src/main/java/io/agentscope/dataagent/web/api/BusinessTermController.java) | 4 | change（17） |
| [web/api/ChannelDirectoryController.java](../../src/main/java/io/agentscope/dataagent/web/api/ChannelDirectoryController.java) | 4 | list（37） |
| [web/api/ChartController.java](../../src/main/java/io/agentscope/dataagent/web/api/ChartController.java) | 2 | get（26） |
| [web/api/ChatController.java](../../src/main/java/io/agentscope/dataagent/web/api/ChatController.java) | 46 | confirmModeling（167） |
| [web/api/ChatOptionsController.java](../../src/main/java/io/agentscope/dataagent/web/api/ChatOptionsController.java) | 3 | options（13） |
| [web/api/DatasetController.java](../../src/main/java/io/agentscope/dataagent/web/api/DatasetController.java) | 29 | uploadWithId（61） |
| [web/api/DatasetGroupController.java](../../src/main/java/io/agentscope/dataagent/web/api/DatasetGroupController.java) | 23 | detail（35） |
| [web/api/DraftReviewController.java](../../src/main/java/io/agentscope/dataagent/web/api/DraftReviewController.java) | 18 | editItem（60） |
| [web/api/ExternalDataSourceController.java](../../src/main/java/io/agentscope/dataagent/web/api/ExternalDataSourceController.java) | 18 | testConnection（34） |
| [web/api/FactQueryWindowController.java](../../src/main/java/io/agentscope/dataagent/web/api/FactQueryWindowController.java) | 4 | save（22） |
| [web/api/FieldScreeningController.java](../../src/main/java/io/agentscope/dataagent/web/api/FieldScreeningController.java) | 5 | save（18） |
| [web/api/GlobalAdministratorController.java](../../src/main/java/io/agentscope/dataagent/web/api/GlobalAdministratorController.java) | 7 | search（20） |
| [web/api/KnowledgeBaseBrowseController.java](../../src/main/java/io/agentscope/dataagent/web/api/KnowledgeBaseBrowseController.java) | 4 | call（13） |
| [web/api/KnowledgeBaseMembershipController.java](../../src/main/java/io/agentscope/dataagent/web/api/KnowledgeBaseMembershipController.java) | 8 | users（20） |
| [web/api/KnowledgeBaseSummaryController.java](../../src/main/java/io/agentscope/dataagent/web/api/KnowledgeBaseSummaryController.java) | 3 | modeling（14） |
| [web/api/KnowledgeGraphController.java](../../src/main/java/io/agentscope/dataagent/web/api/KnowledgeGraphController.java) | 4 | status（49） |
| [web/api/MarketplacesController.java](../../src/main/java/io/agentscope/dataagent/web/api/MarketplacesController.java) | 17 | getSkill（33） |
| [web/api/MdlPublicationController.java](../../src/main/java/io/agentscope/dataagent/web/api/MdlPublicationController.java) | 8 | mdlPublish（19） |
| [web/api/ModelingQuestionController.java](../../src/main/java/io/agentscope/dataagent/web/api/ModelingQuestionController.java) | 14 | decide（20） |
| [web/api/ModelingWorkflowController.java](../../src/main/java/io/agentscope/dataagent/web/api/ModelingWorkflowController.java) | 2 | snapshot（17） |
| [web/api/OntologyController.java](../../src/main/java/io/agentscope/dataagent/web/api/OntologyController.java) | 2 | list（8） |
| [web/api/PublicKnowledgeBaseController.java](../../src/main/java/io/agentscope/dataagent/web/api/PublicKnowledgeBaseController.java) | 4 | call（14） |
| [web/api/QueryPreparationController.java](../../src/main/java/io/agentscope/dataagent/web/api/QueryPreparationController.java) | 2 | status（16） |
| [web/api/RecommendedQuestionController.java](../../src/main/java/io/agentscope/dataagent/web/api/RecommendedQuestionController.java) | 2 | get（15） |
| [web/api/ScheduledTaskController.java](../../src/main/java/io/agentscope/dataagent/web/api/ScheduledTaskController.java) | 8 | update（26） |
| [web/api/SemanticAssetController.java](../../src/main/java/io/agentscope/dataagent/web/api/SemanticAssetController.java) | 14 | save（19） |
| [web/api/SemanticModelingController.java](../../src/main/java/io/agentscope/dataagent/web/api/SemanticModelingController.java) | 34 | workspacePreview（35） |
| [web/api/SessionController.java](../../src/main/java/io/agentscope/dataagent/web/api/SessionController.java) | 15 | inbox（75） |
| [web/api/UserBindingController.java](../../src/main/java/io/agentscope/dataagent/web/api/UserBindingController.java) | 12 | normalizeSkills（17） |
| [web/api/UserDirectoryImportController.java](../../src/main/java/io/agentscope/dataagent/web/api/UserDirectoryImportController.java) | 2 | apply（21） |
| [web/api/UserProfileController.java](../../src/main/java/io/agentscope/dataagent/web/api/UserProfileController.java) | 2 | get（20） |
| [web/artifact/ArtifactStore.java](../../src/main/java/io/agentscope/dataagent/web/artifact/ArtifactStore.java) | 19 | saveLocked（72） |
| [web/artifact/SandboxArtifactReader.java](../../src/main/java/io/agentscope/dataagent/web/artifact/SandboxArtifactReader.java) | 2 | read（44） |
| [web/audit/ActivityEvent.java](../../src/main/java/io/agentscope/dataagent/web/audit/ActivityEvent.java) | 1 | <init>（1） |
| [web/audit/AgentActivityStore.java](../../src/main/java/io/agentscope/dataagent/web/audit/AgentActivityStore.java) | 8 | record（26） |
| [web/auth/AuthController.java](../../src/main/java/io/agentscope/dataagent/web/auth/AuthController.java) | 8 | me（24） |
| [web/auth/AuthSettings.java](../../src/main/java/io/agentscope/dataagent/web/auth/AuthSettings.java) | 5 | <init>（16） |
| [web/auth/BiLoginService.java](../../src/main/java/io/agentscope/dataagent/web/auth/BiLoginService.java) | 5 | login（15） |
| [web/auth/BiTicketCodec.java](../../src/main/java/io/agentscope/dataagent/web/auth/BiTicketCodec.java) | 3 | decode（40） |
| [web/auth/JwtService.java](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java) | 6 | parse（14） |
| [web/auth/UserController.java](../../src/main/java/io/agentscope/dataagent/web/auth/UserController.java) | 3 | changePassword（31） |
| [web/auth/UserStore.java](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java) | 11 | hasRole（3） |
| [web/binding/UserBinding.java](../../src/main/java/io/agentscope/dataagent/web/binding/UserBinding.java) | 1 | <init>（3） |
| [web/binding/UserBindingStore.java](../../src/main/java/io/agentscope/dataagent/web/binding/UserBindingStore.java) | 4 | list（1） |
| [web/catalog/AgentCatalogController.java](../../src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogController.java) | 7 | updateAgent（22） |
| [web/catalog/AgentCatalogService.java](../../src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogService.java) | 27 | buildAndRegisterUca（84） |
| [web/catalog/AgentDefinition.java](../../src/main/java/io/agentscope/dataagent/web/catalog/AgentDefinition.java) | 1 | withTierForCurrentUser（29） |
| [web/catalog/OntologyAgentService.java](../../src/main/java/io/agentscope/dataagent/web/catalog/OntologyAgentService.java) | 7 | buildAndRegister（57） |
| [web/catalog/UserAgentDefinitionStore.java](../../src/main/java/io/agentscope/dataagent/web/catalog/UserAgentDefinitionStore.java) | 8 | toConfigEntry（36） |
| [web/chat/ChatCapacity.java](../../src/main/java/io/agentscope/dataagent/web/chat/ChatCapacity.java) | 12 | execute（47） |
| [web/chat/ChatTurnPreparation.java](../../src/main/java/io/agentscope/dataagent/web/chat/ChatTurnPreparation.java) | 3 | buildInbound（73） |
| [web/config/AgentStateConfig.java](../../src/main/java/io/agentscope/dataagent/web/config/AgentStateConfig.java) | 2 | durableAgentStateStore（46） |
| [web/config/DataAgentConfig.java](../../src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | 18 | builderBootstrap（282） |
| [web/config/SecurityConfig.java](../../src/main/java/io/agentscope/dataagent/web/config/SecurityConfig.java) | 5 | filter（63） |
| [web/config/WebConfig.java](../../src/main/java/io/agentscope/dataagent/web/config/WebConfig.java) | 1 | spaFallback（17） |
| [web/identity/IdentityLinkStore.java](../../src/main/java/io/agentscope/dataagent/web/identity/IdentityLinkStore.java) | 7 | link（17） |
| [web/marketplace/ContributeWorkspaceTool.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/ContributeWorkspaceTool.java) | 3 | contribute（64） |
| [web/marketplace/ContributionApprovalController.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/ContributionApprovalController.java) | 6 | approve（17） |
| [web/marketplace/ContributionToolRegistrar.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/ContributionToolRegistrar.java) | 2 | registerContributionTool（30） |
| [web/marketplace/FileEntry.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/FileEntry.java) | 1 | <init>（8） |
| [web/marketplace/MarketContributionController.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/MarketContributionController.java) | 6 | harvestFromUserSandbox（33） |
| [web/marketplace/MarketContributionService.java](../../src/main/java/io/agentscope/dataagent/web/marketplace/MarketContributionService.java) | 17 | approve（57） |
| [web/middleware/DebugLoggingMiddleware.java](../../src/main/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddleware.java) | 18 | onAgent（34） |
| [web/middleware/ModelingHitlMiddleware.java](../../src/main/java/io/agentscope/dataagent/web/middleware/ModelingHitlMiddleware.java) | 12 | onActing（118） |
| [web/middleware/PageReadTimingFilter.java](../../src/main/java/io/agentscope/dataagent/web/middleware/PageReadTimingFilter.java) | 1 | filter（39） |
| [web/onboarding/OnboardingController.java](../../src/main/java/io/agentscope/dataagent/web/onboarding/OnboardingController.java) | 11 | sample（11） |
| [web/onboarding/OnboardingService.java](../../src/main/java/io/agentscope/dataagent/web/onboarding/OnboardingService.java) | 15 | view（95） |
| [web/persistence/jpa/ActivityEventEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ActivityEventEntity.java) | 21 | getId（3） |
| [web/persistence/jpa/ActivityEventRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ActivityEventRepository.java) | 2 | findByOwnerIdAndAgentIdAndTimestampMsGreaterThanOrderByTimestampMsDesc（3） |
| [web/persistence/jpa/AgentEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/AgentEntity.java) | 51 | getRowId（3） |
| [web/persistence/jpa/AgentEntityRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/AgentEntityRepository.java) | 4 | findVisible（13） |
| [web/persistence/jpa/AgentShareEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/AgentShareEntity.java) | 15 | <init>（14） |
| [web/persistence/jpa/ArtifactEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ArtifactEntity.java) | 0 | — |
| [web/persistence/jpa/ArtifactRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ArtifactRepository.java) | 5 | sessionUsage（4） |
| [web/persistence/jpa/BiLoginTicketEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/BiLoginTicketEntity.java) | 4 | isNew（5） |
| [web/persistence/jpa/BiLoginTicketRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/BiLoginTicketRepository.java) | 1 | deleteExpired（4） |
| [web/persistence/jpa/ChartOptionEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ChartOptionEntity.java) | 10 | <init>（6） |
| [web/persistence/jpa/ChartOptionRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ChartOptionRepository.java) | 1 | findByChartIdAndOwnerId（1） |
| [web/persistence/jpa/ContributionEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ContributionEntity.java) | 29 | getId（3） |
| [web/persistence/jpa/ContributionRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ContributionRepository.java) | 2 | findAllBySourceUserIdOrderByCreatedAtDesc（2） |
| [web/persistence/jpa/ConversationDatasetScopeEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ConversationDatasetScopeEntity.java) | 7 | <init>（6） |
| [web/persistence/jpa/ConversationDatasetScopeRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ConversationDatasetScopeRepository.java) | 0 | — |
| [web/persistence/jpa/DatasetEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetEntity.java) | 33 | getGroupId（3） |
| [web/persistence/jpa/DatasetGroupEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetGroupEntity.java) | 32 | <init>（8） |
| [web/persistence/jpa/DatasetGroupRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetGroupRepository.java) | 14 | findAccessible（22） |
| [web/persistence/jpa/DatasetKnowledgeEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetKnowledgeEntity.java) | 8 | <init>（5） |
| [web/persistence/jpa/DatasetKnowledgeRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetKnowledgeRepository.java) | 0 | — |
| [web/persistence/jpa/DatasetRelationEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetRelationEntity.java) | 38 | <init>（23） |
| [web/persistence/jpa/DatasetRelationRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetRelationRepository.java) | 1 | findByGroupId（1） |
| [web/persistence/jpa/DatasetRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DatasetRepository.java) | 11 | lockById（5） |
| [web/persistence/jpa/DocEnhanceProposalEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DocEnhanceProposalEntity.java) | 33 | getId（3） |
| [web/persistence/jpa/DocEnhanceProposalRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DocEnhanceProposalRepository.java) | 2 | existsByOwnerIdAndGroupIdAndFingerprintAndStatus（2） |
| [web/persistence/jpa/DocEnhanceTaskEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DocEnhanceTaskEntity.java) | 20 | <init>（8） |
| [web/persistence/jpa/DocEnhanceTaskRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/DocEnhanceTaskRepository.java) | 2 | findFirstByOwnerIdAndGroupIdOrderByCreatedAtDesc（2） |
| [web/persistence/jpa/ExternalDataSourceEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ExternalDataSourceEntity.java) | 22 | <init>（20） |
| [web/persistence/jpa/ExternalDataSourceRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ExternalDataSourceRepository.java) | 2 | findByOwnerIdOrderByCreatedAtDesc（1） |
| [web/persistence/jpa/FactQueryWindowEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/FactQueryWindowEntity.java) | 12 | <init>（19） |
| [web/persistence/jpa/FactQueryWindowRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/FactQueryWindowRepository.java) | 2 | findByGroupId（1） |
| [web/persistence/jpa/IdentityLinkEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/IdentityLinkEntity.java) | 16 | equals（6） |
| [web/persistence/jpa/IdentityLinkRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/IdentityLinkRepository.java) | 3 | findByUserId（1） |
| [web/persistence/jpa/JpaPersistenceConfig.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceConfig.java) | 3 | jpaUserStore（9） |
| [web/persistence/jpa/JpaUserAgentDefinitionStore.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserAgentDefinitionStore.java) | 13 | save（56） |
| [web/persistence/jpa/JpaUserBindingStore.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserBindingStore.java) | 9 | update（11） |
| [web/persistence/jpa/JpaUserStore.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserStore.java) | 18 | setAdministrator（19） |
| [web/persistence/jpa/KnowledgeBaseGrantAuditEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeBaseGrantAuditEntity.java) | 2 | <init>（8） |
| [web/persistence/jpa/KnowledgeBaseGrantEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeBaseGrantEntity.java) | 8 | grant（5） |
| [web/persistence/jpa/KnowledgeBaseGrantRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeBaseGrantRepository.java) | 5 | findByGroupId（2） |
| [web/persistence/jpa/KnowledgeGraphBuildTaskEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphBuildTaskEntity.java) | 14 | <init>（7） |
| [web/persistence/jpa/KnowledgeGraphBuildTaskRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphBuildTaskRepository.java) | 2 | findTopByGroupIdOrderByTriggeredAtDesc（1） |
| [web/persistence/jpa/KnowledgeGraphBuildUnitEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphBuildUnitEntity.java) | 22 | <init>（10） |
| [web/persistence/jpa/KnowledgeGraphBuildUnitRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphBuildUnitRepository.java) | 2 | findByGroupIdOrderByCreatedAtAsc（1） |
| [web/persistence/jpa/KnowledgeGraphEntityEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphEntityEntity.java) | 18 | <init>（17） |
| [web/persistence/jpa/KnowledgeGraphEntityRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphEntityRepository.java) | 5 | findByGroupIdAndNormalizedKey（2） |
| [web/persistence/jpa/KnowledgeGraphRelationEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphRelationEntity.java) | 16 | <init>（15） |
| [web/persistence/jpa/KnowledgeGraphRelationRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/KnowledgeGraphRelationRepository.java) | 3 | findByGroupId（1） |
| [web/persistence/jpa/ModelServiceConfigurationEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ModelServiceConfigurationEntity.java) | 4 | <init>（3） |
| [web/persistence/jpa/ModelServiceConfigurationRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ModelServiceConfigurationRepository.java) | 0 | — |
| [web/persistence/jpa/ScheduledTaskEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ScheduledTaskEntity.java) | 30 | <init>（26） |
| [web/persistence/jpa/ScheduledTaskRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/ScheduledTaskRepository.java) | 2 | findAllByOrderByCreatedAtDesc（1） |
| [web/persistence/jpa/SemanticBusinessRuleEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticBusinessRuleEntity.java) | 18 | <init>（14） |
| [web/persistence/jpa/SemanticBusinessRuleRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticBusinessRuleRepository.java) | 3 | findByOwnerIdAndGroupIdOrderByCreatedAtAsc（2） |
| [web/persistence/jpa/SemanticCubeEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticCubeEntity.java) | 24 | <init>（10） |
| [web/persistence/jpa/SemanticCubeRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticCubeRepository.java) | 2 | findByGroupId（1） |
| [web/persistence/jpa/SemanticViewEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticViewEntity.java) | 20 | <init>（16） |
| [web/persistence/jpa/SemanticViewRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SemanticViewRepository.java) | 2 | findByGroupId（1） |
| [web/persistence/jpa/SessionHistoryEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionHistoryEntity.java) | 0 | — |
| [web/persistence/jpa/SessionHistoryRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionHistoryRepository.java) | 2 | adminHistory（25） |
| [web/persistence/jpa/SessionMessageEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionMessageEntity.java) | 1 | project（4） |
| [web/persistence/jpa/SessionMessageRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionMessageRepository.java) | 5 | projectLegacy（5） |
| [web/persistence/jpa/SessionReadStateEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionReadStateEntity.java) | 16 | equals（7） |
| [web/persistence/jpa/SessionReadStateRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionReadStateRepository.java) | 2 | deleteByUserIdAndSessionKey（2） |
| [web/persistence/jpa/SessionRegistryEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionRegistryEntity.java) | 27 | getSessionKey（3） |
| [web/persistence/jpa/SessionRegistryRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/SessionRegistryRepository.java) | 2 | findRetentionCandidates（8） |
| [web/persistence/jpa/UsageEventEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UsageEventEntity.java) | 12 | <init>（6） |
| [web/persistence/jpa/UsageEventRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UsageEventRepository.java) | 7 | countByUserSince（4） |
| [web/persistence/jpa/UserEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UserEntity.java) | 34 | <init>（6） |
| [web/persistence/jpa/UserEntityRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UserEntityRepository.java) | 10 | searchDirectory（10） |
| [web/persistence/jpa/UserMarketplaceEntity.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UserMarketplaceEntity.java) | 15 | <init>（7） |
| [web/persistence/jpa/UserMarketplaceRepository.java](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/UserMarketplaceRepository.java) | 4 | findByUserIdAndMarketplaceId（2） |
| [web/scaffold/WorkspaceScaffolder.java](../../src/main/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolder.java) | 7 | agentsMd（26） |
| [web/session/AdminHistoryQueryService.java](../../src/main/java/io/agentscope/dataagent/web/session/AdminHistoryQueryService.java) | 9 | predicate（27） |
| [web/session/SessionDeletionService.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionDeletionService.java) | 4 | delete（29） |
| [web/session/SessionHistoryService.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionHistoryService.java) | 15 | refreshLocked（136） |
| [web/session/SessionIndexReconciler.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionIndexReconciler.java) | 2 | writeTombstone（9） |
| [web/session/SessionLifecycleScheduler.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionLifecycleScheduler.java) | 4 | start（71） |
| [web/session/SessionReadStateStore.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionReadStateStore.java) | 5 | markRead（11） |
| [web/session/SessionRetentionJob.java](../../src/main/java/io/agentscope/dataagent/web/session/SessionRetentionJob.java) | 4 | run（57） |
| [web/share/AgentAccessGuard.java](../../src/main/java/io/agentscope/dataagent/web/share/AgentAccessGuard.java) | 3 | require（15） |
| [web/share/AgentAclService.java](../../src/main/java/io/agentscope/dataagent/web/share/AgentAclService.java) | 8 | highestMatchingGrant（19） |
| [web/share/AgentShareGrant.java](../../src/main/java/io/agentscope/dataagent/web/share/AgentShareGrant.java) | 0 | — |
| [web/template/TemplateController.java](../../src/main/java/io/agentscope/dataagent/web/template/TemplateController.java) | 3 | get（11） |
| [web/template/TemplateRegistry.java](../../src/main/java/io/agentscope/dataagent/web/template/TemplateRegistry.java) | 24 | loadClasspathFiles（55） |
| [web/toolbus/ToolEventBus.java](../../src/main/java/io/agentscope/dataagent/web/toolbus/ToolEventBus.java) | 8 | toolCall（19） |
| [web/toolbus/ToolNotificationMiddleware.java](../../src/main/java/io/agentscope/dataagent/web/toolbus/ToolNotificationMiddleware.java) | 4 | onActing（90） |
| [web/usage/UsageStore.java](../../src/main/java/io/agentscope/dataagent/web/usage/UsageStore.java) | 17 | hourlyTurns（20） |
| [web/util/WorkspaceCopier.java](../../src/main/java/io/agentscope/dataagent/web/util/WorkspaceCopier.java) | 3 | copy（63） |
| [web/workspace/DataAgentWorkspaceConfig.java](../../src/main/java/io/agentscope/dataagent/web/workspace/DataAgentWorkspaceConfig.java) | 4 | userSandboxRegistry（40） |
| [web/workspace/LazySandbox.java](../../src/main/java/io/agentscope/dataagent/web/workspace/LazySandbox.java) | 13 | ensureDelegate（24） |
| [web/workspace/SharedSandboxFilesystem.java](../../src/main/java/io/agentscope/dataagent/web/workspace/SharedSandboxFilesystem.java) | 12 | uploadFiles（45） |
| [web/workspace/SharedWorkspaceSeeder.java](../../src/main/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeeder.java) | 11 | seedSharedTree（74） |
| [web/workspace/UserControlFilesystem.java](../../src/main/java/io/agentscope/dataagent/web/workspace/UserControlFilesystem.java) | 2 | resolvePath（10） |
| [web/workspace/UserSandboxRegistry.java](../../src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | 25 | cleanupOrphanedContainers（41） |
| [web/workspace/WorkspaceManagerFactory.java](../../src/main/java/io/agentscope/dataagent/web/workspace/WorkspaceManagerFactory.java) | 10 | resolveAgentDataPath（21） |

合计：379个文件，3819个显式方法/构造器；38个包。

## 37张平台表逐表核对

真实MySQL8.4.5空库/升级库/恢复库已比较336列与116个索引列位置（后者不是索引数量）。35张表为JPA实体，另有框架JDBC状态表及停用语义词表。列类型由Hibernate validate核对；声明主键、索引、唯一与显式NOT NULL由PlatformSchemaMysqlTest读取元数据核对。索引列表省略PRIMARY，U表示唯一索引。未证明真实数据基数下查询计划最优。

| 表 / Entity或归属 | 列数 | 主键 | 归属列 | 非主键索引（U唯一） |
| --- | --- | --- | --- | --- |
| `activity_event` / ActivityEventEntity | 10 | id | actor_user_id, owner_id | actor_user_id; owner_id,agent_id,timestamp_ms; U:event_id |
| `dataagent_agent` / AgentEntity | 24 | row_id | owner_id | agent_id; owner_id; U:owner_id,agent_id |
| `dataagent_agent_share` / AgentShareEntity | 7 | id | agent_row_id | agent_row_id; grantee_type,grantee_id |
| `dataagent_agent_state` / 框架JDBC AgentState | 7 | session_id,state_key,item_index | 见上级资源/服务授权 | — |
| `dataagent_bi_login_ticket` / BiLoginTicketEntity | 2 | request_id | 见上级资源/服务授权 | expires_at |
| `dataagent_chart_option` / ChartOptionEntity | 4 | chart_id | owner_id | — |
| `dataagent_contribution` / ContributionEntity | 14 | id | 见上级资源/服务授权 | source_user_id; status; target_agent_id,target_type,target_path |
| `dataagent_conversation_dataset_scope` / ConversationDatasetScopeEntity | 5 | id | session_key, user_id | — |
| `dataagent_dataset` / DatasetEntity | 16 | dataset_id | group_id, owner_id | owner_id; U:owner_id,group_id,name |
| `dataagent_dataset_group` / DatasetGroupEntity | 16 | group_id | group_id, owner_id | owner_id; U:owner_id,name |
| `dataagent_dataset_relation` / DatasetRelationEntity | 15 | relation_id | group_id | — |
| `dataagent_doc_enhance_proposal` / DocEnhanceProposalEntity | 16 | proposal_id | group_id, owner_id | group_id,status; owner_id,group_id; task_id |
| `dataagent_doc_enhance_task` / DocEnhanceTaskEntity | 9 | task_id | group_id, owner_id | group_id,status; owner_id,group_id |
| `dataagent_external_datasource` / ExternalDataSourceEntity | 10 | datasource_id | owner_id | — |
| `dataagent_fact_query_window` / FactQueryWindowEntity | 9 | window_id | group_id | group_id; U:group_id,dataset_id |
| `dataagent_group_knowledge` / DatasetKnowledgeEntity | 3 | group_id | group_id | — |
| `dataagent_kg_build_task` / KnowledgeGraphBuildTaskEntity | 6 | task_id | group_id, owner_id | — |
| `dataagent_kg_build_unit` / KnowledgeGraphBuildUnitEntity | 10 | unit_id | group_id | — |
| `dataagent_kg_entity` / KnowledgeGraphEntityEntity | 8 | entity_id | group_id, owner_id | — |
| `dataagent_kg_relation` / KnowledgeGraphRelationEntity | 7 | relation_id | group_id | — |
| `dataagent_knowledge_base_grant` / KnowledgeBaseGrantEntity | 7 | id | group_id, user_id | U:group_id,user_id; user_id |
| `dataagent_knowledge_base_grant_audit` / KnowledgeBaseGrantAuditEntity | 7 | id | group_id, user_id | group_id,created_at |
| `dataagent_model_service_config` / ModelServiceConfigurationEntity | 3 | config_id | 见上级资源/服务授权 | — |
| `dataagent_scheduled_task` / ScheduledTaskEntity | 14 | task_id | 见上级资源/服务授权 | — |
| `dataagent_semantic_business_rule` / SemanticBusinessRuleEntity | 8 | rule_id | group_id, owner_id | U:group_id,name; owner_id,group_id |
| `dataagent_semantic_cube` / SemanticCubeEntity | 11 | cube_id | group_id | group_id; U:group_id,name |
| `dataagent_semantic_term` / 停用遗留表，保留兼容 | 8 | term_id | group_id | — |
| `dataagent_semantic_view` / SemanticViewEntity | 9 | view_id | group_id | group_id; U:group_id,name |
| `dataagent_user` / UserEntity | 16 | user_id | user_id | U:username |
| `dataagent_user_marketplace` / UserMarketplaceEntity | 7 | id | user_id | user_id; U:user_id,marketplace_id |
| `identity_link` / IdentityLinkEntity | 3 | channel_id,user_id | user_id | channel_id,external_id; user_id |
| `session_artifact` / ArtifactEntity | 9 | id | owner_id | owner_id; owner_id,session_id |
| `session_history` / SessionHistoryEntity | 9 | session_key | session_key | — |
| `session_message` / SessionMessageEntity | 6 | id | session_key | U:session_key,position_no; message_role; session_key,message_role |
| `session_read_state` / SessionReadStateEntity | 3 | session_key,user_id | session_key, user_id | user_id |
| `session_registry` / SessionRegistryEntity | 13 | session_key | session_key, user_id | agent_id; user_id |
| `usage_event` / UsageEventEntity | 5 | id | user_id | agent_id,timestamp_ms; timestamp_ms; user_id,timestamp_ms |

FK：DDL保留`dataagent_agent_share.agent_row_id → dataagent_agent.row_id`；其他多数业务关联由ID和服务/清理流程维护。不能把“没有外键”统一认作错误，也不能因此省略孤儿数据检查。当前迁移是人工受控SQL，没有自动迁移版本台账，后续大规模升级前应专项引入和演练。

结构签名SHA-256（列目录+索引列目录）：`f3250f6a0e5b028a6e69f49bf6fda6260a1410f5fe4486452658d2b70471e261`。
