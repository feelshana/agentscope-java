# 2026-11-05 BI对象字段扩展：本地验收

范围：ADR0138/spec117；bi_report 23列、bi_dashboard 39列，七表95列。
用户取消本轮SQL问答页UI，四个相关源码文件恢复HEAD并移除本轮新CSS；保留图片加载优化及另一任务待评审原型。
用户明确不升级原知识库，将在线上另建知识库关联七表并建模，旧库自行删除；最终没有Java业务源码变更。

## 实测
- 真隔离MySQL 5.7.44→8.4.5：20项通过。每轮独立带归属标签的容器/随机测试口令，只停本轮容器且保留数据。
- 覆盖：39→95列、源NAME大小写及int显示宽度归一化、长文本/4000字符model_ids、配置字段不进入目标、
  migrate重复执行与第二表失败后续作、源缺字段/错类型先拒绝、异源/异常目标/锁竞争拒绝、
  未暂停拒绝、补齐前resume拒绝、基础表刷新失败事务回滚、暂停旧结构定时sync安全跳过。
- 升级后逐项比较旧控制表所有键值与日志原始行：原owner/源指纹/水位/账本/成功截止日完全保留。
- refresh-basics不调用日志同步；五表计数一致，新增字段实际值核对通过。
- Linux准备/切换/cron隔离14项通过。宿主Windows缺fcntl，改Linux；临时容器初次缺可写/可执行tmp目录，调整测试容器tmpfs后通过，无产品改动。
- Java BiSyncStatusServiceTest/AdminBiSyncControllerTest/DocsConsistencyTest共12项通过。
- 图片缓存专项7项包含在前端三路径各85项回归内，全部通过；TypeScript与/dify/dataagent/生产构建通过。
- Maven Spotless检查、文档检查及跳过测试的应用打包通过。
- 知识库刷新字段曾临时探索，用户缩小范围后源码与专项测试全部撤回，不作为本次交付或验证结论。

## 边界与现场步骤
未连接66或真实BI库，未执行生产DDL/同步/备份；线上MySQL8.0.36、源数据量及真实问数待现场。
DDL隐式提交，不承诺跨表回滚；失败保持暂停重试，不重置owner/水位、不直接回退旧脚本。
先旧代码pause/status确认running=false，再更新发布，migrate、refresh-basics、resume；
本次不重新init、不重装cron、不回扫日志、不备份。之后由用户新建知识库并建模。
见[升级指引](../guides/66-bi-asset-fields-upgrade.md)。
