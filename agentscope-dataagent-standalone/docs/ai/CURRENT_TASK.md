# 当前任务：六阶段连续架构治理及统一验收

更新：2026-11-12 Asia/Shanghai；分支codex/architecture-phase1，起点b63a72de，工作区干净起步。

## 用户授权与交付

六轮全部完成并统一阶段验收，减少提交；本批最终一次本地提交，ID以git log核实。不push/合并/部署、不停止现有开发服务，不以类/测试数量作为目标。

## 本批完成

- [x] 发布恢复：MdlPublicationStore持久意图/旧副本，独立字段级版本事务，恢复锁定读等待未决写入；子JVM中断恢复与逐库故障隔离。
- [x] 发布职责：纯投影MdlManifestRenderer、安装/恢复Store，保留CLI/锁/权限/JSON；提交后清理失败只告警。
- [x] 聊天/入口：ChatTurnPreparation抽选库/Inbound，MdlPublicationController承接六URL，排队撤权/HITL/取消契约保留。
- [x] 导入删除：单数据集事务提交后物理清理、持久重试，回滚/掉库/外部表保护；整库失败重新实例化重试与导入回滚验证。
- [x] 公共依赖：范围/解析器迁runtime.session，精确债务21→13，其他保留原因已逐项记录。
- [x] 库表/升级/备份：本机临时MySQL8.4.5空库37表、35实体及索引/唯一/主键/NOT NULL，0143代表升级、模拟跨库和文件配套恢复。
- [x] 最终验收：独立源码Java17 clean verify/npm ci，172类1129项（1108通过/0失败错误/21条件跳过），前端315项、打包、Python15/报告6、actionlint和报告门禁通过。

[spec133](../specs/done/133-architecture-six-stage-completion.md)、[ADR0145](../adr/0145-local-resource-recovery-journals.md)、[工程分析与验收](../test-reports/20261112-architecture-six-stage-acceptance.md)、[379文件/37表目录](../test-reports/20261112-backend-architecture-inventory.md)、[全部测试价值与耗时](../test-reports/20261112-test-class-current-results.md)。

## 实测与状态

最终完整日志.runtime/arch133-full.log，隔离源码.runtime/arch133-ci-validation；Maven4分17秒。必跑三集成类4/1/7均执行。先前换行格式失败/候选1127结果单独保留，最终1129包含收尾两项改进，不混淆选测或历史1100结果。

952个非文档源码文件与最终冻结清单逐字节一致；收尾文档5项/真实升级库schema1项通过，2066链接无失效，同批统一本地提交。自建容器核对ID/标签后已删除；无宿主业务卷或生产操作。开发服务未重启加载，原启动/部署入口不变，无新增DDL/配置。

## 限制与下一步

仍有大型服务、13对历史依赖和人工迁移台账等后续项，见工程报告；单进程journal不保证断电/磁盘损毁/多实例安全。MySQL演练用静止模拟数据，不代表生产规模/活跃跨库一致恢复。真实LLM/Wren、生产现场、GitHub/远端保护未验。

本批六阶段无剩余实现步骤；下一次功能开发按对应边界和日常选测推进。用户决定后续push、合并到个人分支/develop2.0及部署；不自动开展报告中的后续专项。

旧任务见[归档](archive/CURRENT_TASK-before-six-stage-20261112.md)。
