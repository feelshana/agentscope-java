# 后端工程分析与六阶段验收（spec133）

## 结论与范围

项目是有实际分层与框架复用基础的模块化单体，值得持续治理，证据不支持推倒重写，也不能保证AI编写的代码不会出问题。这批重点修正跨资源一致性及职责边界，保留已有合理设计。

从b63a72de出发，按用户授权连续推进六阶段，最终一次本地提交；不push、合并、部署、启停现有服务。此报告与代码同批提交，提交ID以git log核实。生产功能与部署入口保留。

全量结构盘点覆盖应用379个生产Java文件、38个包、836个类型声明、3819个显式方法/构造器。使用JDK17语法树，静态依赖以ArchUnit字节码为准；不计第三方/上游框架为本应用自研代码。文件/方法规模与37表目录见[全量结构附表](20261112-backend-architecture-inventory.md)。深入核查覆盖聊天/准入/范围、官方查询授权、上传/删除、草稿/发布、启动装配、JPA实体与迁移主链；没有逐行证明全部业务算法或全部生产数据正确。

## 用户所关心的设计是否合理

| 方面 | 现状与判断 | 本批处理 |
| --- | --- | --- |
| 模块 | web181、dataset104、runtime72、tools22个文件；单应用依赖上游core/harness，适合当前交付 | 保留单体，不为形式拆Maven模块或微服务；静态门禁阻止下层回调HTTP/装配 |
| Controller | 多数按资源命名；SemanticModelingController原混合建模编辑/建议/发布，ChatController含选库用例 | 发布六个入口迁MdlPublicationController，原URL/权限/状态码不变；聊天请求准备迁ChatTurnPreparation |
| Service | DatasetService仍协调上传、关联、元数据等，MdlPublishService是有锁/状态的协调器 | 纯投影、文件安装恢复分别归专职组件；事务边界修复；保留协调器对关键顺序的统一管理 |
| 实体/DTO/POJO | 35个实体与33个Repository在web.persistence.jpa；Controller中DatasetVO/GroupVO等是HTTP投影，MdlTypes是发布结果；运行范围是runtime状态 | 不把实体和工具执行混为一体；嵌套record在只服务一个接口时可保留；不批量建含义重叠的DO/POJO包 |
| 公共包归属 | 原web.session的会话范围/解析器被dataset/tools/runtime反向引用 | 迁runtime.session，精确历史债务21→13，未扩大白名单 |
| 接口/模式 | WrenCli/ProcessWrenCli隔离命令，FileParser及多个解析器支持变化，DataAgentBootstrap.Builder组装可选项，WorkspaceManagerFactory处理工作区创建 | 保留已有策略/适配/Builder/工厂；普通单实现服务无需强加Service+ServiceImpl，抽象类只用于确有共同行为和替换点的场景 |
| 类/方法规模 | 大类仍有ModelingToolkit、MdlSuggestionService、ChatController、DatasetService；builderBootstrap等方法较长 | 长度作为阅读线索；本批沿已证实的独立职责拆分，剩余项按业务触发与替代覆盖推进，不按行数配额拆 |

类名、目录及字段命名是可读性工具，事务、授权、状态所有权和恢复边界更决定可维护性。不是所有项目都应套阿里规范的全部条目；当前以仓库AGENTS、格式检查、实际分层和自动约束为执行标准。

## 六阶段实改与证据

### 1. 发布中断恢复

旧代码先安装文件、删除旧备份，再保存平台版本，文件与数据库之间存在中断窗口。MdlPublicationStore在移动前写持久意图，保存旧目录与清单，数据库以旧版本条件更新，成功才完成清理。启动逐库恢复，单库失败保留查询阻断，不影响健康库；下次操作按已提交版本恢复，无法判定则保留意图并503；QUERY不使用待恢复版本，MANAGE仍可进入修复。

| 故障点 | 预期 | 证据 |
| --- | --- | --- |
| 元数据回调失败 / 实际SQL后事务回滚 | 旧目录+旧清单+旧DB版本 | Store与真实JPA TransactionTemplate用例 |
| 目录已替换、清单安装失败 | 两者恢复，避免新旧混用 | 部分安装故障注入 |
| 首次发布失败 | 不留伪发布文件 | 首发失败用例 |
| 数据库已提交但客户端异常 | 重新读取已提交版本，完成新快照 | ambiguous commit用例 |
| 文件已换、元数据提交前子进程退出 | 重启恢复旧文件 | 独立子JVM Runtime.halt(23)，文件H2读版本 |
| 文件与数据库已提交后子进程退出 | 重启完成新文件并清理意图 | 独立子JVM、真实H2持久提交（WRITE_DELAY=0） |
| journal损坏/旧副本缺失 | 拒绝猜测、保留阻断 | 两个fail-closed用例 |
| 提交后草稿注释清理失败 | 已发布成功、返回warning，仍发送失效/预热事件 | 非空目录故障夹具，发布版本/事件断言 |

恢复读取使用独立事务的锁定读，等待尚未完成的数据库写事务，避免把网络异常后的未决提交误当成旧版本；真实双事务用例验证读取在写事务结束前等待、提交后读到新版本。

