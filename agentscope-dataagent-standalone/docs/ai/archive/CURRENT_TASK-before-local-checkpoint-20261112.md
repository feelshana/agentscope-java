# 当前任务：发布服务只读预览职责抽取

更新：2026-11-12 Asia/Shanghai；分支codex/architecture-phase1，HEAD5479d152；保留此前所有未提交修改。

## 范围与授权

用户“继续”授权上一轮确定的只读预览抽取，按[spec131](../../specs/131-mdl-read-projection-extraction.md)，沿用ADR0144，无新产品/长期边界，未新增ADR。不提交/push/合并/部署。

## 已实现

- MdlReadService独立负责preview/compiled/view，MdlTypes承载13个原结果record；解析器不再反向依赖发布服务。MdlPublishService保留委托入口及原构造器。
- 全部内部Java类型引用同步，HTTP字段/嵌套JSON维持。发布校验、锁、文件切换、版本和事件等写方法不修改。
- 独立读场景6项与13类型旧版本JSON兼容1项；新增架构规则禁止读组件进入发布/播种、Files写面、workspace非路径及仓库保存/删除。
- ARCHITECTURE与依赖/五主线清单同步；新结果类型为Java内部归属调整，不声明旧嵌套类的二进制兼容。

## 验证状态

- Java17首轮原相关回归71项通过；补充后重点9类83项通过，含完整JpaPersistenceIntegrationTest4项、原发布24项。
- 13类型JSON期望来自阶段2旧编译类的真实Jackson序列化，非新类型生成；模拟数据，无外部业务数据。
- 读写隔离反向变异按预期阻断，恢复源码后clean单元140类1029项全通过、0跳过（1分25秒）；架构5项、读侧6项及兼容1项均通过。见[本轮报告](../../test-reports/20261112-mdl-read-projection-extraction.md)。
- 对照本轮源码快照：52个保留方法/构造器、8个迁移实现及12个其他生产调用方正文核对通过；write方法无算法变化。
- 未重新验证前端/全量integration及条件环境、生产迁移/恢复或真实服务；GitHub首次触发仍待push。

## 待续

本轮实现与验证收尾完成：读取API白名单/方法引用约束增强后，架构/读取/兼容/文档17项及170条相对链接、源码对照/差异检查通过。可审阅。本轮不拆发布写协调；下步先评估校验/计划检查与快照协调的边界及故障断言，恢复演练另列专项。资料同步/双BI仍待用户现场验收，F01–F09不自动实施。

上一阶段见[归档](CURRENT_TASK-before-mdl-read-extraction-20261112.md)。源码、服务加载、提交、部署状态分开记录，未停止开发进程。
