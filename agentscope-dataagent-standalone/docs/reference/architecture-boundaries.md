# 模块依赖契约与主线调用边界

当前依据：ADR0145恢复协议与spec133，分层沿用[ADR0144](../adr/0144-incremental-package-dependency-contracts.md)、[spec130](../specs/130-architecture-dependency-contracts.md)。这是现有单应用的实际职责清单，不表示已经完成理想分层。历史评估见[初评](../test-reports/20261112-architecture-maintainability-assessment.md)。

## 职责与依赖规则

| 位置 | 当前职责 | 约束 |
| --- | --- | --- |
| web.api、控制器、web.config | HTTP/SSE协议、入口认证、Spring装配 | 可调用用例与runtime；下层不得回调入口 |
| dataset | 数据资产、授权规则、导入、建模/发布用例 | 可用当前JPA适配和runtime能力，不依赖HTTP或应用装配 |
| tools/data | Agent工具、官方MCP/CLI适配、结果交付 | 复用资产授权与runtime，不回调控制器/配置 |
| runtime | Agent装配基础、网关、会话、Wren进程、通用观测 | 通用观测不依赖web事件总线；外部交互沿现有契约 |
| web.persistence.jpa | 当前平台持久化适配，名称位置属历史结构 | Entity/Repository不反向依赖其他应用包；Store实现与配置不当作纯数据叶节点 |
| web.auth/session/workspace/artifact等 | 现存公共身份、会话、沙箱、附件能力 | 尚未统一迁移；下层使用按精确类对登记债务，不能整包放行 |

四根包不是严格的单向DAG；dataset、tools、runtime之间仍存在双向协作，本轮不声称消除了循环。持久化归属先记录，后续迁移需处理Spring扫描、接口归属和装配回归，不能只改包名。

## 自动检查与维护

[ArchitectureDependencyTest](../../src/test/java/io/agentscope/dataagent/architecture/ArchitectureDependencyTest.java)用ArchUnit扫描target/classes中当前应用生产字节码，排除测试和依赖JAR作为分析源；不递归导入第三方实现。检查四根覆盖、禁止下层→HTTP/装配、JPA数据叶节点及历史依赖类对。全限定名调用、字段类型和方法签名同样计入，未使用import不构成字节码依赖。

[历史清单](../../src/test/resources/architecture/legacy-web-dependencies.tsv)为外层类→外层类及原因。只对非web源类到web非JPA目标生效；新增类对失败，已消除但未删清单的类对也失败。允许同一既有类对继续调用，不能以此证明耦合完全没有增长。硬性禁止的HTTP/装配边界不能靠清单豁免。清单不得为“让测试绿”重新批量生成；设计改变先更新spec/ADR与原因。

```bash
python scripts/check.py backend --tests ArchitectureDependencyTest
```

新增或迁移类后遇到旧.class残留，先执行mvn clean再运行；CI本来使用clean。报告输出target/architecture/{class-dependencies,root-dependencies,legacy-web-dependencies}.tsv，仅为静态证据；不扫描反射字符串、配置文件、Spring运行图、前端或上游框架内部，不是数据库/资源一致性证明。

## 五条主线：实际调用与恢复边界

### 1. 聊天与问数

`ChatController#stream` → ChatCapacity准入/排队 → `streamAdmitted` → `ChatTurnPreparation#buildInbound/resolveGroupScope` → RuntimeContext/DatasetScope → HarnessGateway/框架Agent → `WrenScopedToolkitMiddleware` → `WrenScopedMcpClient#callTool` → `WrenInstanceRegistry#callAuthorized`。

入口从认证主体取操作者，AgentAccessGuard检查RUN，知识库经QUERY；共享库owner只作资源定位，不替换操作者。零库普通聊天，问数仅单库。MCP调用前及队列取得执行权后复核授权。阻塞准备在boundedElastic上，SSE保持既有协议。

单次聊天不属于一个覆盖模型、工具、会话和附件的数据库事务；失败/取消会经既有框架收尾，不能宣称所有副作用回滚。相关回归：ChatControllerInboundShapeTest、ChatControllerHitlTest、RequestEventIsolationTest、WrenScopedToolkitMiddlewareTest、WrenScopedMcpClientTest。真实模型回答/断连恢复另做服务验收。

### 2. 建模、发布与准备问数

`MdlPublicationController#mdlPublish` → `DatasetGroupService#getGroup`（MANAGE）与ModelingActivity空闲检查 → `MdlPublishService#publish` → 每库发布锁 → workspace锁 → reconcile/资产核查/来源预检 → staging → 官方validate/build/计划检查 → MdlPublicationStore持久意图/快照替换 → 独立平台事务版本条件更新 → 收尾清理 → PublishedQueryProjectChanged → WrenQueryPreparation。