版本提交与失败记账改为REQUIRES_NEW的字段级条件/局部更新，避免过时实体merge覆盖并发修改的名称、modelingRevision等。同一publicationTime用于清单和版本。旧发布无新marker也可作为旧快照保留；新marker放target/.publication-version，不将.platform暴露到发布工程。

### 2. 发布组件职责

MdlManifestRenderer是无文件、JPA、Spring、时钟依赖的投影；MdlPublicationStore只处理本地快照安装/恢复；MdlPublishService保留授权、锁顺序、CLI顺序、播种、问题状态及事件协调。原MdlReadService/MdlBuildChecks边界保持。ArchUnit新增纯投影依赖约束，原JSON和CLI契约由既有MdlPublishServiceTest验证；未为了缩短协调器拆开原子步骤。

### 3. 聊天与HTTP边界

ChatTurnPreparation接收用户/Agent/会话/选库与消息，建立Inbound/RuntimeContext；选择写入仍延迟到ChatTurnScope.accept在网关准入后执行，并再次授权。两段迁移方法的语法树方法体与基线一致（仅归属调整）；原Controller委托保留，HTTP/SSE、HITL、取消仍由协议入口与原框架管理。

MdlPublicationController独立承接preview、compiled、view、validate、initialize、publish，SemanticModelingController继续编辑/建议。六个迁移入口的方法体语法树与基线一致；HTTP绑定用例验证六个旧URL、403权限拒绝和建模中409；既有聊天用例覆盖消息形状、HITL、分析选项、排队后撤权及逐轮范围。

### 4. 导入与删除

DatasetDeletionCleanup在平台元数据删除前持久写意图；afterCommit才移除注册表并drop上传表，afterCompletion回滚则取消。启动/定时重试以独立已提交行是否存在判断；DB不可用不drop，DROP失败保留意图，外部关联表永不drop。成功drop后才忘记整库导入资源登记，防止失去残留资源清理线索。

新增测试验证回滚/提交顺序、定时器不抢未提交意图、掉库、失败再重试、重新实例化、外部关联与恶意/损坏清单。真实H2 JPA事务证明回滚保留平台行与物理资源调用不发生；知识库整库删除测试证明失败保留DELETING/journal，重新实例化后可完成元数据删除与目录清理。物理DROP失败为受控替身；导入原有回滚测试继续保留，不把替身当作真实MySQL故障。

### 5. 公共依赖

ConversationScopeRegistry与SessionTurnParser及镜像测试迁runtime.session，全部调用同步，移除8对退休债务；Selection/转录JSON形状不变。Parser只依赖序列化/JDK；Registry仍使用当前JPA持久化及AnalysisTurn值，不宣称runtime与tools间完全无耦合。

剩余13对逐项理由保留在精确清单：UserStore身份2对；AgentDraftService建议/遗留KG3对；沙箱/工作区3对；附件4对；Python容量1对。前者应随完整身份用例迁移，LLM辅助服务涉及多个调用契约，沙箱/附件/容量应与生命周期一起设计；本批没有证据支持仅为清零债务直接搬走。新增依赖仍失败，非JPA清单不能豁免HTTP/装配硬边界。

### 6. 库表、升级与恢复

使用本机Docker desktop-linux/npipe daemon29.1.3，临时mysql:8.4.5容器带本任务标签、loopback随机端口、tmpfs数据目录、无宿主业务卷。只操作本批自建库；未连接生产数据库。

- 完整DDL建37表、336列、116个索引列位置；35实体的Hibernate validate、主键、声明索引、唯一及显式NOT NULL读取真实元数据校验通过。
- 代表性旧库：从当前schema移除0143新增列/索引、写入模拟历史会话、执行0143增量；原payload未变，升级后全库列/索引签名与新装库一致；旧库的35实体及约束也通过。
- 静止的模拟平台库与独立上传库一起mysqldump，删除本批库后恢复；会话payload、发布/建模版本7/9/8、上传金额合计37.25与结构签名一致。
- 配套模拟工程/marker/主密钥文件复制备份恢复后哈希逐项一致；这是静止模拟集演练，不证明跨库活跃写入时任意时间点一致恢复。

新增PlatformSchemaMysqlTest是只读的external条件测试，使用显式DATAAGENT_SCHEMA_JDBC/USER/PASSWORD环境变量，且要求dataagent_schema_verify_库名；未配置时应如实条件跳过。Hibernate validate本身不校验所有索引，因此另读取DatabaseMetaData比对。复合主键按组合检查，不能错误要求每个@Id字段单列唯一。

DDL没有为本批新增平台表或配置；journal是mdl-home下版本化内部文件。逐表审查未发现本批必须改DDL的实体结构不一致。大部分业务关联依赖服务层，不批量加级联FK，以免改变删除/恢复语义。

## 最终统一验证

Java17.0.14，当前非忽略源码1877文件复制到独立.runtime/arch133-ci-validation，npm ci不触碰开发目录。使用同checkout先前构建的本地框架依赖，本批未改上游框架。最终命令：

