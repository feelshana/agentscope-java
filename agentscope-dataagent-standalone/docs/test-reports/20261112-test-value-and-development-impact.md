> 历史基线：本报告记录阶段1/盘点时状态；后续分层、清理及最新实测见[阶段2报告](20261112-test-lanes-and-cleanup.md)。

# 2026-11-12：全量测试保留价值、耗时与开发影响评估

## 结论

测试文件不应因为存在时间长或过去被禁用就永久保留；也不能因为暂时不跑某功能就只删除其测试。按现行调用、实际断言和最近报告盘点后，大部分仍保护有效行为，7个类是值得配合旧生产代码清理的候选。候选清理对降低维护负担有价值，提速收益很小。优先提速的是测试执行范围、前后端生命周期耦合和上下文初始化。

本轮仅评估、生成清单和记录建议，不删除测试，不调整pom、CI或AGENTS验证规则，不开始业务代码重构。无新增功能spec/ADR；第一阶段未提交的实现继续保留。

## 范围与证据

- 本产品 `src/test/java`：171个Java文件，其中168个测试类、3个辅助文件；对应168份干净Java17全量报告，共1150项，1129执行通过、21条件跳过。
- 前端：22个测试文件，105个不同用例，在3个部署前缀各执行一次。
- Python：15个文件内17个TestCase类、122个方法；另核对新增CI汇总脚本的6项回归、Wren兼容脚本和容器Java夹具。
- [逐类清单](20261112-test-class-inventory.md)覆盖每个类/文件，列出关联现行代码、具体测试方法、断言摘录、耗时、保留或处理建议。类与生产代码的关联采用源码引用和断言核对，不声称取得动态覆盖率。
- 耗时引用上一阶段Windows/Java17隔离副本的完整运行；本轮没有再耗时重跑1150项。Python122项无本轮运行/耗时证据，不将其记作已通过。上游框架、docs/archive历史探针不属于本次产品测试集合。

## 逐类建议汇总

以下分类互斥，合计168个测试类；每个类内部仍可存在细节需要调整，保留不等于每条文字断言都不可修改。

| 主建议 | 类数 | 含义 |
| --- | --- | --- |
| 保留现行回归 | 123 | 解析、权限、数据一致性、会话、协议和现行服务仍有对应行为 |
| 保留并优先提速 | 13 | 真实Spring/JPA上下文或持久化验证，适合减少重复初始化 |
| 保留条件专项/混合用例 | 15 | 真实环境部分单独启用；混合类中的快速用例不能整类跳过 |
| 保留核心，改写文字/结构断言 | 6 | 精确提示词/反射结构断言应收敛；不能当作真实模型行为证明 |
| 保留并细化/拆分部分断言 | 4 | 当前功能与历史方法混合，或反射断言适合改成行为验证 |
| 生产代码配套清理候选 | 7 | 下表所列，需要迁移有效断言并确认废弃边界后再删 |

3个辅助文件是完整应用测试基类、租户表夹具和空数据模型评估夹具，不是漏测或无用的测试类。`AdminModelServiceControllerTest`使用WebTestClient的expectStatus/expectBody断言，不能因没有assertEquals误判为空测试。

## 可清理的具体候选

| 测试类 | 项数 | 上次秒数 | 判断依据与处理边界 |
| --- | --- | --- | --- |
| RefSqlMaterializerTest | 17 | 0.006 | 对应公共工具未找到本模块生产外部引用，且没有Spring装配；可优先评审连同工具删除 |
| KnowledgeEvidenceTest | 9 | 0.013 | 只为旧DatasetService.evidenceFor提供分段/排序；该方法未找到生产调用 |
| DatasetServiceEvidenceTest | 8 | 0.029 | 验证上述旧平台证据检索；主问数已使用官方Wren知识工具 |
| KnowledgeGraphServiceTest | 6 | 0.164 | 服务与控制器只在knowledge-graph.enabled=true时装配，当前产品规则已停用KG |
| SchemaTripleExtractorTest | 1 | 0.002 | 当前生产引用仅来自停用的知识图谱服务 |
| WrenToolkitTest | 38 | 0.132 | 旧工具未找到生产装配；仅被下一个旧包装器引用。CSV/错误格式的有效场景需先核对QueryDataFiles/WrenScopedMcpClient覆盖 |
| WrenMcpToolkitTest | 2 | 0.009 | 旧wren_call_tool包装器未找到生产装配；主流程直接注册官方schema。迁移收据/脱敏保护后再清理 |
| 合计 | 81 | 0.355 | 无法把删掉81项描述为可显著提速 |

