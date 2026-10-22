# 问题资产与建模审阅流程验证

对应 spec 046 / ADR 0056。

实现：问题 YAML 的 modeling 声明、资产覆盖校验、发布快照状态、编号列表与选中详情、第三步发布快捷引导、Prism 高亮与放大阅读、原生确认防重复提交与编辑后一次校验写入。同步 ARCHITECTURE_zh.md。

后端全量验证：`mvn test`，451 个用例，失败 0、错误 0、跳过 12（2026-10-08 18:33）。新增用例覆盖资产缺失、SQL 未引用及字符串伪引用、Cube 基础对象、声明改变使确认失效、历史问题兼容、按知识库隔离发布状态、受保护写面强制声明和越权拒绝。Spotless 检查通过。

单次确认排查：在本机 8080 建立独立 confirm-probe 知识库，通过原生流提交一项业务规则，再提交一次 ConfirmResult。同一工具调用只执行一次，未再次出现 HITL。此测试未复现用户所述重复弹卡，不能据此声称已确定其全部根因。SSE 证据见 wren-gap/single-confirm-result.json。使用 ij-debugger 的不暂停日志点观察 Acting 输入；测试结束已移除助手创建的日志点，保留用户原断点与服务。

最终构建：`mvn package -DskipTests` 于 18:34 成功；其 frontend-maven-plugin 执行的 `npm run build`（TypeScript 检查及 Vite 构建）成功，Spotless 门禁成功。

在隔离服务 8085 上，以最终构建的独立 JAR 完成真实浏览器验证，12 项检查全部通过：编号列表、仅显示选中问题详情、15px 阅读字号、放大阅读弹窗、第三步发布快捷入口、已发布问题状态、发布后问数指引、写入预览字号、单次点击实际写入、同一提案不再次确认、窄屏无横向溢出、无运行时异常。

测试知识库 `76649222-bc35-4c9c-88e4-7d672b3b0b92` 的两项已确认问题经页面发布为 v2；随后发起独立规则写入提案，浏览器只点击一次“采用并写入”，确认请求计数为 1，工作区文件存在且正文正确，同一工具调用不在待确认列表中。测试未修改用户业务知识库，未重启用户 8080 服务。当前运行的用户后端需重启并刷新页面才能完整使用新实现。

证据与复测脚本：

- [浏览器检查结果](modeling-review-journey/browser-checks.json)
- [已发布问题列表截图](modeling-review-journey/published-question-list.png)
- [SQL 放大阅读截图](modeling-review-journey/enlarged-query-sql.png)
- [单次点击写入截图](modeling-review-journey/single-click-written.png)
- `wren-gap/modeling_review_journey_browser.py`
- `wren-gap/development-test.log`、`wren-gap/development-package.log`

资产覆盖检查确认声明的资产存在、查询引用对应逻辑对象（Cube 检查基础对象），不替代业务语义判断；业务口径和结果仍需通过 Wren 执行后由用户确认。历史问题继续兼容为 SQL 示例，不自动推断或追溯生成视图/Cube。