```text
mvn -B -ntp -f <隔离副本>/agentscope-dataagent-standalone/pom.xml clean verify -Dfrontend.npm.install.arguments=ci
python .github/scripts/dataagent_test_report.py <隔离副本>/agentscope-dataagent-standalone/target/surefire-reports
```

| 项目 | 最终实测 |
| --- | --- |
| 后端完整运行 | 172个suite、1129项：1108通过、0失败/错误、21条件跳过；包含本批真实MySQL schema校验 |
| 必跑集成 | JpaPersistenceIntegrationTest 4、H2DemoSeedTest 1、MarketContributionServiceTest 7全部执行、无跳过 |
| 新恢复/约束 | Store8、真实事务7、删除清理6、发布HTTP3、发布协调26、架构7均通过 |
| 前端 | 22文件 × 根路径、/dataagent/、/dify/dataagent/；各105项通过，共315 |
| 构建/打包 | npm ci、Spotless、TypeScript/Vite、普通JAR、可执行JAR及tar全部通过 |
| Python | CI采用的独立工具逻辑15项、报告脚本6项通过，不代表全部Python专项 |
| CI配置/报告 | actionlint及完整Surefire报告门禁通过 |
| 耗时 | 最终完整Maven4分17秒，含依赖安装、编译、前端及打包；后端suite计时合计78.879秒，单次样本不作性能保证 |

[全部172个测试类的现行保护价值、代表方法与实际耗时](20261112-test-class-current-results.md)已逐类列出。21项跳过均为明确外部条件，未新增@Disabled；普通CI未配置专用MySQL校验时会多1项环境跳过。本批使用真实schema环境启用了它。

首轮完整检查被一个新测试文件换行格式挡住，已统一格式；中间候选完整1127项通过后，最后补逐库故障隔离/未决写入锁定读，并对最终源码重新完整1129项验证。开发期间的.platform契约冲突、夹具唯一归属冲突、复合主键误断言均已修正并回归；不将失败或中间候选混为最终结果。日志分别保留在.runtime/arch133-full-attempt1.log、arch133-candidate-full.log、arch133-full.log。

完整检查后只补报告、spec归档及共享记忆；952个非文档源码/测试/构建/脚本/资源文件与最终冻结清单逐字节一致；收尾文档一致性5项、真实升级库schema校验1项均通过（6项0失败/跳过），2066条本批文档相对链接无失效。专用容器已核对本批ID及标签后删除；临时数据在tmpfs，无业务宿主卷，不留后台测试服务。

## 日常开发与运行影响

继续使用原启动、前端调试、打包、部署入口；本批不需要手工执行新增迁移。日常通过scripts/check.py选择相关测试，完整clean verify用于提交合并/发布前，CI提供额外验证；CI不替代本地复现和必要选测。

新增journal与marker应随mdl-home一起持久化/备份；不要手工删除未完成意图。删除返回成功后物理清理仍可能待重试，后台重试和日志记录处理物理残留，避免因清理失败假称数据库回滚。发布恢复无法确定时应先检查数据库和保留副本，再按一致版本修复，不自动放行。

当前开发服务未重启、未加载本批新代码；本地构建/提交不代表已上线。分支沿用户个人分支最终合并验证后再由用户决定push/部署。

## 明确保留的风险与后续建议

| 优先级 | 事实/限制 | 后续触发与验收 |
| --- | --- | --- |
| 高 | 跨库/文件不是原子事务，journal与互斥锁只适用于单进程写入；未验断电/磁盘控制器缓存/多实例争用 | 多实例上线前集中锁与持久任务设计；部署环境进程中断/磁盘故障演练 |
| 高 | 迁移SQL人工选择，当前没有自动版本台账；本批只验证0143代表性升级 | 下一次结构升级前制定迁移工具/基线策略，包含历史库差异、失败重试与回退，不能直接在生产补跑完整DDL |
| 中 | DatasetService与ModelingToolkit等仍较大；配置装配长方法、Controller部分HITL和工作区操作仍偏重 | 对应功能新增时抽出上传/关联用例、HITL决策和工作区操作；先冻结鉴权/路径/取消契约，避免同时改产品行为 |
| 中 | web.persistence.jpa包名历史化，runtime/dataset/tools仍双向依赖 | 按变更域逐步确定接口归属，扫描/装配一起验；不为改名迁72文件 |
| 中 | 大量历史、用量、审计、附件记录持续增长；未做生产规模计划与生命周期压测 | 产品明确保留期限、归档/删除恢复、权限与审计需求后实施；先EXPLAIN/容量基准，不擅自删数据 |
| 中 | 13对历史非JPA依赖仍保留，整库重试有单进程批次与存量journal维护成本 | 结合身份/附件/沙箱生命周期专项收口；监控失败记录和清理积压 |
| 环境 | 真实LLM/Wren答案、生产旧内核、完整线上重启、现场数据量和远端GitHub Actions未在本批验证 | 保留external条件与现场验收，不用mock/本地打包宣称通过 |

没有“AI自己开发就一定不会出问题”的保证。此阶段的价值是把已发现的结构和一致性问题改成有明确职责、有故障证据、有自动约束的实现，同时把尚未证明的边界写清楚。