这是静态证据支持的候选，不是已证明所有外部插件/反射场景都不可能使用。处理时须搜索实际配置、保留适用断言、编译与回归，并同步文档；不删除历史数据库数据。旧Wren包装器还被DataAgentConfigTest用于描述检查，清理时要改写该检查，保留已发布/只读边界。

没有把所有“旧名字”都列为无用：DocEnhanceService仍有现行HTTP调用，DataDynamicContextMiddleware仍给非主Agent装配，MdlSuggestionService仍维护手工关系；SemanticModelingCubeRetirementTest、BiAuthHttpTest中的关闭旧端点断言则在防止退役功能重新暴露。前端对旧历史工具结果的呈现兼容同样应保留。

文本重复筛查未找到可据以直接删除的完全相同测试体，不能由此断言没有逻辑重叠。当前也未证明任何整个现行测试类可以仅因“重复”安全删除。

## 等待时间的主要来源

上轮后端全部类报告耗时相加86.885秒；带Maven启动等开销的纯后端命令为2分02秒。13个真实上下文/持久化类只有41项，却占43.647秒，约一半类报告时间；大量解析/范围等用例虽多但很快。这些是一次样本，冷启动、系统和执行顺序都会影响结果。

较慢的类：AdminHistoryHttpTest 9.658秒、BiAccountKnowledgeHttpTest 6.112秒、MarketContributionServiceTest 4.834秒、H2DemoSeedTest 4.172秒、ChartOwnershipHttpTest 4.137秒、BiAuthHttpTest 3.574秒。都有真实HTTP、JPA或完整装配价值，优先优化夹具/上下文，不直接删。

当前pom把npm安装、前端三个前缀测试和Vite生产构建都绑定到generate-resources。因此：

- `mvn test`会同时做前端工作；`mvn -Dtest=某类 test`通常仍做前端工作。
- `mvn package -DskipTests`只跳过后端Surefire测试，现有前端测试目标仍会执行。
- 完整clean verify的3分42秒包含依赖安装、前端构建、后端重新编译、测试和打包，不能全算成“JUnit太多”。

上一阶段还额外做了干净副本npm ci和Java17验证，部分检查为定位Windows原生文件占用而重复，不应变成日常每次改动固定重复多轮。

## 三个恢复类具体值不值得

本次13个上下文类中包含这三类，上轮合计11.970秒，原有12项都实际通过：

| 类 | 保留价值 | 执行方式建议 |
| --- | --- | --- |
| JpaPersistenceIntegrationTest | 账号CRUD、Agent定义/分享持久化与实际应用装配，仍有效 | 保留；后续可把纯CRUD移到JPA切片，保留小规模完整启动检查 |
| MarketContributionServiceTest | 当前贡献/审批接口仍使用服务，数据库与共享文件的一致性有价值 | 保留；可收窄为JPA+文件集成，业务确定下线后才连同功能清理 |
| H2DemoSeedTest | 本地H2开发模式仍存在，文件模式初始化和密码哈希曾容易漏测 | 保留但优先级低于主线权限/发布，可归入H2或初始化相关验证 |

恢复覆盖不要求这三个类永远保持当前完整Spring上下文，也不要求每次小改都运行它们。CI中现有4/1/7最小数量约束是防漏跑，若经评审重组/删除这些场景，应同步调整门禁，不能让测试数量成为永久不可改变的产品要求。

