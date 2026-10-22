# 2026-10-20 动态窗口隔离与工具门禁验收

关联：[ADR0099](../adr/0099-runtime-window-policy-and-verifiable-sql-gate.md)、[spec085](../specs/done/085-runtime-window-policy-and-gate-verification.md)。

## 修改

- 平台具体最大天数只在数据库和运行时策略上下文中使用，不写入MDL/knowledge；查询提示以当前数据库为准，配置修改无需发布。
- 建模允许自行选择样例日期，无需用户确认，仍经门禁；只保存符合已确认业务过滤的示例，不新增未确认规则。未批量修改现有工程和已发布MDL。
- 新增logs/sql-window-gate.log，记录实际入口、配置快照、逐表结论、状态、耗时及SQL指纹。拒绝后停止本轮重试。模型调用异常只记录异常类型，不输出接口凭证/请求正文。
- 真实负向验收发现拒绝明细字段可能缺失；补充明确字段规范和拒绝示例后通过。此结论属于有限样本验证，不代表LLM对所有SQL均准确。

## 真实模型与实际适配器验证

命令：`mvn surefire:test -Dtest=SqlWindowLiveBoundaryTest -Ddataagent.test.liveSqlWindowGate=true`。运行前已编译当前代码。测试使用当前项目配置的真实模型、真实SqlWindowGate/FactQueryWindowPolicy/OfficialWrenToolkit/WrenScopedMcpClient，策略数据库仓库和查询执行端使用测试替身，只有合成SQL发送给模型，没有真实业务查询、数据库写入、服务重启或工程发布。

| 场景 | CLI / MCP 结果 |
| --- | --- |
| a为19天、b为14天，未使用c不参与 | 均允许，逐表天数正确 |
| 无日期 | 均拒绝 |
| 单表超窗 | 均拒绝 |
| 联表缺b时间范围 | 均拒绝 |
| 联表b超过自身20天 | 均拒绝 |
| 只有单侧日期边界 | 均拒绝 |
| 中文表名与别名 | 均允许 |
| 未登记视图 | 均允许，明细为空 |
| 不限制配置 | 均跳过，不调用检测模型 |

18项全部通过：6 ALLOWED、10 DENIED、2 SKIPPED_NO_LIMIT。每个拒绝后再次调用SELECT 1也被阻止；10项拒绝执行端调用均为0。16项模型判定的适配器总耗时678–2045ms，中位数952ms（含适配器开销，不是模型内部纯耗时）。结果明细：target/spec085-live-window-results.json；成功日志：target/spec085-live-verified.log。

首次受限网络下模型调用失败；放行模型网络后合法路径通过，又修正测试替身默认方法的装配及拒绝明细提示。保留失败日志spec085-live.log、spec085-live-retry.log、spec085-live-network.log、spec085-live-final.log，不能将失败轮算作通过。

## 回归与待验收

定向38项通过；最终mvn test 686项，0失败0错误19条件跳过（target/spec085-tests-final.log）；mvn spotless:check package -DskipTests通过，包含npm run build（target/spec085-package.log）。本次query_cube覆盖范围不变；视图未匹配接受漏检。服务加载新制品后观察独立日志确认实际用户会话的CLI/MCP路径；本报告不声称HTTP/Wren/数据库端到端验收已完成。