建模文件/CLI入口经WrenProjectAccess检查MANAGE、作用域和路径，工作区锁后再次复核；不扩大为查询用户的写权限。发布协调同时操作平台JPA、文件和Wren子进程，不是统一原子事务。MdlPublicationStore在移动旧副本前写journal，旧副本保留至数据库提交；启动及重试按已提交版本恢复，未知/缺失副本拒绝继续。QUERY在待恢复期间返回503，MANAGE保留修复入口。未将文件系统变成数据库事务，适用单进程；不承诺断电或多实例写入一致性。

spec131已将只读预览/编译展示/结构投影抽到MdlReadService，13个结果record归MdlTypes；MdlPublishService保留委托方法，写链路不变。相关回归包括MdlPublishServiceTest、MdlReadServiceTest、MdlTypesCompatibilityTest、SharedDraftWrenAuthorizationTest、WrenPublishedProjectTest、WrenQueryPreparationTest，不能为了拆类改变锁顺序。

### 3. 上传导入与元数据维护

`DatasetController#uploadWithId`/批量入口 → DatasetUploadIntake/Admission → `DatasetService#ingest` → MANAGE及来源组合检查 → DatasetImportService/TableProvisioner → 物理表导入 → 平台DatasetEntity与注册表 → 模型草稿/后续处理。

ingest有平台@Transactional，物理数据集库DDL/写入不是同一事务；KnowledgeBaseOperations先登记资源表名，事务未提交时通过同步回调清理导入表/注册表。数据描述同步经DatasetMetadataDraftSync在事务内修改YAML并注册文件补偿，恢复范围不能扩大为任意进程崩溃。

相关回归：DatasetServiceSourceCombinationTest、DatasetServiceTest、DatasetMetadataDraftSyncTest。导入失败清理由DatasetImportRollbackTest验证；整库资源登记与重试另由MdlPublicationTransactionTest验证，不等于任意失败残留会被自动删除。

### 4. 共享、权限撤销与排队执行

KnowledgeBaseMembershipService维护grant；KnowledgeBaseAccessService的QUERY/MANAGE统一判断；WrenQueryAccess与WrenProjectAccess分别约束已发布读面/草稿写面。WrenInstanceRegistry的授权回调及草稿锁内检查，确保进入队列时有权不等于执行时仍有权。

权限修改只改平台授权记录，不自动取消已经提交给外部引擎的执行；回归重点是“执行前排队撤权”与租户/会话范围不匹配被拒，不承诺追溯抹去已返回数据。相关回归：KnowledgeBaseAccessServiceTest、SharedOfficialWrenAccessTest、SharedDraftWrenAuthorizationTest、WrenScopedMcpClientTest。

### 5. 删除与失败重试

知识库整体：`DatasetGroupController#delete` → `DatasetGroupService#deleteGroup` → requireManageForDeletion → `KnowledgeBaseDeletionService#delete`。先写持久journal并阻止新操作，等待/关闭现有执行，登记表名后删除上传表、目录，分段平台事务清理元数据，写完成标记；retryPending会读取未完成journal重试。外部关联表不作上传物理表删除。

单数据集：`DatasetController#delete` → `DatasetService#delete/deleteEntity`，事务内先登记DatasetDeletionCleanup持久意图，再删元数据/标记模型变化；提交后移除注册表并严格清理上传物理表。回滚取消意图，重启按数据集行是否仍存在决定取消/完成，失败保留意图后台重试；外部关联表永不drop。

知识库操作/锁主要在单进程；journal不等于跨实例锁。故障恢复的已验范围见spec133报告：发布有真实子JVM强制退出+文件H2版本验证；单表/整库删除有真实JPA事务回滚与重新实例化重试，物理DROP失败使用受控替身。未实测生产强杀/真实数据集库故障。

## 校验执行与后续恢复验证

只读投影已按[spec131](../specs/131-mdl-read-projection-extraction.md)完成第一批抽取；读取组件和解析器不依赖发布/播种命令，MdlTypes不依赖业务服务。新增静态检查只允许已知Files读取API，覆盖调用与方法引用，阻断工作区非路径方法及仓库save/delete；这不是对任意反射、第三方IO或运行时副作用的完整证明。

spec132将构建后的View/ref_sql、Cube执行和诊断解析抽入包级MdlBuildChecks；选源/授权、锁、播种/构建和问答状态仍归协调器。spec133进一步将纯元数据投影归MdlManifestRenderer、安装/恢复归MdlPublicationStore；投影门禁阻止Spring/文件/JPA依赖。未为了类行数拆散锁与状态步骤。

## 公共组件与后续边界

ConversationScopeRegistry与SessionTurnParser已归runtime.session；原web.session保留HTTP历史读取服务。精确历史非JPA反向依赖从21对降到13对，身份、辅助LLM、沙箱/工作区、附件与Python容量仍保留原契约，逐项理由见清单及[工程报告](../test-reports/20261112-architecture-six-stage-acceptance.md)。四根包仍不是严格DAG。

主线和数据库演练的具体证据及生产限制以[六阶段验收报告](../test-reports/20261112-architecture-six-stage-acceptance.md)为准；后续优先按真实业务变更拆分大型用例、引入受控迁移台账及实际规模恢复演练，不开展机械加ServiceImpl/DO包/设计模式的重写。
