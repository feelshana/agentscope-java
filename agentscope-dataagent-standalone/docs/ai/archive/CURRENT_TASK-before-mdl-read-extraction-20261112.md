# 当前任务：模块依赖契约与首批反向依赖消减

更新：2026-11-12 Asia/Shanghai；分支codex/architecture-phase1，HEAD5479d152；保留阶段1/2全部未提交修改。

## 范围与授权

用户要求“好的，继续后续”，按[spec130](../../specs/130-architecture-dependency-contracts.md)、[ADR0144](../../adr/0144-incremental-package-dependency-contracts.md)落实依赖规则和计时归位；本地实施，不提交/push/合并/部署。

## 已实现

- ArchitectureDependencyTest扫描生产字节码：四根归属、下层不依赖HTTP/装配、JPA实体/仓库叶节点、历史非JPA反向类对不增长/不留过期例外。ArchUnit1.5.1仅test scope，普通JUnit执行。
- 21组历史类对逐项注明原因，无整包豁免/自动更新；报告写target/architecture，CI附件增加该目录。
- 通用计时迁runtime.observability，关联键归runtime.context，保留ToolEventBus同值别名；消除9个下层类→旧web计时组件依赖。
- [依赖与五条主线清单](../../reference/architecture-boundaries.md)记录职责、权限、事务/文件/子进程及恢复差异；当前仍有跨根循环，不宣称理想分层完成。

## 本轮验证

- Java17重点11类65项全通过，含完整应用JpaPersistenceIntegrationTest，范围以日志运行类为准。
- 临时三种违规分别触发3项规则失败，探针已删除；随后clean重新编译，单元138类1021项全通过、0跳过（Maven1分18秒，架构3.721秒）。
- runtime依赖不含ArchUnit；actionlint通过；过期例外变异也阻断（补充Java21），恢复后Java17架构/文档9项及1151条相对链接/差异检查通过。详见[本轮报告](../../test-reports/20261112-architecture-dependency-contracts.md)。
- 未重跑完整前后端/打包，阶段2完整结果仍为历史；未运行真实模型/Wren/MySQL/Docker或重启恢复/服务验收。

## 待续

本轮代码、验证、文档与共享记忆收尾已完成，待用户审阅。下一批优先评估MdlPublishService只读预览/展示投影抽取，不改变发布锁/权限/版本/文件顺序；跨资源恢复另立专项。GitHub首次CI尚未触发，远端门禁未改；资料同步/双BI待用户运行验收，F01–F09不自动实施。

阶段2快照见[归档](CURRENT_TASK-before-dependency-contracts-20261112.md)。当前源码未提交，未部署或停止开发进程。
