# Spec 062：官方 Wren 向量记忆检索

## 目标

让已发布知识库中的点赞业务问答通过官方 Wren memory 向量能力召回，并保证 MCP 与 CLI 使用同一后端。

## 范围

- 在 CLI 和 `wren serve mcp` 子进程注入官方 memory 后端环境变量。
- 使索引标记包含后端版本，兼容从 grep 升级到 LanceDB 的首次重建。
- 索引完成后校验官方状态，未得到 LanceDB 时返回明确错误。
- 更新问数系统提示，要求每个新问题只发起一次 `recall_queries` 作为相似问答召回入口。

## 验收

- `wren memory status` 显示 `lancedb`。
- `knowledge/sql` 的中文 question 与 SQL 可被官方 `recall_queries` 返回。
- 不同 owner/group 的项目不会共享 `.wren/memory`。
- 不处理历史数据，不新增平台自研向量存储。
