# spec 069：官方工具直接调用与建模、问数证据

## 背景与目标

消除问数二次参数包装，保留多租户权限；为关键指标提供业务验证证据，减少查询结果到图文及点赞来源的失真。用户2026-10-16已确认方案并要求开始实施。

## 方案概述

P1：受权限约束的McpClientWrapper适配官方schema和现有网关，补发现/调用/撤权/只读测试。P2：按调用隔离的Toolkit注册与主运行时切换、多库工具绑定、前端显示及旧入口兼容。P3：自动候选结果、最终采用关联、点赞解析、引用式图表与派生结果。P4：业务口径边界夹具、验证证据展示、三层效果对照和完整回归。各阶段独立记录状态，不能把适配器完成视为主链路已切换。

### AgentScope 原生注册流程

每次问数调用根据 `RuntimeContext` 的用户、会话和 `DatasetScope` 解析授权知识库，复制基础 Toolkit，
为当前 project 创建 `WrenScopedMcpClient`，并调用 `Toolkit.registerMcpClient(client)`。AgentScope 随后从
官方 `listTools()` 返回的 `McpSchema.Tool` 创建原生 `McpTool`，模型直接看到 `run_sql`、`query_cube`、
`recall_queries` 等官方工具及其 `inputSchema`。模型调用只包含官方 arguments；平台字段留在 RuntimeContext。

作用域 Toolkit 同时用于本轮模型 schema 和工具执行，调用结束关闭作用域 client。共享 Agent Toolkit 不保存
当前用户、知识库或 project。若基础 AgentScope 版本没有调用作用域 Toolkit，需在 core/harness 增加
RuntimeContext 作用域解析点，而不是在业务层重新实现 ReAct 或 MCP 协议。

## 影响面

- 后端：WrenMcpToolkit、DataToolkitRegistrar、WrenQueryContextMiddleware、WrenQueryGateway、AnswerQueryMemory、结果产物与图表；按需扩展core/harness通用调用工具隔离。
- 前端：工具展示、结果引用、建模验证状态；协议改动前后端同步。
- 数据：优先复用会话工具回执/产物；新增持久字段必须提供迁移。
- 文档：ARCHITECTURE_zh第3/4章、README、AGENTS、agent-definition及共享记忆随实际切换同步。

## 验收标准

1. 单库下模型直接看到官方具体schema，SQL调用无需tool_name或平台字段；多库无工具名歧义。
2. 空范围/其他用户/排队撤权无法发现或执行；并发会话无身份和工具泄漏，发布版本变化可刷新。
3. 含外协/部门排除、重复用户、一对多关联、NULL、短时间覆盖的夹具验证结果；技术通过但口径不符不标业务通过。
4. 查询成功自动记录候选；最终引用及点赞只采用对应成功结果，局部SQL不冒充整题；跨用户/会话结果引用拒绝。
5. 图表/回答使用同源数据，派生比例通过程序计算，保留截断与NULL；无法验证的业务结论明确边界。

## 不做的事

不更换Wren、不建设KG/自研SQL路由、不改官方技能、不强制每次用户填写测试表、不自动修改线上模型或旧memory。

## 测试要求与进度

权限适配与框架原生注册集成测试；多会话并发、撤权、生命周期测试；结果引用及点赞回归；独立业务标准答案夹具。完成后mvn test、mvn package -DskipTests、frontend npm run build。真实LLM和Docker测试单独记录，未执行不得视为通过。

- ADR/spec：已完成。
- P1—P4：待实施，具体进展见CURRENT_TASK。

## 关联

[ADR0081](../../adr/0081-native-wren-tools-and-answer-evidence.md)。
