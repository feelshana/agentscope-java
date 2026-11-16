# 构建校验执行职责抽取（2026-11-12）

按用户在检查点46facb45后继续的指令实施[spec132](../specs/132-mdl-build-checks-extraction.md)，分支codex/architecture-phase1。沿用ADR0144，本轮无新产品、长期边界或ADR；验证完成后独立本地提交，新ID以git log核实，不push、合并或部署。

## 抽取结果

- 包级[MdlBuildChecks](../../src/main/java/io/agentscope/dataagent/dataset/MdlBuildChecks.java)接收协调器已选定的工程目录、解析Snapshot和外部源，执行View/ref_sql、Cube检查与CLI诊断。依赖只有WrenProperties、WrenCli、WrenProfileHome、ObjectMapper，不持有仓库、工作区、事件、事务或锁。
- 迁移6个原方法：dryRunSqlObjects、dryRunViews、dryRunDerivedModels、derivedModelsOf、dryRunCubes、parseWrenOutput及摘要正则；复制两个很短的issue/firstLine辅助，避免为格式化再造跨层工具。无新对外API、Bean或构造器参数。
- MdlPublishService仍完成来源选择/预检、权限、publish锁→workspace锁、播种/build、快照切换、元数据/版本与事件；在原调用位置委托检查，传selection.external作为既有选择结果，不重复选源。
- 当前顺序保持：validate/build → View/ref_sql检查 → Cube检查 → 已确认问题dry-plan → 快照/版本/事件。单题/批量计划、问答失效/撤销、scratch生命周期仍在原协调流程，未与检查执行一并搬迁。
- 临时连接文件继续每次单独创建、显式传给dry-run、finally删除；命令、中文问题文案、严重性/来源、异常传播和诊断接受规则沿用原实现，未顺手合并build或改变恢复策略。

主服务1609→1424行，新组件238行。新类/import/构造代码略增总量，价值是分清执行检查与持有发布状态的职责，不将行数变化当作性能、正确性或恢复保证。

## 边界检查

ArchitectureDependencyTest新增校验组件规则：应用依赖精确限定为输入/CLI/配置/连接/记录类型，禁止依赖平台仓库、发布协调/读预览、播种、Spring事务/事件、锁、JDBC或ProcessBuilder；Files仅允许createTempFile/deleteIfExists，props仅timeout，profileHome仅connectionInfo。读侧规则也禁止进入MdlBuildChecks。

约束涵盖直接字节码调用和方法引用，不是完整的反射/第三方IO/参数污点证明；连接内容写入经ObjectMapper，临时路径与命令细节由独立测试和方法等价对照验证。没有扩大原21组历史反向类对例外。

## 本轮实际验证

Windows、Java17.0.14，源代码起点46facb45且工作区干净；日志与原主服务/架构测试快照在忽略目录.runtime/phase5-*。

| 检查 | 结果与范围 |
| --- | --- |
| 首轮重点 | 8类97项，0失败/错误/跳过，Maven40.164秒；含发布24项、共享建模授权、问题、资产、控制器及Spring装配 |
| 校验组件独立用例 | 8项通过，完整后端中0.146秒，无真实CLI进程、数据库或外部网络 |
| 负向变异 | 临时加入Files.writeString工程写操作，新增规则按预期失败；探针未执行、已恢复原源码，未放宽规则 |
| 干净完整后端 | clean后运行backend无筛选：167类1100项，1079通过、0失败/错误、21条件跳过；Maven1分48秒，含重新编译 |
| 真实上下文/必跑类 | JpaPersistenceIntegrationTest4、H2DemoSeedTest1、MarketContributionServiceTest7均实际执行、0跳过 |
| 架构与装配 | 架构6项通过（4.004秒）；MdlPublishWiringTest1项通过，构造与权限/删除/事件装配保持 |
| 完整报告门禁 | 干净XML计数及必跑/跳过分类检查通过，不混合旧报告 |
| 源码对照 | 49个保留方法/构造器，除检查委托与对象初始化外正文匹配；6个迁移方法、摘要正则及2个格式辅助逐项等价 |

1091→1100来自独立校验8项和架构1项；原测试没有删减，21条件跳过没有隐藏。本轮跳过前端Maven目标，未重跑前端/打包；父检查点完整前后端clean verify只作为历史结果。

新增8项的保留价值：

| 场景 | 保护内容 |
| --- | --- |
| diagnosticsKeepIssueSeverityTextSourceAndOrder | ERROR/Error/警告的严重性、文案、来源与顺序 |
| unexplainedExitKeepsBoundedFirstLineAsAnError | 无可解析诊断的非零退出不能假绿，提示长度保持 |
| warningOnlyNonzeroExitKeepsTheExistingWarningContract | 原有仅警告的退出处理不因搬迁改变 |
| noSqlAssetsDoNotResolveConnectionsOrInvokeWren | 无SQL资产不接触连接与CLI |
| viewsThenDerivedModelsUseTheSelectedConnectionAndCollectEveryFailure | 视图先于派生模型、引号转义、选定外部源、显式连接文件、逐项收集错误与删除临时文件 |
| unexpectedCliFailureStillRemovesTheConnectionFile | 意外异常传播时仍清理含连接信息的临时文件 |
| connectionPreparationFailureIsLocalAndDoesNotInvokeWren | 连接准备失败转为local问题，不调用CLI |
| cubesUseAllMeasuresSkipEmptyCubesAndContinueAfterFailureWithoutAConnection | 所有度量同批、空Cube跳过、失败不遮盖后续、无连接参与 |

收尾文档一致性5项、168条相对链接、源码等价对照复核与git diff --check通过；前端、构建配置及历史依赖例外清单未变化。生产源码在完整后端验证后没有再修改。

## 未改变与未验证

HTTP、DTO/JSON、构造器签名、生产配置、库表和前端未变；发布/校验原命令次序、授权/锁、文件和版本算法未改。代码完整后端通过不代表真实Wren/MySQL/模型/Docker已验收，更不等于生产故障恢复或跨实例互斥已验证。

原CLI诊断和异常分类逻辑按原样迁移，不宣称本轮校正了所有诊断异常；Files删除失败等未专门注入的分支只保留原逻辑。没有执行真实外部环境、kill/restart、迁移/备份、容量、GitHub首次CI/远端保护或线上验收，也未启动/停止现有服务。

后续先核对元数据投影与快照切换/恢复的输入、写点及失败断言，优先抽取纯计算；不要在没有故障演练时拆开快照交换或扩大事务。资料同步/双BI用户运行验收与F01–F09边界继续保留。
