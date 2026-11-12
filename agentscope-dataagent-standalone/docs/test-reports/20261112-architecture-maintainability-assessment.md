# 2026-11-12：架构与可维护性初评

## 结论与范围

本次由用户要求评估长期依赖 AI 开发后的整体工程质量。结论：已有架构和测试基础，但核心职责集中、包边界耦合、测试与 CI 缺口已经值得安排专项治理；本次证据不支持宣布架构全面合格，也不支持推倒重写。

评估对象是 `agentscope-dataagent-standalone` 当前工作区，而非上游 AgentScope 全仓库审计。分支 `liyufan_dev`，HEAD `27f462df`，包含本轮开始前尚未提交的 spec127 等代码。读取实际代码、构建配置、测试、迁移指引及项目记忆，并运行选定测试；没有实施业务改造、提交、推送、部署、生产数据库操作或服务重启。

这是结构和可维护性初评，不是完整安全扫描、逐表数据库设计审计、性能压测或生产可靠性认证。以下建议尚未确认为实施决策，不新增 spec/ADR，也不改变现行架构边界。

## 已有基础

- [AGENTS.md](../../AGENTS.md) 已规定四个业务包、复用框架、构造器注入、WebFlux 阻塞隔离、多租户范围和文档流程。
- [架构说明](../../ARCHITECTURE_zh.md)、spec、ADR、迁移脚本与验收报告均存在，不能认定项目没有设计过程。
- [pom.xml](../../pom.xml) 将 Spotless 绑定到编译，包含 JUnit、Mockito、Reactor 测试支持，且正常 Maven 生命周期会运行前端测试与构建。
- `KnowledgeBaseAccessService` 集中处理 QUERY/MANAGE，`DatasetScope` 表达调用范围，发布与草稿分离；本次选定权限和发布回归通过。
- [迁移指引](../migrations/README.md) 区分空库安装与既有库升级，要求正式环境 `ddl-auto=validate`，并明确数据库、工程、附件和主密钥配套备份。

这些是可复用的工程资产。它们不能单独证明所有调用路径、生产环境与后续变更均正确。

## 规模与依赖观察

本次按源码物理行统计，包含空行、注释；行数仅用于定位阅读与变更热点，不作为缺陷判定阈值。

| 项目 | 观察 |
| --- | --- |
| 后端生产 Java | 374 个文件，78,289 行 |
| 后端测试 Java | 170 个文件；文件数量不等于覆盖率 |
| `dataset` 直属包 | 92 个 Java 文件，尚未进一步按业务能力拆分 |
| `web.persistence.jpa` | 72 个文件，其中 35 个 Entity、33 个 Repository |
| `ModelingToolkit` | 2,184 行 |
| `MdlPublishService` | 1,898 行 |
| `ChatController` | 1,626 行 |
| `MdlSuggestionService` | 1,582 行 |
| `DatasetService` | 1,288 行 |
| 前端 `ChatPanel` | 1,568 行；本次只做规模观察，未开展完整前端设计评审 |

基于显式 Java import 的顶层包关系存在 `web ↔ dataset`、`dataset ↔ tools`、`dataset ↔ runtime` 等双向引用。这是包依赖观察，不等于 Spring Bean 循环依赖诊断；也不能把同一顶层包内所有子包视为相同职责。

## 主要发现

### A1｜优先：独立应用未被仓库现有根级 CI 构建

证据：[根 Maven 模块列表](../../../pom.xml) 不包含 standalone；[maven-ci.yml](../../../.github/workflows/maven-ci.yml) 在仓库根运行 `clean verify`，三个现有 workflow 中未发现 standalone 的独立构建步骤。push 分支配置仅为 main，另有 pull_request 触发；项目记忆中的共同主线为 develop-2.0。

影响：根级 CI 通过不能证明实际产品应用已编译、测试、打包成功。本机手动检查与文档约定仍发挥作用，但缺少仓库内对应的自动验证路径。本次未读取远端分支保护或外部流水线配置，不能断言不存在仓库外检查。

建议：先增加独立应用的明确 CI 任务和测试结果输出，再核实实际合并流程是否要求该检查通过；保留其独立 Maven 工程定位，不因这个问题直接并回根 reactor。

