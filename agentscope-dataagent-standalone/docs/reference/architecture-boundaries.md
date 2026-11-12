# 模块依赖契约与主线调用边界

当前依据：[ADR0144](../adr/0144-incremental-package-dependency-contracts.md)、[spec130](../specs/130-architecture-dependency-contracts.md)。这是现有单应用的实际职责清单，不表示已经完成理想分层。历史评估见[初评](../test-reports/20261112-architecture-maintainability-assessment.md)。

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

`ChatController#stream` → ChatCapacity准入/排队 → `streamAdmitted`与`resolveGroupScope` → RuntimeContext/DatasetScope → HarnessGateway/框架Agent → `WrenScopedToolkitMiddleware` → `WrenScopedMcpClient#callTool` → `WrenInstanceRegistry#callAuthorized`。

入口从认证主体取操作者，AgentAccessGuard检查RUN，知识库经QUERY；共享库owner只作资源定位，不替换操作者。零库普通聊天，问数仅单库。MCP调用前及队列取得执行权后复核授权。阻塞准备在boundedElastic上，SSE保持既有协议。

单次聊天不属于一个覆盖模型、工具、会话和附件的数据库事务；失败/取消会经既有框架收尾，不能宣称所有副作用回滚。相关回归：ChatControllerInboundShapeTest、ChatControllerHitlTest、RequestEventIsolationTest、WrenScopedToolkitMiddlewareTest、WrenScopedMcpClientTest。真实模型回答/断连恢复另做服务验收。

### 2. 建模、发布与准备问数

`SemanticModelingController#mdlPublish` → `DatasetGroupService#getGroup`（MANAGE）与ModelingActivity空闲检查 → `MdlPublishService#publish` → 每库发布锁 → workspace锁 → reconcile/资产核查/来源预检 → staging → 官方validate/build/计划检查 → 快照替换 → 元数据版本更新 → PublishedQueryProjectChanged → WrenQueryPreparation。

建模文件/CLI入口经WrenProjectAccess检查MANAGE、作用域和路径，工作区锁后再次复核；不扩大为查询用户的写权限。发布协调同时操作平台JPA、文件和Wren子进程，不是统一原子事务。现有replacePublishedArtifacts处理快照切换异常，工程验证失败保留旧发布版本；快照与数据库之间进程中断的重启对账仍需故障注入验收。

spec131已将只读预览/编译展示/结构投影抽到MdlReadService，13个结果record归MdlTypes；MdlPublishService保留委托方法，写链路不变。相关回归包括MdlPublishServiceTest、MdlReadServiceTest、MdlTypesCompatibilityTest、SharedDraftWrenAuthorizationTest、WrenPublishedProjectTest、WrenQueryPreparationTest，不能为了拆类改变锁顺序。

### 3. 上传导入与元数据维护

`DatasetController#uploadWithId`/批量入口 → DatasetUploadIntake/Admission → `DatasetService#ingest` → MANAGE及来源组合检查 → DatasetImportService/TableProvisioner → 物理表导入 → 平台DatasetEntity与注册表 → 模型草稿/后续处理。

ingest有平台@Transactional，物理数据集库DDL/写入不是同一事务；KnowledgeBaseOperations先登记资源表名，事务未提交时通过同步回调清理导入表/注册表。数据描述同步经DatasetMetadataDraftSync在事务内修改YAML并注册文件补偿，恢复范围不能扩大为任意进程崩溃。

相关回归：DatasetServiceSourceCombinationTest、DatasetServiceTest、DatasetMetadataDraftSyncTest。重启残留表/文件对账及真实MySQL升级不是本轮验收。

### 4. 共享、权限撤销与排队执行

KnowledgeBaseMembershipService维护grant；KnowledgeBaseAccessService的QUERY/MANAGE统一判断；WrenQueryAccess与WrenProjectAccess分别约束已发布读面/草稿写面。WrenInstanceRegistry的授权回调及草稿锁内检查，确保进入队列时有权不等于执行时仍有权。

权限修改只改平台授权记录，不自动取消已经提交给外部引擎的执行；回归重点是“执行前排队撤权”与租户/会话范围不匹配被拒，不承诺追溯抹去已返回数据。相关回归：KnowledgeBaseAccessServiceTest、SharedOfficialWrenAccessTest、SharedDraftWrenAuthorizationTest、WrenScopedMcpClientTest。

### 5. 删除与失败重试

知识库整体：`DatasetGroupController#delete` → `DatasetGroupService#deleteGroup` → requireManageForDeletion → `KnowledgeBaseDeletionService#delete`。先写持久journal并阻止新操作，等待/关闭现有执行，登记表名后删除上传表、目录，分段平台事务清理元数据，写完成标记；retryPending会读取未完成journal重试。外部关联表不作上传物理表删除。

单数据集：`DatasetController#delete` → `DatasetService#delete/deleteEntity`，平台@Transactional内删除上传物理表、注册表与元数据、标记模型变化。它与知识库整体删除的journal恢复能力不同，不得共用“删除都是可恢复”的结论。

知识库操作/锁主要在单进程；journal不等于跨实例锁。已有异常重试实现不等于进程强杀/跨数据库失败已验收。后续优先设计单数据集删除与发布的重启恢复演练，先用临时库/工作区和明确故障点。相关测试以KnowledgeBaseOperations/删除和导入场景的现有回归为基础，本轮不连接生产。

## 校验执行与后续恢复验证

只读投影已按[spec131](../specs/131-mdl-read-projection-extraction.md)完成第一批抽取；读取组件和解析器不依赖发布/播种命令，MdlTypes不依赖业务服务。新增静态检查只允许已知Files读取API，覆盖调用与方法引用，阻断工作区非路径方法及仓库save/delete；这不是对任意反射、第三方IO或运行时副作用的完整证明。

下一阶段先评估发布校验/计划检查与快照协调的边界、现有失败断言和锁顺序，再选择小批抽取；不能为了减少行数拆开原子步骤或扩大数据库事务。现行单数据集删除/发布跨资源恢复仍待独立故障演练；数据库迁移、备份与灾难恢复另列专项。


spec132将构建后的View/ref_sql、Cube执行和诊断解析抽入包级MdlBuildChecks；依赖只限CLI、超时设置、连接档案、序列化及输入记录。选源/授权、发布锁→工作区锁、播种/构建、快照/版本/事件仍归协调器；问题dry-plan涉及确认状态和问答文件，暂不迁移。新规则约束校验组件无平台仓库/事务/锁/事件依赖，Files仅允许临时连接创建/删除；此静态约束不证明任意反射、第三方IO或跨资源恢复安全。

本轮后续优先评估纯元数据投影与快照文件切换/恢复的边界，先列故障点及旧断言，不在校验迁移中合并命令、扩大事务或改变失败状态。恢复演练、真实Wren与生产部署仍独立验收。
