# spec130：模块依赖契约与首批反向依赖消减

## 背景与授权

用户要求在测试治理完成后继续架构优化。沿用codex/architecture-phase1及全部既有修改；本轮落实初评A4与治理顺序2/3，局部消减计时组件错层依赖，不批量拆分核心业务大类。

## 方案

1. 使用仅test scope的ArchUnit核心库，由普通JUnit测试分析生产字节码；不引入额外测试引擎。现有单元通道和完整CI自动执行。
2. 生产类仍限定web、tools/data、dataset、runtime四根；dataset/runtime/tools不得依赖HTTP控制器、web.api、web.config及应用入口。JPA实体/仓库不反向依赖其他业务包。
3. web.persistence.jpa仍是现有平台持久化适配包，允许业务使用；其余dataset/runtime/tools到web的既有依赖以精确外层类对及原因登记，新增或过期例外失败，不按整个包豁免，不自动更新基线。
4. 将通用ExecutionTimingMiddleware迁runtime.observability；将运行上下文关联键归runtime.context，ToolEventBus保留同值别名。消费者同步迁移，不改变日志字段、计数、SSE/requestId或业务行为。
5. 形成五条主线的入口/授权/事务与文件/外部调用/恢复边界清单及实际依赖矩阵；写ADR0144说明渐进边界，作为后续职责抽取依据。

## 验收

- 字节码扫描只覆盖当前应用生产类，四根覆盖且非空；检测全限定名调用，不只搜索import。
- 规则能阻断新业务→控制器/装配依赖、JPA实体反向依赖及新增历史类对；变异验证后恢复源文件，不扩大例外让测试通过。
- 计时回归保留请求/会话/trace关联、模型/工具终态、Observer计数与不影响执行的行为，补上下文缺失/故障退化检查。
- 重点权限/查询准备/原生MCP/工具事件/聊天及完整应用装配回归通过；记录架构检查自身耗时。按本轮影响运行单元通道与格式/文档，不重复无关前端构建。
- 无HTTP接口、库表、生产配置或部署步骤变更；不提交/push/合并/部署，不把静态依赖门禁当作Spring装配、运行正确性或恢复验证的替代。

## 本地验收状态

本轮实现与验证完成：Java17重点65项、干净单元1021项、最终架构/文档9项通过；违规与过期例外变异均阻断。细节见[报告](../test-reports/20261112-architecture-dependency-contracts.md)。尚未提交/push或触发GitHub，暂留当前规格区；合并前仍需完整clean verify。
