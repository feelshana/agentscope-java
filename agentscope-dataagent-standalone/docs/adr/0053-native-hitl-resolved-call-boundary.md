# ADR 0053：原生 HITL 的已处理调用边界

- 日期：2026-10-08
- 状态：已接受

## 背景

ADR 0052 联调发现拒绝后仍无法修正。独立测试在调试器中观察到 `ASKING|result=DENIED|pending=1`：AgentScope 原生拒绝保留历史 ToolUseBlock 的 ASKING 状态，新增 DENIED ToolResultBlock。原生全部拒绝分支再次经过 acting 中间件，业务门禁误将其暂停，反馈因此无法进入 reasoning。currentSession 也会误恢复旧卡片。

## 决策

- `ModelingHitlMiddleware#onActing` 依据本调用 AgentState 的非 suspended ToolResultBlock 排除已处理工具 ID，不改变历史工具状态，继续委托原生循环；未处理写调用仍须确认。
- `ChatController#findAskingTools` 使用相同已处理边界，既不恢复已拒绝旧卡，也不允许重复确认已处理调用。
- 前端在消息/确认流结束后回读原生 currentSession，以持久化待确认提案校准展示；不维护独立确认状态机、不自动批准。

## 后果

拒绝、确认、恢复与推理继续完全由 core/harness 执行；业务层只修正门禁过滤与 UI 投影。无实体或协议帧变更。回归覆盖拒绝后委托、已处理卡片不可恢复、反馈一次性与调用隔离。