Spring可缓存相同上下文，动态属性、profiles和DirtiesContext等会影响复用。不能直接删掉DirtiesContext提速后让不同临时目录/数据库互相污染；需要一起设计夹具生命周期。[Spring官方说明](https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html)

## 下一步更有效的提速方案（建议，未实施）

1. **日常编辑时按改动选测**：后端改动先运行相关类并跳过前端目标，前端改动先运行对应测试/类型检查；保留清晰的已测范围。
2. **合并/发布前完整验证**：由CI执行一次候选版本完整检查，关键业务人工验收补充。相同版本已通过的同项检查不机械重复。
3. **优化上下文**：保留少量完整应用启动验证，其余能用JPA切片/局部应用上下文的用例缩小范围；保证原来的业务断言仍在。
4. **迁移历史链路**：逐组清理上述7个候选及对应生产代码，主要减少理解和维护成本。
5. **整理提示词与真实专项**：保留资源/安全规则，减少对中文原句的绑定；15个条件/混合类按具体方法分层。WrenProcessEnvironmentTest和UserSandboxDockerReadinessTest还有无需真实环境的快速用例，不整类排除。
6. **补齐Python入口说明**：122项并未进入当前默认Maven或新增应用CI；识别可快速运行的单元测试，部署/同步变更时再启用独立Docker/MySQL夹具，不能为了“全跑”连接生产。

例如，已有配置支持在standalone目录执行：

```powershell
mvn "-Dtest=DatasetMetadataDraftSyncTest" "-Dskip.npm=true" "-Dskip.installnodenpm=true" test
```

这是指定后端测试的执行方式，不代表全量已验收。Surefire支持指定测试类，[官方说明](https://maven.apache.org/surefire/maven-surefire-plugin/examples/single-test.html)。当前pom还设置failIfNoSpecifiedTests=false，选错名称可能漏跑，使用时应核对Tests run；正式快测入口适合增加防漏跑校验。

AGENTS目前仍规定任务收尾的完整检查，本轮没有悄悄修改。若采纳分层方案，应把日常验证与合并/发布门禁写清楚，并相应修改pom/脚本/CI和规则后再执行。

## 第一阶段对原开发、运行和部署的影响

| 操作 | 当前影响 |
| --- | --- |
| 继续写功能/运行前端开发服务器 | 编辑入口和命令不变；当前目录在优化分支，尚未并回个人分支，继续功能开发时需明确目标分支 |
| 正常启动后端 | 启动类、应用配置、数据库与业务HOME不变；测试HOME参数不作用于正常应用 |
| Maven运行测试 | 增加12项有效执行；测试进程user.home改为target/test-home，不复用开发者HOME下的配置和运行数据 |
| IDE直接运行完整应用JUnit类 | 需要额外留意：pom的Surefire user.home不自动作用于IDE JUnit。测试基类隔离Spring目录，但Bootstrap的静态默认路径读取JVM user.home；宜委托Maven运行，或为测试运行配置专用-Duser.home。不要把该测试参数加到正常应用启动配置 |
| 打包 | 原命令和JAR/tar布局不变；已有前端测试/构建仍执行，没有新增DDL或生产配置要求 |
| 推送/合并 | 工作流推送后才可能运行；本轮尚未推送。CI失败是否禁止合并取决于远端分支保护，本轮未修改 |
| 线上部署 | 仍按原来的人工发布入口；CI无自动部署，不会因推送直接重启服务 |

本轮没有以删除测试来改动这些操作。IDE隔离边界是本次审查发现的当前实现限制，后续完善测试入口时应处理；Maven验证结果不能代替IDE直接运行方式的验证。

本轮文档5项、清单完整性、932条相对链接与git diff --check通过；未再次执行完整业务回归，也未删除测试或修改第一阶段代码。
