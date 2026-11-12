> spec130将通用计时类迁入runtime，相关源码链接已跟随迁移；本报告数字仍为原阶段实测。

> 历史基线：本报告记录阶段1/盘点时状态；后续分层、清理及最新实测见[阶段2报告](20261112-test-lanes-and-cleanup.md)。

# 全量测试逐项清单（2026-11-12）

范围：本产品168个后端测试类、3个Java辅助文件，另附22个前端测试文件、15个Python测试文件中的实际测试类，以及新增CI报告测试。上游AgentScope框架及docs/archive历史探针不在本次产品盘点范围。

后端耗时来自上一阶段干净隔离副本的Java17全量1150项报告：0失败/错误、21跳过；本轮未重跑全量。耗时含各类初始化，只有一次样本，不能作为稳定性能或删类后节省时间的保证。关联代码来自源码引用和断言核对，不是覆盖率或完整动态调用图。

“保留”指核心契约仍有效，不承诺类中每个固定文字断言都必要。“清理候选”没有自动删除，必须核对替代覆盖和生产代码一起处理。测试辅助文件本来就不应单独产生测试报告。

## 后端总表

| 编号/测试类 | 当前保护内容 | 关联现行代码（部分） | 用例/跳过/秒 | 建议 |
| --- | --- | --- | --- | --- | --- |
| J001 [AnswerQueryMemoryTest](../../src/test/java/io/agentscope/dataagent/dataset/AnswerQueryMemoryTest.java) | 点赞问答只收录当前操作者成功的业务SQL，排除诊断与分析查询 | [AnswerQueryMemory](../../src/main/java/io/agentscope/dataagent/dataset/AnswerQueryMemory.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 9 / 0 / 1.768 | 保留现行回归 |
| J002 [AssetExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetExampleServiceTest.java) | 资产样例只引用当前库模型和允许的查询表 | [AssetExampleService](../../src/main/java/io/agentscope/dataagent/dataset/AssetExampleService.java), [MdlWorkspaceReader](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java) | 3 / 0 / 0.080 | 保留现行回归 |
| J003 [AssetProposalStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetProposalStoreTest.java) | 资产提议先记录再由用户决定，不提前改文件 | [AssetProposalStore](../../src/main/java/io/agentscope/dataagent/dataset/AssetProposalStore.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 3 / 0 / 0.183 | 保留现行回归 |
| J004 [BaselineMdlServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BaselineMdlServiceTest.java) | 数据变化后的草稿准备、首次发布失败和跨租户隔离 | [BaselineMdlService](../../src/main/java/io/agentscope/dataagent/dataset/BaselineMdlService.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java) | 5 / 0 / 0.499 | 保留现行回归 |
| J005 [BiExtraSyncStatusTest](../../src/test/java/io/agentscope/dataagent/dataset/BiExtraSyncStatusTest.java) | 扩展同步只读状态、缺口、任务归属和错误脱敏 | [BiExtraSyncStatus](../../src/main/java/io/agentscope/dataagent/dataset/BiExtraSyncStatus.java), [BiSyncStatusService](../../src/main/java/io/agentscope/dataagent/dataset/BiSyncStatusService.java) | 4 / 0 / 0.235 | 保留现行回归 |
| J006 [BiSyncStatusServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BiSyncStatusServiceTest.java) | 基础同步日期水位、运行锁和错误脱敏 | [BiSyncStatusService](../../src/main/java/io/agentscope/dataagent/dataset/BiSyncStatusService.java) | 5 / 0 / 0.047 | 保留现行回归 |
| J007 [BusinessDocumentContentTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessDocumentContentTest.java) | 业务文档读取限制在有MANAGE权限的登记文件 | [BusinessDocumentService](../../src/main/java/io/agentscope/dataagent/dataset/BusinessDocumentService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java) | 1 / 0 / 0.045 | 保留现行回归 |
| J008 [BusinessTermServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessTermServiceTest.java) | 术语和同义词草稿、发布版本及排队撤权 | [BusinessTermService](../../src/main/java/io/agentscope/dataagent/dataset/BusinessTermService.java), [DraftItemService](../../src/main/java/io/agentscope/dataagent/dataset/DraftItemService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 10 / 0 / 0.487 | 保留现行回归 |
| J009 [DatasetImportLimitsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportLimitsTest.java) | 上传行列/单元格限额、背压和失败清表 | [DatasetImportService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetImportService.java), [DatasetUploadProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadProperties.java), [SchemaGenerationService](../../src/main/java/io/agentscope/dataagent/dataset/parser/SchemaGenerationService.java), [TableProvisioner](../../src/main/java/io/agentscope/dataagent/dataset/TableProvisioner.java) | 9 / 0 / 0.990 | 保留现行回归 |
| J010 [DatasetImportRollbackTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportRollbackTest.java) | 平台事务失败时清理新物理表，提交后保留 | [DatasetImportService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetImportService.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 4 / 0 / 0.278 | 保留现行回归 |
| J011 [DatasetMetadataDraftSyncTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSyncTest.java) | 元数据精确合并到草稿、事务回滚和排队撤权 | [DatasetMetadataDraftSync](../../src/main/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSync.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DraftChangeLog](../../src/main/java/io/agentscope/dataagent/dataset/DraftChangeLog.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 7 / 0 / 0.388 | 保留现行回归 |
| J012 [DatasetParsingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetParsingTest.java) | CSV类型、引号、前导零、空数据与截断边界 | [Identifiers](../../src/main/java/io/agentscope/dataagent/dataset/Identifiers.java), [ParsedTable](../../src/main/java/io/agentscope/dataagent/dataset/parser/ParsedTable.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java), [TypeInferrer](../../src/main/java/io/agentscope/dataagent/dataset/parser/TypeInferrer.java) | 9 / 0 / 0.017 | 保留现行回归 |
| J013 [DatasetSamplingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetSamplingTest.java) | 受限采样、空值、禁用和样本文件清理 | [DatasetSampling](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java), [DatasetSampleDocuments](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampleDocuments.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 4 / 0 / 0.235 | 保留现行回归 |
| J014 `DatasetServiceEvidenceTest`（后续清理已移除，见阶段2报告） | 旧自定义文档证据检索的分段排序与引用 | [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java), [DatasetImportService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetImportService.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 8 / 0 / 0.029 | 清理候选，未删除 |
| J015 [DatasetServiceMdlDirtyTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceMdlDirtyTest.java) | 说明变化保留旧发布查询，物理结构变化仍阻断 | [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java), [DatasetSampling](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java) | 14 / 0 / 0.227 | 保留现行回归 |
| J016 [DatasetServiceSourceCombinationTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceSourceCombinationTest.java) | 单Wren来源约束、跨租户、原子关联和刷新撤权 | [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetSampling](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java), [DatasetImportService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetImportService.java) | 11 / 0 / 0.066 | 保留现行回归 |
| J017 [DatasetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceTest.java) | 视图语句显示与上传日期区间文字生成 | [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 4 / 0 / 0.003 | 保留现行回归 |
| J018 [DatasetUploadAdmissionTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadAdmissionTest.java) | 上传全局限额、同名互斥、取消与用户隔离 | [DatasetUploadAdmission](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadAdmission.java), [DatasetUploadProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadProperties.java) | 5 / 0 / 0.024 | 保留现行回归 |
| J019 [DatasetUploadIntakeTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadIntakeTest.java) | 上传磁盘暂存、实际大小、取消和清理 | [DatasetUploadIntake](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadIntake.java), [DatasetUploadAdmission](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadAdmission.java), [DatasetUploadProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadProperties.java) | 6 / 0 / 0.259 | 保留现行回归 |
| J020 [DatasetUploadPolicyDefaultsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadPolicyDefaultsTest.java) | 发布YAML与上传策略类默认限额一致且可配置 | [DatasetUploadProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetUploadProperties.java) | 2 / 0 / 0.134 | 保留现行回归 |
| J021 [DataSourceEngineTest](../../src/test/java/io/agentscope/dataagent/dataset/DataSourceEngineTest.java) | MySQL版本和Doris支持范围，拒绝非MySQL | [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java), [ExternalDataSourcePolicy](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java) | 2 / 0 / 0.002 | 保留现行回归 |
| J022 [DemoDataServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoDataServiceTest.java) | 演示样例白名单、旧来源关闭和读账号配置 | [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java), [DemoDataProperties](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataProperties.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 5 / 0 / 0.049 | 保留现行回归 |
| J023 [DemoSampleFilesTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoSampleFilesTest.java) | 真实样例文件经控制器/解析器保留主键和金额 | [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java), [DemoDataProperties](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataProperties.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 1 / 0 / 1.468 | 保留现行回归 |
| J024 [DocEnhanceServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DocEnhanceServiceTest.java) | 文档建议去重、人工采纳状态和租户边界 | [DocEnhanceService](../../src/main/java/io/agentscope/dataagent/dataset/DocEnhanceService.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java) | 7 / 0 / 0.357 | 保留现行回归 |
| J025 [DraftItemServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftItemServiceTest.java) | 逐项草稿编辑、关系/计算列、日志与锁后权限 | [DraftItemService](../../src/main/java/io/agentscope/dataagent/dataset/DraftItemService.java), [DraftChangeLog](../../src/main/java/io/agentscope/dataagent/dataset/DraftChangeLog.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 9 / 0 / 1.114 | 保留现行回归 |
| J026 [DraftReviewServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftReviewServiceTest.java) | 草稿差异、单项撤销、全部丢弃和并发权限 | [DraftReviewService](../../src/main/java/io/agentscope/dataagent/dataset/DraftReviewService.java), [DraftChangeLog](../../src/main/java/io/agentscope/dataagent/dataset/DraftChangeLog.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java) | 8 / 0 / 0.522 | 保留现行回归 |
| J027 [ExcelParserTest](../../src/test/java/io/agentscope/dataagent/dataset/ExcelParserTest.java) | 实际Excel解析、原始表头和文件类型支持 | [ExcelParser](../../src/main/java/io/agentscope/dataagent/dataset/parser/ExcelParser.java), [ParsedTable](../../src/main/java/io/agentscope/dataagent/dataset/parser/ParsedTable.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 3 / 0 / 0.164 | 保留现行回归 |
| J028 [ExternalDataSourcePolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicyTest.java) | 连接参数白名单、规范化和禁止注入 | [ExternalDataSourcePolicy](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java) | 16 / 0 / 0.177 | 保留现行回归 |
| J029 [FactQueryWindowPolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicyTest.java) | 逐库日期门禁、缓存、撤权及已发布资产继承 | [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [PublishedAssetWindows](../../src/main/java/io/agentscope/dataagent/dataset/PublishedAssetWindows.java) | 8 / 0 / 0.145 | 保留现行回归 |
| J030 [FactTableWindowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FactTableWindowServiceTest.java) | 事实表角色/日期配置、跨库拒绝和排队撤权 | [FactTableWindowService](../../src/main/java/io/agentscope/dataagent/dataset/FactTableWindowService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java), [DatasetSampling](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java) | 8 / 0 / 0.026 | 保留现行回归 |
| J031 [FieldScreeningServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FieldScreeningServiceTest.java) | 字段纳入选择、时间列保护和已建模引用检查 | [FieldScreeningService](../../src/main/java/io/agentscope/dataagent/dataset/FieldScreeningService.java), [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 7 / 0 / 0.433 | 保留现行回归 |
| J032 [KnowledgeBaseAccessServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessServiceTest.java) | QUERY/MANAGE、共享、撤权、管理员降权和删除状态 | [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java), [KnowledgeBaseOperations](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseOperations.java), [UserStore](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java) | 8 / 0 / 0.136 | 保留现行回归 |
| J033 [KnowledgeBaseBrowseTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseBrowseTest.java) | 已发布只读投影、凭据隐藏和读取后撤权 | [KnowledgeBaseBrowseService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseBrowseService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 5 / 0 / 0.558 | 保留现行回归 |
| J034 `KnowledgeEvidenceTest`（后续清理已移除，见阶段2报告） | 旧平台文档分段、词项和分数计算 | `KnowledgeEvidence`（后续清理已移除，见阶段2报告） | 9 / 0 / 0.013 | 清理候选，未删除 |
| J035 [KnowledgeGraphServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeGraphServiceTest.java) | 已停用知识图谱的节点去重、关系持久化与构建守卫 | [KnowledgeGraphService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeGraphService.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [AgentDraftService](../../src/main/java/io/agentscope/dataagent/web/ai/AgentDraftService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 6 / 0 / 0.164 | 清理候选，未删除 |
| J036 [MdlCatalogTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlCatalogTest.java) | 已发布模型目录、时间列和样例元数据读取 | [MdlCatalog](../../src/main/java/io/agentscope/dataagent/dataset/MdlCatalog.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 8 / 0 / 0.185 | 保留现行回归 |
| J037 [MdlPublishServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishServiceTest.java) | 草稿校验构建、原子发布、失败保留旧版和来源隔离 | [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [MdlSeeder](../../src/main/java/io/agentscope/dataagent/dataset/MdlSeeder.java), [MdlCatalog](../../src/main/java/io/agentscope/dataagent/dataset/MdlCatalog.java) | 24 / 0 / 2.968 | 保留现行回归 |
| J038 [MdlPublishWiringTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishWiringTest.java) | Spring选择完整发布构造器并注入权限、删除和事件依赖 | [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [MdlSeeder](../../src/main/java/io/agentscope/dataagent/dataset/MdlSeeder.java), [MdlWorkspaceReader](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java) | 1 / 0 / 0.275 | 保留，细化或拆分断言 |
| J039 [MdlQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionServiceTest.java) | 用户确认问题的权限、修订、工作区锁和日期门禁 | [MdlQuestionService](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionService.java), [MdlQuestionStore](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionStore.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 10 / 0 / 0.092 | 保留现行回归 |
| J040 [MdlQuestionStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionStoreTest.java) | 问答确认、撤销、证据快照、过期复核与发布样例 | [MdlQuestionStore](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionStore.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [MdlWorkspaceReader](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java) | 31 / 0 / 2.362 | 保留现行回归 |
| J041 [MdlSeederTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSeederTest.java) | 基础模型增量播种、保留用户编辑和物理冲突报告 | [MdlSeeder](../../src/main/java/io/agentscope/dataagent/dataset/MdlSeeder.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 9 / 0 / 0.417 | 保留现行回归 |
| J042 [MdlSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSuggestionServiceTest.java) | 手工关系及旧关系/指标/视图建议与工作区同步 | [MdlSuggestionService](../../src/main/java/io/agentscope/dataagent/dataset/MdlSuggestionService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [MdlWorkspaceReader](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java) | 29 / 0 / 0.392 | 保留，细化或拆分断言 |
| J043 [MdlWorkspaceReaderTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlWorkspaceReaderTest.java) | 官方YAML、规则、关系、视图与ref_sql读取 | [MdlWorkspaceReader](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceReader.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 13 / 0 / 0.475 | 保留现行回归 |
| J044 [ModelingConfirmationServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingConfirmationServiceTest.java) | 业务澄清卡、问题语义、决定和操作者隔离 | [ModelingConfirmationService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingConfirmationService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java) | 6 / 0 / 0.335 | 保留现行回归 |
| J045 [ModelingHubServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingHubServiceTest.java) | 建模概览只读发布信息及读取过程降权 | [ModelingHubService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingHubService.java), [ModelingConfirmationService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingConfirmationService.java), [DraftReviewService](../../src/main/java/io/agentscope/dataagent/dataset/DraftReviewService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java) | 3 / 0 / 0.083 | 保留现行回归 |
| J046 [ModelingRevisionTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingRevisionTest.java) | 真实JPA乐观版本与发布修订不能覆盖较新变更 | [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [ConversationScopeRegistry](../../src/main/java/io/agentscope/dataagent/web/session/ConversationScopeRegistry.java) | 2 / 0 / 2.553 | 保留，优先提速 |
| J047 [ModelingWorkflowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingWorkflowServiceTest.java) | 建模步骤、工程校验和可选问题不误阻断已发布查询 | [ModelingWorkflowService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingWorkflowService.java), [ModelingWorkflowStore](../../src/main/java/io/agentscope/dataagent/dataset/ModelingWorkflowStore.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 7 / 0 / 0.382 | 保留现行回归 |
| J048 [SchemaTableRoleTest](../../src/test/java/io/agentscope/dataagent/dataset/parser/SchemaTableRoleTest.java) | 复用Schema模型返回表角色，不按日期列猜测事实表 | [SchemaGenerationService](../../src/main/java/io/agentscope/dataagent/dataset/parser/SchemaGenerationService.java) | 2 / 0 / 0.036 | 保留现行回归 |
| J049 [ProcessWrenCliTest](../../src/test/java/io/agentscope/dataagent/dataset/ProcessWrenCliTest.java) | 子进程启动失败、超时、退出码和不泄露参数的耗时日志 | [ProcessWrenCli](../../src/main/java/io/agentscope/dataagent/dataset/ProcessWrenCli.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [WrenTiming](../../src/main/java/io/agentscope/dataagent/dataset/WrenTiming.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 4 / 0 / 0.340 | 保留现行回归 |
| J050 [PublishedAssetWindowsTest](../../src/test/java/io/agentscope/dataagent/dataset/PublishedAssetWindowsTest.java) | View/ref_sql依赖继承最严格时间窗口 | [PublishedAssetWindows](../../src/main/java/io/agentscope/dataagent/dataset/PublishedAssetWindows.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java) | 5 / 0 / 0.003 | 保留现行回归 |
| J051 [QuestionDependenciesTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionDependenciesTest.java) | 已确认问题读取的模型/关系/资产依赖及变化提示 | [QuestionDependencies](../../src/main/java/io/agentscope/dataagent/dataset/QuestionDependencies.java) | 2 / 0 / 0.080 | 保留现行回归 |
| J052 [QuestionSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionSuggestionServiceTest.java) | 试问建议去重限量、错误降级和时间范围来源 | [QuestionSuggestionService](../../src/main/java/io/agentscope/dataagent/dataset/QuestionSuggestionService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [DatasetSampling](../../src/main/java/io/agentscope/dataagent/dataset/DatasetSampling.java) | 4 / 0 / 0.006 | 保留现行回归 |
| J053 [RecommendedQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/RecommendedQuestionServiceTest.java) | 推荐问题日期、手工覆盖和发布后更新 | [RecommendedQuestionService](../../src/main/java/io/agentscope/dataagent/dataset/RecommendedQuestionService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java), [QuestionSuggestionService](../../src/main/java/io/agentscope/dataagent/dataset/QuestionSuggestionService.java) | 8 / 0 / 0.042 | 保留现行回归 |
| J054 `RefSqlMaterializerTest`（后续清理已移除，见阶段2报告） | 未找到生产调用的旧SQL表名改写工具 | `RefSqlMaterializer`（后续清理已移除，见阶段2报告） | 17 / 0 / 0.006 | 清理候选，未删除 |
| J055 [SchemaTripleExtractorTest](../../src/test/java/io/agentscope/dataagent/dataset/SchemaTripleExtractorTest.java) | 已停用知识图谱的确定性Schema三元组提取 | [SchemaTripleExtractor](../../src/main/java/io/agentscope/dataagent/dataset/SchemaTripleExtractor.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 1 / 0 / 0.002 | 清理候选，未删除 |
| J056 [SemanticAssetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SemanticAssetServiceTest.java) | 语义资产核查、问题验证、证据和发布门禁 | [SemanticAssetService](../../src/main/java/io/agentscope/dataagent/dataset/SemanticAssetService.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [MdlQuestionService](../../src/main/java/io/agentscope/dataagent/dataset/MdlQuestionService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 21 / 0 / 0.827 | 保留现行回归 |
| J057 [SharedDraftWrenAuthorizationTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedDraftWrenAuthorizationTest.java) | 共享建模的身份不替换、锁后撤权和只读用户拒绝 | [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [OfficialWrenToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkit.java) | 12 / 0 / 0.341 | 保留现行回归 |
| J058 [SharedOfficialWrenAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedOfficialWrenAccessTest.java) | 共享查询只用已发布快照，不读草稿并及时撤权 | [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java), [UserStore](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java) | 1 / 0 / 0.057 | 保留现行回归 |
| J059 [SqlExampleDocumentTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleDocumentTest.java) | SQL Markdown编辑保持字面值、换行和其他字段 | [SqlExampleDocument](../../src/main/java/io/agentscope/dataagent/dataset/SqlExampleDocument.java) | 14 / 0 / 0.020 | 保留现行回归 |
| J060 [SqlExampleRecallTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleRecallTest.java) | 官方问答召回结果和发布样例元数据兼容 | [SqlExampleRecall](../../src/main/java/io/agentscope/dataagent/dataset/SqlExampleRecall.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 4 / 0 / 0.020 | 保留现行回归 |
| J061 [SqlQaExampleMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleMemoryIntegrationTest.java) | 真实官方LanceDB索引拒绝后的回滚及召回 | [SqlQaExampleService](../../src/main/java/io/agentscope/dataagent/dataset/SqlQaExampleService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java) | 1 / 1 / 0.008 | 保留，条件专项/混合 |
| J062 [SqlQaExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleServiceTest.java) | 问答草稿/点赞来源修订、索引失败恢复及并发串行 | [SqlQaExampleService](../../src/main/java/io/agentscope/dataagent/dataset/SqlQaExampleService.java), [SqlExampleDocument](../../src/main/java/io/agentscope/dataagent/dataset/SqlExampleDocument.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 10 / 0 / 1.084 | 保留现行回归 |
| J063 [SqlWindowAuditTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowAuditTest.java) | 日期门禁日志区分检查/缓存/拒绝且不记录SQL | [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java) | 1 / 0 / 0.006 | 保留现行回归 |
| J064 [SqlWindowGateTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowGateTest.java) | 日期校验返回值、超时取消、模型快照和失败关闭 | [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java) | 12 / 0 / 0.080 | 保留现行回归 |
| J065 [SqlWindowRulesTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowRulesTest.java) | 可确定的SQL日期范围检查与复杂SQL回交模型 | [SqlWindowRules](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowRules.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java) | 6 / 0 / 0.004 | 保留现行回归 |
| J066 [SyntheticDemoMysqlTest](../../src/test/java/io/agentscope/dataagent/dataset/SyntheticDemoMysqlTest.java) | 真实隔离MySQL演示初始化幂等和只读账号范围 | [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java), [ExternalDataSourcePolicy](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java), [ExcelParser](../../src/main/java/io/agentscope/dataagent/dataset/parser/ExcelParser.java), [DemoDataProperties](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataProperties.java) | 1 / 1 / 0.000 | 保留，条件专项/混合 |
| J067 [TableProvisionerTest](../../src/test/java/io/agentscope/dataagent/dataset/TableProvisionerTest.java) | 上传物理表DDL排序规则与字段可空性 | [TableProvisioner](../../src/main/java/io/agentscope/dataagent/dataset/TableProvisioner.java), [ColumnSchema](../../src/main/java/io/agentscope/dataagent/dataset/parser/ColumnSchema.java) | 2 / 0 / 0.000 | 保留现行回归 |
| J068 [TemporalConditionTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalConditionTest.java) | 时间字段真实格式对应的SQL比较表达式 | [TemporalCondition](../../src/main/java/io/agentscope/dataagent/dataset/TemporalCondition.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java) | 4 / 0 / 0.030 | 保留现行回归 |
| J069 [TemporalSamplesTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalSamplesTest.java) | 严格时间采样识别并保留人工确认属性 | [TemporalSamples](../../src/main/java/io/agentscope/dataagent/dataset/TemporalSamples.java), [ModelTemporalMetadata](../../src/main/java/io/agentscope/dataagent/dataset/ModelTemporalMetadata.java) | 4 / 0 / 0.047 | 保留现行回归 |
| J070 [WrenProfileHomeTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenProfileHomeTest.java) | Wren连接配置的字面凭据、SSL、修复漂移与来源范围 | [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [ExternalDataSourcePolicy](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java), [DatasetStoreProperties](../../src/main/java/io/agentscope/dataagent/dataset/DatasetStoreProperties.java) | 11 / 0 / 0.071 | 保留现行回归 |
| J071 [WrenPropertiesResolveTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPropertiesResolveTest.java) | 跨系统Wren可执行文件解析及显式配置优先 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 6 / 0 / 0.038 | 保留现行回归 |
| J072 [WrenPublishedProjectTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPublishedProjectTest.java) | 已发布工程快照、索引复用和官方文件名隔离 | [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenCli](../../src/main/java/io/agentscope/dataagent/dataset/WrenCli.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 4 / 0 / 0.382 | 保留现行回归 |
| J073 [WrenQueryAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryAccessTest.java) | 选库、归属和首次未发布时的问数权限 | [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java), [ConversationScopeRegistry](../../src/main/java/io/agentscope/dataagent/web/session/ConversationScopeRegistry.java) | 2 / 0 / 0.002 | 保留现行回归 |
| J074 [WrenQueryMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryMemoryIntegrationTest.java) | 真实Wren问答文件和向量跨MCP会话召回 | [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), `WrenMcpToolkit`（后续清理已移除，见阶段2报告）, [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java) | 1 / 1 / 0.003 | 保留，条件专项/混合 |
| J075 [WrenQueryPreparationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryPreparationTest.java) | 提交后预热、事件合并、撤权和失败重试 | [WrenQueryPreparation](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryPreparation.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 4 / 0 / 0.133 | 保留现行回归 |
| J076 [WrenSkillsLocatorTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenSkillsLocatorTest.java) | 跨系统官方技能包路径发现与无效目录处理 | [WrenSkillsLocator](../../src/main/java/io/agentscope/dataagent/dataset/WrenSkillsLocator.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java) | 8 / 0 / 0.108 | 保留现行回归 |
| J077 [WrenTimingIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenTimingIntegrationTest.java) | 真实CLI索引重用及样例变化的分段耗时 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [ProcessWrenCli](../../src/main/java/io/agentscope/dataagent/dataset/ProcessWrenCli.java) | 1 / 1 / 0.004 | 保留，条件专项/混合 |
| J078 [DocsConsistencyTest](../../src/test/java/io/agentscope/dataagent/DocsConsistencyTest.java) | 文档规模、链接、ADR取代关系及spec索引完整 | 文档文件 | 5 / 0 / 0.103 | 保留现行回归 |
| J079 [WebhookInboundMapperTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundMapperTest.java) | 外部消息身份、会话、Agent和空输入映射 | [WebhookInboundMapper](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundMapper.java), [WebhookInboundRequest](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundRequest.java) | 8 / 0 / 0.005 | 保留现行回归 |
| J080 [WebhookSignatureTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignatureTest.java) | Webhook HMAC、篡改拒绝和签名往返 | [WebhookSignature](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignature.java), [WebhookCallbackController](../../src/main/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookCallbackController.java) | 8 / 0 / 0.070 | 保留现行回归 |
| J081 [GatewayTurnLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/GatewayTurnLifecycleTest.java) | 排队轮次作用域、恢复会话归属和忙时失败 | [SessionKind](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionKind.java), [SessionEntry](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionEntry.java), [HarnessGateway](../../src/main/java/io/agentscope/dataagent/runtime/gateway/HarnessGateway.java), [AgentManagerConfig](../../src/main/java/io/agentscope/dataagent/runtime/session/AgentManagerConfig.java) | 3 / 0 / 0.167 | 保留现行回归 |
| J082 [HarnessGatewaySandboxlessTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/HarnessGatewaySandboxlessTest.java) | 免沙箱Agent跳过借用，其他Agent懒启动并复用 | [HarnessGateway](../../src/main/java/io/agentscope/dataagent/runtime/gateway/HarnessGateway.java), [LazySandbox](../../src/main/java/io/agentscope/dataagent/web/workspace/LazySandbox.java), [AgentManagerConfig](../../src/main/java/io/agentscope/dataagent/runtime/session/AgentManagerConfig.java), [UserSandboxRegistry](../../src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | 3 / 0 / 0.088 | 保留现行回归 |
| J083 [ManagedModelHttpTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ManagedModelHttpTest.java) | 真实本地HTTP替身验证模型路径、Bearer、SSE和安全错误 | [ManagedModelFactory](../../src/main/java/io/agentscope/dataagent/runtime/model/ManagedModelFactory.java), [ModelServiceCipher](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceCipher.java), [ModelServiceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java) | 11 / 0 / 0.818 | 保留现行回归 |
| J084 [ModelServiceCipherTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceCipherTest.java) | 密钥持久性、认证加密和密钥丢失/错误处理 | [ModelServiceCipher](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceCipher.java) | 2 / 0 / 0.032 | 保留现行回归 |
| J085 [ModelServiceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistryTest.java) | 模型配置加密、显式激活、修订和轮次快照 | [ModelServiceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java), [ModelServiceCipher](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceCipher.java), [ManagedModelFactory](../../src/main/java/io/agentscope/dataagent/runtime/model/ManagedModelFactory.java), [SwitchingModel](../../src/main/java/io/agentscope/dataagent/runtime/model/SwitchingModel.java) | 6 / 0 / 0.300 | 保留现行回归 |
| J086 [SwitchingModelTest](../../src/test/java/io/agentscope/dataagent/runtime/model/SwitchingModelTest.java) | 并行订阅和同轮辅助调用固定模型快照 | [SwitchingModel](../../src/main/java/io/agentscope/dataagent/runtime/model/SwitchingModel.java), [ModelServiceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java) | 2 / 0 / 0.003 | 保留现行回归 |
| J087 [AnalysisSkillMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/AnalysisSkillMiddlewareTest.java) | 普通问数、待确认和确认后分析的提示与执行约定 | [AnalysisSkillMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/session/AnalysisSkillMiddleware.java), [AnalysisTurn](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisTurn.java) | 3 / 0 / 0.003 | 保留核心，改写文本/结构断言 |
| J088 [DataDynamicContextMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/DataDynamicContextMiddlewareTest.java) | 非主Agent兼容装配的模型目录、选库与无物理回退 | [DataDynamicContextMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/session/DataDynamicContextMiddleware.java), [DataSource](../../src/main/java/io/agentscope/dataagent/tools/data/DataSource.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java), [DatasetContextProvider](../../src/main/java/io/agentscope/dataagent/dataset/DatasetContextProvider.java) | 12 / 0 / 0.012 | 保留，细化或拆分断言 |
| J089 [GroupScopedMemoryNamespaceTest](../../src/test/java/io/agentscope/dataagent/runtime/session/GroupScopedMemoryNamespaceTest.java) | 记忆命名空间按用户/知识库隔离及旧范围兼容 | [GroupScopedMemoryNamespace](../../src/main/java/io/agentscope/dataagent/runtime/session/GroupScopedMemoryNamespace.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java) | 6 / 0 / 0.002 | 保留现行回归 |
| J090 [ProjectFilesContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/ProjectFilesContextTest.java) | 建模资料清单和确认选项上下文 | [ModelingConfirmationService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingConfirmationService.java), [WrenProjectContextMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenProjectContextMiddleware.java) | 2 / 0 / 0.044 | 保留现行回归 |
| J091 [QuestionDateGateTest](../../src/test/java/io/agentscope/dataagent/runtime/session/QuestionDateGateTest.java) | 原生生命周期日期澄清、历史恢复与权限 | [QuestionDateGate](../../src/main/java/io/agentscope/dataagent/runtime/session/QuestionDateGate.java), [MdlCatalog](../../src/main/java/io/agentscope/dataagent/dataset/MdlCatalog.java), [DatasetScope](../../src/main/java/io/agentscope/dataagent/dataset/DatasetScope.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java) | 5 / 0 / 0.051 | 保留现行回归 |
| J092 [RuntimeWindowContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/RuntimeWindowContextTest.java) | 日期门禁上下文、服务时间、提示透传与中文提示 | [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [DataAgentConfig](../../src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | 7 / 0 / 0.066 | 保留核心，改写文本/结构断言 |
| J093 [SessionStateLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/session/SessionStateLifecycleTest.java) | 会话重置/移除只清当前归属检查点并支持重试 | [SessionStore](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionStore.java), [SessionResetPolicy](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionResetPolicy.java), [SessionKind](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionKind.java), [SessionEntry](../../src/main/java/io/agentscope/dataagent/runtime/session/SessionEntry.java) | 4 / 0 / 0.111 | 保留现行回归 |
| J094 [WrenSamplingContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenSamplingContextTest.java) | 建模采样上下文范围、表角色、日期与当前轮目的 | [WrenProjectAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenProjectAccess.java), [WrenProjectContextMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenProjectContextMiddleware.java) | 9 / 0 / 0.110 | 保留核心，改写文本/结构断言 |
| J095 [WrenScopedToolkitMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenScopedToolkitMiddlewareTest.java) | 按本轮授权挂载官方schema及显式联网开关 | [WrenScopedToolkitMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenScopedToolkitMiddleware.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [WrenToolForwarder](../../src/main/java/io/agentscope/dataagent/runtime/session/WrenToolForwarder.java) | 4 / 0 / 0.056 | 保留现行回归 |
| J096 [SubagentOrchestrationSwitchTest](../../src/test/java/io/agentscope/dataagent/runtime/SubagentOrchestrationSwitchTest.java) | 配置默认关闭子Agent的反射注解和YAML约定 | [DataAgentConfig](../../src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | 2 / 0 / 0.017 | 保留核心，改写文本/结构断言 |
| J097 [WrenConnectionRecoveryIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryIntegrationTest.java) | 真实Wren代理中断后的有界重连与重复故障 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [WrenInstanceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistry.java) | 2 / 2 / 0.002 | 保留，条件专项/混合 |
| J098 [WrenConnectionRecoveryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryTest.java) | 故障识别、一次重放、冷却、取消和重连时撤权 | [WrenConnectionFailure](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenConnectionFailure.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java) | 30 / 0 / 0.454 | 保留现行回归 |
| J099 [WrenInstanceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistryTest.java) | MCP实例池、互斥、缓存、关闭预算和排队撤权 | [WrenInstanceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistry.java), [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java) | 18 / 0 / 0.398 | 保留现行回归 |
| J100 [WrenMcpClassLoaderTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMcpClassLoaderTest.java) | 受限线程上下文类加载器与官方MCP初始化 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenInstanceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistry.java) | 2 / 0 / 0.012 | 保留现行回归 |
| J101 [WrenMysqlLimitIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMysqlLimitIntegrationTest.java) | 真实官方CLI/MCP的LIMIT和截断兼容 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenInstanceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistry.java), [ProcessWrenCli](../../src/main/java/io/agentscope/dataagent/dataset/ProcessWrenCli.java) | 1 / 1 / 0.001 | 保留，条件专项/混合 |
| J102 [WrenProcessEnvironmentTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenProcessEnvironmentTest.java) | Python兼容资源安装与条件启用的真实Wren回归 | [WrenProcessEnvironment](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenProcessEnvironment.java) | 2 / 1 / 0.024 | 保留，条件专项/混合 |
| J103 [IsolatedApplicationTestSupport](../../src/test/java/io/agentscope/dataagent/support/IsolatedApplicationTestSupport.java) | 三个完整应用测试的临时目录和外部沙箱替身基类 | [DataAgentApp](../../src/main/java/io/agentscope/dataagent/web/DataAgentApp.java) | 辅助文件 | 测试辅助，保留 |
| J104 [AnalysisScriptsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisScriptsTest.java) | 本地Python实际运行归因/异常/预测脚本的数值与样本边界 | [AnalysisSkills](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisSkills.java) | 4 / 4 / 0.651 | 保留，条件专项/混合 |
| J105 [AnalysisSkillsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisSkillsTest.java) | 分析技能打包、重装和参数确认卡 | [AnalysisSkills](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisSkills.java), [AnalysisToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisToolkit.java), [AnalysisTurn](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisTurn.java) | 4 / 0 / 0.097 | 保留现行回归 |
| J106 [ChartBuilderTest](../../src/test/java/io/agentscope/dataagent/tools/data/ChartBuilderTest.java) | 图表类型推断、日期、维度、轴及无效图表规范 | [ChartBuilder](../../src/main/java/io/agentscope/dataagent/tools/data/ChartBuilder.java), [DataAgentToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | 9 / 0 / 0.040 | 保留现行回归 |
| J107 [DataAgentToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/DataAgentToolkitTest.java) | 禁止物理查询工具和ECharts呈现结果 | [DataAgentToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java) | 4 / 0 / 0.002 | 保留现行回归 |
| J108 [ModelingToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/ModelingToolkitTest.java) | 草稿文件写入/预检/回滚/权限及部分旧结构化工具 | [ModelingToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/ModelingToolkit.java), [ModelingWorkflowService](../../src/main/java/io/agentscope/dataagent/dataset/ModelingWorkflowService.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 64 / 0 / 1.549 | 保留，细化或拆分断言 |
| J109 [OfficialWrenCliIntegrationTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenCliIntegrationTest.java) | 安装的官方CLI与平台适配器在隔离工程上对照 | [OfficialWrenToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkit.java), `WrenMcpToolkit`（后续清理已移除，见阶段2报告）, [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java) | 1 / 1 / 0.003 | 保留，条件专项/混合 |
| J110 [OfficialWrenToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkitTest.java) | 原生文件和CLI的权限、只读、路径、轮次和构建边界 | [OfficialWrenToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkit.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) | 31 / 0 / 1.580 | 保留现行回归 |
| J111 [QueryDataFilesTest](../../src/test/java/io/agentscope/dataagent/tools/data/QueryDataFilesTest.java) | 查询结果精确CSV落地、大小限制和存储失败降级 | [QueryDataFiles](../../src/main/java/io/agentscope/dataagent/tools/data/QueryDataFiles.java), [ArtifactStore](../../src/main/java/io/agentscope/dataagent/web/artifact/ArtifactStore.java) | 3 / 0 / 0.140 | 保留现行回归 |
| J112 [RunPythonToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/RunPythonToolTest.java) | 确认后分析、禁止抄写大批行和脚本按需安装 | [RunPythonTool](../../src/main/java/io/agentscope/dataagent/tools/data/RunPythonTool.java), [AnalysisTurn](../../src/main/java/io/agentscope/dataagent/tools/data/AnalysisTurn.java) | 5 / 0 / 0.006 | 保留现行回归 |
| J113 `TenantTablesFixture`（后续清理已移除，见阶段2报告） | 工具测试共用的两张隔离H2分析表夹具 | [DataSource](../../src/main/java/io/agentscope/dataagent/tools/data/DataSource.java) | 辅助文件 | 测试辅助，保留 |
| J114 [WebSearchToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/WebSearchToolTest.java) | 显式授权联网、本地HTTP替身和真实原生Agent调用 | [WebSearchTool](../../src/main/java/io/agentscope/dataagent/tools/data/WebSearchTool.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java) | 7 / 0 / 1.752 | 保留现行回归 |
| J115 `WrenMcpToolkitTest`（后续清理已移除，见阶段2报告） | 未找到生产装配的旧wren_list_tools/wren_call_tool包装器 | `WrenMcpToolkit`（后续清理已移除，见阶段2报告）, [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java) | 2 / 0 / 0.009 | 清理候选，未删除 |
| J116 [WrenScopedMcpClientTest](../../src/test/java/io/agentscope/dataagent/tools/data/WrenScopedMcpClientTest.java) | 当前官方MCP schema、只读、数据范围、撤权、缓存和文件 | [WrenScopedMcpClient](../../src/main/java/io/agentscope/dataagent/tools/data/WrenScopedMcpClient.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [WrenPublishedProject](../../src/main/java/io/agentscope/dataagent/dataset/WrenPublishedProject.java) | 19 / 0 / 0.101 | 保留现行回归 |
| J117 `WrenToolkitTest`（后续清理已移除，见阶段2报告） | 未找到生产装配的旧wren_run_sql/Cube包装器 | `WrenToolkit`（后续清理已移除，见阶段2报告）, [WrenQueryGateway](../../src/main/java/io/agentscope/dataagent/runtime/wren/WrenQueryGateway.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlCatalog](../../src/main/java/io/agentscope/dataagent/dataset/MdlCatalog.java) | 38 / 0 / 0.132 | 清理候选，未删除 |
| J118 [AgentDraftServiceTest](../../src/test/java/io/agentscope/dataagent/web/ai/AgentDraftServiceTest.java) | 绕过Agent中间件的直调模型转录日志 | [AgentDraftService](../../src/main/java/io/agentscope/dataagent/web/ai/AgentDraftService.java) | 3 / 0 / 0.004 | 保留现行回归 |
| J119 [AdminBiSyncControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminBiSyncControllerTest.java) | 全局管理员只读同步状态、禁缓存和阻塞隔离 | [AdminBiSyncController](../../src/main/java/io/agentscope/dataagent/web/api/AdminBiSyncController.java), [BiSyncStatusService](../../src/main/java/io/agentscope/dataagent/dataset/BiSyncStatusService.java) | 3 / 0 / 0.630 | 保留现行回归 |
| J120 [AdminHistoryHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminHistoryHttpTest.java) | 真实HTTP/JPA管理员历史、统一过滤、导出和权限撤销 | [AdminHistoryOverviewController](../../src/main/java/io/agentscope/dataagent/web/api/AdminHistoryOverviewController.java), [AdminConversationController](../../src/main/java/io/agentscope/dataagent/web/api/AdminConversationController.java), [ArtifactController](../../src/main/java/io/agentscope/dataagent/web/api/ArtifactController.java), [AuthSettings](../../src/main/java/io/agentscope/dataagent/web/auth/AuthSettings.java) | 4 / 0 / 9.658 | 保留，优先提速 |
| J121 [AdminModelServiceControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminModelServiceControllerTest.java) | HTTP断言DSL验证模型管理权限、禁缓存和安全错误 | [AdminModelServiceController](../../src/main/java/io/agentscope/dataagent/web/api/AdminModelServiceController.java), [ModelServiceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java) | 3 / 0 / 0.514 | 保留现行回归 |
| J122 [AnswerFeedbackControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AnswerFeedbackControllerTest.java) | 点赞拒绝别人会话以及建模Agent写反馈 | [AnswerFeedbackController](../../src/main/java/io/agentscope/dataagent/web/api/AnswerFeedbackController.java), [AnswerQueryMemory](../../src/main/java/io/agentscope/dataagent/dataset/AnswerQueryMemory.java), [AgentAccessGuard](../../src/main/java/io/agentscope/dataagent/web/share/AgentAccessGuard.java), [SessionController](../../src/main/java/io/agentscope/dataagent/web/api/SessionController.java) | 2 / 0 / 0.590 | 保留现行回归 |
| J123 [BusinessTermControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/BusinessTermControllerTest.java) | 术语增量请求未带delete字段时正确绑定 | [BusinessTermController](../../src/main/java/io/agentscope/dataagent/web/api/BusinessTermController.java) | 1 / 0 / 0.009 | 保留现行回归 |
| J124 [ChartOwnershipHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/ChartOwnershipHttpTest.java) | 真实HTTP/JPA私有图表归属和精确账号比较 | [ChartController](../../src/main/java/io/agentscope/dataagent/web/api/ChartController.java), [JwtService](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java), [DataAgentToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java), [AuthSettings](../../src/main/java/io/agentscope/dataagent/web/auth/AuthSettings.java) | 5 / 0 / 4.137 | 保留，优先提速 |
| J125 [ChatControllerAnalysisTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerAnalysisTest.java) | 聊天请求中的分析选项、参数和新手进度 | [ChatController](../../src/main/java/io/agentscope/dataagent/web/api/ChatController.java) | 4 / 0 / 0.116 | 保留现行回归 |
| J126 [ChatControllerHitlTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerHitlTest.java) | 聊天流/确认/选库/权限/撤权/容量和会话作用域 | [ChatController](../../src/main/java/io/agentscope/dataagent/web/api/ChatController.java), [ConversationScopeRegistry](../../src/main/java/io/agentscope/dataagent/web/session/ConversationScopeRegistry.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [AgentCatalogService](../../src/main/java/io/agentscope/dataagent/web/catalog/AgentCatalogService.java) | 18 / 0 / 1.636 | 保留现行回归 |
| J127 [ChatControllerInboundShapeTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerInboundShapeTest.java) | 传入消息只包装USER，不注入额外SYSTEM | [ChatController](../../src/main/java/io/agentscope/dataagent/web/api/ChatController.java) | 2 / 0 / 0.001 | 保留现行回归 |
| J128 [DatasetControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetControllerTest.java) | 单个/批量上传、发布时机、部分成功和权限失败 | [DatasetController](../../src/main/java/io/agentscope/dataagent/web/api/DatasetController.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [BaselineMdlService](../../src/main/java/io/agentscope/dataagent/dataset/BaselineMdlService.java) | 9 / 0 / 0.210 | 保留现行回归 |
| J129 [DatasetGroupControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetGroupControllerTest.java) | 知识库详情投影、文档保存不触发旧分析和批量关联 | [DatasetGroupController](../../src/main/java/io/agentscope/dataagent/web/api/DatasetGroupController.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [DatasetController](../../src/main/java/io/agentscope/dataagent/web/api/DatasetController.java) | 7 / 0 / 0.366 | 保留现行回归 |
| J130 [ExternalDataSourceConnectionTest](../../src/test/java/io/agentscope/dataagent/web/api/ExternalDataSourceConnectionTest.java) | 临时连接测试、凭据归属、公共演示来源和编辑边界 | [ExternalDataSourceController](../../src/main/java/io/agentscope/dataagent/web/api/ExternalDataSourceController.java), [ExternalDataSourcePolicy](../../src/main/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicy.java), [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java), [DataSourceIntrospector](../../src/main/java/io/agentscope/dataagent/dataset/DataSourceIntrospector.java) | 9 / 0 / 0.200 | 保留现行回归 |
| J131 [QueryPreparationControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/QueryPreparationControllerTest.java) | 预热状态的Agent权限和知识库授权 | [QueryPreparationController](../../src/main/java/io/agentscope/dataagent/web/api/QueryPreparationController.java), [AgentAccessGuard](../../src/main/java/io/agentscope/dataagent/web/share/AgentAccessGuard.java), [WrenQueryPreparation](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryPreparation.java) | 2 / 0 / 0.127 | 保留现行回归 |
| J132 [RecommendedQuestionControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/RecommendedQuestionControllerTest.java) | QUERY可读推荐问题，陌生人不可见 | [RecommendedQuestionController](../../src/main/java/io/agentscope/dataagent/web/api/RecommendedQuestionController.java), [RecommendedQuestionService](../../src/main/java/io/agentscope/dataagent/dataset/RecommendedQuestionService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java) | 2 / 0 / 0.172 | 保留现行回归 |
| J133 [SemanticModelingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingControllerTest.java) | 建模API权限、发布投影、JSON结构和文件范围 | [SemanticModelingController](../../src/main/java/io/agentscope/dataagent/web/api/SemanticModelingController.java), [DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java), [ModelingToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/ModelingToolkit.java), [DocEnhanceService](../../src/main/java/io/agentscope/dataagent/dataset/DocEnhanceService.java) | 15 / 0 / 1.079 | 保留现行回归 |
| J134 [SemanticModelingCubeRetirementTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingCubeRetirementTest.java) | 已退役Cube路由在查权限后返回Gone | [SemanticModelingController](../../src/main/java/io/agentscope/dataagent/web/api/SemanticModelingController.java), [DatasetGroupService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetGroupService.java), [MdlSuggestionService](../../src/main/java/io/agentscope/dataagent/dataset/MdlSuggestionService.java) | 1 / 0 / 0.014 | 保留现行回归 |
| J135 [UserBindingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/UserBindingControllerTest.java) | 当前用户的通道绑定CRUD、索引及输入校验 | [UserBindingController](../../src/main/java/io/agentscope/dataagent/web/api/UserBindingController.java), [UserBinding](../../src/main/java/io/agentscope/dataagent/web/binding/UserBinding.java), [UserBindingStore](../../src/main/java/io/agentscope/dataagent/web/binding/UserBindingStore.java) | 2 / 0 / 0.025 | 保留现行回归 |
| J136 [BiAccountKnowledgeHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAccountKnowledgeHttpTest.java) | 真实HTTP/JPA的BI人员、共享/公共库和撤权 | [BiLoginService](../../src/main/java/io/agentscope/dataagent/web/auth/BiLoginService.java), [BiTicketCodec](../../src/main/java/io/agentscope/dataagent/web/auth/BiTicketCodec.java), [JwtService](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java), [AuthController](../../src/main/java/io/agentscope/dataagent/web/auth/AuthController.java) | 6 / 0 / 6.112 | 保留，优先提速 |
| J137 [BiAuthHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAuthHttpTest.java) | 真实HTTP的BI登录策略、错误和关闭旧任务端点 | [BiLoginService](../../src/main/java/io/agentscope/dataagent/web/auth/BiLoginService.java), [BiTicketCodec](../../src/main/java/io/agentscope/dataagent/web/auth/BiTicketCodec.java), [JwtService](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java), [AuthController](../../src/main/java/io/agentscope/dataagent/web/auth/AuthController.java) | 3 / 0 / 3.574 | 保留，优先提速 |
| J138 [BiTicketCodecTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiTicketCodecTest.java) | BI票据有效期、篡改、浏览器互通和登录策略 | [BiTicketCodec](../../src/main/java/io/agentscope/dataagent/web/auth/BiTicketCodec.java), [BiLoginService](../../src/main/java/io/agentscope/dataagent/web/auth/BiLoginService.java), [JwtService](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java), [AuthController](../../src/main/java/io/agentscope/dataagent/web/auth/AuthController.java) | 5 / 0 / 0.151 | 保留现行回归 |
| J139 [JwtSessionExpiryTest](../../src/test/java/io/agentscope/dataagent/web/auth/JwtSessionExpiryTest.java) | 24小时固定令牌截止时间与旧令牌兼容 | [JwtService](../../src/main/java/io/agentscope/dataagent/web/auth/JwtService.java) | 2 / 0 / 0.006 | 保留现行回归 |
| J140 [ChatCapacityTest](../../src/test/java/io/agentscope/dataagent/web/chat/ChatCapacityTest.java) | 真实并发准入、用户公平、排队取消和超时释放 | [ChatCapacity](../../src/main/java/io/agentscope/dataagent/web/chat/ChatCapacity.java) | 7 / 0 / 1.108 | 保留现行回归 |
| J141 [AgentStateConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateConfigTest.java) | 框架状态持久化、用户/库隔离、重建和不支持存储失败 | [AgentStateConfig](../../src/main/java/io/agentscope/dataagent/web/config/AgentStateConfig.java) | 6 / 0 / 0.702 | 保留现行回归 |
| J142 [AgentStateMysqlIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateMysqlIntegrationTest.java) | 真实MySQL状态版本/命名空间/并发与背景数据性能 | [AgentStateConfig](../../src/main/java/io/agentscope/dataagent/web/config/AgentStateConfig.java) | 3 / 3 / 0.000 | 保留，条件专项/混合 |
| J143 [DataAgentConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/DataAgentConfigTest.java) | 装配和工具边界，以及大量提示文字精确断言 | [DataAgentConfig](../../src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java), [DataAgentToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/DataAgentToolkit.java), `WrenMcpToolkit`（后续清理已移除，见阶段2报告）, [ModelingTurnRules](../../src/main/java/io/agentscope/dataagent/runtime/session/ModelingTurnRules.java) | 20 / 0 / 0.187 | 保留核心，改写文本/结构断言 |
| J144 [EmptyOrdersQueryFixture](../../src/test/java/io/agentscope/dataagent/web/config/EmptyOrdersQueryFixture.java) | 真实空数据库SQL聚合语义的模型评估夹具 | 测试参考夹具 | 辅助文件 | 测试辅助，保留 |
| J145 [EmptyOrdersQueryFixtureTest](../../src/test/java/io/agentscope/dataagent/web/config/EmptyOrdersQueryFixtureTest.java) | 模型评估夹具保持空聚合/分组/时间类型的真实语义 | 测试参考夹具 | 3 / 0 / 0.411 | 保留现行回归 |
| J146 [QueryDateBehaviorLiveTest](../../src/test/java/io/agentscope/dataagent/web/config/QueryDateBehaviorLiveTest.java) | 真实付费模型日期澄清、历史续问和空结果行为 | [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java), [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java), [DataAgentConfig](../../src/main/java/io/agentscope/dataagent/web/config/DataAgentConfig.java) | 1 / 1 / 0.000 | 保留，条件专项/混合 |
| J147 [SqlWindowLiveBoundaryTest](../../src/test/java/io/agentscope/dataagent/web/config/SqlWindowLiveBoundaryTest.java) | 真实付费模型经两个适配器执行日期边界判断 | [SqlWindowGate](../../src/main/java/io/agentscope/dataagent/dataset/SqlWindowGate.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java), [OfficialWrenToolkit](../../src/main/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkit.java), [FactQueryWindowPolicy](../../src/main/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicy.java) | 1 / 1 / 0.000 | 保留，条件专项/混合 |
| J148 [WrenLiveModelingEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveModelingEvaluationTest.java) | 真实模型、原生确认和官方技能建模行为 | [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProjectAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenProjectAccess.java), [WrenProjectFilesystem](../../src/main/java/io/agentscope/dataagent/tools/data/WrenProjectFilesystem.java), [MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java) | 1 / 1 / 0.000 | 保留，条件专项/混合 |
| J149 [WrenLiveQueryEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveQueryEvaluationTest.java) | 真实模型与官方MCP生成SQL对照独立参考答案 | `WrenMcpToolkit`（后续清理已移除，见阶段2报告）, [WrenProperties](../../src/main/java/io/agentscope/dataagent/dataset/WrenProperties.java), [WrenProfileHome](../../src/main/java/io/agentscope/dataagent/dataset/WrenProfileHome.java), [WrenQueryAccess](../../src/main/java/io/agentscope/dataagent/dataset/WrenQueryAccess.java) | 1 / 1 / 0.000 | 保留，条件专项/混合 |
| J150 [MarketContributionServiceTest](../../src/test/java/io/agentscope/dataagent/web/marketplace/MarketContributionServiceTest.java) | 真实JPA及文件的贡献提交/批准/拒绝一致性 | [MarketContributionService](../../src/main/java/io/agentscope/dataagent/web/marketplace/MarketContributionService.java), [FileEntry](../../src/main/java/io/agentscope/dataagent/web/marketplace/FileEntry.java), [DataAgentBootstrap](../../src/main/java/io/agentscope/dataagent/runtime/DataAgentBootstrap.java) | 7 / 0 / 4.834 | 保留，优先提速 |
| J151 [DebugLoggingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddlewareTest.java) | 对话转录、工具参数、媒体占位和消息去重 | [DebugLoggingMiddleware](../../src/main/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddleware.java) | 6 / 0 / 0.012 | 保留现行回归 |
| J152 [ExecutionTimingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/observability/ExecutionTimingMiddlewareTest.java) | 模型和工具错误有对应结束耗时事件 | [ExecutionTimingMiddleware](../../src/main/java/io/agentscope/dataagent/runtime/observability/ExecutionTimingMiddleware.java) | 1 / 0 / 0.024 | 保留现行回归 |
| J153 [ModelingHitlMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/ModelingHitlMiddlewareTest.java) | 建模原生确认/技术修复/批量失败隔离和旧工具边界 | [ModelingHitlMiddleware](../../src/main/java/io/agentscope/dataagent/web/middleware/ModelingHitlMiddleware.java) | 16 / 0 / 0.049 | 保留现行回归 |
| J154 [OnboardingControllerTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingControllerTest.java) | 新手接口只作用于当前用户并校验提示编号 | [OnboardingController](../../src/main/java/io/agentscope/dataagent/web/onboarding/OnboardingController.java), [OnboardingService](../../src/main/java/io/agentscope/dataagent/web/onboarding/OnboardingService.java), [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java) | 2 / 0 / 0.007 | 保留现行回归 |
| J155 [OnboardingServiceTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingServiceTest.java) | 四步引导、重置、日期阶段和自有资源计数 | [OnboardingService](../../src/main/java/io/agentscope/dataagent/web/onboarding/OnboardingService.java), [DemoDataService](../../src/main/java/io/agentscope/dataagent/dataset/DemoDataService.java), [KnowledgeBaseAccessService](../../src/main/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessService.java) | 17 / 0 / 0.181 | 保留现行回归 |
| J156 [AgentVisibilityQueryTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/AgentVisibilityQueryTest.java) | 真实JPA过滤自有/直接/工作区授权及撤销 | 测试参考夹具 | 1 / 0 / 1.986 | 保留，优先提速 |
| J157 [BiLoginPersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/BiLoginPersistenceTest.java) | 真实JPA票据防重放、保留角色和并发首次登录 | [BiLoginService](../../src/main/java/io/agentscope/dataagent/web/auth/BiLoginService.java), [BiTicketCodec](../../src/main/java/io/agentscope/dataagent/web/auth/BiTicketCodec.java), [UserStore](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java), [AuthSettings](../../src/main/java/io/agentscope/dataagent/web/auth/AuthSettings.java) | 2 / 0 / 1.844 | 保留，优先提速 |
| J158 [DatasetGroupMdlBackfillTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/DatasetGroupMdlBackfillTest.java) | 旧NULL修订迁移和共享/公共知识库查询分页 | 测试参考夹具 | 4 / 0 / 0.180 | 保留，优先提速 |
| J159 [H2DemoSeedTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/H2DemoSeedTest.java) | 显式开发模式下文件H2种子SQL与密码初始化 | [UserStore](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java), [JpaUserStore](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserStore.java) | 1 / 0 / 4.172 | 保留，优先提速 |
| J160 [JpaPersistenceIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceIntegrationTest.java) | 完整应用真实JPA账号、Agent定义与分享往返 | [JpaPersistenceConfig](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceConfig.java), [JpaUserStore](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserStore.java), [JpaUserAgentDefinitionStore](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserAgentDefinitionStore.java), [UserStore](../../src/main/java/io/agentscope/dataagent/web/auth/UserStore.java) | 4 / 0 / 2.964 | 保留，优先提速 |
| J161 [JpaUserBindingStoreTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserBindingStoreTest.java) | 真实JPA有序持久化通道绑定 | [JpaUserBindingStore](../../src/main/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserBindingStore.java), [UserBinding](../../src/main/java/io/agentscope/dataagent/web/binding/UserBinding.java) | 1 / 0 / 0.085 | 保留，优先提速 |
| J162 [ModelServicePersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/ModelServicePersistenceTest.java) | 模型加密配置经真实数据库重建后仍可读取 | [ModelServiceCipher](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceCipher.java), [ModelServiceRegistry](../../src/main/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistry.java), [ManagedModelFactory](../../src/main/java/io/agentscope/dataagent/runtime/model/ManagedModelFactory.java) | 1 / 0 / 1.548 | 保留，优先提速 |
| J163 [WorkspaceScaffolderTest](../../src/test/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolderTest.java) | 工作区初始化结构与保留既有文件 | [WorkspaceScaffolder](../../src/main/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolder.java) | 2 / 0 / 0.130 | 保留现行回归 |
| J164 [ConversationScopeRegistryTest](../../src/test/java/io/agentscope/dataagent/web/session/ConversationScopeRegistryTest.java) | 用户/会话选库持久化、轮次快照和并发原子写 | [ConversationScopeRegistry](../../src/main/java/io/agentscope/dataagent/web/session/ConversationScopeRegistry.java), [SessionTurnParser](../../src/main/java/io/agentscope/dataagent/web/session/SessionTurnParser.java) | 5 / 0 / 0.176 | 保留现行回归 |
| J165 [SessionTurnParserTest](../../src/test/java/io/agentscope/dataagent/web/session/SessionTurnParserTest.java) | 持久化工具结果的JSON字符串封套解析 | [SessionTurnParser](../../src/main/java/io/agentscope/dataagent/web/session/SessionTurnParser.java) | 1 / 0 / 0.002 | 保留现行回归 |
| J166 [RequestEventIsolationTest](../../src/test/java/io/agentscope/dataagent/web/toolbus/RequestEventIsolationTest.java) | 同一会话不同轮次的工具事件隔离 | [ToolEventBus](../../src/main/java/io/agentscope/dataagent/web/toolbus/ToolEventBus.java) | 1 / 0 / 0.014 | 保留现行回归 |
| J167 [SharedSkillContentTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedSkillContentTest.java) | 打包技能正文和旧工具名/提示词精确约定 | [SharedWorkspaceSeeder](../../src/main/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeeder.java) | 12 / 0 / 0.038 | 保留核心，改写文本/结构断言 |
| J168 [SharedWorkspaceSeederTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeederTest.java) | 资源种子幂等、升级、修补与保留人工/市场文件 | [SharedWorkspaceSeeder](../../src/main/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeeder.java) | 8 / 0 / 1.301 | 保留现行回归 |
| J169 [UserControlFilesystemTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserControlFilesystemTest.java) | 用户文件命名空间路径与根目录边界 | [UserControlFilesystem](../../src/main/java/io/agentscope/dataagent/web/workspace/UserControlFilesystem.java) | 1 / 0 / 0.031 | 保留现行回归 |
| J170 [UserSandboxCapacityTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxCapacityTest.java) | 沙箱预算、闲置驱逐与运行中保护 | [UserSandboxRegistry](../../src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java) | 2 / 0 / 0.015 | 保留现行回归 |
| J171 [UserSandboxDockerReadinessTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxDockerReadinessTest.java) | 进程边界故障、错误区分/重试，含条件30秒超时 | [UserSandboxRegistry](../../src/main/java/io/agentscope/dataagent/web/workspace/UserSandboxRegistry.java), [LazySandbox](../../src/main/java/io/agentscope/dataagent/web/workspace/LazySandbox.java) | 15 / 1 / 0.921 | 保留，条件专项/混合 |

## 后端逐类断言与处理依据

下面列出实际测试名称（参数化展开合并为方法），并摘录两条断言；长断言可能截断，完整预期以链接源文件为准。没有把辅助方法当成测试用例。

### J001 AnswerQueryMemoryTest

- 源码：[AnswerQueryMemoryTest](../../src/test/java/io/agentscope/dataagent/dataset/AnswerQueryMemoryTest.java)。保护：点赞问答只收录当前操作者成功的业务SQL，排除诊断与分析查询。
- 建议：保留现行回归。
- 实际方法：`diagnosticAndNonChineseQuestionsAreExcluded`；`successfulBusinessChineseQueryIsSelectedWithoutDeclarationTool`；`inputsWithoutIdsStillRejectMismatchedReceipts`；`sharedQueryMemberCanSaveButRevokedMemberCannotSelectReceipt`；`receiptOnlyTranscriptIsAcceptedButDiagnosticInputIsNeverPromoted`；`officialAnalysisQueriesAreNeverSavedAsExamples`；`officialReceiptSelectsOnlySuccessfulMarkedQueriesOfCurrentOwner`；`missingResultIdFindsTheMatchingSqlAmongRepeatedQuestions`；`explicitLikeRetriesPreviouslyEmptyCandidatesAndThenRemainsIdempotent`。
- 断言摘录：`assertEquals(1, service.candidates("alice", "a", turns.apply("BUSINESS")).size());`。
- 断言摘录：`assertTrue(service.candidates("alice", "a", turns.apply("ANALYSIS")).isEmpty());`。

### J002 AssetExampleServiceTest

- 源码：[AssetExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetExampleServiceTest.java)。保护：资产样例只引用当前库模型和允许的查询表。
- 建议：保留现行回归。
- 实际方法：`sqlMayReadOnlyKnowledgeBaseTablesAndItsOwnCtes`；`examplesNamingTheKnowledgeBaseModelsAreKept`；`examplesAboutOtherTablesAreDropped`。
- 断言摘录：`assertThat(examples) .extracting(AssetExampleService.Example::kind) .containsExactly("查询模板", "已有 SQL");`。
- 断言摘录：`assertThat(examples.get(1).text()).startsWith(AssetExampleService.SQL_LEAD);`。

### J003 AssetProposalStoreTest

- 源码：[AssetProposalStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/AssetProposalStoreTest.java)。保护：资产提议先记录再由用户决定，不提前改文件。
- 建议：保留现行回归。
- 实际方法：`aProposalIsDecidedOnce`；`namesMustBeNewAndKindsLimitedToQueryTemplates`；`proposalIsRecordedWithoutWritingAnyAssetFile`。
- 断言摘录：`assertThat(created.status()).isEqualTo(AssetProposalStore.PENDING);`。
- 断言摘录：`assertThat(store.list("g")).singleElement().matches(p -&gt; p.name().equals("报表访问用户明细"));`。

### J004 BaselineMdlServiceTest

- 源码：[BaselineMdlServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BaselineMdlServiceTest.java)。保护：数据变化后的草稿准备、首次发布失败和跨租户隔离。
- 建议：保留现行回归。
- 实际方法：`emptyGroupDeletesArtifactsAndResetsLifecycle`；`foreignOwnerIsHiddenAsNotFound`；`rebuildExceptionKeepsPublishedSnapshotMessage`；`preparesDraftWithoutPublishingAndInvalidatesRunningInstance`；`firstPublicationFailureReportsInitializationFailure`。
- 断言摘录：`assertThat(service.publishAfterDatasetChange("alice", "g1")).isSameAs(published);`。
- 断言摘录：`assertThat(result.ok()).isTrue();`。

### J005 BiExtraSyncStatusTest

- 源码：[BiExtraSyncStatusTest](../../src/test/java/io/agentscope/dataagent/dataset/BiExtraSyncStatusTest.java)。保护：扩展同步只读状态、缺口、任务归属和错误脱敏。
- 建议：保留现行回归。
- 实际方法：`partialSuccessShowsPerTableCoverageAndGapsWithoutLeakingPrivateState`；`verifyIsSeparateAndRunningLockWinsWithInterruptedAndPausedStates`；`failuresAndMalformedRecordsNeverExposeDriverOrJsonSecrets`；`onlyThreeReadQueriesAndOwnerMustMatchExtraTask`。
- 断言摘录：`assertEquals("SUCCESS_WITH_GAPS", view.state());`。
- 断言摘录：`assertTrue(view.hasGaps());`。

### J006 BiSyncStatusServiceTest

- 源码：[BiSyncStatusServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BiSyncStatusServiceTest.java)。保护：基础同步日期水位、运行锁和错误脱敏。
- 建议：保留现行回归。
- 实际方法：`lockDistinguishesRunningInterruptedAndPausing`；`staleDataAndEarlyMorningGraceDoNotPretendSchedulerWasInstalled`；`sqlFailuresNeverReturnDriverConnectionOrCredentialText`；`fixedReadOnlyProjectionOmitsPrivateControlKeysAndHandlesUninitialized`；`datesAreInclusiveForHumansAndFailuresExposeOnlyKnownMessages`。
- 断言摘录：`assertEquals("2026-11-02", view.completedThrough());`。
- 断言摘录：`assertEquals("CURRENT", view.freshness());`。

### J007 BusinessDocumentContentTest

- 源码：[BusinessDocumentContentTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessDocumentContentTest.java)。保护：业务文档读取限制在有MANAGE权限的登记文件。
- 建议：保留现行回归。
- 实际方法：`readsOnlyListedDocumentsOfAManagedKnowledgeBase`。
- 断言摘录：`assertEquals("# 口径\n净销售额=实付-退款", service.content("u1", "g1", "业务口径.md").content());`。
- 断言摘录：`assertEquals( 404, assertThrows( DatasetException.class, () -&gt; service.content("u1", "g1", "../../secret.md")) .status());`。

### J008 BusinessTermServiceTest

- 源码：[BusinessTermServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/BusinessTermServiceTest.java)。保护：术语和同义词草稿、发布版本及排队撤权。
- 建议：保留现行回归。
- 实际方法：`appendCreatesTheFileAndSectionWhenMissing`；`queuedReadersRecheckRevokedPermissionsBeforeReadingFiles`；`termsAndSynonymsAreUniqueWithinTheKnowledgeBase`；`editsWaitWhileTheModelingAssistantRuns`；`strangersCannotReadAnotherKnowledgeBasesTerms`；`queuedManagersRecheckDowngradedRoleBeforeReadingDrafts`；`entriesFollowTheOfficialBulletFormatAndFreeTextIsKept`；`managersEditTheDraftAndSeeItAgainstThePublishedVersion`；`revokedAccessWhileWaitingForTheLockIsRefused`；`queryUsersSeeOnlyThePublishedVersionAndCannotEdit`。
- 断言摘录：`assertThat(terms).hasSize(3);`。
- 断言摘录：`assertThat(terms.get(0).term()).isEqualTo("GMV");`。

### J009 DatasetImportLimitsTest

- 源码：[DatasetImportLimitsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportLimitsTest.java)。保护：上传行列/单元格限额、背压和失败清表。
- 建议：保留现行回归。
- 实际方法：`unfinishedBatchWindowIsBounded`；`headerOnlyFileNeverCreatesTable`；`cellLimitIsCombinedWithRows`；`rowLimitRejectsWithoutTruncating`；`asyncFailureIsDrainedBeforeDrop`；`limitAfterFirstCommitDropsOnlyFreshTable`；`columnLimitIncludesSparseHeaders`；`exactRowBoundarySucceeds`；`cleanupFailureStaysVisible`。
- 断言摘录：`assertThatThrownBy(() -&gt; csv("id\n1\n2\n3\n")) .isInstanceOf(DatasetException.class) .hasMessageContaining("行数");`。
- 断言摘录：`assertThatThrownBy(() -&gt; csv("a,b,c\n1,2,3\n")) .isInstanceOf(DatasetException.class) .hasMessageContaining("列数");`。

### J010 DatasetImportRollbackTest

- 源码：[DatasetImportRollbackTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetImportRollbackTest.java)。保护：平台事务失败时清理新物理表，提交后保留。
- 建议：保留现行回归。
- 实际方法：`metadataFailureRemovesOnlyNewTable`；`transactionRollbackCleansPhysicalTable`；`cleanupFailureRetainsActionableMessage`；`transactionCommitPreservesPhysicalTable`。
- 断言摘录：`assertThatThrownBy(this::ingest).hasMessage("metadata failed");`。
- 断言摘录：`assertThatThrownBy(this::ingest).hasMessageContaining("临时表待清理：ds_fresh");`。

### J011 DatasetMetadataDraftSyncTest

- 源码：[DatasetMetadataDraftSyncTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSyncTest.java)。保护：元数据精确合并到草稿、事务回滚和排队撤权。
- 建议：保留现行回归。
- 实际方法：`absentModelOnlySavesMetadataAndCorruptModelDoesNotSave`；`saveFailureRestoresOriginalFileAndChangeLog`；`queuedWriteRechecksManagePermissionAfterLock`；`transactionRollbackRestoresFilesBeforeUnlockAndCommitKeepsDraft`；`emptyDescriptionAndOmittedDisplayNameDoNotRenameOrChangeFormula`；`invalidMappingOrModelShapeCannotSilentlySaveOnlyTheDataset`；`explicitEditsPreserveOtherDraftChangesAndPublishedBytes`。
- 断言摘录：`assertEquals("saved", sync.update("manager", dataset, "new table", edit, () -&gt; "saved"));`。
- 断言摘录：`assertEquals("新说明", model.at("/columns/0/properties/description").asText());`。

### J012 DatasetParsingTest

- 源码：[DatasetParsingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetParsingTest.java)。保护：CSV类型、引号、前导零、空数据与截断边界。
- 建议：保留现行回归。
- 实际方法：`parsesSimpleCsvWithHeader`；`infersColumnTypes`；`handlesQuotedCommaAndNewline`；`rejectsEmptyCsv`；`truncatesBeyondRowLimit`；`columnSchemaRecordKeepsOriginalName`；`sanitizesChineseHeadersToFallbacks`；`preservesLeadingZeroIdentifiersAndKeepsCompactDates`；`sanitizeAndDedupeIdentifiers`。
- 断言摘录：`assertEquals("BIGINT", TypeInferrer.infer(List.of("1", "2", "-3")));`。
- 断言摘录：`assertEquals("DECIMAL(18,6)", TypeInferrer.infer(List.of("1.5", "2", "3.25")));`。

### J013 DatasetSamplingTest

- 源码：[DatasetSamplingTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetSamplingTest.java)。保护：受限采样、空值、禁用和样本文件清理。
- 建议：保留现行回归。
- 实际方法：`handlesEmptyNullRepeatedAndLongValues`；`disabledDoesNotConnectAndFailureDoesNotRetry`；`writesBoundedOfficialDocumentsAndRemovesDeletedDatasets`；`boundsRowsAndQueriesOnceWithoutAggregates`。
- 断言摘录：`assertThat(sample.sampledRows()).isEqualTo(1000);`。
- 断言摘录：`assertThat(sample.columns().get(0).values()).hasSize(20);`。

### J014 DatasetServiceEvidenceTest

- 源码：`DatasetServiceEvidenceTest`（后续清理已移除，见阶段2报告）。保护：旧自定义文档证据检索的分段排序与引用。
- 建议：清理候选，未删除。仅测试旧evidenceFor链路；当前主问数使用官方知识工具。
- 实际方法：`shortDocIsInjectedWholeEvenWithoutTermOverlap`；`matchedPassageOutranksWholeDocFallback`；`unrelatedQueryYieldsNull`；`termlessQueryStillInjectsShortDoc`；`returnsTopPassageWithCitation`；`groupWithoutDocumentIsSkipped`；`nullOwnerBlankQueryAndBadLimitReturnNull`；`onlyGroupsFilterNarrowsTheSearch`。
- 断言摘录：`assertTrue(out != null && !out.isBlank(), "a matching passage must come back");`。
- 断言摘录：`assertTrue(out.contains("【知识库「测试MDL」 › 2026年9月咪咕视频考核口径】"));`。

### J015 DatasetServiceMdlDirtyTest

- 源码：[DatasetServiceMdlDirtyTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceMdlDirtyTest.java)。保护：说明变化保留旧发布查询，物理结构变化仍阻断。
- 建议：保留现行回归。
- 实际方法：`editingDescriptionsDoesNotPublishANeverPublishedKnowledgeBase`；`deleteEntityMarksGroupDirty`；`everyMutationRequiresANewPublicationEvenWhenAlreadyDirty`；`updateColumnDescriptionsLeavesPublishedQueryAvailable`；`fieldLabelsRemainSeparateFromPhysicalNamesAndLegacyEditsKeepTheLabel`；`markMdlDirtyFlipsPublishedGroupToDirty`；`sharedQueryUsesVersion14AfterDescriptionAndLabelEdits`；`markMdlDirtyNoOpsOnBlankOrOrphanIds`；`saveKnowledgeOnlyChangesDraft`；`samplingThatObservesRemovedOrRetypedColumnsKeepsTheGate`；`refreshingOnlySamplesKeepsPublishedQueriesAvailable`；`metadataEditsNeverClearAnEarlierPhysicalChangeGate`；`datasetPageSaveProducesActualDraftDiffWhilePublishedRevisionRemainsUsable`；`queuedMetadataSaveMergesLatestOtherFieldInsteadOfWritingItsStaleCopy`。
- 断言摘录：`assertEquals("我的新说明", service.readColumns(entity).get(0).description());`。
- 断言摘录：`assertEquals("同事刚保存的说明", service.readColumns(entity).get(1).description());`。

### J016 DatasetServiceSourceCombinationTest

- 源码：[DatasetServiceSourceCombinationTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceSourceCombinationTest.java)。保护：单Wren来源约束、跨租户、原子关联和刷新撤权。
- 建议：保留现行回归。
- 实际方法：`rejectsUploadedAndExternalTablesCombinationBeforeWriting`；`refreshFailurePreservesSuccessfulSample`；`retiredBuiltinIdentityCannotBeAssociatedAsTheNewDemo`；`rejectsNonMysqlSourceBeforeWriting`；`rejectsDifferentExternalSourcesBeforeWriting`；`metadataFailureOnLaterTableLeavesNoPartialAssociation`；`associatesPreparedMysqlTablesAsOneBatch`；`anotherUsersSourceCannotBeAssociated`；`refreshChecksManagementAgainAfterWaitingForLock`；`rejectsDuplicateRequestBeforeIntrospectionOrWriting`；`builtinSourceIsAssociatedOnlyFromTheDemoSchema`。
- 断言摘录：`assertThat( service.associateTables( "alice", "group-1", DemoDataService.SOURCE_ID, DemoDataService.SCHEMA, List.of("demo_orders"), false)) .hasSize(1);`。
- 断言摘录：`assertThatThrownBy( () -&gt; service.associateTables( "alice", "group-1", DemoDataService.SOURCE_ID, "mysql", List.of("user"), false)) .isInstanceOf(DatasetException.class);`。

### J017 DatasetServiceTest

- 源码：[DatasetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetServiceTest.java)。保护：视图语句显示与上传日期区间文字生成。
- 建议：保留现行回归。
- 实际方法：`flattenStatementSkipsBlankStatements`；`flattenStatementCapsLengthWithEllipsis`；`flattenStatementFoldsWhitespaceOntoOneLine`；`uploadWritesEachDateColumnsSpanIntoItsDescription`。
- 断言摘录：`assertThat(DatasetService.flattenStatement(sql)) .isEqualTo( "SELECT user_segment, COUNT(*) AS cnt FROM 点击详情数据 GROUP BY user_segment");`。
- 断言摘录：`assertThat(out).hasSize(401).endsWith("…").startsWith("SELECT aaa");`。

### J018 DatasetUploadAdmissionTest

- 源码：[DatasetUploadAdmissionTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadAdmissionTest.java)。保护：上传全局限额、同名互斥、取消与用户隔离。
- 建议：保留现行回归。
- 实际方法：`statusIsScopedAndIdentifiersCannotBeReused`；`cancellationBeforeWorkerStartsCannotBeResumed`；`globalCapacityAndSameNameAreSharedAcrossUsers`；`invalidConfigurationIsRejected`；`cancellationDoesNotReleaseRunningWorker`。
- 断言摘录：`assertThatThrownBy(() -&gt; gate.acquire("bob", "g", "sales", "two")) .isInstanceOf(DatasetException.class) .hasMessageContaining("重复");`。
- 断言摘录：`assertThatThrownBy(() -&gt; gate.acquire("c", "c", "c", "three")) .isInstanceOf(DatasetException.class) .hasMessageContaining("名额");`。

### J019 DatasetUploadIntakeTest

- 源码：[DatasetUploadIntakeTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadIntakeTest.java)。保护：上传磁盘暂存、实际大小、取消和清理。
- 建议：保留现行回归。
- 实际方法：`cancelledWorkerKeepsFileAndSlotUntilItActuallyEnds`；`actualBytesAreLimitedAndSlotIsReleased`；`batchTotalFailsBeforeImportAndReleasesReadSlot`；`cancelledStagingCannotCreateAnUntrackedDescription`；`descriptionAndFileShareRequestBudget`；`stagingUsesDiskAndDeletesAfterSuccess`。
- 断言摘录：`assertThat(Files.exists(description)).isFalse();`。
- 断言摘录：`assertThat(Files.exists(staged.file())).isFalse();`。

### J020 DatasetUploadPolicyDefaultsTest

- 源码：[DatasetUploadPolicyDefaultsTest](../../src/test/java/io/agentscope/dataagent/dataset/DatasetUploadPolicyDefaultsTest.java)。保护：发布YAML与上传策略类默认限额一致且可配置。
- 建议：保留现行回归。
- 实际方法：`deploymentCanStillOverrideBothLimits`；`omittingTheUploadSectionDoesNotChangeTheShippedLimits`。
- 断言摘录：`assertThat(defaults.getMaxRows()) .isEqualTo(Long.parseLong(shipped.getProperty("dataagent.upload.max-rows")));`。
- 断言摘录：`assertThat(defaults.getMaxCells()) .isEqualTo(Long.parseLong(shipped.getProperty("dataagent.upload.max-cells")));`。

### J021 DataSourceEngineTest

- 源码：[DataSourceEngineTest](../../src/test/java/io/agentscope/dataagent/dataset/DataSourceEngineTest.java)。保护：MySQL版本和Doris支持范围，拒绝非MySQL。
- 建议：保留现行回归。
- 实际方法：`nonMysqlKindsAreRefusedWithoutConnecting`；`mysqlEightAndDorisAreSupportedButOlderMysqlIsNot`。
- 断言摘录：`assertThat(DataSourceIntrospector.engineError("8.0.35", "MySQL Community Server - GPL")) .isNull();`。
- 断言摘录：`assertThat(DataSourceIntrospector.engineError("9.1.0", "MySQL Community Server - GPL")) .isNull();`。

### J022 DemoDataServiceTest

- 源码：[DemoDataServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoDataServiceTest.java)。保护：演示样例白名单、旧来源关闭和读账号配置。
- 建议：保留现行回归。
- 实际方法：`onlyKnownBundledFilesAreOffered`；`withoutAReaderPasswordTheSourceStaysUnavailable`；`legacyJdbcConfigurationDoesNotExposeOrChangeOldDemoSource`；`nothingIsOfferedWhenDisabled`；`legacyFilesAreNotOfferedAndOldSchemaIsNotAccepted`。
- 断言摘录：`assertThat(service.samples()).containsExactly("示例订单.xlsx", "示例客户.xlsx");`。
- 断言摘录：`assertThat(service.sample("示例客户.xlsx")).isNotEmpty();`。

### J023 DemoSampleFilesTest

- 源码：[DemoSampleFilesTest](../../src/test/java/io/agentscope/dataagent/dataset/DemoSampleFilesTest.java)。保护：真实样例文件经控制器/解析器保留主键和金额。
- 建议：保留现行回归。
- 实际方法：`bundledSamplesWorkWithoutADatabaseAndKeepKeysAndMoney`。
- 断言摘录：`assertThat(service.sourceAvailable()).isFalse();`。
- 断言摘录：`assertThat(service.samples()).containsExactly("示例订单.xlsx", "示例客户.xlsx");`。

### J024 DocEnhanceServiceTest

- 源码：[DocEnhanceServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DocEnhanceServiceTest.java)。保护：文档建议去重、人工采纳状态和租户边界。
- 建议：保留现行回归。
- 实际方法：`modelFailureMarksTaskFailedWithoutProposals`；`mdlAdoptionMarksDirtyValidatesAndPersistsValidationError`；`duplicateItemsAndRepeatedAnalysisDoNotCreateDuplicateProposals`；`businessRuleAdoptionPersistsTenantAndGroup`；`analysisKeepsActionableDifferencesAndDropsCovered`；`documentAnalysisDropsCubeProposalsAndOldCubeAdoptionCannotWrite`；`conflictCannotBeAdoptedAndForeignTenantCannotProbe`。
- 断言摘录：`assertThat(task.getStatus()).isEqualTo(DocEnhanceTaskEntity.STATUS_READY);`。
- 断言摘录：`assertThat(savedProposals).hasSize(2);`。

### J025 DraftItemServiceTest

- 源码：[DraftItemServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftItemServiceTest.java)。保护：逐项草稿编辑、关系/计算列、日志与锁后权限。
- 建议：保留现行回归。
- 实际方法：`draftIsShownAsBusinessItemsNotFiles`；`columnPropertiesAreWrittenAndMarkedAsUserDescribed`；`onlyCalculatedColumnsAreCreatedOrDeletedAndTheGateCanRefuse`；`sinceLimitsItemsToFilesWrittenInThatTurnAndCarriesTheBasis`；`itemsAreEditedAndDeletedOneByOne`；`staleOrForeignEditsAreRefused`；`relationshipsAreAddedBetweenExistingColumns`；`pageEditsNeedManageAndAreRecheckedInsideTheLock`；`aBulletMovesToAnotherSectionOfTheSameFile`。
- 断言摘录：`assertEquals("MODIFIED", orders.kind());`。
- 断言摘录：`assertEquals( List.of("__table__", "amount"), orders.fields().stream().map(DraftItemService.Field::name).sorted().toList());`。

### J026 DraftReviewServiceTest

- 源码：[DraftReviewServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/DraftReviewServiceTest.java)。保护：草稿差异、单项撤销、全部丢弃和并发权限。
- 建议：保留现行回归。
- 实际方法：`documentsAreDraftChangesWithHistoryAndPublishStatus`；`discardMakesTheDraftEqualToTheSnapshot`；`foreignUserSeesNothingAndChangesNothing`；`revertRestoresThePublishedFileOrRemovesADraftOnlyFile`；`changesComeFromTheDiffAndCarryJournalSourcesAndAuthors`；`runningAssistantBlocksRevertAndDiscard`；`sampleMaterialAndConfirmedPairsAreListedButNotRevertible`；`userEditsAreValidatedJournaledAndBlockedWhileTheAssistantRuns`。
- 断言摘录：`assertEquals(3, review.changes().size());`。
- 断言摘录：`assertEquals("RULE", rules.category());`。

### J027 ExcelParserTest

- 源码：[ExcelParserTest](../../src/test/java/io/agentscope/dataagent/dataset/ExcelParserTest.java)。保护：实际Excel解析、原始表头和文件类型支持。
- 建议：保留现行回归。
- 实际方法：`columnSchemaFromExcelKeepsHeader`；`parsesFirstSheetWithTypes`；`supportsOnlyExcelExtensions`。
- 断言摘录：`assertEquals(2, t.columns().size());`。
- 断言摘录：`assertEquals("customer", t.columns().get(0).name());`。

### J028 ExternalDataSourcePolicyTest

- 源码：[ExternalDataSourcePolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/ExternalDataSourcePolicyTest.java)。保护：连接参数白名单、规范化和禁止注入。
- 建议：保留现行回归。
- 实际方法：`emptyAllowlistAllowsTargetAndLegacySafeOptionsRemainSupported`；`rejectsUntrustedConnectionInstructions`；`structuredMysqlIsCanonicalAndRevalidatedWithoutChanges`。
- 断言摘录：`assertThat(url) .contains( "connectTimeout=10000", "socketTimeout=30000", "allowLoadLocalInfile=false");`。
- 断言摘录：`assertThat(policy.normalize("mysql", url)).isEqualTo(url);`。

### J029 FactQueryWindowPolicyTest

- 源码：[FactQueryWindowPolicyTest](../../src/test/java/io/agentscope/dataagent/dataset/FactQueryWindowPolicyTest.java)。保护：逐库日期门禁、缓存、撤权及已发布资产继承。
- 建议：保留现行回归。
- 实际方法：`resolvesDatabaseLogicalNameAndSkipsUnlimitedAndForeignDatasets`；`noConfiguredLimitDoesNotCallModel`；`refusalStopsSameTurnButNotNextTurnOrOtherKnowledgeBase`；`assetWithoutTimeColumnIsRefusedWithoutAskingTheModel`；`approvalIsSharedAcrossRequestsButRefusalsAreNot`；`publishedAssetsJoinTheRulesAndReachTheGate`；`sameSqlApprovalIsReusedOnlyUntilConfigurationChanges`；`existingWindowDoesNotApplyAfterTableIsConfirmedAsDimension`。
- 断言摘录：`assertThat(policy.rules("g")) .containsExactly(new SqlWindowGate.Rule("逻辑a", List.of("时间"), 30));`。
- 断言摘录：`assertThatThrownBy(() -&gt; policy.requireAllowed(ctx, "g", "SELECT COUNT(*) FROM 逻辑a")) .isInstanceOf(DatasetException.class) .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");`。

### J030 FactTableWindowServiceTest

- 源码：[FactTableWindowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FactTableWindowServiceTest.java)。保护：事实表角色/日期配置、跨库拒绝和排队撤权。
- 建议：保留现行回归。
- 实际方法：`revocationWhileWaitingForDatasetLockPreventsSharedRoleAndWindowWrites`；`factAndUnknownAreReportedSeparatelyWithoutReadingMdl`；`dimensionWithDateFieldsIsNotAWindowCandidate`；`roleConfirmationCannotWriteAnotherKnowledgeBase`；`rejectsCrossKnowledgeBaseConfiguration`；`savesChosenColumnWithoutAnyModelingOrPublicationDependency`；`validatesSelectionAndMissingDays`；`confirmingDimensionRemovesPreviouslyRegisteredFactWindow`。
- 断言摘录：`assertThat(saved.timeColumn()).isEqualTo("支付日期");`。
- 断言摘录：`assertThat(saved.tableName()).isEqualTo("订单");`。

### J031 FieldScreeningServiceTest

- 源码：[FieldScreeningServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/FieldScreeningServiceTest.java)。保护：字段纳入选择、时间列保护和已建模引用检查。
- 建议：保留现行回归。
- 实际方法：`anotherUserCannotSeeOrChangeTheFields`；`confirmedChoicesPruneTheDraftKeepUserDescriptionsAndAreNeverAddedBack`；`theTimeColumnCannotBeExcludedAndATableKeepsOneColumn`；`autoModelingWaitsForEveryTableToBeConfirmed`；`adjustingAfterModelingListsReferencesBeforeRemovingAndAsksToRemodel`；`modelRolesAreParsedDefensively`；`withoutAModelEveryColumnIsUnknownAndIncludedAndTheTimeColumnIsLocked`。
- 断言摘录：`assertThat(table.fields()).allMatch(f -&gt; f.role().equals("UNKNOWN") && f.included());`。
- 断言摘录：`assertThat(field(table, "order_time").locked()).isTrue();`。

### J032 KnowledgeBaseAccessServiceTest

- 源码：[KnowledgeBaseAccessServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseAccessServiceTest.java)。保护：QUERY/MANAGE、共享、撤权、管理员降权和删除状态。
- 建议：保留现行回归。
- 实际方法：`pendingDeletionBlocksDataButAllowsAuthorizedRetry`；`emptySelectionCannotExpandToAllAndPaginationCarriesAcl`；`revokeAndAdminDemotionAreEffectiveWithExistingIdentity`；`managerCanQueryAndManageWithoutChangingActor`；`queryMemberCanQueryButCannotManage`；`unknownAndUnassignedGroupAreIndistinguishable`；`creatorAndCurrentGlobalAdminManageWithoutExplicitGrant`；`removedUserCannotUseRetainedGrant`。
- 断言摘录：`assertThat(access.requireManage("owner", "g1")).isSameAs(group);`。
- 断言摘录：`assertThat(access.requireManage("admin", "g1")).isSameAs(group);`。

### J033 KnowledgeBaseBrowseTest

- 源码：[KnowledgeBaseBrowseTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeBaseBrowseTest.java)。保护：已发布只读投影、凭据隐藏和读取后撤权。
- 建议：保留现行回归。
- 实际方法：`readablePreviewIsBoundedAndRechecksAccessAfterReading`；`readsPublishedOnlyAndProjectsOutCredentials`；`projectsRolesKeysAndDescriptionsWithoutOtherProperties`；`summariesDropAKnowledgeBaseRevokedWhileReading`；`summariesCoverOnlyVisibleKnowledgeBases`。
- 断言摘录：`assertThat(serialized) .contains("净收入扣除退款", "销售") .doesNotContain("credential", "password", "未发布草稿");`。
- 断言摘录：`assertThat(result.models().get(0)) .containsEntry("tableRole", "FACT") .containsEntry("description", "访问日志");`。

### J034 KnowledgeEvidenceTest

- 源码：`KnowledgeEvidenceTest`（后续清理已移除，见阶段2报告）。保护：旧平台文档分段、词项和分数计算。
- 建议：清理候选，未删除。仅被DatasetService.evidenceFor引用；该方法在生产源码未找到调用。
- 实际方法：`headinglessDocSplitsOnBlankLines`；`oversizedSectionIsHardSplit`；`shortDocStaysWholeWithLeadingTitle`；`markdownDocSplitsAtHeadings`；`blankInputsAreRejected`；`termsMixAsciiTokensAndCjkBigrams`；`shortDocBoundaryFollowsNormalisedLength`；`singleCjkCharStaysUnigram`；`scoreWeightsBodyAndTitleHits`。
- 断言摘录：`assertThat(chunks).hasSize(1);`。
- 断言摘录：`assertThat(chunks.get(0).title()).isEqualTo("2026年9月考核口径");`。

### J035 KnowledgeGraphServiceTest

- 源码：[KnowledgeGraphServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/KnowledgeGraphServiceTest.java)。保护：已停用知识图谱的节点去重、关系持久化与构建守卫。
- 建议：清理候选，未删除。被测服务和控制器受knowledge-graph.enabled=true限制；当前规则停用KG，保留历史数据另行处理。
- 实际方法：`triggerBuildRejectsWhenModelMissing`；`triggerBuildRejectsWhenInJvmLockHeld`；`persistExtractionDedupesEntitiesAndResolvesRelations`；`persistExtractionSkipsRelationsWithUnknownEndpoints`；`executeBuildMarksUnitFailedWithoutAbortingOthers`；`triggerBuildRejectsEmptySelection`。
- 断言摘录：`assertEquals(2, entityTable.size(), "duplicate entity text must not create a second node");`。
- 断言摘录：`assertEquals("包含字段", rel.getValue().getLabel());`。

### J036 MdlCatalogTest

- 源码：[MdlCatalogTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlCatalogTest.java)。保护：已发布模型目录、时间列和样例元数据读取。
- 建议：保留现行回归。
- 实际方法：`loadReturnsEmptyForBlankOrPathUnsafeGroupId`；`loadParsesEveryManifestSection`；`examplesReadOfficialAndLegacyPublishedFilesForTheRequestedGroup`；`loadReturnsEmptyForMissingManifest`；`timeColumnsComeFromTypesOrRegisteredTemporalColumns`；`loadToleratesMinimalManifestWithoutRelationsOrCubes`；`coveredDatasetIdsDrivesThePerDatasetPromptSplit`；`loadReturnsEmptyForMalformedJson`。
- 断言摘录：`assertThat(catalog.confirmedExamples("gA")).containsExactly(confirmed, official);`。
- 断言摘录：`assertThat(catalog.confirmedExamples("gB")).isEmpty();`。

### J037 MdlPublishServiceTest

- 源码：[MdlPublishServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishServiceTest.java)。保护：草稿校验构建、原子发布、失败保留旧版和来源隔离。
- 建议：保留现行回归。
- 实际方法：`publishBlocksOnUnknownRefSqlReference`；`viewSeparatesDerivedModelsFromPhysical`；`preparationDoesNotPublishAndPublicationCoversOnlyItsStartingRevision`；`uploadedViewChecksUseStoreAndFailureKeepsPublishedVersion`；`confirmedQuestionFailingDryPlanIsLeftOutWithoutBlockingPublication`；`assetTimeColumnsBesideTheSnapshotAreNotADraftChange`；`onlySuccessfulPublicationEmitsQueryPreparationEvent`；`refusesMixedSources`；`viewParsesWorkspaceFiles`；`derivedModelDryRunFailureAbortsPublish`；`publishDryRunsDerivedModelsAndMarksManifest`；`previewDiffCardTracksPublication`；`publishBlocksOnChainedDerivedModelReference`；`deleteArtifactsRemovesGroupRoot`；`externalViewValidationAndPublishUseSelectedConnection`；`cubeDryRunFailureFailsFirstPublish`；`coalescesRapidBaselineRequests`；`publishPreservesOfficialDerivedModelRefSqlInSnapshot`；`publishesWorkspaceEndToEnd`；`concurrentKnowledgeBasesDoNotShareCheckConnections`；`failedPublishKeepsPreviousSnapshot`；`publishBlocksOnViewReferenceInRefSql`；`questionVerificationDoesNotPublishAndUnconfirmedQuestionDoesNotBlockPublication`；`emptyKnowledgeBaseFailsFast`。
- 断言摘录：`assertTrue(service.prepareModeling("g1").ok());`。
- 断言摘录：`assertTrue(publicationEvents.isEmpty());`。

### J038 MdlPublishWiringTest

- 源码：[MdlPublishWiringTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlPublishWiringTest.java)。保护：Spring选择完整发布构造器并注入权限、删除和事件依赖。
- 建议：保留，细化或拆分断言。完整构造器选错可能绕过权限/事件；可改成行为断言，不宜只因反射实现细节而删。
- 实际方法：`springCreatesPublisherWithPermissionDeletionAndEventDependenciesTogether`。
- 断言摘录：`assertThat(ReflectionTestUtils.getField(service, "operations")) .isSameAs(context.getBean(KnowledgeBaseOperations.class));`。
- 断言摘录：`assertThat(ReflectionTestUtils.getField(service, "access")) .isSameAs(context.getBean(KnowledgeBaseAccessService.class));`。

### J039 MdlQuestionServiceTest

- 源码：[MdlQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionServiceTest.java)。保护：用户确认问题的权限、修订、工作区锁和日期门禁。
- 建议：保留现行回归。
- 实际方法：`suggestionsAreScopedAndSkipExistingQuestions`；`sqlThatDoesNotPlanIsRefusedOnSaveAndConfirm`；`foreignOwnerIsCheckedBeforeReadingAnyFiles`；`planErrorsSkipCliNotesBeforeTheOfficialError`；`questionChangesBumpTheModelingRevisionInsideTheWorkspaceLock`；`aPublishCannotStartWhileAQuestionIsBeingDecided`；`confirmationPassesTheTimeWindowGateBeforeWritingButReturnDoesNot`；`narrowedScopeCannotReadValidateOrDecideAnotherGroup`；`questionValidationPassesTheSameTimeWindowGateBeforeExecution`；`missingTenantCannotUseQuestionTools`。
- 断言摘录：`assertThat(locked).containsExactly(true, true, true);`。
- 断言摘录：`assertThat(checking).isNotEmpty().doesNotContain(false);`。

### J040 MdlQuestionStoreTest

- 源码：[MdlQuestionStoreTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlQuestionStoreTest.java)。保护：问答确认、撤销、证据快照、过期复核与发布样例。
- 建议：保留现行回归。
- 实际方法：`legacyClassificationIsIgnoredButOldConfirmationHashesRemainValid`；`confirmedQuestionNeedsReviewWhenWhatItReadsChanges`；`assetVerifiedQuestionsReplaceTheSameQuestionAndKeepTheirOrigin`；`publishedFilteringDropsStaleOptionalAndUnverifiedExamples`；`topQuestionMayUseRankingOrOfficialLimit`；`revokedConfirmationReturnsToUnconfirmedWithReason`；`modelCoverageAndPlanChangesExpireReceipts`；`humanCorrectionInvalidatesOnlyItsExampleAndKeepsSemanticAssets`；`canonicalPathsCannotBypassProtectedDirectories`；`evidenceSurvivesReloadAndDecisionAndNeverChangesWithTheWorkspace`；`humanArchiveRemovesDraftExampleButKeepsHistoryAndPublishedVersion`；`confirmationNeedsNoExecutionAndBindsToTheQuestionText`；`changeToAModelTheSqlReadsNeedsReviewAndLeavesThePublishDryPlan`；`legacyExamplesRemainValidButNewWritesNeedAnExplicitPlan`；`analysisIsReadLenientlyAndKeptOutOfTheConfirmation`；`rejectionRemovesExampleAndCanBeConfirmedAgain`；`receiptKeepsRowsAcrossLegacyStorageAndSpringHttpSerialization`；`platformPathsAreProtectedButOfficialExamplesAreWritable`；`successfulExecutionStillRequiresHumanConfirmationAndWritesOfficialPair`；`confirmedPairIsShippedByThePublishCopyAndArchivedPairIsNot`；`publishedStateUsesSnapshotAndIsIsolatedByGroup`；`questionsConfirmedBeforeSpec108UseThePublishedVersionAsBaseline`；`legacyReceiptWithoutEvidenceStillLoadsAndCanBeRevalidated`；`archivedQuestionsLeaveReviewAndPublishGateButKeepSourceAndReceipt`；`questionPlansRejectRetiredCubeStrategiesAndAssetReferences`；`correctionsRejectUnsafeSqlAndConcurrentChangesWithoutOverwriting`；`failedOrTruncatedTrialRunsDoNotBlockConfirmation`；`onlyConfirmedQuestionsShipWhileStandaloneExamplesStay`；`confirmationRejectsUnsafeSqlButOtherQuestionsStayListed`；`sqlWriteAndMultiStatementAreRejected`；`assetPlansMustExistAndBeReferencedBeforeConfirmation`。
- 断言摘录：`assertThrows(DatasetException.class, () -&gt; store.requireQuestion("g1", "sales"));`。
- 断言摘录：`assertEquals("CONFIRMED", review.status());`。

### J041 MdlSeederTest

- 源码：[MdlSeederTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSeederTest.java)。保护：基础模型增量播种、保留用户编辑和物理冲突报告。
- 建议：保留现行回归。
- 实际方法：`reportsTypeConflictWithoutRewriting`；`seedsNewDatasetIntoWorkspace`；`convergesTamperedProjectYmlWithoutReinitializing`；`reconcileIsIdempotent`；`excludedColumnsAreNeverSeededAndNewColumnsAppendToTheSeedLayout`；`resolveWritableGuardsPlatformFilesAndTraversal`；`appendsMissingColumnsPreservingAgentEdits`；`removesModelFileForDeletedDatasetAndReportsDanglingRefs`；`reportsRemovedPhysicalColumn`。
- 断言摘录：`assertTrue(result.issues().isEmpty(), String.valueOf(result.issues()));`。
- 断言摘录：`assertTrue(result.changed());`。

### J042 MdlSuggestionServiceTest

- 源码：[MdlSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlSuggestionServiceTest.java)。保护：手工关系及旧关系/指标/视图建议与工作区同步。
- 建议：保留，细化或拆分断言。手工关系仍有现行调用；Cube/View等旧方法需逐方法核对，不能整类删除。
- 实际方法：`suggestCubesFiltersItemsWithUnknownColumns`；`createCubeMirrorsWorkspaceMetadata`；`createCubeWithoutSeededModelIsRejectedAndWritesNothing`；`confirmWithSwapFlipsDirectionAndJoinType`；`confirmAndRejectAreScopedToOwningGroup`；`manualRelationRejectsForeignDatasetAndUnknownColumn`；`relationPromptCarriesTableFactsSampleRowsAndTheFactDimRule`；`llmProposalWithNonPkDimensionKeyIsKept`；`createViewMirrorsWorkspaceFiles`；`refreshMergesLlmCandidatesSkippingKnownPairs`；`malformedRelationshipsAreNotSilentlyReplaced`；`viewCreateValidatesSqlShapeAndUniqueness`；`seededEmptyArrayKeepsBothConfirmedRelationships`；`addManualRelationAppendsAndThenGrowsCompositeKey`；`swappedReconfirmReplacesTheSameEntryInsteadOfDuplicating`；`probeFillsManyToOneWhenSourceSideIsNotUnique`；`confirmWritesOfficialRelationshipEntry`；`probeLeavesJoinTypeNullWhenAmbiguousOrEmpty`；`reconfirmIsIdempotentOnTheFile`；`llmReversedFkToPkProposalIsReorientedFactToDim`；`renameCubeMovesTheWorkspaceDirectory`；`llmParsingToleratesMalformedItems`；`deleteCubeRemovesTheWorkspaceDirectory`；`viewDeleteRequiresExistence`；`manualRelationUpgradesExistingEdgeOnSameColumnPair`；`cubeCreateValidatesNameUniquenessAndDatasetOwnership`；`rejectNeverTouchesTheFile`；`createCubeRejectsWhenTheWorkspaceDirectoryAlreadyExists`；`llmFailureDoesNotBreakRefresh`。
- 断言摘录：`assertEquals(2, relationsById.size(), "duplicate pair must not be stored twice");`。
- 断言摘录：`assertEquals("PENDING", llm.getStatus());`。

### J043 MdlWorkspaceReaderTest

- 源码：[MdlWorkspaceReaderTest](../../src/test/java/io/agentscope/dataagent/dataset/MdlWorkspaceReaderTest.java)。保护：官方YAML、规则、关系、视图与ref_sql读取。
- 建议：保留现行回归。
- 实际方法：`readsModelsRelationsCubesViews`；`refSqlFileWinsOverInlineKey`；`physicalModelKeepsNullRefSql`；`malformedModelFileBecomesIssue`；`readsAViewStatementWrittenInlineInMetadata`；`readsFileLayoutRefSqlModel`；`readKnowledgeRulesYieldsEmptyWithoutDirectory`；`modelDirMissingMetadataBecomesIssue`；`untrackedModelKeepsNullDatasetId`；`readsInlineRefSqlKeyAsFallback`；`readKnowledgeRulesConcatenatesRuleFilesSorted`；`readsPageMaintainedColumnProperties`；`missingWorkspaceYieldsEmpty`。
- 断言摘录：`assertEquals("SELECT 1", view.sql());`。
- 断言摘录：`assertTrue(s.issues().isEmpty(), String.valueOf(s.issues()));`。

### J044 ModelingConfirmationServiceTest

- 源码：[ModelingConfirmationServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingConfirmationServiceTest.java)。保护：业务澄清卡、问题语义、决定和操作者隔离。
- 建议：保留现行回归。
- 实际方法：`anotherUserCannotSeeOrAnswerTheQuestions`；`askingAboutAQuestionWithdrawsItsGuessedSql`；`linkedQuestionsTravelWithTheCardAndItsDecision`；`resolvingReturnsDecisionsAndRemovesQuestions`；`optionsAreValidatedAndDuplicatesRefused`；`questionsArePrivateToThePlatformAndNeverPublished`。
- 断言摘录：`assertTrue(Files.isRegularFile(stored));`。
- 断言摘录：`assertEquals("只在推广场景排除", created.options().get(0).label());`。

### J045 ModelingHubServiceTest

- 源码：[ModelingHubServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingHubServiceTest.java)。保护：建模概览只读发布信息及读取过程降权。
- 建议：保留现行回归。
- 实际方法：`queryAccessGetsPublishedCountsOnlyAndPrivateGroupsAreOmitted`；`revokingManageDuringReadDropsDraftInformation`；`rejectsBatchesLargerThanOnePage`。
- 断言摘录：`assertThat(hub.summaries("reader", List.of("g", "private", "g"))) .containsExactly(new ModelingHubService.Summary("g", counts, null, null));`。
- 断言摘录：`assertThat(hub.summaries("member", List.of("g"))) .containsExactly(new ModelingHubService.Summary("g", null, null, null));`。

### J046 ModelingRevisionTest

- 源码：[ModelingRevisionTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingRevisionTest.java)。保护：真实JPA乐观版本与发布修订不能覆盖较新变更。
- 建议：保留，优先提速。
- 实际方法：`publicationGateIsRecheckedAndDoesNotExpandAuthorization`；`oldPublicationCannotCoverNewMutationAndStaleMergeCannotResetRevision`。
- 断言摘录：`assertThat(stale.getModelingRevision()).isEqualTo(1);`。
- 断言摘录：`assertThat(pending.getModelingRevision()).isEqualTo(2);`。

### J047 ModelingWorkflowServiceTest

- 源码：[ModelingWorkflowServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/ModelingWorkflowServiceTest.java)。保护：建模步骤、工程校验和可选问题不误阻断已发布查询。
- 建议：保留现行回归。
- 实际方法：`noDataAndFailedInitializationNeverClaimQueryAvailability`；`foreignOwnerIsRejectedBeforeWorkspaceReads`；`missingOrNarrowedTenantCannotAccessFiles`；`failedEngineeringIsDistinctFromPendingBusinessConfirmation`；`modelEditExpiresEngineeringButKeepsQuestionConfirmationsAndPublishedQueries`；`basePublishedModelIsQueryableWithoutRequiringQuestions`；`optionalQuestionsDoNotBlockPublicationButEngineeringStillDoes`。
- 断言摘录：`assertTrue(state.queryAvailable());`。
- 断言摘录：`assertEquals("COMPLETE", state.stage());`。

### J048 SchemaTableRoleTest

- 源码：[SchemaTableRoleTest](../../src/test/java/io/agentscope/dataagent/dataset/parser/SchemaTableRoleTest.java)。保护：复用Schema模型返回表角色，不按日期列猜测事实表。
- 建议：保留现行回归。
- 实际方法：`roleIsReturnedByTheExistingSchemaModelCall`；`fallbackDoesNotGuessFactFromDateColumn`。
- 断言摘录：`assertThat(result.tableRole()).isEqualTo(role.equals("not-a-role") ? "UNKNOWN" : role);`。
- 断言摘录：`assertThat(result.columns().get(0).displayName()).isEqualTo("记录编号");`。

### J049 ProcessWrenCliTest

- 源码：[ProcessWrenCliTest](../../src/test/java/io/agentscope/dataagent/dataset/ProcessWrenCliTest.java)。保护：子进程启动失败、超时、退出码和不泄露参数的耗时日志。
- 建议：保留现行回归。
- 实际方法：`missingExecutableReturnsGuidedResult`；`timeoutIsMeasuredAndKeepsExistingResultContract`；`successTimingsExcludeArgumentsAndOutput`；`nonzeroExitIsMarkedFailedWithoutLoggingArguments`。
- 断言摘录：`assertThat(result.exitCode()).isEqualTo(-1);`。
- 断言摘录：`assertThat(result.output()) .contains("wren 可执行文件「definitely-not-a-real-wren-xyz」无法启动") .contains("pip install 'wrenai[mysql,mcp]' 'mcp&lt;2'") .contains("DATAAGENT_WREN_EXECUTABLE");`。

### J050 PublishedAssetWindowsTest

- 源码：[PublishedAssetWindowsTest](../../src/test/java/io/agentscope/dataagent/dataset/PublishedAssetWindowsTest.java)。保护：View/ref_sql依赖继承最严格时间窗口。
- 建议：保留现行回归。
- 实际方法：`refSqlMatchesThePhysicalTableEvenWhenFullyQualified`；`assetsOutsideLimitedTablesAreLeftAlone`；`viewOverRefSqlModelInheritsThroughTheModel`；`assetWithoutConfirmedTimeColumnIsReportedNotGuessed`；`viewOverLimitedModelInheritsTheTightestWindowOfWhatItReads`。
- 断言摘录：`assertThat(derived.rules()) .containsExactly(new SqlWindowGate.Rule("sales_valid", List.of("day"), 7));`。
- 断言摘录：`assertThat(derived.unconfigured()).isEmpty();`。

### J051 QuestionDependenciesTest

- 源码：[QuestionDependenciesTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionDependenciesTest.java)。保护：已确认问题读取的模型/关系/资产依赖及变化提示。
- 建议：保留现行回归。
- 实际方法：`changesAreReadableAndUnchangedSnapshotsGiveNone`；`physicalTableNamesViewsAndRelationshipsCountAsDependencies`。
- 断言摘录：`assertThat(physical).containsKeys("node:访问明细", "asset:访问宽表", "relation:访问_用户");`。
- 断言摘录：`assertThat(physical.keySet()).noneMatch(k -&gt; k.contains("订单"));`。

### J052 QuestionSuggestionServiceTest

- 源码：[QuestionSuggestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/QuestionSuggestionServiceTest.java)。保护：试问建议去重限量、错误降级和时间范围来源。
- 建议：保留现行回归。
- 实际方法：`parseKeepsAtMostThreeNewDistinctQuestions`；`parseToleratesMissingOrBrokenOutput`；`sampledTimeRangesComeFromTheLimitedTablesOwnSamples`；`unavailableModelOrReadFailureFallsBackToEmpty`。
- 断言摘录：`assertEquals(List.of("A", "B", "C"), QuestionSuggestionService.parse(raw, List.of("营收")));`。
- 断言摘录：`assertTrue(QuestionSuggestionService.parse(null, List.of()).isEmpty());`。

### J053 RecommendedQuestionServiceTest

- 源码：[RecommendedQuestionServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/RecommendedQuestionServiceTest.java)。保护：推荐问题日期、手工覆盖和发布后更新。
- 建议：保留现行回归。
- 实际方法：`withoutADatedFactTableTheOriginalSuggestionIsUsed`；`theTrialStoresWhatItGenerates`；`theQuestionUsesThePlatformDatesAndAsksForADailySeries`；`anEditedQuestionIsKeptAcrossPublishes`；`theDraftModelGivesTheTrialItsRecommendation`；`editingSavesTheModelersTextAndBlankTextReturnsToGeneration`；`uploadedTablesUseTheirRealRangeAndExternalTablesTheLatestMonthOrShorterWindow`；`anUnreadableUploadedTableIsSkippedRatherThanGivenGuessedDates`。
- 断言摘录：`assertThat(candidates) .extracting(RecommendedQuestionService.Candidate::table) .containsExactly("报表访问明细", "外部访问");`。
- 断言摘录：`assertThat(candidates.get(0).datePhrase()).isEqualTo("2026-09-20 至 2026-10-07");`。

### J054 RefSqlMaterializerTest

- 源码：`RefSqlMaterializerTest`（后续清理已移除，见阶段2报告）。保护：未找到生产调用的旧SQL表名改写工具。
- 建议：清理候选，未删除。工具类无Spring注解；本模块生产源码无外部类型引用。与生产工具一起评审删除，非直接删测试。
- 实际方法：`skipsDottedQualifiedNames`；`neverTouchesStringLiterals`；`ignoresStringsAndComments`；`decodesQuotedIdentifiers`；`excludesCteNamesButKeepsTheirSources`；`deduplicatesCaseInsensitivelyKeepingFirstCase`；`collectsFromAndJoinRefsInFirstAppearanceOrder`；`replacesQuotedReferencesIncludingQuotes`；`materializeIsNoOpWithoutSqlOrMap`；`resolveIsNullSafeAndCaseInsensitive`；`doesNotTreatExtractSubclauseFromAsTable`；`keepsUnmappedReferencesUntouched`；`lookupIsExactFirstThenCaseInsensitive`；`collectsCommaSeparatedFromList`；`rewritesEveryMappedReference`；`tailFirstReplacementKeepsEarlierOffsets`；`physicalNameQuotesOnlyNonPlainParts`。
- 断言摘录：`assertEquals( List.of("orders", "customers", "regions"), RefSqlMaterializer.extractTableRefs( "SELECT * FROM orders o JOIN customers AS c ON o.cid = c.id" + " LEFT JOIN regions r ON c.rid = r.id"));`。
- 断言摘录：`assertEquals( List.of("orders"), RefSqlMaterializer.extractTableRefs( "SELECT * FROM orders a JOIN ORDERS b ON a.id = b.id"));`。

### J055 SchemaTripleExtractorTest

- 源码：[SchemaTripleExtractorTest](../../src/test/java/io/agentscope/dataagent/dataset/SchemaTripleExtractorTest.java)。保护：已停用知识图谱的确定性Schema三元组提取。
- 建议：清理候选，未删除。生产引用只来自同样已停用的KnowledgeGraphService。
- 实际方法：`extractsBackboneTriples`。
- 断言摘录：`assertTrue( triples.stream() .anyMatch(t -&gt; t.predicate().equals("包含字段") && t.object().equals("日期")), "field entity uses the semantic description, not the raw column id");`。
- 断言摘录：`assertTrue( triples.stream() .anyMatch(t -&gt; t.predicate().equals("示例") && t.object().equals("北京市")), "low-cardinality sample values become 示例 triples");`。

### J056 SemanticAssetServiceTest

- 源码：[SemanticAssetServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SemanticAssetServiceTest.java)。保护：语义资产核查、问题验证、证据和发布门禁。
- 建议：保留现行回归。
- 实际方法：`windowOrAggregationOverALimitedTableWarns`；`userWithoutManageSeesNothingOfTheDraft`；`assetOverLimitedTableCannotPassReviewWithoutTimeColumn`；`assetMatchingThePublishedSnapshotNeedsNoReview`；`anyEditVoidsTheEarlierConclusion`；`problemCasesCannotBeSavedAsQuestions`；`publishPinsConfirmedTimeColumnsBesideTheSnapshot`；`newAssetBlocksPublishingUntilReviewedAndVerified`；`generatedSqlMustUseTheAsset`；`savingRefusesPathsOutsideTheAsset`；`verdictNeedsACompleteSuccessfulRun`；`expectedCasesAreSavedAsQuestionsOnlyForTheSqlThatRan`；`timeColumnMustBeAnOutputColumnOfTheAsset`；`windowRefusalIsReportedPerQuestionAndNeverReachesTheEngine`；`verifyingBeforeReviewIsRefusedWithoutRunningAnything`；`newRefSqlModelIsAnAssetBeforeItsSqlIsWritten`；`deletingAnAssetRemovesItsRuleEntriesAndKeepsOtherRules`；`problemVerdictKeepsTheAssetUnverified`；`sqlThatNeverTouchesTheAssetCannotVerifyIt`；`reviewShowsDatesWrittenIntoTheAssetButLeavesTheDecisionToAPerson`；`sqlFileBeforeMetadataIsRefusedWithoutWritingAnything`。
- 断言摘录：`assertThat(asset().status()).isEqualTo(AssetReviewStore.REVIEW_PENDING);`。
- 断言摘录：`assertThat(store.publishIssues(GROUP)) .singleElement() .matches(i -&gt; i.message().contains("尚未核查"));`。

### J057 SharedDraftWrenAuthorizationTest

- 源码：[SharedDraftWrenAuthorizationTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedDraftWrenAuthorizationTest.java)。保护：共享建模的身份不替换、锁后撤权和只读用户拒绝。
- 建议：保留现行回归。
- 实际方法：`revocationWhileWaitingForWorkspacePreventsConnectionBinding`；`basicModelingRejectsCubeBeforeBindingSharedConnection`；`managersUseSharedConnectionWithoutReplacingActor`；`queryOnlyAndUnsharedUsersCannotBindOrExecuteDraft`。
- 断言摘录：`assertThat(projects.resolve(actor, "g").ownerId()).isEqualTo(actor);`。
- 断言摘录：`assertThat(toolkit.execute(context(actor), command)).contains("退出码：0");`。

### J058 SharedOfficialWrenAccessTest

- 源码：[SharedOfficialWrenAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/SharedOfficialWrenAccessTest.java)。保护：共享查询只用已发布快照，不读草稿并及时撤权。
- 建议：保留现行回归。
- 实际方法：`sharedQueryUsesPublishedProjectButCannotReadDraftAndRevocationTakesEffect`。
- 断言摘录：`assertThat(access.resolve(context, "g").getOwnerId()).isEqualTo("owner");`。
- 断言摘录：`assertThat(Files.readString(projects.prepare("reader", "g").resolve("target/mdl.json"))) .isEqualTo("{\"models\":[]}");`。

### J059 SqlExampleDocumentTest

- 源码：[SqlExampleDocumentTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleDocumentTest.java)。保护：SQL Markdown编辑保持字面值、换行和其他字段。
- 建议：保留现行回归。
- 实际方法：`unrelatedMarkdownCodeIsNotModified`；`foldedWithoutTagsKeepsClosingDelimiterOnItsOwnLine`；`invalidDocumentsFailBeforeWriting`；`crlfAndLiteralNewlinesNeverFlattenSql`；`structuredEditPreservesExactValuesAndOtherFields`。
- 断言摘录：`assertThat(doc.question()).isEqualTo(question);`。
- 断言摘录：`assertThat(doc.sql()).isEqualTo(sql);`。

### J060 SqlExampleRecallTest

- 源码：[SqlExampleRecallTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlExampleRecallTest.java)。保护：官方问答召回结果和发布样例元数据兼容。
- 建议：保留现行回归。
- 实际方法：`modelingAgentDoesNotExposeCubeTools`；`officialResultsPreserveOrderAndFields`；`publishedMetadataRetainsDefinitionAndSupportsMultilineSql`；`canonicalCubePathsUseOfficialWorkspace`。
- 断言摘录：`assertTrue(names.stream().noneMatch(name -&gt; name.contains("cube")));`。
- 断言摘录：`assertEquals("营收排名", result.get(0).question());`。

### J061 SqlQaExampleMemoryIntegrationTest

- 源码：[SqlQaExampleMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleMemoryIntegrationTest.java)。保护：真实官方LanceDB索引拒绝后的回滚及召回。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`rollbackRemovesActuallyIndexedRejectedValuesAndRestoresOfficialRecall`。
- 断言摘录：`assertThat(built.ok()).as(built.output()).isTrue();`。
- 断言摘录：`assertThatThrownBy( () -&gt; service.update( "alice", "g", "liked", "query.md", "已拒绝写入测试问题", "SELECT COUNT(*) FROM orders")) .isInstanceOf(DatasetException.class) .hasMessageContaining("已恢复");`。

### J062 SqlQaExampleServiceTest

- 源码：[SqlQaExampleServiceTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlQaExampleServiceTest.java)。保护：问答草稿/点赞来源修订、索引失败恢复及并发串行。
- 建议：保留现行回归。
- 实际方法：`concurrentLikedEditsAndIndexAreSerialized`；`modelingRepeatedEditsStayDraftAndPreserveStrings`；`invalidRequestsNeverWrite`；`confirmedQuestionProjectionCannotBypassQuestionRevisionLifecycle`；`failedIndexRestoresSourceProjectionAndPreviousIndex`；`malformedFileIsVisibleUneditableAndUnchanged`；`failedDeleteIndexRestoresFile`；`likedRepeatedEditsUpdateProjectionAndOfficialIndex`；`failedRecoveryLeavesNoSuccessMarkerAndNextReadRebuilds`；`independentDraftEditsAndDeletesAdvanceTheModelingRevision`。
- 断言摘录：`assertThat(updated.sql()).isEqualTo(sql);`。
- 断言摘录：`assertThat(service.list("owner", "g")) .anySatisfy( e -&gt; { assertThat(e.sql()).isEqualTo(sql);`。

### J063 SqlWindowAuditTest

- 源码：[SqlWindowAuditTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowAuditTest.java)。保护：日期门禁日志区分检查/缓存/拒绝且不记录SQL。
- 建议：保留现行回归。
- 实际方法：`auditDistinguishesChecksCacheSkipsAndBlockedRetryWithoutLoggingSql`。
- 断言摘录：`assertThatThrownBy( () -&gt; policy.requireAllowed( ctx, "g", "SELECT count(*) FROM a", "run_sql")) .isInstanceOf(DatasetException.class);`。
- 断言摘录：`assertThatThrownBy(() -&gt; policy.requireAllowed(ctx, "g", sql, "run_sql")) .isInstanceOf(DatasetException.class);`。

### J064 SqlWindowGateTest

- 源码：[SqlWindowGateTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowGateTest.java)。保护：日期校验返回值、超时取消、模型快照和失败关闭。
- 建议：保留现行回归。
- 实际方法：`emptyConfigurationSkipsModelAndFailureClosesGate`；`independentGateSubscriptionUsesTheParentTurnsModelSnapshot`；`preservesMissingConditionAndOverWindowDetails`；`hangingModelTimesOutAndCancelsWithoutAllowingSql`；`acceptsPerTableWindowsAndDoesNotApplyUnusedNarrowWindow`；`emptyRowsKeepTheModelRefusalBecauseNothingCanBeVerified`；`verdictFollowsRowsWhenModelConclusionContradictsThem`；`rejectsMalformedAndIncompleteJson`；`unregisteredSqlMayHaveNoMatchedTables`；`modelReceivesOriginalSqlAndChineseLogicalNamesOnly`；`validatesReturnedRowsAgainstTheirOwnRegistration`；`exactMaximumIsAcceptedButEvenAFractionOverItIsRejected`。
- 断言摘录：`assertThat( new SqlWindowGate(provider) .check( "SELECT SUM(amount) FROM orders WHERE day &gt;= NOW() -" + " INTERVAL 7 DAY", List.of( new SqlWindowGate.Rule( "orders", List.of("day"), 30)), context) .allowed()) .…`。
- 断言摘录：`assertThat( new SqlWindowGate(provider, java.time.Duration.ofMillis(10)) .check( "SELECT SUM(x) FROM a WHERE time1 &gt;= NOW() - INTERVAL 7" + " DAY", RULES) .allowed()) .isFalse();`。

### J065 SqlWindowRulesTest

- 源码：[SqlWindowRulesTest](../../src/test/java/io/agentscope/dataagent/dataset/SqlWindowRulesTest.java)。保护：可确定的SQL日期范围检查与复杂SQL回交模型。
- 建议：保留现行回归。
- 实际方法：`branchesSubqueriesAndRelativeDatesAreLeftToTheModel`；`windowsFollowTheGateConventions`；`missingOrOneSidedRangesAreRefusedPerTable`；`literalsAndCommentsCannotFakeARange`；`unregisteredTablesNeedNoRange`；`decidesTheGeneratedMonthlyJoinWithoutTheModel`。
- 断言摘录：`assertThat(decision.allowed()).isTrue();`。
- 断言摘录：`assertThat(decision.tables()) .singleElement() .satisfies( t -&gt; { assertThat(t.table()).isEqualTo("report_log");`。

### J066 SyntheticDemoMysqlTest

- 源码：[SyntheticDemoMysqlTest](../../src/test/java/io/agentscope/dataagent/dataset/SyntheticDemoMysqlTest.java)。保护：真实隔离MySQL演示初始化幂等和只读账号范围。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`seedsOnceKeepsLegacyDataAndReaderCannotQueryOtherSchemas`。
- 断言摘录：`assertThat(service.sourceAvailable()).isTrue();`。
- 断言摘录：`assertThat(service.sourceAvailable()).isTrue();`。

### J067 TableProvisionerTest

- 源码：[TableProvisionerTest](../../src/test/java/io/agentscope/dataagent/dataset/TableProvisionerTest.java)。保护：上传物理表DDL排序规则与字段可空性。
- 建议：保留现行回归。
- 实际方法：`buildCreateTableDdlHandlesSingleColumn`；`buildCreateTableDdlPinsCollationAndRendersNullability`。
- 断言摘录：`assertThat(ddl) .isEqualTo( "CREATE TABLE IF NOT EXISTS 'ds_bob0000_11111111_orders' (" + "'city' VARCHAR(255) NULL, 'amount' DECIMAL(18,6) NOT NULL" + ") ENGINE=InnoDB DEFAULT CHARSET=utf8mb4" + " COLLATE=utf8…`。
- 断言摘录：`assertThat(ddl).contains("COLLATE=utf8mb4_0900_ai_ci");`。

### J068 TemporalConditionTest

- 源码：[TemporalConditionTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalConditionTest.java)。保护：时间字段真实格式对应的SQL比较表达式。
- 建议：保留现行回归。
- 实际方法：`sortableExpressionsParseOnlyTextThatDoesNotSortAsTime`；`paddedNativeAndNumericColumnsCompareDirectlyAndNeverUseLike`；`unpaddedTextIsParsedWithTheSourcesOwnFunction`；`describeUsesTheSampledFormatOfRegisteredTimeColumnsOnly`。
- 断言摘录：`assertThat(TemporalCondition.of("visit_time", "uuuu/M/d H:mm", "VARCHAR", "mysql")) .isEqualTo( "STR_TO_DATE(visit_time, '%Y/%c/%e %k:%i') &gt;= '2026-10-01 00:00:00' AND" + " STR_TO_DATE(visit_time, '%Y/%c/%e %k:…`。
- 断言摘录：`assertThat(TemporalCondition.of("d", "uuuu-M-d", "TEXT", "postgres")) .isEqualTo( "TO_TIMESTAMP(d, 'YYYY-FMMM-FMDD') &gt;= '2026-10-01' AND" + " TO_TIMESTAMP(d, 'YYYY-FMMM-FMDD') &lt; '2026-11-01'");`。

### J069 TemporalSamplesTest

- 源码：[TemporalSamplesTest](../../src/test/java/io/agentscope/dataagent/dataset/TemporalSamplesTest.java)。保护：严格时间采样识别并保留人工确认属性。
- 建议：保留现行回归。
- 实际方法：`strictDatesCountEverySampleAndDoNotTreatNumericIdsAsDates`；`metadataEnrichmentPreservesConfirmedPropertiesAndIsIdempotent`；`compactDatesAndDateTimesWithoutSeparatorsAreRecognized`；`unpaddedTextDatesAreRecognizedAndPaddedOnesKeepTheirExactFormat`。
- 断言摘录：`assertThat(dates.format("VARCHAR")).isEqualTo("uuuu-MM-dd");`。
- 断言摘录：`assertThat(dates.format("BIGINT")).isNull();`。

### J070 WrenProfileHomeTest

- 源码：[WrenProfileHomeTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenProfileHomeTest.java)。保护：Wren连接配置的字面凭据、SSL、修复漂移与来源范围。
- 建议：保留现行回归。
- 实际方法：`sslModeFollowsExplicitUrlSwitchesOnly`；`parseJdbcUrlDefaultsPortAndAcceptsBareHost`；`renderAllAppendsExternalMysqlProfilesAndSkipsOtherKinds`；`ensureWritesProfileOnceAndRepairsDrift`；`parseJdbcUrlAllowEmptyDbKeepsDatabaseBlank`；`cliConnectionKeepsLiteralCredentialsAndExternalSsl`；`ensureAllRebuildsFileFromProvidedSources`；`renderPinsEveryProfileField`；`parseJdbcUrlRejectsMalformedUrls`；`renderFailsWithoutDatasetUrl`；`parseJdbcUrlExtractsHostPortDatabase`。
- 断言摘录：`assertThat(connection.path("password").asText()).isEqualTo(source.getPassword());`。
- 断言摘录：`assertThat(connection.path("port").asText()).isEqualTo("13306");`。

### J071 WrenPropertiesResolveTest

- 源码：[WrenPropertiesResolveTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPropertiesResolveTest.java)。保护：跨系统Wren可执行文件解析及显式配置优先。
- 建议：保留现行回归。
- 实际方法：`linuxProbesWren`；`unavailableMessageCarriesCrossPlatformGuidance`；`linuxIgnoresWindowsStyleBinary`；`windowsProbesWrenExe`；`explicitValueIsUsedVerbatim`；`defaultFallsBackToPlainNameWhenNoCandidateMatches`。
- 断言摘录：`assertThat(props.resolveExecutable()).isEqualTo("C:\\venvs\\wren\\Scripts\\wren.exe");`。
- 断言摘录：`assertThat(props("wren", true, List.of()).resolveExecutable()) .isEqualTo(WrenProperties.DEFAULT_EXECUTABLE);`。

### J072 WrenPublishedProjectTest

- 源码：[WrenPublishedProjectTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenPublishedProjectTest.java)。保护：已发布工程快照、索引复用和官方文件名隔离。
- 建议：保留现行回归。
- 实际方法：`deploysPublishedManifestVerbatimAndRefreshesOfficialIndexOnSourceChange`；`simultaneousPreparationBuildsOnceAndWarmQueryReusesIt`；`officialLanceSuccessIsDistinctFromGrepAndUnknownOutput`；`sameOfficialFilenamesNeverOverwritePublishedExamplesOrLeakDrafts`。
- 断言摘录：`assertThat( WrenPublishedProject.confirmsLanceIndex( "Indexed 13 schema items.\n" + "Indexed 2 pair(s) from knowledge/sql/.\n")) .isTrue();`。
- 断言摘录：`assertThat( WrenPublishedProject.confirmsLanceIndex( "Indexed 13 schema items, 6 seed queries.\r\n")) .isTrue();`。

### J073 WrenQueryAccessTest

- 源码：[WrenQueryAccessTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryAccessTest.java)。保护：选库、归属和首次未发布时的问数权限。
- 建议：保留现行回归。
- 实际方法：`selectionCannotExposeAnotherOwnerOrUnselectedGroup`；`unpublishedModelCannotBeQueried`。
- 断言摘录：`assertThat(access.visible(ctx)).containsExactly(a);`。
- 断言摘录：`assertThat(access.resolve(ctx, null)).isSameAs(a);`。

### J074 WrenQueryMemoryIntegrationTest

- 源码：[WrenQueryMemoryIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryMemoryIntegrationTest.java)。保护：真实Wren问答文件和向量跨MCP会话召回。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`modelingAndLikedPairsRemainRecallableAcrossMcpSessions`。
- 断言摘录：`assertThat(instructions).contains("不为补齐枚举", "不能用样本代替");`。
- 断言摘录：`assertThat(registry.recallWarm("g")).isTrue();`。

### J075 WrenQueryPreparationTest

- 源码：[WrenQueryPreparationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenQueryPreparationTest.java)。保护：提交后预热、事件合并、撤权和失败重试。
- 建议：保留现行回归。
- 实际方法：`transactionCommitSchedulesAndRollbackDoesNot`；`warmsActualGatewayAfterInvalidationAndHidesStateAfterRevocationOrEviction`；`afterCommitPreparationAdmissionFailureDoesNotFailPublication`；`coalescesQueuedEventsKeepsGroupsSeparateAndRetriesAfterFailure`。
- 断言摘录：`assertThat(service.status("alice", "a").state()).isEqualTo("QUEUED");`。
- 断言摘录：`assertThat(service.status("alice", "a").state()).isEqualTo("READY");`。

### J076 WrenSkillsLocatorTest

- 源码：[WrenSkillsLocatorTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenSkillsLocatorTest.java)。保护：跨系统官方技能包路径发现与无效目录处理。
- 建议：保留现行回归。
- 实际方法：`windowsUserSiteLayoutIsDetected`；`explicitConfigWinsWhenValid`；`invalidExplicitFallsBackToInference`；`directoryWithoutSkillMdYieldsEmpty`；`windowsVenvLayoutIsDetected`；`bareExecutableYieldsEmpty`；`unixPython3LayoutIsDetected`；`unixVenvLayoutIsDetected`。
- 断言摘录：`assertEquals( Optional.of(explicit.toAbsolutePath().normalize()), new WrenSkillsLocator(props).locate());`。
- 断言摘录：`assertEquals( Optional.of(inferred.toAbsolutePath().normalize()), new WrenSkillsLocator(props).locate(), "must fall through to executable inference");`。

### J077 WrenTimingIntegrationTest

- 源码：[WrenTimingIntegrationTest](../../src/test/java/io/agentscope/dataagent/dataset/WrenTimingIntegrationTest.java)。保护：真实CLI索引重用及样例变化的分段耗时。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`realIndexReuseAndExampleChangeHaveSeparateTimings`。
- 断言摘录：`assertThat(Files.readString(runtime.resolve(".indexed-source"))).isEqualTo(marker);`。
- 断言摘录：`assertThat(Files.readString(runtime.resolve(".indexed-source"))).isNotEqualTo(marker);`。

### J078 DocsConsistencyTest

- 源码：[DocsConsistencyTest](../../src/test/java/io/agentscope/dataagent/DocsConsistencyTest.java)。保护：文档规模、链接、ADR取代关系及spec索引完整。
- 建议：保留现行回归。结构门禁耗时很小；只证明文档结构/链接，不证明业务或架构正确。
- 实际方法：`documentsEveryAgentReadsHaveNoBrokenRelativeLinks`；`supersededAdrsRecordWhoReplacedThem`；`everyAdrHasAStatusAndIsListedInTheIndex`；`topLevelSpecsAreOnlyActiveOnesListedInTheIndex`；`coreDocumentsStayWithinTheirSizeBudgets`。
- 断言摘录：`assertThat(lines("AGENTS.md")).as("AGENTS.md 行数上限 150，超出请先合并或删减").isLessThanOrEqualTo(150);`。
- 断言摘录：`assertThat(lines("docs/ai/CURRENT_TASK.md")) .as("CURRENT_TASK.md 只保留当前任务，上限 80 行") .isLessThanOrEqualTo(80);`。

### J079 WebhookInboundMapperTest

- 源码：[WebhookInboundMapperTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookInboundMapperTest.java)。保护：外部消息身份、会话、Agent和空输入映射。
- 建议：保留现行回归。
- 实际方法：`blankPreferredAgentIdIsIgnored`；`missingMessageIsRejected`；`preferredAgentIdIsPropagated`；`sessionIdScopesPeerButNotSender`；`nullRequestIsRejected`；`missingExternalUserIdIsRejected`；`mapsBareUserMessage`；`whitespaceIsStripped`。
- 断言摘录：`assertThat(out).isPresent();`。
- 断言摘录：`assertThat(msg.channelId()).isEqualTo("ops-webhook");`。

### J080 WebhookSignatureTest

- 源码：[WebhookSignatureTest](../../src/test/java/io/agentscope/dataagent/runtime/channel/webhook/WebhookSignatureTest.java)。保护：Webhook HMAC、篡改拒绝和签名往返。
- 建议：保留现行回归。
- 实际方法：`signAndVerifyRoundTrips`；`tamperedBodyFailsVerification`；`differentSecretsProduceDifferentDigests`；`emptyBodyIsHandled`；`hmacIsDeterministic`；`hmacMatchesRfc4231Vector1`；`constantTimeEqualsAcceptsMatches`；`constantTimeEqualsRejectsMismatches`。
- 断言摘录：`assertThat(hex) .isEqualTo("b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7");`。
- 断言摘录：`assertThat(first).isEqualTo(second);`。

### J081 GatewayTurnLifecycleTest

- 源码：[GatewayTurnLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/GatewayTurnLifecycleTest.java)。保护：排队轮次作用域、恢复会话归属和忙时失败。
- 建议：保留现行回归。
- 实际方法：`busyStreamFailsInsteadOfCompletingEmpty`；`queuedTurnCannotOverwriteActiveScopeAndSecondTurnUsesItsOwnSnapshot`；`restoredIndexChecksOwnerAndRejectsDeletedSession`。
- 断言摘录：`assertThat(scopes.get("alice", "runtime")).isEqualTo(scope.groupIds());`。
- 断言摘录：`assertThat(firstRunning.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();`。

### J082 HarnessGatewaySandboxlessTest

- 源码：[HarnessGatewaySandboxlessTest](../../src/test/java/io/agentscope/dataagent/runtime/gateway/HarnessGatewaySandboxlessTest.java)。保护：免沙箱Agent跳过借用，其他Agent懒启动并复用。
- 建议：保留现行回归。
- 实际方法：`sandboxlessAgentSkipsTheRegistryBorrowEntirely`；`clearingTheSandboxlessSetRestoresLazyBorrowingForEveryId`；`everyOtherAgentBorrowsOnlyOnFirstOperationAndReusesTheUserSandbox`。
- 断言摘录：`assertThat(builder.build().get(SandboxContext.class)).isNull();`。
- 断言摘录：`assertThat(ctx).isNotNull();`。

### J083 ManagedModelHttpTest

- 源码：[ManagedModelHttpTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ManagedModelHttpTest.java)。保护：真实本地HTTP替身验证模型路径、Bearer、SSE和安全错误。
- 建议：保留现行回归。
- 实际方法：`errorsAreSafeAndDoNotRetryOrActivate`；`nativeClientUsesConfiguredPathBearerSseAndToolCalls`。
- 断言摘录：`assertThat(registry.test(service.id(), 1).block(Duration.ofSeconds(8)).success()).isTrue();`。
- 断言摘录：`assertThat(paths).containsExactly("/gateway/complete", "/gateway/complete");`。

### J084 ModelServiceCipherTest

- 源码：[ModelServiceCipherTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceCipherTest.java)。保护：密钥持久性、认证加密和密钥丢失/错误处理。
- 建议：保留现行回归。
- 实际方法：`fileKeyIsDurableAuthenticatedAndNotRecreatedOnLoss`；`suppliedKeyRequires32BytesAndWrongKeyCannotDecrypt`。
- 断言摘录：`assertThat(first) .doesNotContain("synthetic-private-value") .isNotEqualTo(cipher.encrypt("synthetic-private-value"));`。
- 断言摘录：`assertThat(new ModelServiceCipher("", home.toString()).decrypt(first)) .isEqualTo("synthetic-private-value");`。

### J085 ModelServiceRegistryTest

- 源码：[ModelServiceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/model/ModelServiceRegistryTest.java)。保护：模型配置加密、显式激活、修订和轮次快照。
- 建议：保留现行回归。
- 实际方法：`cachedNativeAgentPinsWholeToolTurnAndNextTurnUsesNewService`；`revisionsNamesAndCredentialsAreValidated`；`staleProbeNeverApprovesNewRevisionAndDuplicateProbeIsRejected`；`failedProbeDoesNotSwitchOrLeakProviderErrors`；`encryptedRestoreAndExplicitDraftApplication`；`absentManagedServiceUsesStartupAndMissingKeyNeverFallsBack`。
- 断言摘录：`assertThat(registry.test(p.id(), p.revision()).block(Duration.ofSeconds(5)).success()) .isTrue();`。
- 断言摘录：`assertThat(registry.view().active().id()).isNull();`。

### J086 SwitchingModelTest

- 源码：[SwitchingModelTest](../../src/test/java/io/agentscope/dataagent/runtime/model/SwitchingModelTest.java)。保护：并行订阅和同轮辅助调用固定模型快照。
- 建议：保留现行回归。
- 实际方法：`independentSubscriptionsKeepTheParentTurnWhileNewOperationsSeeTheSwitch`；`unmanagedModelsAndOtherCallContextsRemainIndependent`。
- 断言摘录：`assertThat(SwitchingModel.forCall(switching, context).getModelName()) .isEqualTo("first");`。
- 断言摘录：`assertThat(SwitchingModel.forCall(switching, null).getModelName()) .isEqualTo("second");`。

### J087 AnalysisSkillMiddlewareTest

- 源码：[AnalysisSkillMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/AnalysisSkillMiddlewareTest.java)。保护：普通问数、待确认和确认后分析的提示与执行约定。
- 建议：保留核心，改写文本/结构断言。保留确认前后分支；措辞可以简化为结构与关键约束。
- 实际方法：`confirmedParametersRunTheFixedScript`；`anOrdinaryQueryOnlyLearnsNotToUseTheAnalysisSkills`；`anUnconfirmedChoiceOnlyProposesParameters`。
- 断言摘录：`assertThat(prompt(null)) .startsWith("基础提示") .contains("用户没有选择时不要加载这些技能", "不要调用 propose_analysis") .doesNotContain("本轮分析方法");`。
- 断言摘录：`assertThat(prompt(new AnalysisTurn("anomaly", null))) .contains("《异常检测》（待确认参数）", "加载技能 anomaly", "调用一次 propose_analysis") .contains("本轮不执行 run_sql");`。

### J088 DataDynamicContextMiddlewareTest

- 源码：[DataDynamicContextMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/DataDynamicContextMiddlewareTest.java)。保护：非主Agent兼容装配的模型目录、选库与无物理回退。
- 建议：保留，细化或拆分断言。DataAgentConfig仍给非主Agent安装该兼容中间件；不能因主问数不用就删除。
- 实际方法：`publishedGroupRendersLogicalSectionWithoutPhysicalHints`；`explicitEmptyScopeIsOrdinaryChatAndLoadsNoKnowledgeModel`；`publishedGroupInjectsSqlOnlyRoutingAndDistinctTimeSemantics`；`groupFilteredScopeShowsOnlySelectedGroup`；`noVisibleSourcesLeavesPromptUntouched`；`dirtyGroupUsesPublishedDirectoryWithoutPhysicalFallback`；`unpublishedGroupWithoutStateDoesNotExposePhysicalOverview`；`absentCatalogDoesNotExposePhysicalOverview`；`failedGroupRendersUnavailableStateWithoutPhysicalHints`；`knowledgeBaseOverviewStillRendersAlongsideLogicalSection`；`groupScopedBusinessRulesRenderWithoutDocumentsOrVisibleSources`；`multipleSelectedGroupsExposeBothDirectoriesAndExplicitRoutingGuidance`。
- 断言摘录：`assertThat(prompt(middleware, new DatasetScope("alice", List.of()))) .contains("仅进行普通聊天", "不要查询企业数据库") .doesNotContain("[DATA_SOURCES_OVERVIEW]", "[KNOWLEDGE_BASE_OVERVIEW]");`。
- 断言摘录：`assertThat(prompt(middleware, new DatasetScope("alice", List.of("gA", "gOther")))) .contains("订单分析", "库存库", "自行判断需要查询哪些库", "按对应 group_id 分别调用工具");`。

### J089 GroupScopedMemoryNamespaceTest

- 源码：[GroupScopedMemoryNamespaceTest](../../src/test/java/io/agentscope/dataagent/runtime/session/GroupScopedMemoryNamespaceTest.java)。保护：记忆命名空间按用户/知识库隔离及旧范围兼容。
- 建议：保留现行回归。
- 实际方法：`multiGroupTurnFallsBackToOwnerLevel`；`singleGroupTurnScopesMemoryToOwnerAndGroup`；`missingScopeOrGlobalScopeFallsBackToOwnerLevel`；`ownerIdOnScopeCoversWhenUserIdIsAbsent`；`noOwnerFallsBackToSessionThenEmpty`；`twoKnowledgeBasesNeverShareANamespace`。
- 断言摘录：`assertEquals(List.of("admin", "kb-g-4444"), ns(rc));`。
- 断言摘录：`assertEquals(List.of("admin"), ns(rc));`。

### J090 ProjectFilesContextTest

- 源码：[ProjectFilesContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/ProjectFilesContextTest.java)。保护：建模资料清单和确认选项上下文。
- 建议：保留现行回归。
- 实际方法：`modelingFilesAreListedSoTheAssistantNeedNotWalkDirectories`；`longOptionLabelsAreShortenedInsteadOfRefused`。
- 断言摘录：`assertThat(text) .contains( "当前工程文件清单", "models/user/metadata.yml", "relationships.yml", "knowledge/rules/general.md", "raw/docs/业务规则.md", "无需再 list_files") .doesNotContain(".platform", "target/", "cubes/", ".g…`。
- 断言摘录：`assertThat(text) .contains("空目录（无需查看）：knowledge/glossary", "没有单独的采样文件") .doesNotContain("knowledge/rules、", "knowledge/caveats");`。

### J091 QuestionDateGateTest

- 源码：[QuestionDateGateTest](../../src/test/java/io/agentscope/dataagent/runtime/session/QuestionDateGateTest.java)。保护：原生生命周期日期澄清、历史恢复与权限。
- 建议：保留现行回归。
- 实际方法：`clarificationUsesNativeLifecycleAndDoesNotDiscoverWren`；`emptySelectionSkipsGateModelAndForeignSelectionFailsBeforeModel`；`historyContainsClarificationAndDateReplyWithoutRewritingOrMetadata`；`validRangeIsInjectedAndGateRunsOnlyOncePerInvocation`；`invalidModelOutputAndFailureAskWithoutContinuing`。
- 断言摘录：`assertThat(answer.getTextContent()).contains("请指定统计起止日期");`。
- 断言摘录：`assertThat(ctx.getAgentState().getContext()) .anyMatch(m -&gt; m.getRole() == MsgRole.USER && m.getTextContent().contains("访问最多"));`。

### J092 RuntimeWindowContextTest

- 源码：[RuntimeWindowContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/RuntimeWindowContextTest.java)。保护：日期门禁上下文、服务时间、提示透传与中文提示。
- 建议：保留核心，改写文本/结构断言。保留时间范围与隔离行为；中文措辞断言只保护提示内容。
- 实际方法：`queryContextGivesTheTimeConditionFromThePublishedColumnFormat`；`serviceTimeRefreshesAcrossMidnightUsingTheSameZone`；`dateFirstRuleAppearsOnlyForRegisteredLimitedTables`；`everyQueryModelCallEndsWithTheChineseOnlyReminder`；`officialWorkflowPromptIsPassedThroughVerbatimAndNeverBlocksTheTurn`；`modelingPromptSeparatesOperationalWindowsAndBusinessDefinitions`；`queryingUsesOnlyVisibleDatabasePolicyAndRefreshesWithoutPublishing`。
- 断言摘录：`assertThat(middleware.onSystemPrompt(null, context, "base").block()) .contains("当前时间：2026-10-20T23:59:59+08:00", "当前日期：2026-10-20", "服务时区：Asia/Shanghai") // No knowledge base selected: ask the user to pick one,…`。
- 断言摘录：`assertThat(middleware.onSystemPrompt(null, context, "base").block()) .contains("当前时间：2026-10-21T00:00:01+08:00", "当前日期：2026-10-21", "服务时区：Asia/Shanghai") .doesNotContain("当前日期：2026-10-20");`。

### J093 SessionStateLifecycleTest

- 源码：[SessionStateLifecycleTest](../../src/test/java/io/agentscope/dataagent/runtime/session/SessionStateLifecycleTest.java)。保护：会话重置/移除只清当前归属检查点并支持重试。
- 建议：保留现行回归。
- 实际方法：`failedCheckpointDeleteKeepsSessionForRetry`；`removalCleansAllCheckpointKeysAndIsIdempotent`；`resetDeletesOnlyOldOwnerSlotAndNewConversationStartsEmpty`；`maintenanceUsesTheSameCheckpointCleanupPath`。
- 断言摘录：`assertThat(manager.resetSession(session.sessionKey())).isTrue();`。
- 断言摘录：`assertThat(reset.sessionId()).isNotEqualTo(session.sessionId());`。

### J094 WrenSamplingContextTest

- 源码：[WrenSamplingContextTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenSamplingContextTest.java)。保护：建模采样上下文范围、表角色、日期与当前轮目的。
- 建议：保留核心，改写文本/结构断言。保留来源/轮次范围；提示内容检查不等于模型遵守不查询规则。
- 实际方法：`buildStateReflectsWhetherCompiledMdlExists`；`tablesAddedAfterAPublishAreListedForIncrementalModeling`；`turnPurposeSelectsTheContextSections`；`modelingListsProbeBoundaryPerTableSourceAndServiceTime`；`modelingSeesOnlyCurrentProjectsDatabaseWindows`；`noUploadedTablesKeepsEveryTableProbeFree`；`relationCandidatesComeFromColumnNamesNotTypes`；`everyModelingModelCallEndsWithTheChineseOnlyReminder`；`projectPromptKeepsPolicyWithoutInjectingRawSamplingData`。
- 断言摘录：`assertThat(one).doesNotContain("first-secret", "摘要截断").hasSizeLessThan(8000);`。
- 断言摘录：`assertThat(two).contains("采样").doesNotContain("second-sample", "first-secret");`。

### J095 WrenScopedToolkitMiddlewareTest

- 源码：[WrenScopedToolkitMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/session/WrenScopedToolkitMiddlewareTest.java)。保护：按本轮授权挂载官方schema及显式联网开关。
- 建议：保留现行回归。
- 实际方法：`modelSeesOfficialSchemasOnlyWhenAKnowledgeBaseIsBound`；`onlyAnOptedInInvocationSeesTheWebSearchSchema`；`emptySelectionStillRunsOrdinaryChatWithoutRegisteringWrenTools`；`standInsOnTheSharedToolkitForwardOnlyToThisInvocationsScopedTools`。
- 断言摘录：`assertThat(forwarded.tools()) .extracting(tool -&gt; tool.getName()) .containsExactly("web_search");`。
- 断言摘录：`assertThat(forwarded.tools()).isEmpty();`。

### J096 SubagentOrchestrationSwitchTest

- 源码：[SubagentOrchestrationSwitchTest](../../src/test/java/io/agentscope/dataagent/runtime/SubagentOrchestrationSwitchTest.java)。保护：配置默认关闭子Agent的反射注解和YAML约定。
- 建议：保留核心，改写文本/结构断言。保护默认配置有价值；反射字段名/注解文本可改成有效配置与实际装配验证。
- 实际方法：`applicationYmlDeclaresSubagentsDisabledByDefault`；`configFieldDefaultsSubagentsToDisabled`。
- 断言摘录：`assertThat(field.getType()).isEqualTo(boolean.class);`。
- 断言摘录：`assertThat(annotation).as("%s must be read from configuration", KEY).isNotNull();`。

### J097 WrenConnectionRecoveryIntegrationTest

- 源码：[WrenConnectionRecoveryIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryIntegrationTest.java)。保护：真实Wren代理中断后的有界重连与重复故障。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`realProcessRecoversWithoutPublishAndBoundsRepeatedOutage`。
- 断言摘录：`assertThat(root.getFileName().toString()).startsWith("wren-recovery-");`。
- 断言摘录：`assertThat(nativeFailure).isNotNull();`。

### J098 WrenConnectionRecoveryTest

- 源码：[WrenConnectionRecoveryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenConnectionRecoveryTest.java)。保护：故障识别、一次重放、冷却、取消和重连时撤权。
- 建议：保留现行回归。
- 实际方法：`transportFailureDoesNotReplayButInvalidates`；`connectionOpenFailureBeforeEngineWrappingIsRecognized`；`recoversOnceAndReusesReplacement`；`successEchoAndOtherToolsNeverTriggerReplay`；`queuedSameGroupCallsShareOneRecoveryWhileOtherGroupRuns`；`businessAndUnrecognizedErrorsKeepInstance`；`replacementBusinessErrorDoesNotCauseAnotherRebuild`；`transportFailureDuringRecoveryStillEnforcesCooldown`；`revocationAfterFailedQueryCannotRebuildSharedEngine`；`failedReplacementStartupStillEnforcesCooldownAndReleasesBudget`；`cancelledFailureDoesNotRebuild`；`persistentFailureIsBoundedAndLaterCallCanRecover`；`recognizesOnlySupportedConnectionDiagnostics`；`revocationDuringReplacementCreationPreventsQuery`。
- 断言摘录：`assertThat(project.getFileName().toString()).isEqualTo("published");`。
- 断言摘录：`assertThat(profile).isEqualTo("fixture");`。

### J099 WrenInstanceRegistryTest

- 源码：[WrenInstanceRegistryTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenInstanceRegistryTest.java)。保护：MCP实例池、互斥、缓存、关闭预算和排队撤权。
- 建议：保留现行回归。
- 实际方法：`revokedQueuedDiscoveryCannotReachMcpOrInvalidateSharedInstance`；`revokedQueuedCallCannotReachMcpOrInvalidateSharedInstance`；`mdlResourceIsReadableChineseWithTheSameJsonValue`；`instructionsCacheIsInstanceScopedAndNeverSkipsAuthorization`；`concurrentCallsOnOneGroupAreSerialized`；`failedDiscoveryIsNotCached`；`workflowPromptKeepsEveryTextPartInOrder`；`readsPinnedExternalProfileFromSnapshot`；`fallsBackToDefaultOnMalformedOrBlankMarker`；`fallsBackToDefaultProfileWithoutMarker`；`distinctGroupsStillRunInParallel`；`failedEngineCloseRetainsBudgetUntilRetryConfirmsCleanup`；`authorizationIsRecheckedAfterAcquireAndDenialKeepsInstance`；`recallWarmupUsesPooledInstanceAndEvictionClearsReadiness`；`mdlResourceTextKeepsOnlyTextContents`；`discoveryIsCachedPerInstanceAndInvalidationDropsIt`；`fullPoolNeverEvictsAnExecutingEngineAndReclaimsItAfterRelease`；`failedInstructionsAreNeverCached`。
- 断言摘录：`assertThat(registry.recallWarm("a")).isFalse();`。
- 断言摘录：`assertThat(checks.get()).isGreaterThanOrEqualTo(2);`。

### J100 WrenMcpClassLoaderTest

- 源码：[WrenMcpClassLoaderTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMcpClassLoaderTest.java)。保护：受限线程上下文类加载器与官方MCP初始化。
- 建议：保留现行回归。
- 实际方法：`diagnosticReproducesMapperDiscoveryFailureUnderRestrictedLoader`；`initializesWithApplicationLoaderAndRestoresRestrictedWorkerOnFailure`。
- 断言摘录：`assertThat(Thread.currentThread().getContextClassLoader()) .isSameAs(WrenInstanceRegistry.class.getClassLoader());`。
- 断言摘录：`assertThat(McpJsonMapper.createDefault()).isNotNull();`。

### J101 WrenMysqlLimitIntegrationTest

- 源码：[WrenMysqlLimitIntegrationTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenMysqlLimitIntegrationTest.java)。保护：真实官方CLI/MCP的LIMIT和截断兼容。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`cliAndMcpAcceptSqlLimitsAndPreserveTruncation`。
- 断言摘录：`assertThat(Files.isRegularFile(project.resolve("target/mdl.json"))).isTrue();`。
- 断言摘录：`assertThat(baseline.ok()).as(baseline.payload()).isTrue();`。

### J102 WrenProcessEnvironmentTest

- 源码：[WrenProcessEnvironmentTest](../../src/test/java/io/agentscope/dataagent/runtime/wren/WrenProcessEnvironmentTest.java)。保护：Python兼容资源安装与条件启用的真实Wren回归。
- 建议：保留，条件专项/混合。2项中1项需真实Wren；资源安装用例仍属默认快速验证，不应整类移出默认集合。
- 实际方法：`installsPackagedResourceAndPreservesInheritedPythonPath`；`installedWrenRunsCompatibilityRegressionSuite`。
- 断言摘录：`assertThat(environment.get("PYTHONPATH")) .endsWith(java.io.File.pathSeparator + "existing-path");`。
- 断言摘录：`assertThat(environment.get("DATAAGENT_WREN_COMPAT")).isEqualTo("1");`。

### J103 IsolatedApplicationTestSupport

- 源码：[IsolatedApplicationTestSupport](../../src/test/java/io/agentscope/dataagent/support/IsolatedApplicationTestSupport.java)。保护：三个完整应用测试的临时目录和外部沙箱替身基类。
- 建议：测试辅助，保留。
- 无独立测试报告：辅助基类/夹具，按其使用方的用例验证。

### J104 AnalysisScriptsTest

- 源码：[AnalysisScriptsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisScriptsTest.java)。保护：本地Python实际运行归因/异常/预测脚本的数值与样本边界。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`forecastRefusesShortHistoryAndOtherwiseGivesIntervals`；`anomalyFindsTheSpikeAndTheHolidayDipsDeterministically`；`attributionSplitsTheChangeByTheDimensionWhoseMixShiftedMost`；`distinctMetricsAreComparedPerValueWithoutAnAdditiveSplit`。
- 断言摘录：`assertThat(p.waitFor(120, TimeUnit.SECONDS)).isTrue();`。
- 断言摘录：`assertThat(r.path("ok").asBoolean()).isTrue();`。

### J105 AnalysisSkillsTest

- 源码：[AnalysisSkillsTest](../../src/test/java/io/agentscope/dataagent/tools/data/AnalysisSkillsTest.java)。保护：分析技能打包、重装和参数确认卡。
- 建议：保留现行回归。
- 实际方法：`onlyBuiltInMethodsAreAccepted`；`everySkillShipsItsInstructionsAndOneScript`；`materialisingReplacesAnEarlierVersion`；`theParameterCardIsOnlyForAnUnconfirmedAnalysisTurn`。
- 断言摘录：`assertThat(AnalysisSkills.normalize(" Attribution ")).isEqualTo("attribution");`。
- 断言摘录：`assertThat(AnalysisSkills.normalize("anomaly")).isEqualTo("anomaly");`。

### J106 ChartBuilderTest

- 源码：[ChartBuilderTest](../../src/test/java/io/agentscope/dataagent/tools/data/ChartBuilderTest.java)。保护：图表类型推断、日期、维度、轴及无效图表规范。
- 建议：保留现行回归。
- 实际方法：`explicitHeatmapUsesSeriesAsTheSecondDimension`；`invalidSpecsAreRefusedWithReadableMessages`；`aLongTrendKeepsEveryDayWithAZoomBarAndTheQuestionAsTitle`；`anEmptySpecKeepsTheInferredChart`；`withoutAQuestionTheTitleNamesTheMetric`；`numericIdColumnsAreDimensionsNotMetrics`；`explicitStackedBarGroupsBySeriesColumn`；`explicitDualAxisPutsY2OnTheRightAxis`；`aDateSeriesIsALineEvenWhenTheWordingLooksLikeAComparison`。
- 断言摘录：`assertThat(chart.chartType()).isEqualTo("line");`。
- 断言摘录：`assertThat(chart.chartType()).isEqualTo("line");`。

### J107 DataAgentToolkitTest

- 源码：[DataAgentToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/DataAgentToolkitTest.java)。保护：禁止物理查询工具和ECharts呈现结果。
- 建议：保留现行回归。
- 实际方法：`renderChartAppliesTargetMarkLine`；`renderChartRejectsUnchartableData`；`physicalQueryToolsAreRemoved`；`renderChartBuildsDeterministicOption`。
- 断言摘录：`assertThat(toolNames) .containsExactly("render_chart") .doesNotContain("prepare_data_context", "query_structured_data");`。
- 断言摘录：`assertThat(out).contains("\"chart\":\"echarts\"");`。

### J108 ModelingToolkitTest

- 源码：[ModelingToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/ModelingToolkitTest.java)。保护：草稿文件写入/预检/回滚/权限及部分旧结构化工具。
- 建议：保留，细化或拆分断言。文件原子性/权限不可删；旧结构化工具和Cube命令用例可按实际注册逐项拆分。
- 实际方法：`createViewRejectsNameClashWithExistingAssets`；`readFileRejectsTraversalAndMissingFiles`；`addRelationValidatesCompositePairsAndMarksDirty`；`stateFallsBackToDbDatasetsBeforeSeeding`；`validateMdlRendersIssues`；`createViewRejectsMysqlOnlyDialectFunctions`；`createViewRejectsDdlAndMultiStatements`；`commandPackageFailsFastOnUninitializedWorkspace`；`listFilesEnumeratesWorkspaceAndHintsEmptyState`；`officialChannelToolDescriptionsMatchCliReality`；`previewFailsWhenStrictValidationFailsAndKeepsWorkspace`；`confirmRelationMarksDirtyAndReports`；`decideRelationsRejectsMalformedJson`；`completeViewAndQuestionPlanIsValidatedTogetherBeforeAnyFileIsWritten`；`dryPlanGateBlocksWriteWhenSerdeRejectsManifest`；`writeFileRejectsMalformedYamlWithoutTouchingWorkspace`；`writeFileKeepsWorkspaceUntouchedWhenValidationFails`；`patchFileReplacesUniqueMatchThroughTheGate`；`crossTenantWritesCollapseIntoUnknownGroupError`；`previewPatchRejectsAmbiguousOriginalAndKeepsOldContent`；`confirmRelationRejectsInvalidJoinType`；`enrichContextModeFollowsOfficialLockingRule`；`buildGateBlocksWriteWhenContextBuildFails`；`readFileReturnsExactContentAndPlatformFilesAreReadable`；`decideRelationConfirmsAdjustsRejectsAndSkips`；`preflightDeniesAnotherTenantsGroupBeforeWorkspaceAccess`；`listBusinessRulesReadsWorkspaceRuleFilesAndRejectsForeignGroup`；`addRelationRejectsMismatchedColumnCounts`；`buildFailureBlocksCommandPackage`；`decideRelationsRejectsWholeBatchWhenAnyEntryIsInvalid`；`batchPlanCompileFailureAndEmptyQuestionLeaveWorkspaceUnchanged`；`createViewGateFailureLeavesWorkspaceUntouched`；`patchFileRejectsAmbiguousOriginalUnlessReplaceAll`；`previewPatchShowsMergedContentWithoutWriting`；`writeFileRefusesPlatformOwnedAndOutOfBoundsPaths`；`decideRelationsKeepDecisionsWhenWorkspaceValidationFails`；`stateRendersWorkspaceSnapshotWithCandidateQueue`；`createViewWritesPairedFilesThroughTheTripleGate`；`cubeQueryArgvCarriesFullShape`；`createViewAcceptsWithQueries`；`createViewRejectsBadNames`；`dryPlanBuildsFirstThenTranslates`；`batchPlanPreflightDoesNotWriteAndCommitRejectsStaleBase`；`stateAndEngineeringValidationExposeTheSameNextAction`；`patchOnMissingFileDirectsToWriteTool`；`patchFileRejectsMissingOriginalWithoutChanges`；`stateSurfacesWorkspaceParseIssues`；`previewWriteExistingFileCarriesOldContentForDiff`；`cubeQueryDefaultsToSqlOnlyAndOmitsFlagWhenExecuting`；`questionWriteRequiresPlanAndDoesNotLeakAcrossScopes`；`contextShowAndSkillsGetAssembleArgv`；`modelingScriptGuidesTheYamlFirstFlow`；`batchRuntimeWriteFailureRestoresPreviouslyWrittenFiles`；`retiredStructuredWriteToolsAreGone`；`singleQuestionPreflightRejectsEmptySqlBeforeHumanReview`；`previewFailsFastOnMalformedYamlBeforeValidation`；`decideRelationsAppliesMixedDecisionsAndRunsWorkspaceValidation`；`writeFileAutoInitializesWorkspaceSkeleton`；`previewRejectsPlatformFilesEvenWithValidContent`；`stateWithoutGroupAndAmbiguousScopeErrors`；`batchPlanRejectsProtectedDuplicateAndInvalidFilesWithoutWriting`；`previewWriteNewFilePassesGatesWithoutWriting`；`previewRejectsTraversalAndInvalidInputs`；`writeFileCreatesFileAfterStrictValidation`。
- 断言摘录：`assertTrue(writer.apply(GROUP, plan, base, true).startsWith("error:"));`。
- 断言摘录：`assertEquals("Original rule", read("knowledge/rules/existing.md"));`。

### J109 OfficialWrenCliIntegrationTest

- 源码：[OfficialWrenCliIntegrationTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenCliIntegrationTest.java)。保护：安装的官方CLI与平台适配器在隔离工程上对照。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`adapterAndDirectCliAgreeOnBuildQueryAndMemory`。
- 断言摘录：`assertThat(instructions.exitCode()).as(instructions.output()).isZero();`。
- 断言摘录：`assertThat(instructions.output()).contains("不能用样本代替", "不为补齐枚举");`。

### J110 OfficialWrenToolkitTest

- 源码：[OfficialWrenToolkitTest](../../src/test/java/io/agentscope/dataagent/tools/data/OfficialWrenToolkitTest.java)。保护：原生文件和CLI的权限、只读、路径、轮次和构建边界。
- 建议：保留现行回归。
- 实际方法：`autoModelingWithoutDocumentsCannotWriteRulesAndEveryWriteIsJournaled`；`repeatedQueriesReuseTheBindingUntilTheProfileChanges`；`dataQueriesAreCappedPerTurnAndTheNextTurnStartsFresh`；`registrationContainsOnlyNativeFilesAndOfficialCli`；`cliBindsPathsAndRejectsShellOverridesAndMutatingSql`；`unknownOptionErrorNamesTheAllowedOptions`；`proposalsComeFromTheCoachAndRefSqlOnlyFromAssetTurns`；`cubeIsRefusedWithoutRunningTheCli`；`queryPinsAuthorizedConnectionAndPreservesOfficialOutput`；`modelsDirectoryOnlyAcceptsOfficialModelFiles`；`compiledDraftIsStaleOnceAnyBuildInputChangesAfterIt`；`confirmationQuestionsAreCappedPerTurnAndScopedToTheProject`；`nativeRootGlobPreservesRootFilesAndHidesPlatformFiles`；`sharedDatabaseGateRejectsQueryAndMemoryBeforeCliExecution`；`genericPreviewNeverWritesOrRunsSemanticValidation`；`officialBuildUpdatesPublicationGuidanceAndFileChangesInvalidateIt`；`scopeCannotSelectAnotherOwnerOrMultipleProjects`；`toolDescriptionTellsTheModelToQueryModelNamesNotPhysicalTables`；`onlySourceWritingMemoryCommandsRequireCliConfirmation`；`basicModelingNeitherReadsNorWritesCubesAndViewsButPreservesThem`；`coachWritesQuestionsOnlyAndAwaitingQuestionsKeepEmptySql`；`brokenYamlIsRejectedAndTheOriginalFileIsKept`；`memoryStoreIsRefusedInPreflightAndNeverReachesTheWindowGate`；`cachedBindingDoesNotSkipTheAuthorizationCheck`；`mountedSkillsCloseTheCliSkillsChannel`；`queryOnAnUnbuiltDraftBuildsFirstAndAFailedBuildSkipsTheQuery`；`assetTurnOpensViewsOnlyAndKeepsEveryOtherBoundary`；`nativeFilesRejectTraversalProtectedPathsAndOverwrites`；`externalTablesCannotBeProbedOrRetriedOnOtherDatesAfterAnEmptyResult`；`cartesianJoinsAreRejectedButOrdinaryCommasAreNot`；`coachTurnReadsViewsButCannotWriteThem`。
- 断言摘录：`assertThat(adapter.execute(alice, List.of("query", "--sql", sql))) .contains("SQL_TIME_WINDOW_REJECTED");`。
- 断言摘录：`assertThat(adapter.execute(alice, List.of("dry-plan", "--sql", sql))) .doesNotContain("SQL_TIME_WINDOW_REJECTED");`。

### J111 QueryDataFilesTest

- 源码：[QueryDataFilesTest](../../src/test/java/io/agentscope/dataagent/tools/data/QueryDataFilesTest.java)。保护：查询结果精确CSV落地、大小限制和存储失败降级。
- 建议：保留现行回归。
- 实际方法：`ordinaryQueriesPersistExactCsvAndExposeDownloadWithoutStartingDocker`；`storageFailureDoesNotTurnASuccessfulQueryIntoAnExecutionError`；`oversizedResultsNeverWriteAFileOrStartDocker`。
- 断言摘录：`assertThat(note).contains("数据文件", "2 行 × 2 列", "`下载查询数据`（后续清理已移除，见阶段2报告）");`。
- 断言摘录：`assertThat(new String(bytes.getValue(), StandardCharsets.UTF_8)) .isEqualTo("部门,次数\n\"市场,经营部\",3\n,0\n");`。

### J112 RunPythonToolTest

- 源码：[RunPythonToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/RunPythonToolTest.java)。保护：确认后分析、禁止抄写大批行和脚本按需安装。
- 建议：保留现行回归。
- 实际方法：`analysisRequiresConfirmationBeforeStartingPython`；`analysisScriptsAreInstalledOnlyWhenTheCodeCallsThem`；`readingDataFilesAndSmallConstantsAreNotCountedAsCopiedRows`；`copiedResultRowsAreRefusedBeforeTouchingTheSandbox`；`dictRowsCountLikeListRows`。
- 断言摘录：`assertThat(new RunPythonTool(fs).runPython(rc, "print(1)", "预测")) .startsWith("error:") .contains("确认");`。
- 断言摘录：`assertThat(out).startsWith("error: 代码中写入了超过 20 行的数据字面量").contains("pd.read_csv");`。

### J113 TenantTablesFixture

- 源码：`TenantTablesFixture`（后续清理已移除，见阶段2报告）。保护：工具测试共用的两张隔离H2分析表夹具。
- 建议：测试辅助，保留。
- 无独立测试报告：辅助基类/夹具，按其使用方的用例验证。

### J114 WebSearchToolTest

- 源码：[WebSearchToolTest](../../src/test/java/io/agentscope/dataagent/tools/data/WebSearchToolTest.java)。保护：显式授权联网、本地HTTP替身和真实原生Agent调用。
- 建议：保留现行回归。
- 实际方法：`onlyTheQueryAgentSearchesAndOnlyWithAKey`；`failuresBecomeChineseNotesInsteadOfErrors`；`neverSendsSearchWithoutExplicitTurnPermission`；`longQueriesAreCutAndEmptyOnesNeverLeave`；`configuredSearchIsReportedAvailableOnlyWhenMountedOnTheQueryAgent`；`realAgentCallsTheSearchToolForAnAuthorizedTurnWithoutAKnowledgeBase`；`sendsOnlyTheSearchWordsAndFormatsResults`。
- 断言摘录：`assertThat(sent.fieldNames()) .toIterable() .containsExactlyInAnyOrder("query", "count", "need_content");`。
- 断言摘录：`assertThat(sent.path("count").asInt()).isEqualTo(2);`。

### J115 WrenMcpToolkitTest

- 源码：`WrenMcpToolkitTest`（后续清理已移除，见阶段2报告）。保护：未找到生产装配的旧wren_list_tools/wren_call_tool包装器。
- 建议：清理候选，未删除。未找到生产装配；主流程使用WrenScopedMcpClient直接注册官方schema。迁移记录/脱敏断言后再清理。
- 实际方法：`officialArgumentsPassThroughAndOnlySuccessfulBusinessCallsReceiveReceipts`；`nestedPlatformMetadataIsStrippedBeforeOfficialCallAndCreatesReceipt`。
- 断言摘录：`assertThat(tool.callTool(context, "g", "run_sql", args, null)) .doesNotContain("WREN_QUERY_RECEIPT");`。
- 断言摘录：`assertThat(tool.callTool(context, "g", "run_sql", args, "有多少客户")) .contains("WREN_QUERY_RECEIPT", "有多少客户");`。

### J116 WrenScopedMcpClientTest

- 源码：[WrenScopedMcpClientTest](../../src/test/java/io/agentscope/dataagent/tools/data/WrenScopedMcpClientTest.java)。保护：当前官方MCP schema、只读、数据范围、撤权、缓存和文件。
- 建议：保留现行回归。
- 实际方法：`distinctCountsAveragesAndRatiosAreNotTotalled`；`largeAnalysisResultsReachTheModelAsASummaryAndTheFileStaysComplete`；`completeMultiRowResultsCarryPlatformColumnTotals`；`databaseWindowRefusalPreventsMcpExecutionWithoutReadingMdl`；`duplicateSuccessfulQueryReusesPayloadWithoutAnotherReceiptAndStillChecksAccess`；`writeToolsAndMutatingSqlAreDeniedAndCloseDoesNotStopSharedEngine`；`truncatedAnalysisResultsProduceNoDataFile`；`queryRowsReachTheModelAsCompactArraysWithTheSameValues`；`queuedQueryCannotRunAfterAnotherDatasetRequiresModeling`；`successfulQueryHandsRowsToPythonAsADataFile`；`ordinaryQueriesKeepWhatTheModelAskedForAndAllRows`；`smallAnalysisResultsReachTheModelWhole`；`foreignScopeCannotDiscoverAndRevocationInvalidatesExistingClient`；`nativeRegistrationExposesOfficialSchemaAndExecutesWithoutEnvelope`；`separateClientsKeepProjectBindingsAndPreserveOfficialErrors`；`queuedCallRechecksRevocationBeforeExecution`；`failedQueriesAreNotCached`；`analysisTurnsQueryOnlyAfterConfirmationAndAreReceiptedAsAnalysis`；`analysisQueriesAlwaysAskForTheFullLimit`。
- 断言摘录：`assertThatThrownBy( () -&gt; client.callTool( "run_sql", Map.of("sql", "SELECT SUM(o.amount) FROM orders o")) .block()) .hasMessageContaining("SQL_TIME_WINDOW_REJECTED");`。
- 断言摘录：`assertThat(((McpSchema.TextContent) first.content().get(0)).text()) .contains("WREN_QUERY_RECEIPT");`。

### J117 WrenToolkitTest

- 源码：`WrenToolkitTest`（后续清理已移除，见阶段2报告）。保护：未找到生产装配的旧wren_run_sql/Cube包装器。
- 建议：清理候选，未删除。未找到生产装配；只被旧WrenMcpToolkit引用。须先核对数据文件断言由QueryDataFiles/WrenScopedMcpClient覆盖。
- 实际方法：`runSqlRejectsExplicitLimitOrOffsetClauses`；`oversizedResultSkipsHandoffWithHint`；`cubeRequiresMeasuresAndForwardsStructuredArguments`；`distinctEndTimeDimensionNormalizesGranularityCase`；`orderByTimeDimensionOutputAliasIsRewrittenToRawName`；`matchingPublishedViewAllowsDirectViewQuery`；`orderByRawAndNonMatchingMembersPassThroughUntouched`；`runSqlRejectsNonSelectAndNegativeLimit`；`cubeOrderByMemberRejectionCarriesRawNameGuidance`；`csvEscapesCommasQuotesAndNewlines`；`unrelatedBaseModelQueryIsNotBlockedByPublishedView`；`describeModelValidatesNamesWithoutLeakingUnknownModels`；`engineRejectionIsRelayedVerbatim`；`dirtyGroupServesPreviousPublishWithNote`；`sqlResultIsPersistedAsCsvAndReferenced`；`samePayloadReusesContentAddressedFileName`；`failedAndInitializingGroupsAreNotQueryableWithoutSnapshot`；`describeModelFoldsRelationProjectionFieldsByDefault`；`cubeRejectsNegativeOffset`；`unpublishedGroupReturnsGuidedError`；`cubeResultIsPersistedAsCsvAndReferenced`；`blankGroupIdAndMissingScopeAreGuided`；`handoffFailureDegradesToMarkdownOnlyResult`；`sameEndendTimeDimensionIsWidenedToOneGranularityUnit`；`describeModelGuidesViewAndCubeNamesToTheirTools`；`publishedGroupRunsSqlAndRendersRows`；`groupIdAcceptsKnowledgeBaseName`；`bakedRuntimeContextResolvesOwner`；`unexpectedPayloadShapeFallsBackToRawJson`；`legacyConstructorSkipsHandoff`；`describeModelReturnsPublishedFieldsRelationsAndCubes`；`conversationGroupFilterBlocksOtherGroups`；`toolDescriptionsCarryWrenOnlyGates`；`knowledgeBaseNameResolvesOnlyWithinVisibleGroups`；`foreignGroupIsIndistinguishableFromMissing`；`transportFailureBecomesErrorStringNotException`；`dataFilesAreScopedPerSessionDirectory`；`truncationAndEmptyResultsAreAnnotated`。
- 断言摘录：`assertThat(out).startsWith("error:");`。
- 断言摘录：`assertThat(out).contains("没有有效的已发布 MDL").contains("基础 MDL 初始化");`。

### J118 AgentDraftServiceTest

- 源码：[AgentDraftServiceTest](../../src/test/java/io/agentscope/dataagent/web/ai/AgentDraftServiceTest.java)。保护：绕过Agent中间件的直调模型转录日志。
- 建议：保留现行回归。
- 实际方法：`chatBlockingModelingTranscriptsToTheModelingLog`；`transcriptMatchesTheAgentMiddlewareRecordShape`；`chatBlockingRecordsPromptAndReplyInLlmTranscript`。
- 断言摘录：`assertThat(reply).isEqualTo("回复内容");`。
- 断言摘录：`assertThat(records(appender)) .containsExactly(record("USER", "请给出关系建议"), record("ASSISTANT", "回复内容"));`。

### J119 AdminBiSyncControllerTest

- 源码：[AdminBiSyncControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminBiSyncControllerTest.java)。保护：全局管理员只读同步状态、禁缓存和阻塞隔离。
- 建议：保留现行回归。
- 实际方法：`adminReadsWithoutCachingOnBoundedElasticAndNoWriteEndpointExists`；`onlyGlobalAdminCanReadAndDeniedRequestsNeverAccessDatabase`；`extraTaskRequiresGlobalAdminAndRunsReadOnlyOffEventLoop`。
- 断言摘录：`assertTrue(thread.get().startsWith("boundedElastic"));`。
- 断言摘录：`assertTrue(thread.get().startsWith("boundedElastic"));`。

### J120 AdminHistoryHttpTest

- 源码：[AdminHistoryHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminHistoryHttpTest.java)。保护：真实HTTP/JPA管理员历史、统一过滤、导出和权限撤销。
- 建议：保留，优先提速。
- 实际方法：`overviewUsesOneFilterForContentCountsUsersStablePagesAndExport`；`readsOnlySelectedUsersFinalRepliesAndRevokesPrivilegesOnExistingTokens`；`csvQuotesValuesAndNeutralizesFormulaPrefixes`；`legacyMessageProjectionBackfillsWithoutResurrectingDeletedOrChangedMessages`。
- 断言摘录：`assertThat(request("GET", base + path, ordinary).statusCode()).isEqualTo(403);`。
- 断言摘录：`assertThat(request("GET", base + path, null).statusCode()).isEqualTo(401);`。

### J121 AdminModelServiceControllerTest

- 源码：[AdminModelServiceControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AdminModelServiceControllerTest.java)。保护：HTTP断言DSL验证模型管理权限、禁缓存和安全错误。
- 建议：保留现行回归。采用WebTestClient.expectStatus/expectBody断言DSL；没有assertEquals并不等于没有断言。
- 实际方法：`administratorGetsNonCacheableView`；`unauthenticatedAndNonGlobalAdminsAreDeniedEveryEndpointBeforeRegistryAccess`；`rejectedConfigurationReturnsActionableSafeReason`。
- 断言摘录：`WebTestClient.expectStatus()/expectBody()：按HTTP结果断言`。

### J122 AnswerFeedbackControllerTest

- 源码：[AnswerFeedbackControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/AnswerFeedbackControllerTest.java)。保护：点赞拒绝别人会话以及建模Agent写反馈。
- 建议：保留现行回归。
- 实际方法：`modelingAgentCannotWriteAnswerFeedback`；`anotherUsersSessionIsRejectedBeforeReadingOrSavingQueries`。
- 断言摘录：`assertThrows( ResponseStatusException.class, () -&gt; controller .feedback( "data-agent", "foreign", "answer", new AnswerFeedbackController.Request("UP"), auth) .block());`。
- 断言摘录：`assertThrows( ResponseStatusException.class, () -&gt; controller.status("data-agent", "foreign", "answer", auth).block());`。

### J123 BusinessTermControllerTest

- 源码：[BusinessTermControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/BusinessTermControllerTest.java)。保护：术语增量请求未带delete字段时正确绑定。
- 建议：保留现行回归。
- 实际方法：`changeBindsWithoutDeleteFlag`。
- 断言摘录：`assertEquals("净增用户", change.term());`。
- 断言摘录：`assertNull(change.delete());`。

### J124 ChartOwnershipHttpTest

- 源码：[ChartOwnershipHttpTest](../../src/test/java/io/agentscope/dataagent/web/api/ChartOwnershipHttpTest.java)。保护：真实HTTP/JPA私有图表归属和精确账号比较。
- 建议：保留，优先提速。
- 实际方法：`exactOwnerCheckDoesNotDependOnDatabaseCollation`；`legacyAndForeignMalformedChartsAreNotParsedOrExposed`；`sharedToolkitConcurrentCallsPersistIndependentOwners`；`actualHttpEnforcesOwnerAndPreservesPayloadAfterPersistenceReload`；`missingIdentityNeverSavesOrLeaksIntoToolSchema`。
- 断言摘录：`assertThat(charts.findById(id).orElseThrow().getOwnerId()).isEqualTo("alice");`。
- 断言摘录：`assertThat(own.statusCode()).isEqualTo(200);`。

### J125 ChatControllerAnalysisTest

- 源码：[ChatControllerAnalysisTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerAnalysisTest.java)。保护：聊天请求中的分析选项、参数和新手进度。
- 建议：保留现行回归。
- 实际方法：`confirmedParametersAreKeptAsJson`；`onboardingMilestonesNeedAKnowledgeBaseAndAConfirmedAnalysis`；`requestsWithoutAnalysisFieldsStillBind`；`onlyBuiltInMethodsBecomeAnAnalysisTurn`。
- 断言摘录：`assertThat(ChatController.analysisTurn(request(null, null))).isNull();`。
- 断言摘录：`assertThat(ChatController.analysisTurn(request("usage", null))).isNull();`。

### J126 ChatControllerHitlTest

- 源码：[ChatControllerHitlTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerHitlTest.java)。保护：聊天流/确认/选库/权限/撤权/容量和会话作用域。
- 建议：保留现行回归。
- 实际方法：`allGroupsIsRejectedWithoutExpandingAuthorization`；`unauthorizedSendCannotConsumeQuestionCapacity`；`modelingRejectsNoneMultipleAndAllButAllowsOneManagedGroup`；`streamingDoneEchoesTheInboundRequestId`；`explicitSingleSelectionStillRechecksRevocationEachTurn`；`normalChatAcceptsOneGroupAndRejectsMultipleGroups`；`scopeIsNotSavedUntilTurnAcceptanceAndRevocationBeforeAcceptanceIsRejected`；`confirmationEventIsExposedAsHitlRequestFrame`；`ordinaryChatDoesNotExpandToUserKnowledgeBases`；`currentSessionRestoresPersistedAskingTool`；`webSearchPermissionIsBoundToTheQueryRequestAndDoesNotCarryOver`；`continuationPreservesOneLibraryAndRequiresReselectionForOldMultiScopes`；`confirmationRejectsChangedKnowledgeBaseScope`；`plainConversationCanSelectKnowledgeBasesAndRevokedSelectionFails`；`modelingConversationStillCannotSwitchItsKnowledgeBase`；`confirmationRejectsNullGroupIdAsBadRequest`；`streamingEntryBindsConfirmedAnalysisAndClearsItOnTheNextOrdinaryTurn`；`confirmationRejectsOversizedFeedbackAndFeedbackOnApproval`。
- 断言摘录：`assertThat(capture.getValue().method()).isEqualTo("forecast");`。
- 断言摘录：`assertThat(capture.getValue().confirmedParams()).contains("horizon", "7", "次数");`。

### J127 ChatControllerInboundShapeTest

- 源码：[ChatControllerInboundShapeTest](../../src/test/java/io/agentscope/dataagent/web/api/ChatControllerInboundShapeTest.java)。保护：传入消息只包装USER，不注入额外SYSTEM。
- 建议：保留现行回归。
- 实际方法：`neverInjectsSystemMessages`；`wrapsUserMessageOnly`。
- 断言摘录：`assertThat(msgs).hasSize(1);`。
- 断言摘录：`assertThat(msgs.get(0).getRole()).isEqualTo(MsgRole.USER);`。

### J128 DatasetControllerTest

- 源码：[DatasetControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetControllerTest.java)。保护：单个/批量上传、发布时机、部分成功和权限失败。
- 建议：保留现行回归。
- 实际方法：`permissionFailureDoesNotReadOrImportFile`；`deleteRepublishesOwningGroupOnce`；`batchFileCountAndDuplicateNamesRejectBeforeImport`；`batchUploadPublishesOnlyAfterAllFilesComplete`；`batchPublicationFailureKeepsEverySavedDataset`；`singleUploadPublishesBaselineOnce`；`publicationFailureReportsThatSavedDataIsNotQueryable`；`batchKeepsSuccessfulFileWhenAnotherFileFails`；`externalRowCountIsUnknownWhileUploadCountRemainsExact`。
- 断言摘录：`assertThat(DatasetController.toVOStatic(entity, List.of()).rowCount()).isNull();`。
- 断言摘录：`assertThat(DatasetController.toVOStatic(entity, List.of()).rowCount()).isEqualTo(123456L);`。

### J129 DatasetGroupControllerTest

- 源码：[DatasetGroupControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/DatasetGroupControllerTest.java)。保护：知识库详情投影、文档保存不触发旧分析和批量关联。
- 建议：保留现行回归。
- 实际方法：`documentUploadSavesSourceWithoutStartingLegacyAnalysis`；`tablesCanBeAssociatedOneByOneAndPublishedOnceAtTheEnd`；`associationFailureDoesNotAttemptBaselinePublication`；`associatePublishesBaselineOnceForTheWholeBatch`；`unauthorizedDetailDoesNotReadDatasetMetadata`；`anotherOwnersDocumentCannotBeSavedOrAnalyzed`；`queryAndManageUseTheSameDatasetProjectionWithoutExposingDraftKnowledge`。
- 断言摘录：`assertThat(reader.group().canManage()).isFalse();`。
- 断言摘录：`assertThat(reader.group().canQuery()).isTrue();`。

### J130 ExternalDataSourceConnectionTest

- 源码：[ExternalDataSourceConnectionTest](../../src/test/java/io/agentscope/dataagent/web/api/ExternalDataSourceConnectionTest.java)。保护：临时连接测试、凭据归属、公共演示来源和编辑边界。
- 建议：保留现行回归。
- 实际方法：`builtinSourceIsListedForEveryoneWithoutConnectionDetails`；`cannotReuseAnotherUsersSavedCredentials`；`unsupportedSourceIsRefusedBeforeItIsSaved`；`builtinSourceCannotBeEditedOrDeleted`；`editedSourceKeepsSavedPasswordWhenBlankAndNeverSavesCandidate`；`builtinOnlyExposesCurrentTablesAndLegacyIdentityIsNotShared`；`builtinSourceOnlyBrowsesTheDemoSchema`；`testsUnsavedParametersWithoutPersistingAndReturnsDatabaseError`；`builtinSourceIsHiddenWhenItIsNotAvailable`。
- 断言摘录：`assertThat(result.connected()).isFalse();`。
- 断言摘录：`assertThat(result.error()).contains("Host is not allowed");`。

### J131 QueryPreparationControllerTest

- 源码：[QueryPreparationControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/QueryPreparationControllerTest.java)。保护：预热状态的Agent权限和知识库授权。
- 建议：保留现行回归。
- 实际方法：`knowledgeBaseDenialDoesNotExposeItsPreparation`；`deniedAgentCannotReadPreparationState`。
- 断言摘录：`assertThatThrownBy( () -&gt; controller .status( "a", new UsernamePasswordAuthenticationToken("bob", "")) .block()) .isInstanceOf(ResponseStatusException.class);`。
- 断言摘录：`assertThatThrownBy( () -&gt; controller .status( "alice-group", new UsernamePasswordAuthenticationToken("bob", "")) .block()) .isInstanceOf(ResponseStatusException.class) .hasMessageContaining("403");`。

### J132 RecommendedQuestionControllerTest

- 源码：[RecommendedQuestionControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/RecommendedQuestionControllerTest.java)。保护：QUERY可读推荐问题，陌生人不可见。
- 建议：保留现行回归。
- 实际方法：`strangersCannotSeeIt`；`queryUsersReadTheQuestion`。
- 断言摘录：`assertThat(body.question()).isEqualTo("最近一个月每天的访问次数");`。
- 断言摘录：`assertThatThrownBy( () -&gt; controller .get( "g", new UsernamePasswordAuthenticationToken( "eve", null, java.util.List.of())) .block()) .isInstanceOf(ResponseStatusException.class) .hasMessageContaining("404");`。

### J133 SemanticModelingControllerTest

- 源码：[SemanticModelingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingControllerTest.java)。保护：建模API权限、发布投影、JSON结构和文件范围。
- 建议：保留现行回归。
- 实际方法：`workspaceFileRejectsPlatformRuntimeAndNonAssetPaths`；`overviewAssemblesGroupStateAndDatasets`；`proposalHttpResponsesKeepBusinessJsonWithJackson3`；`mdlEndpointsDelegateForOwnedGroup`；`mdlViewReturnsDraftAndChangedForOwnedGroup`；`cubeOverviewHttpResponseKeepsNestedArraysWithJackson3`；`enhanceEndpointsDelegateAndMapLatestTask`；`workspaceFileReadsEngineeringAssetForOwnedGroup`；`foreignGroupYields404OnEveryEndpoint`；`missingOrInvalidStoredJsonReturnsEmptyContainers`。
- 断言摘录：`assertThat(error).isInstanceOf(ResponseStatusException.class);`。
- 断言摘录：`assertThat(((ResponseStatusException) error).getStatusCode()) .isEqualTo(HttpStatus.NOT_FOUND);`。

### J134 SemanticModelingCubeRetirementTest

- 源码：[SemanticModelingCubeRetirementTest](../../src/test/java/io/agentscope/dataagent/web/api/SemanticModelingCubeRetirementTest.java)。保护：已退役Cube路由在查权限后返回Gone。
- 建议：保留现行回归。
- 实际方法：`retiredRoutesReturnGoneOnlyAfterCheckingKnowledgeBaseOwnership`。
- 断言摘录：`assertEquals( 410, assertThrows( ResponseStatusException.class, () -&gt; controller.suggestCubes("g", alice).block()) .getStatusCode() .value());`。
- 断言摘录：`assertThrows( ResponseStatusException.class, () -&gt; controller.suggestCubes("g", alice).block()) .getStatusCode() .value());`。

### J135 UserBindingControllerTest

- 源码：[UserBindingControllerTest](../../src/test/java/io/agentscope/dataagent/web/api/UserBindingControllerTest.java)。保护：当前用户的通道绑定CRUD、索引及输入校验。
- 建议：保留现行回归。
- 实际方法：`rejectsInvalidInputAndMissingIndexes`；`supportsUserScopedCrudWithFrontendContract`。
- 断言摘录：`assertThat(created.channelId()).isEqualTo("chatui");`。
- 断言摘录：`assertThat(created.displayLabel()).isEqualTo("My default session");`。

### J136 BiAccountKnowledgeHttpTest

- 源码：[BiAccountKnowledgeHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAccountKnowledgeHttpTest.java)。保护：真实HTTP/JPA的BI人员、共享/公共库和撤权。
- 建议：保留，优先提速。
- 实际方法：`accountEntryCreatesOrdinaryUserAndNeverAcceptsClientRoles`；`directoryPreviewAcceptsPayloadBeyondDefaultCodecBuffer`；`readableHttpProjectionUsesPublishedJsonAndRejectsUnassignedUsers`；`failedBatchDoesNotLeavePartialGrants`；`publicAccessIsDynamicReadOnlyAndRevokedOnNextCheck`；`importsAreIdempotentPreserveRolesAndSupportDuplicateNamesAndFilters`。
- 断言摘录：`assertThat(response.statusCode()).isEqualTo(200);`。
- 断言摘录：`assertThat(response.statusCode()).isEqualTo(200);`。

### J137 BiAuthHttpTest

- 源码：[BiAuthHttpTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiAuthHttpTest.java)。保护：真实HTTP的BI登录策略、错误和关闭旧任务端点。
- 建议：保留，优先提速。
- 实际方法：`prodEntryPolicyAndActualHttpAuthentication`；`productionValidationResponsesKeepTheActionableReasonWithoutStackTraces`；`scheduledTaskEndpointsAreClosedForEveryRole`。
- 断言摘录：`assertThat( client.send( request.build(), HttpResponse.BodyHandlers.ofString()) .statusCode()) .isEqualTo(403);`。
- 断言摘录：`assertThat(response.statusCode()).isEqualTo(401);`。

### J138 BiTicketCodecTest

- 源码：[BiTicketCodecTest](../../src/test/java/io/agentscope/dataagent/web/auth/BiTicketCodecTest.java)。保护：BI票据有效期、篡改、浏览器互通和登录策略。
- 建议：保留现行回归。
- 实际方法：`productionPolicyAndJwtExpiry`；`developmentPasswordLoginRemainsAvailable`；`decodesAndEnforcesTimeBounds`；`rejectsMalformedTamperedAndInjectedClaims`；`browserModuleVectorIsInteroperable`。
- 断言摘录：`assertThat(codec.decode(ticket("liyufanwx", NOW, id)).username()).isEqualTo("liyufanwx");`。
- 断言摘录：`assertThat(codec.decode(ticket("liyufanwx", NOW - 60000, id)).issuedAt()) .isEqualTo(NOW - 60000);`。

### J139 JwtSessionExpiryTest

- 源码：[JwtSessionExpiryTest](../../src/test/java/io/agentscope/dataagent/web/auth/JwtSessionExpiryTest.java)。保护：24小时固定令牌截止时间与旧令牌兼容。
- 建议：保留现行回归。
- 实际方法：`previouslyIssuedTokensKeepTheirSignedDeadline`；`useDoesNotMoveTheSignedDeadlineAndTheTokenExpiresAt24Hours`。
- 断言摘录：`assertThat(claims.getIssuedAt().toInstant()).isEqualTo(SIGN_IN);`。
- 断言摘录：`assertThat(claims.getExpiration().toInstant()).isEqualTo(SIGN_IN.plusSeconds(86_400));`。

### J140 ChatCapacityTest

- 源码：[ChatCapacityTest](../../src/test/java/io/agentscope/dataagent/web/chat/ChatCapacityTest.java)。保护：真实并发准入、用户公平、排队取消和超时释放。
- 建议：保留现行回归。
- 实际方法：`preventsOneUserAndOneConversationFromTakingAllSlots`；`cancelledPythonRetainsBudgetUntilRealOperationEnds`；`executionDeadlineEndsWorkAndReleasesBudget`；`racingSubscriptionAndCancelLeaveNoSlotsOrConversationKeys`；`queueTimeoutDoesNotConsumeExecutionBudget`；`waitingCancelNeverStartsWorkAndFreesConversation`；`admitsSixteenActualUsersAndBoundsQueue`。
- 断言摘录：`assertThat(capacity.activeCount()).isEqualTo(16);`。
- 断言摘录：`assertThat(capacity.waitingCount()).isEqualTo(16);`。

### J141 AgentStateConfigTest

- 源码：[AgentStateConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateConfigTest.java)。保护：框架状态持久化、用户/库隔离、重建和不支持存储失败。
- 建议：保留现行回归。
- 实际方法：`h2ReloadsLatestSnapshotAndSeparatesUsersAndDatabases`；`rebuiltAgentReceivesPersistedConversationWithoutFrontendHistoryReplay`；`unsupportedDatabaseFailsInsteadOfSilentlyDiscardingState`；`productionMissingSchemaFailsWithoutAttemptingToCreateIt`；`explicitFrameworkStoreOverridesDefaultWithoutRequiringDataSource`；`mysqlRequiresSelectedDatabase`。
- 断言摘录：`assertThat(first).isInstanceOf(JsonFileAgentStateStore.class);`。
- 断言摘录：`assertThat( reloaded.get("alice", "same", "agent_state", AgentState.class) .orElseThrow() .getContext() .get(0) .getTextContent()) .isEqualTo("latest");`。

### J142 AgentStateMysqlIntegrationTest

- 源码：[AgentStateMysqlIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/config/AgentStateMysqlIntegrationTest.java)。保护：真实MySQL状态版本/命名空间/并发与背景数据性能。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`tenConcurrentOwnersAndSessionsNeverReadEachOthersState`；`actualMysqlKeepsLatestSnapshotAndChecksVersionsAndExactNamespaces`；`indexedReadWriteMeasurementsWithBackgroundRowsAndDifferentPayloadSizes`。
- 断言摘录：`assertThat(store).isInstanceOf(MysqlAgentStateStore.class);`。
- 断言摘录：`assertThat(v1).isEqualTo(1);`。

### J143 DataAgentConfigTest

- 源码：[DataAgentConfigTest](../../src/test/java/io/agentscope/dataagent/web/config/DataAgentConfigTest.java)。保护：装配和工具边界，以及大量提示文字精确断言。
- 建议：保留核心，改写文本/结构断言。装配/工具权限断言有效；大量中文句子contains/doesNotContain可随提示编辑失效，建议拆出结构契约及行为评测。
- 实际方法：`modelTransportDefaultsToHttp11AndAllowsExplicitProtocolOverride`；`defaultSysPromptDoesNotRestateSkillLevelDetail`；`defaultSysPromptPointsAtWrenOnlyWorkflow`；`defaultSysPromptKeepsAlwaysOnBehaviourGates`；`eachAgentListsOnlyTheOfficialSkillsItUses`；`questionAnswersLeadWithTheConclusion`；`queryVisualizationPrefersPythonWithEchartsFallback`；`defaultSysPromptUsesRelevantExamplesSqlAndCompleteFinalAnswer`；`chatUiAlwaysUsesConversationScopedSessions`；`defaultSysPromptRequiresCountConsistency`；`builtInWrenAgentsCannotInheritUnscopedWorkspaceMcpServers`；`customKnowledgeRetrievalIsNotAnAgentTool`；`physicalQueryToolsAreAbsentFromAgentToolkits`；`queryAgentUsesPlatformPromptWithoutLegacyWorkspaceContext`；`modelingPromptMatchesTheOfficialCliContractAndSourceTiers`；`confirmationsDoNotRecommendGuesses`；`conversationScopedChatUiConfigIsKeptAsIs`；`wrenToolDescriptionsRequirePublishedLogicalModels`；`queryPromptKeepsWebSearchToExternalInformation`；`sqlIsMysqlEightWithLiteralDateRanges`。
- 断言摘录：`assertThat(actual.getMcpServers()).isEmpty();`。
- 断言摘录：`assertThat(actual.getDeny()).containsExactly("execute_shell_command");`。

### J144 EmptyOrdersQueryFixture

- 源码：[EmptyOrdersQueryFixture](../../src/test/java/io/agentscope/dataagent/web/config/EmptyOrdersQueryFixture.java)。保护：真实空数据库SQL聚合语义的模型评估夹具。
- 建议：测试辅助，保留。
- 无独立测试报告：辅助基类/夹具，按其使用方的用例验证。

### J145 EmptyOrdersQueryFixtureTest

- 源码：[EmptyOrdersQueryFixtureTest](../../src/test/java/io/agentscope/dataagent/web/config/EmptyOrdersQueryFixtureTest.java)。保护：模型评估夹具保持空聚合/分组/时间类型的真实语义。
- 建议：保留现行回归。保护真实模型评测的参考夹具；删除会削弱评测可信性，并非生产功能缺失。
- 实际方法：`emptyTableAggregateHasOneRowWithZeroCountAndNullRevenue`；`typedDatesAndDifferentModelsKeepTheirActualResultShape`；`emptyGroupedQueryHasNoRows`。
- 断言摘录：`assertThat(rows).hasSize(1);`。
- 断言摘录：`assertThat(rows.get(0)).containsEntry("order_cnt", 0).containsEntry("revenue", null);`。

### J146 QueryDateBehaviorLiveTest

- 源码：[QueryDateBehaviorLiveTest](../../src/test/java/io/agentscope/dataagent/web/config/QueryDateBehaviorLiveTest.java)。保护：真实付费模型日期澄清、历史续问和空结果行为。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`asksBeforeSqlResumesFromHistoryAndAcceptsEmptyResults`。
- 断言摘录：`assertThat(tools.sql).isEmpty();`。
- 断言摘录：`assertThat(tools.recalls).isZero();`。

### J147 SqlWindowLiveBoundaryTest

- 源码：[SqlWindowLiveBoundaryTest](../../src/test/java/io/agentscope/dataagent/web/config/SqlWindowLiveBoundaryTest.java)。保护：真实付费模型经两个适配器执行日期边界判断。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`realModelAcceptsAndRejectsBeforeBothExecutionAdapters`。
- 断言摘录：`assertThat(result.contains("SQL_TIME_WINDOW_REJECTED")) .as(scenario.name() + adapter) .isEqualTo(!scenario.allowed());`。
- 断言摘录：`assertThat(tools.execute(ctx, List.of("query", "--sql", "SELECT 1"))) .contains("SQL_TIME_WINDOW_REJECTED");`。

### J148 WrenLiveModelingEvaluationTest

- 源码：[WrenLiveModelingEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveModelingEvaluationTest.java)。保护：真实模型、原生确认和官方技能建模行为。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`enrichDocumentAndValidateOfficialAssetsWithNativeConfirmation`。
- 断言摘录：`assertThat(commands.execute(ctx, List.of("context", "validate", "--strict"))) .contains("退出码：0");`。
- 断言摘录：`assertThat(commands.execute(ctx, List.of("context", "build"))).contains("退出码：0");`。

### J149 WrenLiveQueryEvaluationTest

- 源码：[WrenLiveQueryEvaluationTest](../../src/test/java/io/agentscope/dataagent/web/config/WrenLiveQueryEvaluationTest.java)。保护：真实模型与官方MCP生成SQL对照独立参考答案。
- 建议：保留，条件专项/混合。条件跳过未计为通过；使用独立夹具运行相关真实环境验收。
- 实际方法：`evaluateGeneratedQueriesAgainstIndependentReference`。
- 断言摘录：`assertThat(reports).hasSize(count);`。
- 断言摘录：`assertThat(reports).allSatisfy(report -&gt; assertThat(report).doesNotContainKey("errorType"));`。

### J150 MarketContributionServiceTest

- 源码：[MarketContributionServiceTest](../../src/test/java/io/agentscope/dataagent/web/marketplace/MarketContributionServiceTest.java)。保护：真实JPA及文件的贡献提交/批准/拒绝一致性。
- 建议：保留，优先提速。现行贡献/审批接口仍使用服务；可收窄至JPA+文件，保留至少一项完整启动验证。
- 实际方法：`submitInsertsPendingRow`；`approveSkillMaterializesSkillMdUnderSharedRoot`；`rejectTransitionsWithoutFilesystemWrite`；`submitRejectsInvalidInputBeforePersisting`；`approveOnMissingIdThrows`；`terminalStatusesAreNotRetransitionable`；`approveSubagentMaterializesAtLiteralPath`。
- 断言摘录：`assertThat(submitted.getId()).isNotNull();`。
- 断言摘录：`assertThat(submitted.getStatus()).isEqualTo(ContributionEntity.STATUS_PENDING);`。

### J151 DebugLoggingMiddlewareTest

- 源码：[DebugLoggingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddlewareTest.java)。保护：对话转录、工具参数、媒体占位和消息去重。
- 建议：保留现行回归。
- 实际方法：`decodesJsonEncodedToolResults`；`rendersToolUseArgumentsAsReadableLines`；`routesRecordsToTheConfiguredTranscriptLogger`；`rendersExactlyRoleTypeAndText`；`replacesMediaPayloadsWithPlaceholder`；`writesEachMessageOncePerSession`。
- 断言摘录：`assertThat(rendered).isEqualTo("role: SYSTEM\ntype: text\ntext:\n# 角色\n\n你是数据分析助手\n");`。
- 断言摘录：`assertThat(rendered).doesNotContain(NOISE.toArray(String[]::new));`。

### J152 ExecutionTimingMiddlewareTest

- 源码：[ExecutionTimingMiddlewareTest](../../src/test/java/io/agentscope/dataagent/runtime/observability/ExecutionTimingMiddlewareTest.java)。保护：模型和工具错误有对应结束耗时事件。
- 建议：保留现行回归。
- 实际方法：`modelAndToolErrorsHaveCorrelatedTerminalTimings`。
- 断言摘录：`assertThat(records).anyMatch(s -&gt; s.contains("model.first_output"));`。
- 断言摘录：`assertThat(records).anyMatch(s -&gt; s.contains("completion.model.tail"));`。

### J153 ModelingHitlMiddlewareTest

- 源码：[ModelingHitlMiddlewareTest](../../src/test/java/io/agentscope/dataagent/web/middleware/ModelingHitlMiddlewareTest.java)。保护：建模原生确认/技术修复/批量失败隔离和旧工具边界。
- 建议：保留现行回归。
- 实际方法：`officialBuildRunsWithoutConfirmationButMemoryStorePausesAndResumes`；`allowedMutationContinuesAfterNativeConfirmation`；`pausesBeforeFirstMutationAndPersistsAskingState`；`repairFeedbackRequiresCorrelatedNativeDenialAndIsCallScoped`；`relationDecisionToolStillRequiresConfirmation`；`oneInvalidWriteDoesNotPoisonAnotherWriteInSameBatch`；`nativeReactLoopRepairsTechnicalFailureBeforeFirstHumanPrompt`；`failedPreflightProducesRecoverableErrorWithoutHumanConfirmation`；`cubeProposalReferenceIsAnsweredWithAnErrorAndNeverLoaded`；`everyDraftFileWriteGoesStraightToTheDraftWithoutACard`；`absoluteProjectModelPathIsTechnicalAndSkipsConfirmation`；`recomputeOnlySuggestRelationsLeavesTheGate`；`unexpectedPreflightFailureAlsoReturnsErrorAndCorrectedPlanCanAsk`；`gateCoversFileWritesAndRelationQueueButNotRetiredStructuredWrites`；`readOnlyToolContinuesWithoutConfirmation`；`nativeDeniedCallDoesNotOpenTheSameConfirmationAgain`。
- 断言摘录：`assertThat(events) .filteredOn(RequireUserConfirmEvent.class::isInstance) .singleElement() .satisfies( event -&gt; assertThat(((RequireUserConfirmEvent) event).getToolCalls()) .extracting(ToolUseBlock::getId) .con…`。
- 断言摘录：`assertThat(((RequireUserConfirmEvent) event).getToolCalls()) .extracting(ToolUseBlock::getId) .containsExactly("good"));`。

### J154 OnboardingControllerTest

- 源码：[OnboardingControllerTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingControllerTest.java)。保护：新手接口只作用于当前用户并校验提示编号。
- 建议：保留现行回归。
- 实际方法：`everyEndpointUsesTheCurrentUser`；`anUnknownTipIsABadRequest`。
- 断言摘录：`assertThatThrownBy(() -&gt; controller.tip("x", bob).block()) .isInstanceOf(ResponseStatusException.class) .hasMessageContaining("400");`。

### J155 OnboardingServiceTest

- 源码：[OnboardingServiceTest](../../src/test/java/io/agentscope/dataagent/web/onboarding/OnboardingServiceTest.java)。保护：四步引导、重置、日期阶段和自有资源计数。
- 建议：保留现行回归。
- 实际方法：`tourPositionIsSavedForTheCallerAndClearedWhenItEnds`；`tipsAreRememberedAndUnknownTipsRejected`；`aNewUserStartsWithTheFullChecklist`；`uploadedOrExternalDataCompletesTheFirstStep`；`unknownUsersAreRejected`；`stagesFollowCalendarDaysInShanghai`；`milestonesAreRecordedOnceAndUnknownOnesIgnored`；`resetStartsOverAndOnlyNewKnowledgeBasesCount`；`malformedTourPositionIsRejected`；`someoneElsesPublishedKnowledgeBaseKeepsFourIncompleteSteps`；`recordingATipBeforeFirstStatusDoesNotRequireAStartTimestamp`；`finishingEveryStepKeepsTheChecklistUntilTheDayEnds`；`ownKnowledgeBaseKeepsFourStepsEvenWhenAPublicOneIsPublished`；`anotherUsersKnowledgeBaseDoesNotCount`；`resetWithOnlyASharedKnowledgeBaseShowsAllFourStepsAgain`；`dismissHidesAndReopenRestartsTheSevenDays`；`completedUserCanReopenOnFollowingDayWithoutLosingProgress`。
- 断言摘录：`assertThat(status.stage()).isEqualTo(Stage.full);`。
- 断言摘录：`assertThat(done(status)).containsExactly(false, false, false, false);`。

### J156 AgentVisibilityQueryTest

- 源码：[AgentVisibilityQueryTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/AgentVisibilityQueryTest.java)。保护：真实JPA过滤自有/直接/工作区授权及撤销。
- 建议：保留，优先提速。
- 实际方法：`filtersOwnDirectWorkspaceAndRevokedGrantsInTheDatabase`。
- 断言摘录：`assertThat(agents.findVisible("alice", null)) .extracting(AgentEntity::getAgentId) .containsExactlyInAnyOrder("own", "shared", "workspace");`。
- 断言摘录：`assertThat(agents.findVisible("alice", "shared")).hasSize(1);`。

### J157 BiLoginPersistenceTest

- 源码：[BiLoginPersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/BiLoginPersistenceTest.java)。保护：真实JPA票据防重放、保留角色和并发首次登录。
- 建议：保留，优先提速。
- 实际方法：`concurrentFirstLoginsShareOneOrdinaryUser`；`preservesRolesAndDurablyRejectsReplayAcrossServiceInstances`。
- 断言摘录：`assertThat(logged.userId()).isEqualTo(old.userId());`。
- 断言摘录：`assertThat(logged.roles()).contains("admin");`。

### J158 DatasetGroupMdlBackfillTest

- 源码：[DatasetGroupMdlBackfillTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/DatasetGroupMdlBackfillTest.java)。保护：旧NULL修订迁移和共享/公共知识库查询分页。
- 建议：保留，优先提速。迁移与列表查询有价值；旧NULL报错用例说明迁移前置条件，不应固化为期望产品故障。
- 实际方法：`backfillRestoresLegacyRows`；`nullMdlVersionBreaksEntityLoad`；`modelingListPaginatesAfterSearchAccessAndDeletionFilters`；`newestQueryableCandidatesIncludeSharedAndPublicButSkipDraftsAndPrivateGroups`。
- 断言摘录：`assertThatThrownBy(() -&gt; repository.findById("legacy-null")) .isInstanceOf(JpaSystemException.class);`。
- 断言摘录：`assertThat(reloaded.getMdlVersion()).isZero();`。

### J159 H2DemoSeedTest

- 源码：[H2DemoSeedTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/H2DemoSeedTest.java)。保护：显式开发模式下文件H2种子SQL与密码初始化。
- 建议：保留，优先提速。本地H2模式仍受支持；保留在H2/初始化相关验证，优先缩小上下文而非作为每次小修改的必跑项。
- 实际方法：`bobAndAliceSeededWithMatchingPasswords`。
- 断言摘录：`assertThat(bob.roles()).containsExactly("user");`。
- 断言摘录：`assertThat(alice.roles()).containsExactly("user");`。

### J160 JpaPersistenceIntegrationTest

- 源码：[JpaPersistenceIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceIntegrationTest.java)。保护：完整应用真实JPA账号、Agent定义与分享往返。
- 建议：保留，优先提速。账号/Agent持久化和真实装配仍有效；与其他上下文整合前保证独立库/文件和数据清理。
- 实际方法：`agentDefinitionRoundTripsAndCarriesWorkspacePath`；`jpaBeansAreWiredByDefault`；`userCrudRoundTripsThroughJpa`；`explicitDevelopmentSeedCreatesAdminOnEmptyDatabase`。
- 断言摘录：`assertThat(userStore).isInstanceOf(JpaUserStore.class);`。
- 断言摘录：`assertThat(agentStore).isInstanceOf(JpaUserAgentDefinitionStore.class);`。

### J161 JpaUserBindingStoreTest

- 源码：[JpaUserBindingStoreTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaUserBindingStoreTest.java)。保护：真实JPA有序持久化通道绑定。
- 建议：保留，优先提速。
- 实际方法：`persistsOrderedBindingsOnUserRow`。
- 断言摘录：`assertThat(store.list("alice")).containsExactly(first, second);`。
- 断言摘录：`assertThat(store.update("alice", 0, second)).contains(second);`。

### J162 ModelServicePersistenceTest

- 源码：[ModelServicePersistenceTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/ModelServicePersistenceTest.java)。保护：模型加密配置经真实数据库重建后仍可读取。
- 建议：保留，优先提速。
- 实际方法：`encryptedConfigurationSurvivesRegistryRecreationAndDatabaseRoundTrip`。
- 断言摘录：`assertThat(row.getEncryptedPayload()) .doesNotContain("synthetic-test-key", "persistent fixture");`。
- 断言摘录：`assertThat(restarted.view().services()) .singleElement() .satisfies(p -&gt; assertThat(p.id()).isEqualTo(profile.id()));`。

### J163 WorkspaceScaffolderTest

- 源码：[WorkspaceScaffolderTest](../../src/test/java/io/agentscope/dataagent/web/scaffold/WorkspaceScaffolderTest.java)。保护：工作区初始化结构与保留既有文件。
- 建议：保留现行回归。
- 实际方法：`doesNotOverwriteExistingFiles`；`writesExpectedLayout`。
- 断言摘录：`assertThat(tmp.resolve("AGENTS.md")).exists();`。
- 断言摘录：`assertThat(tmp.resolve("tools.json")).exists();`。

### J164 ConversationScopeRegistryTest

- 源码：[ConversationScopeRegistryTest](../../src/test/java/io/agentscope/dataagent/web/session/ConversationScopeRegistryTest.java)。保护：用户/会话选库持久化、轮次快照和并发原子写。
- 建议：保留现行回归。
- 实际方法：`persistedSelectionAndAllModeSurviveRestartAndCanChangeNextTurn`；`modelingPurposeIsPerUserConversationAndClearedByTheNextPlainRequest`；`sameSessionNameIsIsolatedByUserAndTracksLatestAcceptedTurn`；`concurrentSnapshotsAreWrittenAtomically`；`perMessageSnapshotRetainsNamesAfterConversationSelectionChanges`。
- 断言摘录：`assertThat(turns.get(0).knowledgeScope().groupIds()).containsExactly("g1");`。
- 断言摘录：`assertThat(turns.get(0).knowledgeScope().groupNames()).containsEntry("g1", "销售库");`。

### J165 SessionTurnParserTest

- 源码：[SessionTurnParserTest](../../src/test/java/io/agentscope/dataagent/web/session/SessionTurnParserTest.java)。保护：持久化工具结果的JSON字符串封套解析。
- 建议：保留现行回归。
- 实际方法：`unwrapsJsonStringEnvelopeFromPersistedToolResult`。
- 断言摘录：`assertEquals(receipt, turns.get(0).toolResult());`。

### J166 RequestEventIsolationTest

- 源码：[RequestEventIsolationTest](../../src/test/java/io/agentscope/dataagent/web/toolbus/RequestEventIsolationTest.java)。保护：同一会话不同轮次的工具事件隔离。
- 建议：保留现行回归。
- 实际方法：`sameSessionDifferentTurnsDoNotReceiveEachOthersEvents`。
- 断言摘录：`assertThat(received).extracting(ToolEventBus.ToolEvent::toolCallId).containsExactly("own");`。

### J167 SharedSkillContentTest

- 源码：[SharedSkillContentTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedSkillContentTest.java)。保护：打包技能正文和旧工具名/提示词精确约定。
- 建议：保留核心，改写文本/结构断言。保留资源完整性和禁用工具名；旧技能正文精确措辞应核对实际挂载范围，不代表真实Agent行为。
- 实际方法：`pythonAnalysisReadsQueryDataFilesInsteadOfCopyingRows`；`skillsDoNotReferenceRetiredToolNames`；`sqlAnalysisRejectsPhysicalFallback`；`pythonAnalysisKeepsCurrentToolNamesAndChineseLabelRule`；`skillsUseWrenOnlyChannel`；`sqlAnalysisCarriesModelDiscoveryHowTo`；`sqlAnalysisKeepsAntiProbeHowTo`；`everyShippedSkillIsNonEmptyAndHasFrontmatter`；`chartRenderingPointsAtEvidenceRetrieval`；`sqlAnalysisUsesSqlAndRelevantExamples`；`sqlAnalysisCarriesCrossTableDedupRules`；`sqlAnalysisGatesDelegationOnToolAvailability`。
- 断言摘录：`assertThat(body).as("%s must not be empty", path).isNotBlank();`。
- 断言摘录：`assertThat(body).as("%s must start with frontmatter", path).startsWith("---");`。

### J168 SharedWorkspaceSeederTest

- 源码：[SharedWorkspaceSeederTest](../../src/test/java/io/agentscope/dataagent/web/workspace/SharedWorkspaceSeederTest.java)。保护：资源种子幂等、升级、修补与保留人工/市场文件。
- 建议：保留现行回归。
- 实际方法：`upgradesShippedFileThatWasNotHandEdited`；`keepsOperatorHandEdit`；`healsZeroByteSkillFile`；`seedsEveryShippedFileAndWritesManifest`；`secondPassChangesNothing`；`keepsUnknownFileAtShippedPathWhenManifestAlreadyExists`；`adoptsDriftedFilesOnFirstRunWithNoManifest`；`neverTouchesMarketplaceContribution`。
- 断言摘录：`assertThat(result.copied()).isGreaterThan(0);`。
- 断言摘录：`assertThat(result.total()).isEqualTo(result.copied());`。

### J169 UserControlFilesystemTest

- 源码：[UserControlFilesystemTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserControlFilesystemTest.java)。保护：用户文件命名空间路径与根目录边界。
- 建议：保留现行回归。
- 实际方法：`absoluteRouteSuffixAndRootListingStayInsideUserNamespace`。
- 断言摘录：`assertThat(fs.write(alice, "/agent/sessions/a.jsonl", "alice only").isSuccess()).isTrue();`。
- 断言摘录：`assertThat(fs.read(bob, "/agent/sessions/a.jsonl", 0, 0).isSuccess()).isFalse();`。

### J170 UserSandboxCapacityTest

- 源码：[UserSandboxCapacityTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxCapacityTest.java)。保护：沙箱预算、闲置驱逐与运行中保护。
- 建议：保留现行回归。
- 实际方法：`manySequentialUsersShareAContainerBudget`；`reclaimsIdleUsersButNeverAnActiveOperation`。
- 断言摘录：`assertThatThrownBy(() -&gt; registry.acquire("c", "agent")).hasMessageContaining("繁忙");`。
- 断言摘录：`assertThat(c.sandbox()).isSameAs(third);`。

### J171 UserSandboxDockerReadinessTest

- 源码：[UserSandboxDockerReadinessTest](../../src/test/java/io/agentscope/dataagent/web/workspace/UserSandboxDockerReadinessTest.java)。保护：进程边界故障、错误区分/重试，含条件30秒超时。
- 建议：保留，条件专项/混合。15项中1项真实30秒超时条件跳过；其余进程替身用例无需Docker且应保留默认运行。
- 实际方法：`realHangingProcessReachesTheActualThirtySecondTimeout`；`recoveryRetriesWithoutRestartingTheRegistryOrLazySandbox`；`realProcessLaunchReportsMissingExecutable`；`aMissingMemoryFileHasANormalNotFoundResultAfterReadinessSucceeds`；`realNonzeroChildProcessPreservesItsErrorOutput`；`differentFailuresProduceTheSameMemoryReadErrorButDistinctWarnings`；`daemonFailureAfterSuccessfulStartupIsNotThisReadinessException`。
- 断言摘录：`assertThat(read.isSuccess()).isFalse();`。
- 断言摘录：`assertThat(read.error()).contains(MESSAGE);`。

## 前端22个文件

上一阶段每个部署前缀执行105项，共三个前缀。没有默认浏览器DOM/E2E运行器；SSR断言不能证明按钮点击、布局和副作用执行正确。三个前缀的重复执行是在验证真实部署兼容，并非105项不同业务测试重复了三份源码。

| 文件 | 当前保护内容 | 关联代码 | 项数/前缀 | 建议 |
| --- | --- | --- | --- | --- |
| [analysis.test](../../frontend/tests/analysis.test.mjs) | 分析建议的数据阈值、标量/日期边界及只读编辑器 | [analysisSuggestions](../../frontend/src/utils/analysisSuggestions.ts), [SqlEditor](../../frontend/src/components/SqlEditor.tsx) | 7 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [answer-display.test](../../frontend/tests/answer-display.test.mjs) | 答案引用、结果下载和图表/图片区分 | [answerRefs](../../frontend/src/components/answerRefs.ts) | 3 | 保留；接口/解析/状态机行为回归 |
| [auth-session.test](../../frontend/tests/auth-session.test.mjs) | 登录失效、并发401、跨账号、Request语义和部署前缀 | [auth](../../frontend/src/api/auth.ts), [paths](../../frontend/src/api/paths.ts) | 13 | 保留；接口/解析/状态机行为回归 |
| [bi-account.test](../../frontend/tests/bi-account.test.mjs) | BI入口读取账号而非显示名 | [biAccount](../../frontend/src/utils/biAccount.ts) | 1 | 保留；接口/解析/状态机行为回归 |
| [bi-sync.test](../../frontend/tests/bi-sync.test.mjs) | 同步状态、空初始化、缺口和安全错误渲染 | [BiSyncStatusPanel](../../frontend/src/components/BiSyncStatusPanel.tsx), [BiExtraSyncStatusPanel](../../frontend/src/components/BiExtraSyncStatusPanel.tsx) | 4 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [calculated-expression.test](../../frontend/tests/calculated-expression.test.mjs) | 计算公式格式化不改字面值和语义 | [formatCalculatedExpression](../../frontend/src/components/modeling/formatCalculatedExpression.ts) | 2 | 保留；接口/解析/状态机行为回归 |
| [conversation-polish.test](../../frontend/tests/conversation-polish.test.mjs) | 中文输入法、滚动阈值、中断显示和错误脱敏 | [chatInteraction](../../frontend/src/utils/chatInteraction.ts), [friendlyError](../../frontend/src/utils/friendlyError.ts), [ErrorNotice](../../frontend/src/components/ErrorNotice.tsx) | 6 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [fact-window-setup.test](../../frontend/tests/fact-window-setup.test.mjs) | 事实表/维度判定和日期设置流程 | [factWindowSetup](../../frontend/src/utils/factWindowSetup.ts) | 4 | 保留；接口/解析/状态机行为回归 |
| [guided-tour.test](../../frontend/tests/guided-tour.test.mjs) | 引导锁定跟随用户/知识库和真实工作流阶段 | [guidedTour](../../frontend/src/state/guidedTour.ts) | 4 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [image-cache.test](../../frontend/tests/image-cache.test.mjs) | 图片缓存并发、引用计数、账号切换与释放 | [imageCache](../../frontend/src/api/imageCache.ts), [auth](../../frontend/src/api/auth.ts) | 7 | 保留；接口/解析/状态机行为回归 |
| [knowledge-history-contract.test](../../frontend/tests/knowledge-history-contract.test.mjs) | 历史过滤、CSV拒绝错误、元数据名称契约 | [adminHistory](../../frontend/src/api/adminHistory.ts), [datasets](../../frontend/src/api/datasets.ts) | 3 | 保留；接口/解析/状态机行为回归 |
| [onboarding-actions.test](../../frontend/tests/onboarding-actions.test.mjs) | 重开/重置API动作区分及失败透传 | [onboarding](../../frontend/src/api/onboarding.ts) | 3 | 保留；接口/解析/状态机行为回归 |
| [onboarding.test](../../frontend/tests/onboarding.test.mjs) | 新手状态请求竞态和跨账号缓存隔离 | [onboarding](../../frontend/src/api/onboarding.ts) | 4 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [python-answer-images.test](../../frontend/tests/python-answer-images.test.mjs) | Python产出图片引用和受保护URL | [Markdown](../../frontend/src/components/Markdown.tsx), [PythonCodeBlock](../../frontend/src/components/PythonCodeBlock.tsx), [artifacts](../../frontend/src/api/artifacts.ts) | 3 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [semantic-workbench.test](../../frontend/tests/semantic-workbench.test.mjs) | 规则解析、字段卡片顺序和布局关系 | [ruleText](../../frontend/src/components/modeling/ruleText.ts), [workbenchLayout](../../frontend/src/components/modeling/workbenchLayout.ts) | 6 | 保留；接口/解析/状态机行为回归 |
| [single-knowledge-selection.test](../../frontend/tests/single-knowledge-selection.test.mjs) | 零/单库选择与历史多库重新选择 | [singleKnowledgeSelection](../../frontend/src/utils/singleKnowledgeSelection.ts) | 2 | 保留；接口/解析/状态机行为回归 |
| [stream-and-html.test](../../frontend/tests/stream-and-html.test.mjs) | SSE分帧/UTF8/取消/终止与HTML转义 | [chat](../../frontend/src/api/chat.ts), [html](../../frontend/src/utils/html.ts) | 8 | 保留；接口/解析/状态机行为回归 |
| [tool-call-status.test](../../frontend/tests/tool-call-status.test.mjs) | 工具调用失败、恢复成功和未返回状态 | [ToolCallBlock](../../frontend/src/components/ToolCallBlock.tsx) | 2 | 保留；SSR部分仅证明静态渲染，核心交互另做少量浏览器验收 |
| [tool-inspector.test](../../frontend/tests/tool-inspector.test.mjs) | 工具结果JSON解包、截断、收据和空值类型 | [toolInspectorPresentation](../../frontend/src/utils/toolInspectorPresentation.ts) | 16 | 保留；接口/解析/状态机行为回归 |
| [upload-policy.test](../../frontend/tests/upload-policy.test.mjs) | 上传策略失败可重试及旧后端默认值 | [datasets](../../frontend/src/api/datasets.ts) | 1 | 保留；接口/解析/状态机行为回归 |
| [web-search.test](../../frontend/tests/web-search.test.mjs) | 联网来源引用与显式开关重试 | [webSearch](../../frontend/src/utils/webSearch.ts) | 2 | 保留；接口/解析/状态机行为回归 |
| [wren-presentation.test](../../frontend/tests/wren-presentation.test.mjs) | 官方/旧历史工具结果呈现及截断标记 | [wrenToolPresentation](../../frontend/src/utils/wrenToolPresentation.ts) | 4 | 保留；接口/解析/状态机行为回归 |

## Python逐测试类（15个文件）

本轮静态核对，未重跑：不能填入不存在的耗时/通过结果。当前Maven与新增应用CI没有自动发现这些文件，新增CI只执行另一个报告汇总脚本的6项测试。存在纯逻辑、Shell替身和真实Docker/MySQL专项混合，应按具体类/方法分层，不能全目录强开条件开关。

| 文件/类 | 当前保护内容与代码 | 方法数 | 条件与建议 |
| --- | --- | --- | --- |
| [test_answer_quality](../../src/test/python/test_answer_quality.py) / AnswerQualityTest | 独立答案检查器的正确数值、标识符、Decimal和比率语义；[checks](../fixtures/answer-quality/checks.py) | 4 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_bi_extra_shell](../../src/test/python/test_bi_extra_shell.py) / ExtraShellTest | 扩展同步Cron/运行脚本的真实隔离Linux守卫；[bi-extra-sync](../../docker/bi-extra-sync.sh), [install-bi-extra-sync-cron](../../docker/install-bi-extra-sync-cron.sh) | 1 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "isolated Linux opt-in") |
| [test_bi_extra_sync](../../src/test/python/test_bi_extra_sync.py) / ExtraUnitTest | 扩展六表同步单元边界和真实MySQL事务/断线/缺口恢复；[extra_sync](../../deploy/bi-sync/extra_sync.py), [extra_schema](../../deploy/bi-sync/extra_schema.py), [configure_extra](../../deploy/bi-sync/configure_extra.py) | 10 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_bi_extra_sync](../../src/test/python/test_bi_extra_sync.py) / ExtraMysqlTest | 扩展六表同步单元边界和真实MySQL事务/断线/缺口恢复；[extra_sync](../../deploy/bi-sync/extra_sync.py), [extra_schema](../../deploy/bi-sync/extra_schema.py), [configure_extra](../../deploy/bi-sync/configure_extra.py) | 19 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_BI_EXTRA_INTEGRATION") == "1",                      "isolated Docker MySQL5.7/8 opt-in") |
| [test_bi_sync_mysql](../../src/test/python/test_bi_sync_mysql.py) / BiSyncMysqlTest | 基础七表镜像的真实幂等、事务、只读和增量恢复；[bi_sync](../../deploy/bi-sync/bi_sync.py) | 20 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_BI_SYNC_INTEGRATION") == "1", "isolated Docker/MySQL opt-in") |
| [test_check_wren](../../src/test/python/test_check_wren.py) / WrenPreflightTest | 发布前Wren依赖预检的包名与固定版本约束；[check-wren](../../src/main/bin/check-wren.py) | 3 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_container_runtime](../../src/test/python/test_container_runtime.py) / ContainerRuntimeTest | 容器运行入口、socket组、持久目录和进程生命周期；[runtime-entrypoint](../../docker/runtime-entrypoint.sh), [dataagent-container](../../docker/dataagent-container.sh) | 11 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "real Docker opt-in") |
| [test_deploy_formal](../../src/test/python/test_deploy_formal.py) / DeploymentTest | 正式发布脚本的锁、分支/版本、自更新、预检和失败恢复；[deploy-formal](../../docker/deploy-formal.sh) | 22 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_formal_compose_env](../../src/test/python/test_formal_compose_env.py) / ComposeEnvTest | 真实Compose逐字保留私有dotenv值；[configure](../../deploy/bi-sync/configure.py) | 1 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_COMPOSE_ENV_TESTS") == "1", "real Compose opt-in") |
| [test_formal_preparation](../../src/test/python/test_formal_preparation.py) / FormalPreparationTest | 私有配置/外部库预检/升级回退和Cron保留原条目；[rehearsal-app](../../docker/rehearsal-app.sh), [database-preflight](../../docker/database-preflight.py) | 14 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.name == "posix" and os.geteuid() == 0, "Linux container fixture") |
| [test_import_bi_users](../../src/test/python/test_import_bi_users.py) / BiUserImportTest | BI人员Excel导入字段白名单和拒绝公式；[import-bi-users](../integration/import-bi-users.py) | 2 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_profile_wren](../../src/test/python/test_profile_wren.py) / WrenProfileTest | Wren函数耗时口径、退出码、异常和报告不覆盖；[profile-wren](../../src/main/bin/profile-wren.py) | 6 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖 |
| [test_rehearsal_app](../../src/test/python/test_rehearsal_app.py) / RehearsalAppTest | 真实应用镜像的登录、持久化和预检失败保护；[rehearsal-app](../../docker/rehearsal-app.sh) | 1 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_APP_REHEARSAL_TESTS") == "1", "real application/Docker opt-in") |
| [test_rehearsal_mysql](../../src/test/python/test_rehearsal_mysql.py) / RehearsalMysqlTest | 隔离MySQL首次安装、重启和账号权限；[rehearsal-mysql](../../docker/rehearsal-mysql.sh) | 1 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_MYSQL_REHEARSAL_TESTS") == "1", "real Docker opt-in") |
| [test_release_logging](../../src/test/python/test_release_logging.py) / ReleaseLoggingTest | 发布启动脚本与日志配置的结构检查；[dataagent](../../src/main/bin/dataagent.sh), [logback-spring](../../src/main/resources/logback-spring.xml) | 2 | 保留，按部署/同步变更运行。无类级显式跳过；仍需确认Python包与Shell夹具依赖；脚本源码/配置形状断言可收敛，不当作真实日志运行验收 |
| [test_source_build](../../src/test/python/test_source_build.py) / SourceBuildTest | 源码构建守卫、离线镜像及外置配置检查；[build-from-source](../../docker/build-from-source.sh) | 1 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1", "real Docker opt-in") |
| [test_source_build](../../src/test/python/test_source_build.py) / ApplicationImageTest | 源码构建守卫、离线镜像及外置配置检查；[build-from-source](../../docker/build-from-source.sh) | 4 | 保留，按部署/同步变更运行。条件专项：unittest.skipUnless(os.environ.get("DATAAGENT_CONTAINER_TESTS") == "1"                      and os.environ.get("DATAAGENT_TEST_APPLICATION_IMAGE"), "application image opt-in") |

### Python具体方法

- `test_answer_quality.py::AnswerQualityTest`：`test_correct_alternative_numbers_do_not_rescue_wrong_primary_answer`；`test_textual_identifiers_preserve_leading_zeros`；`test_money_totals_use_decimal_and_catch_one_cent_errors`；`test_ratio_totals_and_labels_containing_the_word_total_are_not_additive`。
- `test_bi_extra_shell.py::ExtraShellTest`：`test_shell_guards_on_linux`。
- `test_bi_extra_sync.py::ExtraUnitTest`：`test_verify_persists_result_when_unused_status_connection_would_expire`；`test_connection_allows_long_queries_and_stream_backpressure`；`test_source_retry_is_bounded_and_never_retries_sql_errors`；`test_exact_six_table_projection_and_no_eid_uniqueness`；`test_report_formulas_preserve_confirmed_divisor_and_sum_of_ratios`；`test_structural_partition_parse_and_no_unique_key_assumption`；`test_gap_interval_merge_and_successful_backfill_split`；`test_private_config_refuses_overwrite_and_never_echoes_password`；`test_configure_private_file_and_preserve_existing_base`；`test_invalid_endpoint_and_batch_rejected_before_any_connection`。
- `test_bi_extra_sync.py::ExtraMysqlTest`：`test_all_six_end_to_end_formulas_long_url_and_old_task_untouched`；`test_rerun_preserves_real_duplicates_and_includes_late_row`；`test_publish_failure_rolls_back_delete_and_preserves_progress`；`test_source_disconnect_after_partial_stage_reconnects_without_duplicate_live_rows`；`test_actual_killed_source_session_retries_current_day`；`test_target_disconnect_does_not_reconnect_or_mask_error_and_saves_failure`；`test_ttl_roll_forward_does_not_delete_archived_target_rows`；`test_missing_history_and_empty_monitoring_snapshot_are_explicit`；`test_empty_monitoring_rerun_keeps_previous_day_values`；`test_pause_during_extract_and_resume_does_not_touch_live`；`test_unowned_table_extra_unique_key_and_wrong_source_are_rejected`；`test_missing_source_column_fails_before_live_replacement`；`test_migrate_nullable_column_and_retry_init`；`test_source_account_is_readonly`；`test_verify_detects_same_count_source_update`；`test_archived_checksum_is_verified_without_source_replay`；`test_concurrent_task_refused_but_pause_can_interrupt`；`test_explicit_short_first_run_records_unprocessed_gap`；`test_daily_sync_refreshes_exact_lookback_and_catches_up_after_downtime`。
- `test_bi_sync_mysql.py::BiSyncMysqlTest`：`test_asset_migration_backfill_preserves_all_log_progress_and_data`；`test_asset_migration_partial_ddl_retry_and_old_status`；`test_asset_migration_checks_source_and_all_targets_before_ddl`；`test_asset_migration_rejects_unknown_target_wrong_owner_and_concurrency`；`test_basic_backfill_failure_keeps_pause_and_all_previous_rows`；`test_initial_idempotent_incremental_and_verify`；`test_interrupted_day_rollback_and_resume`；`test_dimension_failure_keeps_all_old_snapshots`；`test_historical_null_empty_and_quoted_projects_are_not_lost`；`test_committed_project_days_resume_without_double_counting`；`test_new_project_and_missed_days_are_caught_up`；`test_source_requires_existing_project_time_index`；`test_old_task_owner_is_not_silently_reused`；`test_verify_detects_target_count_damage`；`test_execution_status_source_failure_redaction_and_offline_pause`；`test_pause_inside_batch_rolls_back_current_day_and_resumes`；`test_status_reports_running_without_source_connection`；`test_unowned_target_and_source_write_rejected`；`test_overlapping_job_and_changed_source_rejected`；`test_formal_preflight_uses_jdbc_database_and_requires_admin`。
- `test_check_wren.py::WrenPreflightTest`：`test_mysqlclient_is_checked_by_its_actual_import_name`；`test_missing_mysqlclient_is_rejected_even_if_pymysql_is_available`；`test_pinned_package_version_is_still_enforced`。
- `test_container_runtime.py::ContainerRuntimeTest`：`test_check_dependencies_and_daemon`；`test_bad_port_rejected`；`test_foreign_release_directory_rejected`；`test_missing_profile_rejected`；`test_missing_cache_rejected`；`test_multiple_jars_rejected`；`test_non_root_socket_group`；`test_sibling_container_same_absolute_shared_path`；`test_controller_rejects_foreign_labels`；`test_controller_start_stop_replace_and_persistent_files`；`test_java_foreground_term_and_wren_subprocess`。
- `test_deploy_formal.py::DeploymentTest`：`test_success_and_self_update_use_target_entry`；`test_same_revision_skips_build_and_restart`；`test_inherit_proxy_mode`；`test_dirty_source_preserved`；`test_wrong_branch_is_not_switched`；`test_diverged_history_is_not_reset`；`test_wrong_directory_marker_stops`；`test_private_continuation_cannot_be_called_without_held_locks`；`test_missing_compose_plugin_stops_before_build`；`test_release_lock_blocks_second_publisher`；`test_standalone_source_update_lock_is_respected`；`test_probe_failure_stops_and_redacts_url_credentials`；`test_fetch_retries_are_bounded`；`test_timed_out_probe_and_wrong_ref_stop_before_fetch`；`test_build_or_record_failures_never_upgrade`；`test_controller_failures_report_old_health_or_failed_recovery`；`test_inconsistent_final_image_is_not_success`；`test_invalid_network_mode_is_rejected`；`test_unhealthy_baseline_is_not_replaced`；`test_health_verification_failure_is_not_success`；`test_real_builder_publishes_only_its_successful_result`；`test_real_builder_static_failure_has_no_result_file`。
- `test_formal_compose_env.py::ComposeEnvTest`：`test_actual_container_receives_literal_values`。
- `test_formal_preparation.py::FormalPreparationTest`：`test_real_shell_prepare_external_and_legacy_preserve_files`；`test_private_files_preserve_special_passwords_and_refuse_overwrite`；`test_jdbc_parsing_and_rejection`；`test_failed_preflight_never_stops_old`；`test_failed_start_restores_old_and_candidate_port`；`test_success_and_revert_restore_candidate_port`；`test_unhealthy_candidate_never_stops_old`；`test_stale_configuration_never_stops_old`；`test_configuration_write_failure_still_attempts_old_service`；`test_config_and_table_whitelist`；`test_cron_refuses_stale_sync_without_touching_schedule`；`test_cron_preserves_existing_entries_and_read_error_blocks_write`；`test_remove_cron_preserves_other_lines_and_never_contacts_database`；`test_paused_sync_cannot_install_cron`。
- `test_import_bi_users.py::BiUserImportTest`：`test_selects_only_profile_columns`；`test_refuses_formula_input`。
- `test_profile_wren.py::WrenProfileTest`：`test_nested_same_stage_has_union_time_separate_from_inclusive_sum`；`test_only_known_functions_are_reported`；`test_success_exit_is_preserved_and_output_has_no_arguments`；`test_failed_exit_is_preserved`；`test_python_exception_still_produces_failed_report`；`test_report_never_overwrites_an_existing_file`。
- `test_rehearsal_app.py::RehearsalAppTest`：`test_real_image_login_persistence_and_preflight_failure`。
- `test_rehearsal_mysql.py::RehearsalMysqlTest`：`test_first_install_restart_permissions_and_guards`。
- `test_release_logging.py::ReleaseLoggingTest`：`test_launcher_prepares_logs_for_both_execution_paths`；`test_transcript_paths_and_separate_loggers_are_preserved`。
- `test_source_build.py::SourceBuildTest`：`test_guards_in_linux`。
- `test_source_build.py::ApplicationImageTest`：`test_offline_image_check_without_host_configuration`；`test_missing_external_configuration_blocks_start`；`test_invalid_port_blocks_start`；`test_private_profile_inside_jar_is_rejected`。

## CI自身测试及嵌套夹具

- [test_dataagent_test_report](../../../.github/scripts/test_dataagent_test_report.py)：6项，保留。验证缺报告、缺类、禁用、失败、条件跳过及错误计数不会制造CI假绿；上一阶段已实际通过。
- [test_limit](../../src/test/resources/wren-compat/test_limit.py)：由WrenProcessEnvironmentTest的条件用例调用，保留为真实官方Wren兼容专项，不再另加进1150项统计。
- [RuntimeFixture](../../src/test/resources/container-runtime/RuntimeFixture.java)：容器入口生命周期夹具，不是JUnit类；保留给test_container_runtime.py，不误算为缺失测试。
