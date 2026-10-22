# ADR 0070：官方 MCP 查询凭证与 Auto-pilot 确认收敛

- 状态：Accepted
- 日期：2026-10-12
- 关联：[spec 059](../specs/done/059-official-mcp-query-receipts-and-feedback.md)、[ADR 0069](0069-wren-native-modeling-and-query-agents.md)

## 背景

问数 Agent 使用官方 Wren MCP 的 `wren_call_tool`。官方工具的 `arguments` 必须保持原始 schema，平台自己的 `answer_question`、`query_type` 不能混入其中。实际日志中模型把 `answer_question` 放进了 `arguments`，导致查询可以成功，但平台无法建立成功查询与最终答案的关联，点赞时也就找不到可保存的 question 与 SQL。

建模阶段还存在两个确认层：官方 `enrich-context` 对 View、Cube、Relationship 等高影响变更确认，平台又对每个文件写入逐次确认，错误的 YAML 字段还会触发多轮修正。

## 决策

1. `wren_call_tool` 继续透传官方 MCP 工具和官方 `arguments`。`answer_question`、`query_type` 属于平台外层元数据；平台同时兼容模型误将它们放入 `arguments` 的调用，并在发送给 Wren 前移除。
2. 成功的 `run_sql`/`query_cube` 且存在中文业务 question 时生成 `[WREN_QUERY_RECEIPT]`。凭证包含 group、question、实际 SQL、limit、工具名和业务类型。
3. 点赞保存只消费成功查询凭证；只保存中文 question、业务查询和成功 SQL。保存通过官方 `wren memory store` 完成，历史数据不迁移，也不处理取消点赞。
4. 建模保留官方 Auto-pilot 的高影响确认；平台将同一模型响应中的写入合并确认，只读 CLI 检查不重复确认，写入预检错误返回可执行的官方字段修正提示。
5. 平台日志记录 receipt 生成、点赞候选数量、memory store 的 project/question/sql hash/exit code，便于区分查询成功、凭证缺失和存储失败。

## 取舍

平台只做租户、凭证和反馈适配，不复制 Wren 的 SQL、memory 检索或建模规则。对旧的嵌套元数据调用只做读取时兼容，不回填历史样例。

## 验证

需要验证：官方 MCP 查询参数不包含平台字段；成功查询生成 receipt；点赞后 `wren memory store` 成功并可由官方 recall 返回；诊断、失败、无中文 question 不保存；建模普通检查不重复确认，高影响写入仍需确认。