### A2｜优先：三个集成测试类被显式禁用

源码证据：

- [JpaPersistenceIntegrationTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/JpaPersistenceIntegrationTest.java)
- [H2DemoSeedTest](../../src/test/java/io/agentscope/dataagent/web/persistence/jpa/H2DemoSeedTest.java)
- [MarketContributionServiceTest](../../src/test/java/io/agentscope/dataagent/web/marketplace/MarketContributionServiceTest.java)

三个类均有 `@Disabled("Pending fix for circular dependency between builderBootstrap and userSandboxRegistry")`。现存测试报告分别记录 4、1、7 项跳过，共 12 项。

影响：持久化、初始化、市场能力的一部分集成验证未运行。禁用注释是待核实的历史原因，不足以证明当前生产启动仍有该循环依赖，也不足以直接启用后保证测试通过。先前记录的 33 项跳过不能全部解释成缺少真实 LLM/Docker/Wren 环境。

建议：逐类检查是否为过宽的 Spring 测试上下文、历史装配问题或仍存在的实际依赖环，再修复并恢复执行。跳过统计区分明确禁用、缺少外部环境和其他原因；不能以新增 mock 单测替代被禁用的集成验证。

### A3｜优先排期：核心类承担了过多不同职责

[ChatController](../../src/main/java/io/agentscope/dataagent/web/api/ChatController.java) 除 HTTP/SSE 外，还包括知识库范围解析、RuntimeContext 构造、会话命令、人工确认、容量准入、活动与新手进度记录等编排。

[MdlPublishService](../../src/main/java/io/agentscope/dataagent/dataset/MdlPublishService.java) 同时包含发布互斥、来源选择、工作区校验与构建、预览与视图投影、发布快照等逻辑。[DatasetService](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java) 完整构造器有 19 个参数，涉及导入、元数据、权限及多类存储协作。

影响：一次局部需求容易同时触及授权、事务、文件与协议，阅读、测试装配和回归范围扩大。这里的依据是职责与依赖集中，不能仅因超过千行就认定设计错误。

建议：先明确用例边界和现有行为测试，再按“聊天用例编排 / SSE 协议适配”“发布协调 / 校验 / 预览投影”“导入 / 元数据维护”等候选职责逐步抽取。具体拆分仍需设计评审，不先批量移动文件或建立大量空接口。

### A4｜中期：四包规则尚未形成清晰、自动检查的依赖契约

大量持久化类型位于 `web.persistence.jpa`，业务与 runtime 因而依赖 web 顶层包。例如 `DatasetService` 直接引用其中的 Entity/Repository。还存在非持久化耦合：`WrenQueryAccess` 引用 `web.session.ConversationScopeRegistry`，`WrenQueryPreparation` 引用 `web.middleware.ExecutionTimingMiddleware`，`HarnessGateway` 引用 `web.workspace.UserSandboxRegistry`。

影响：web 实际承载了超出 HTTP 适配器的公共服务，图中的层名不足以表达真实边界。顶层双向引用中既有包归属问题，也有实际跨职责依赖，必须分别判断。本次未发现专门检查包依赖方向、分层和循环的自动门禁；`DocsConsistencyTest` 明确只检查文档结构，不验证架构描述与实现一致。

