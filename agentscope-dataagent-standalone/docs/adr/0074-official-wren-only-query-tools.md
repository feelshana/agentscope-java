# ADR 0074：问数 Agent 仅使用官方 Wren 能力

## 状态

Accepted

## 背景

问数 Agent 当前同时注册了官方 Wren MCP 适配器、旧版项目自定义 `WrenToolkit`、平台知识检索 `retrieve_evidence` 和直接读取 knowledge 文件的 `wren_read_knowledge`。这些入口都能出现在模型工具列表中，导致同一问题可能在不同工具之间路由，日志中出现重复的知识读取、旧工具名和额外诊断查询。

Wren 官方已经提供 usage skill、MCP 工具和 memory/knowledge 能力。问数侧只需要把当前知识库的官方工具 schema 动态透传给 AgentScope，并保留查询结果的 Python 分析和图表展示能力。

## 决策

1. 问数 Agent 的语义查询、模型发现、Cube、业务规则、相似问答召回和知识读取统一使用当前已发布 Wren 工程提供的官方 MCP/CLI 能力。`wren_call_tool` 只是按知识库动态转发官方 MCP，不实现第二套 SQL、召回或指标路由。
2. 从问数 Agent 运行时注册中移除旧 `WrenToolkit` 的 `wren_run_sql`、`wren_query_cube`、`wren_describe_model`、`wren_recall_examples` 等项目自定义语义工具。
3. 从问数 Agent 运行时注册中移除平台自定义 `retrieve_evidence` 和 `wren_read_knowledge`。业务规则使用官方 `get_instructions`，相似问答只使用官方 `recall_queries`；需要工程目录时使用官方 `list_knowledge` 或 CLI 能力。
4. 保留 `run_python` 和 `render_chart`，它们只消费 Wren 已返回的数据，不查询物理数据库、不生成替代 SQL、不改变 MDL。
5. 查询实例固定绑定当前知识库的最新已发布 `target/mdl.json`；草稿和历史版本不进入问数运行时。
6. 官方 `run_sql` 的参数约束由适配器统一校验：SQL 只使用逻辑模型，行数通过官方 `limit` 参数控制，禁止显式 `LIMIT/OFFSET` 与连接器重复追加。
7. 每个新会话遵循官方 usage 的首次发现流程；同一会话复用已经发现的 schema。业务查询结果足够时不追加诊断查询，只有失败、结果不足或用户明确要求核验时才诊断。

## 后果

问数工具列表变短且只有一个语义来源，官方 skill 的行为可以直接决定查询、召回和 knowledge 版本。旧项目工具类可以保留为迁移期源码兼容，但不能再挂载到问数 Agent；后续应删除无调用方的旧实现和测试。

`run_python` 与 `render_chart` 仍然属于平台展示能力，因此前端继续对 Wren JSON、查询回执和结果文件做结构化展示。

## 验证

- 主 Agent 工具列表不包含 `wren_run_sql`、`wren_query_cube`、`wren_describe_model`、`wren_recall_examples`、`retrieve_evidence`、`wren_read_knowledge`。
- 官方 `wren_call_tool` 能调用 `recall_queries`、`get_instructions`、`run_sql`、`query_cube` 并保留原始 JSON。
- 带显式 `LIMIT/OFFSET` 的官方 `run_sql` 调用在适配器层被拒绝，使用 `arguments.limit` 的调用正常透传。
- `run_python` 和 `render_chart` 仍可注册并消费 Wren 结果。
- 查询使用已发布 MDL，不能读取草稿或其他租户工程。
