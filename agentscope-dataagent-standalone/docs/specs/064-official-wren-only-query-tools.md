# spec 064：清理问数 Agent 的项目自定义工具

## 背景与目标

`LLM.log` 显示每个新对话都能正确进入官方 Wren MCP，但问数 Agent 仍暴露旧的项目 SQL/召回工具、自定义知识检索和直接 knowledge 文件读取工具。目标是让问数语义能力完全由 Wren 官方 skill、MCP/CLI 和当前已发布 MDL 决定，同时保留 Python 分析与图表展示。

## 方案概述

- `DataToolkitRegistrar` 只向 data-agent 注册官方 `WrenMcpToolkit`、结果展示用 `render_chart` 和 `run_python`。
- `DataAgentToolkit` 删除 `retrieve_evidence` 的 AgentScope 工具暴露，只保留 `render_chart`。
- `WrenToolkit` 不再注册到 data-agent；其旧语义工具不再进入模型工具 schema。
- `DataAgentConfig`、`DataDynamicContextMiddleware` 和 `WrenQueryContextMiddleware` 只引用官方工具名和官方调用节奏。
- `WrenMcpToolkit` 对官方 `run_sql` 做只读、逻辑模型和 LIMIT/OFFSET 参数校验，并保留官方原始 JSON 与查询回执。
- 前端继续使用 `WrenToolResult` 展示官方 JSON、rows、matches、queries、SQL 和 receipt。

## 影响面

- 后端：`DataToolkitRegistrar`、`DataAgentToolkit`、`WrenMcpToolkit`、问数提示词和动态上下文。
- 前端：只校正官方工具标签和结果展示，不增加新的查询工具。
- 文档：同步 `ARCHITECTURE_zh.md`、README、ADR 0074。
- 数据：不迁移、不处理历史问答；继续使用当前已发布知识库的 Wren memory。

## 验收标准

1. Given 启动 data-agent，When 枚举工具，Then 只有官方 Wren 适配器、`run_python`、`render_chart` 和框架通用工具，不能看到旧 `WrenToolkit` 或 `retrieve_evidence`。
2. Given 用户提出业务问题，When Agent 查询，Then 相似问答、规则、模型和 SQL 都通过 `wren_call_tool` 调用官方 Wren 工具。
3. Given 官方 `run_sql` 参数包含 SQL `LIMIT` 或 `OFFSET`，When 适配器执行，Then 返回明确错误并要求使用官方 `limit` 参数。
4. Given 官方 Wren 返回 JSON，When 前端展示，Then 以可读字段、表格、SQL 和原始 JSON 展示，不把官方结果改写成项目自定义语义结果。
5. Given 已发布版本变化，When 下次查询启动实例，Then 使用最新发布的 `target/mdl.json`，不读取草稿或历史版本。
6. Given 用户需要图表或 Python 分析，When Wren 查询已返回数据文件，Then `run_python` 和 `render_chart` 仍可正常使用。

## 不做的事情

- 不实现平台自研向量检索、关键词召回、SQL 路由或指标计算。
- 不把 `run_python`、`render_chart` 改造成数据库查询工具。
- 不迁移或重建历史 memory 数据。
- 不修改官方 Wren skills、MCP server 或 MDL 编译产物。

## 测试要求

- 更新 Agent 工具注册测试，断言旧项目工具未注册、展示工具仍注册。
- 更新 Wren MCP 适配器测试，覆盖官方 JSON 透传、只读校验和 LIMIT/OFFSET 拒绝。
- 更新提示词/动态上下文测试，断言不再出现旧项目工具名。
- 运行后端测试、Spotless、前端构建。