建议：先写出当前依赖图及允许的方向，识别公共身份、会话范围、持久化和外部 Wren 适配的归属，再逐步约束新增依赖。可使用 [ArchUnit 官方提供的分层与循环检查能力](https://www.archunit.org/userguide/html/000_Index.html)，先建立现状基线、阻止新增违规，再消化已有问题。没有证据表明当前必须拆微服务或全面采用 DDD。

### A5｜重要待验证：跨数据库、文件、子进程的恢复边界

[DatasetMetadataDraftSync#update](../../src/main/java/io/agentscope/dataagent/dataset/DatasetMetadataDraftSync.java) 在平台库事务期间修改 YAML 和草稿日志，通过 TransactionSynchronization 回滚文件；[DatasetService#ingest](../../src/main/java/io/agentscope/dataagent/dataset/DatasetService.java) 在平台事务内进行物理表导入并注册失败清理。[MdlWorkspaceService](../../src/main/java/io/agentscope/dataagent/dataset/MdlWorkspaceService.java) 和发布链使用进程内锁。

已有补偿、锁顺序和回归测试是积极证据。数据库事务仍不能自动把文件、另一数据源或子进程纳入同一原子提交；本次仅验证了指定正常/异常路径，没有验证进程强制退出、重启恢复或多实例共享工作区。

影响：这些路径比一般 CRUD 更容易出现部分完成、恢复歧义或长事务，是后续可靠性审查重点；本次没有复现数据损坏，不将其报告为已证实故障。进程内锁符合现有单进程预算约束，不能据此宣称已支持跨实例互斥。

建议：先明确单实例运行边界、关键操作状态与恢复步骤，再针对导入、发布、删除设计故障注入和重启验收。只有选定方案后再决定事务缩短、持久化操作记录、对账或其他机制，不能仅添加 `@Transactional` 解决跨资源一致性。

### A6｜中期：库表演进主要依赖人工选择和执行迁移

[迁移指引](../migrations/README.md) 已有完整 DDL、条件增量与正式 validate 流程，因此不能评价为“没有数据库设计”。但独立 pom 未配置 Flyway/Liquibase，现有升级依赖逐项检查缺列并选择 SQL。本次未观察到统一自动迁移历史与校验门禁。

影响：版本与环境增多后，可能产生漏迁、重复执行或结构漂移；validate 的价值是阻止不匹配启动，不能替代版本化升级验证。

建议：先核对实体、首次 DDL、增量脚本的一致性，并测试空库安装和代表性旧版本升级；再根据 DBA 执行边界选择保留人工执行加校验记录，或引入迁移工具。逐表键、索引、外键/逻辑关联、JSON/TEXT、保留策略及并发更新仍需另做审查，不能据本次抽样宣布全部合理。

## 建议治理顺序（待讨论，未实施）

1. 补 standalone CI，核实合并门禁，分类报告跳过测试，并恢复三个禁用测试类。
2. 以聊天、建模发布、上传导入、权限撤销、资源删除为主线，形成实际调用与事务/文件边界清单。
3. 确定轻量的模块职责和依赖契约，增加自动架构检查，优先阻止新问题。
4. 逐次抽取高耦合类职责，每次保留 API 和行为并执行相关回归，避免一次性重写。
5. 补数据库升级、失败恢复、备份恢复与容量验收，形成明确的发布检查。

后续需求设计应简短说明：目标和验收标准、受影响模块、类职责、数据变更、授权、事务和异常恢复、兼容与测试。小改动按风险简化，跨模块和长期取舍遵守现有 spec/ADR 规则。不必为每个方法预写设计文档，也不以模式数量或接口数量衡量质量。[阿里 Java 规约](https://github.com/alibaba/p3c) 可作为编码检查参考，系统边界与数据一致性仍需项目自己的设计和验证。

## 本次验证与限制

实际执行：

```powershell
mvn -B "-Dskip.npm=true" "-Dskip.installnodenpm=true" "-Dtest=DocsConsistencyTest,KnowledgeBaseAccessServiceTest,MdlPublishServiceTest,DatasetMetadataDraftSyncTest" test
```

结果：Spotless 通过；选定测试 44 项，失败 0、错误 0、跳过 0。其中文档 5、权限 8、发布 24、草稿同步 7。前端相关 Maven 目标明确跳过，避免本次评估重新生成受跟踪 static 文件。

前一阶段报告中的全量 1150 项和前端三个前缀各 105 项为历史结果，不是本次重新执行。没有运行本次全量测试、package、前端 build、真实 LLM/Wren/Docker、生产升级、压力或灾难恢复测试。现存 surefire 目录还保留一个 11 月 5 日失败报告，混合汇总整个目录会把历史失败与当前结果混在一起；不能用这种汇总宣称当前回归失败或全量通过。

报告与共享记忆补充后已复核 `DocsConsistencyTest`：5 项通过，Spotless 再次通过，`git diff --check` 通过；原 spec127 本地验收任务继续保留，本次只新增评估记录。
