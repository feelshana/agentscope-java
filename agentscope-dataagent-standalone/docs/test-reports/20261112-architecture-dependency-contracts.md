> 后续交付：spec128–131已统一纳入[本地检查点](20261112-architecture-checkpoint.md)，并补当前完整验证。本报告保留原阶段的测量范围与提交状态。

# 架构依赖契约与计时组件归位（2026-11-12）

在codex/architecture-phase1，基线5479d152，保留阶段1/2全部未提交修改。用户授权继续后，落实[spec130](../specs/130-architecture-dependency-contracts.md)与[ADR0144](../adr/0144-incremental-package-dependency-contracts.md)；这轮完成依赖契约和第一批错层依赖消减，不表示整个架构优化已完成。

## 改动与效果

- 新增ArchitectureDependencyTest，使用仅test scope的ArchUnit核心API与现有JUnit引擎，扫描当前应用生产字节码。版本固定1.5.1，官方发布记录见[ArchUnit releases](https://github.com/TNG/ArchUnit/releases/tag/v1.5.1)，API用途见[官方说明](https://www.archunit.org/)。不增加运行时框架或部署服务。
- 四项门禁：四根包/工具包归属；下层不依赖HTTP/应用装配；JPA Entity/Repository不反向依赖其他业务包；历史下层→web非JPA精确类对不得增加或残留过期例外。
- 清单保留21组历史类对及原因，覆盖身份、会话、沙箱、附件、LLM辅助及容量职责。web.persistence.jpa仍按现有适配包使用，并未把所有web包视为HTTP层。门禁不自动更新基线，不按耦合数量阈值抵消新增问题。
- 通用ExecutionTimingMiddleware从web.middleware迁runtime.observability，所有消费者同步更新；InvocationContextKeys归runtime.context，ToolEventBus公开常量保留原值别名。组件顺序、日志字段/阶段、trace与计数、CallTiming.Observer、请求/会话关联、SSE协议均保持。
- 用上一轮干净副本字节码复核，9个非web外层类曾依赖旧web计时类，迁移后这些反向类对消除。它们是FactQueryWindowPolicy、WrenPublishedProject、WrenQueryPreparation、HarnessGateway、QuestionDateGate、WrenScopedToolkitMiddleware、WrenInstanceRegistry、OfficialWrenToolkit、WrenScopedMcpClient；仅import扫描会漏掉其中若干全限定名调用。
- 完成[职责与五条主线清单](../reference/architecture-boundaries.md)：聊天/问数、建模发布、上传导入、排队撤权、删除；区分数据库事务、文件补偿、外部进程和重启恢复，明确单数据集删除与整库journal恢复不同。
- CI继续完整clean verify，附件增加target/architecture静态报告；现有开发/部署入口保持。本轮没有前端业务、HTTP接口、库表或生产配置变更。

## 实测结果

Windows、Java17.0.14，日志位于忽略目录.runtime；以下均为本轮实测，不使用上轮完整回归冒充本轮结果。

| 检查 | 结果 | 耗时/说明 |
| --- | --- | --- |
| 重点回归 | 11类65项通过、零跳过 | Maven44.307秒；计时、查询准备/发布工程、权限、原生MCP、作用域、日期门禁及完整JpaPersistenceIntegrationTest |
| 反向变异验证 | 临时加入三种违规，四项架构规则中对应三项失败、0错误；探针已删除 | 验证业务全限定名调用控制器、JPA Entity依赖业务、未知下层→web类对，不是只断言当前绿灯 |
| 清理探针及旧.class后单元通道 | 138类1021项全部通过，0失败/错误/跳过 | Maven1分18秒，含干净重新编译；与上轮带缓存43秒不可直接比较 |
| 架构类本身 | 4项通过 | 单元通道报告3.721秒，包含字节码导入 |
| 运行时依赖核对 | dependency:tree运行范围不含ArchUnit | 仅test scope，未改生产依赖 |
| 工作流静态检查 | actionlint通过 | 未推送，GitHub尚未实际触发 |

单元项变化：上轮1015 + 架构4 + 计时异常/无上下文2 = 1021；原计时回归随迁移保留，新增两项覆盖独立后台记录的空关联字段以及计时元数据失败不能改写Agent成功/失败结果。新类不带integration/external标签，自动进入日常单元与完整CI。新增测试价值是边界和退化行为，不按数量衡量质量。

首轮空债务清单按预期报告现有类对，完成逐项原因核对后登记；没有为HTTP/装配等硬规则登记例外。补充过期例外变异：临时加入已不存在的类对，按预期阻断并恢复清单（此补充使用默认Java21.0.6）；随后Java17架构/文档9项全通过。最终1151条相对链接、生产调用方仅计时类型引用变化的对照、计时实现等价核对及git diff --check通过。

## 实际依赖矩阵

来自本次干净生产字节码，371个Java源码文件，去重后1435个应用内部外层类对（含同根依赖）。下表只计跨根类对，行是调用/依赖方，列是目标；不是import数、方法调用次数或运行耗时。web方向包含大量JPA与公共能力，不能直接判断为违规。

| 来源 → 目标 | dataset | runtime | tools | web |
| --- | ---: | ---: | ---: | ---: |
| dataset | — | 15 | 5 | 131 |
| runtime | 30 | — | 7 | 20 |
| tools | 59 | 7 | — | 17 |
| web | 107 | 87 | 15 | — |

原始文件在target/architecture/{class-dependencies,root-dependencies,legacy-web-dependencies}.tsv，CI附件也将保存。历史类对源清单与原因见[清单](../../src/test/resources/architecture/legacy-web-dependencies.tsv)，报告文件不是自动放宽门禁的输入。

## 限制与下一步

静态检查不覆盖反射字符串、配置注入、Spring运行循环或跨资源原子性；类对合并不限制既有类对内新增方法依赖，现有跨根循环仍然存在。常量可能被javac内联，字节码图不是源文本引用的完整替代。已有删除重试实现不能作为强杀/重启验收证明。

本轮未重新运行包含所有integration/条件类的完整后端、前端三前缀或打包；它们的上轮结果仍只属于阶段2。已运行本轮相关完整应用装配和干净单元通道，符合当前按变更选测规则。未运行真实LLM/Wren/MySQL/Docker、恢复演练、服务重启、生产迁移或部署。合并/发布前仍需完整clean verify；GitHub首次触发与远端分支保护尚未验证。没有提交、push、合并、部署或停止现有开发进程。

下一批优先评估MdlPublishService只读预览/展示投影的职责抽取，保留发布互斥、授权、文件/版本更新顺序及现有契约；发布/删除的跨资源恢复另立故障演练，不在这轮顺手改写。
