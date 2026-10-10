# 建模修正与对话空间验证（2026-10-08）

## 日志与根因

原项目访问人数 Top1 问题 SQL 带 LIMIT 1，常用问题预检禁止显式 LIMIT/OFFSET，因此不能采用。字段样例被当作实际最新日期，以及普通口径确认被当作指定必验，也是日志中的准确性问题，已完善提示约束。

更深的阻断是原生拒绝后历史 ToolUseBlock 仍为 ASKING，但已有 DENIED ToolResultBlock。独立 JUnit 调试观察 `ASKING|result=DENIED|pending=1`，建模中间件在原生全部拒绝分支再次暂停，不能进入修正推理。已处理调用过滤与 currentSession 恢复投影均已修正。原生恢复不保存确认文本，因此通过调用独有 RuntimeContext 在匹配 DENIED 后补入反馈；仍复用原生循环，不自动批准工具。

## 已执行验证

- `mvn spotless:apply`：通过。
- `mvn test`：443 项，0 失败、0 错误、12 跳过；包含拒绝后委托、旧卡不可恢复、反馈参数校验、调用隔离与无 LIMIT 的排名 SQL 门禁。
- `mvn package -DskipTests`：通过，2026-10-08 10:35:38 完成。
- `cd frontend && npm run build`：通过。
- 浏览器 12 项检查全通过，详见 [检查结果](modeling-repair/browser-checks.json)。在既有测试知识库和独立会话中，以真实 LLM 创建未批准提案；测试仅替换展示草案为含 LIMIT 的错误内容，并将会话请求定向到独立测试会话。预检、原生拒绝/修正、后续新提案与 Wren 预检均为真实服务调用。不是原业务 Top1 查询的完整数据准确性验收。
- 原错误卡不能采用；点击“让助手修正”后真实原生流生成不同工具 ID 的有效新提案，新确认卡可见，未自动写入问题文件。
- 1440×900 下文档栏 70px，对话区 1092px，侧栏 300px；弹窗查看全文无布局变化，760px 窄屏无横向溢出，无运行时异常。

## 运行与证据

仅更新经进程身份核对的 8085 验证服务，保留其元数据和模型，未重启用户的 DataAgentApp/8080 调试服务。前端 Vite 当前默认代理 8080；该服务需要重新加载后端代码才能使用本次修复。

- [失败卡片](modeling-repair/failed-proposal-desktop.png)
- [修正后的确认卡](modeling-repair/repaired-proposal-desktop.png)
- [文档弹窗](modeling-repair/document-dialog.png)
- [窄屏](modeling-repair/narrow-screen.png)
- [调试证据](modeling-repair/debugger-evidence.txt)
- [修正请求](modeling-repair/repair-request.json)

独立调试测试已停止，助手断点已移除；用户原有 DataAgentApp 会话和异常断点保持原状。既有业务组的待确认提案未自动批准或修改。

关联：[ADR 0052](../adr/0052-modeling-preview-repair-feedback.md)、[ADR 0053](../adr/0053-native-hitl-resolved-call-boundary.md)、[spec 043](../specs/043-modeling-repair-and-chat-space.md)。
