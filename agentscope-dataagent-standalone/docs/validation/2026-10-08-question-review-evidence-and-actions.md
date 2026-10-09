# 问题验收依据与操作闭环验证

日期：2026-10-08。需求：[spec 044](../specs/044-question-review-evidence-and-actions.md)，决策：[ADR 0054](../adr/0054-question-review-evidence-snapshot.md)。

## 完成内容

- 问题验证凭据新增 evidence：外层 SQL 与验证当时全部视图定义目录；复用 MdlWorkspaceReader，在现有工作区锁内生成。验收卡就地展开查看定义，不将目录称作物理执行计划。
- 业务确认直接应用返回 Review，显示确认已保存、确认人和时间，已确认卡撤下原确认按钮，保留重验和退回；服务端 workflow 指引下一步，允许发布时提供入口。
- 建模与验证的问题清单均提供显著添加按钮，复用原生 dialog，可输入多个问题；带入对话仅预填，未发送/未经 HITL 不保存。预填保留未发送内容，按知识库隔离并支持重复添加相同文本。
- 旧凭据可加载，无快照时提示重新验证；模型变更后保留旧证据、状态过期，禁止确认旧结果。

## 构建与自动化测试

独立仓库根 pom 使用 mvn test、mvn package -DskipTests；上层多模块 -pl 路径在此 checkout 不适用。

- mvn spotless:apply / package 内 spotless:check：通过。
- mvn test：445 项，0 失败，0 错误，12 跳过。
- mvn package -DskipTests：通过，含最后的前端输入隔离改动。
- frontend npm run build：通过，最终单独构建也通过。

日志位于 wren-gap/development-{format,test,package,frontend}.log，结果位于相应 -result.json。新增测试验证证据持久化、决定保留证据、视图变更导致过期、旧凭据缺失字段兼容；发布链路测试核对实际生成证据。原多租户服务测试继续通过。

## 浏览器与真实 Wren

使用 8085 独立验证服务与既有测试知识库 2938f8c8-6fee-44aa-a700-6aa8c9769c98，不修改用户 8080 服务的数据。驱动 question_review_browser_check.py 用唯一名称创建临时视图/问题测试文件作为测试前置，实际 Wren 草稿验证查询已支付且未软删除订单，得到 9 条；该 fixture 不声称验证自然语言建模生成质量。测试结束只清理本次唯一命名的视图、问题、凭据与示例文件，没有发布测试草稿。

16 项检查全部通过：真实 Wren 执行、证据含视图定义、同卡展示 SQL、两个阶段添加入口、多问题预填、未发送不写入、确认按钮退出、确认人/时间、刷新恢复、下一步、过期后旧定义保持且不可确认、窄屏无横向溢出、无运行时异常。确认失败提示使用一次受控 HTTP 409 模拟，随后成功确认与刷新均走真实后端接口。

结果：[browser-checks.json](question-review/browser-checks.json)。截图：[视图依据](question-review/view-evidence.png)、[确认与下一步](question-review/confirmed-next-step.png)、[过期保留旧依据](question-review/stale-original-evidence.png)、[窄屏](question-review/narrow-review.png)。两次早期驱动由于未等待异步卡片挂载而中断，已补充等待；最终记录为全部通过。

## 使用范围

8085 验证服务已加载新构建；8080 用户后端需重启加载新后端代码。Vite 默认代理 8080。旧问题的验证凭据没有视图快照，需要点击 Wren 验证当前草稿重新生成，再审阅确认。视图定义目录包含本次验证时全部视图，未实现 SQL 依赖精确裁剪。
