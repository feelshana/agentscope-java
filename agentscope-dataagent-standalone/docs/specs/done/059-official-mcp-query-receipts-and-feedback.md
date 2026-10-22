# Spec 059：官方 MCP 查询凭证与点赞保存

状态：实施中

## 目标

修复官方 MCP 问数链路中 question/SQL 成功执行但点赞后无法保存的问题，并降低 Auto-pilot 建模中的重复确认。

## 行为

### MCP 调用

- `arguments` 原样遵循官方工具 schema。
- 平台元数据 `answer_question`、`query_type` 位于 `wren_call_tool` 外层。
- 兼容嵌套元数据：调用前提取并从官方参数删除。
- `run_sql` 和 `query_cube` 成功执行时，若有中文业务 question，返回唯一 `[WREN_QUERY_RECEIPT]`。
- SQL 内出现显式 `LIMIT/OFFSET` 仍拒绝，使用官方/工具的 limit 参数。

### 点赞保存

- 反馈服务从当前会话最终回答之前的工具记录提取 receipt。
- 只接受成功、中文、业务 question，以及 `run_sql/query_cube` 的实际 SQL。
- 点赞后调用官方 `wren memory store --nl ... --sql ...`。
- 返回 `SAVED`、`NONE` 或失败状态，并记录候选数量和 store 结果。
- 不处理历史数据，不实现取消点赞。

### 建模确认

- 保留官方 Auto-pilot 对新 View/Cube/Relationship 的业务确认。
- 同一模型响应中的多个写入合并为一次平台确认。
- `context validate/build` 等只读检查不产生写入确认。
- 文件预检错误必须指出 `write_file`/`edit_file` 和官方字段要求，避免重复盲改。

## 影响范围

- `WrenMcpToolkit`：外层元数据归一化、receipt、日志。
- `AnswerQueryMemory`：receipt 候选提取和 store 诊断日志。
- `ModelingHitlMiddleware`：确认批次与只读命令边界。
- 前端反馈 API 协议保持不变。

## 验收

1. `run_sql` 成功日志包含 receipt，且官方 arguments 没有 `answer_question/query_type`。
2. 点赞成功返回 `memoryStatus=SAVED`，`knowledge/sql/*.md` 出现新样例。
3. 官方 recall 能返回该样例。
4. 失败查询、诊断查询、无中文 question 不保存。
5. 建模的普通 validate/build 不重复弹确认，高影响写入仍经 HITL。
