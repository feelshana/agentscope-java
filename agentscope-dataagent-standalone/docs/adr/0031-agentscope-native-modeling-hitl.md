# ADR 0031: 采用 AgentScope 原生中断与恢复实现建模 HITL

- 状态：已采纳（实施见 specs/017）
- 日期：2026-10-04

## 背景

建模助手会调用关系、Cube、View、术语和业务规则等写工具。当前交互先输出文本选项，再等待用户自然语言回复，LLM 才决定是否调用工具。它有三个结构性问题：

1. “展示提案”和“允许执行”不是同一个可校验对象，参数可能在两轮之间漂移；
2. 前端无法提供稳定的按钮、图形预览和调整表单，只能把 A/B/C 当文本；
3. 暂停、刷新恢复、重复提交和拒绝语义依赖对话猜测，缺乏持久化状态机。

AgentScope 已提供完整的 HITL 原语：Middleware 在 acting 阶段把工具调用标记为 `ASKING`，发出 `RequireUserConfirmEvent` 和 `RequestStopEvent(PERMISSION_ASKING)`；Harness 将 AgentState 写入现有 `AgentStateStore`；恢复消息通过 `Msg.METADATA_CONFIRM_RESULTS` 携带 `ConfirmResult`。`ConfirmResult` 还允许提交保持 ID 不变的修改后 `ToolUseBlock`，适合关系方向、基数和字段调整。

## 决策

建模助手采用 AgentScope 原生中断/恢复机制，不自建审批协议，不修改 core/harness。

具体决策：

- 新增应用层 `ModelingHitlMiddleware`，只安装到 `modeling-agent`；
- Middleware 仅拦截建模写工具，将上下文中的对应调用改为 `ToolCallState.ASKING`，然后按官方事件顺序暂停；
- `ChatController` 将 `RequireUserConfirmEvent` 映射为 `hitl_request` SSE，并提供建模确认恢复端点；
- 恢复端点从服务端持久化暂停点核对 user、session、group、tool ID、tool name 和白名单，再构造 `ConfirmResult`；
- 参数调整只替换 `ToolUseBlock.input`，工具 ID 和名称保持不变，让原 ReAct 循环继续执行；
- `DatasetScope` 和建模服务继续承担最终租户校验，HITL Controller 增加恢复前的越权与篡改防线；
- 前端确认卡只是原生事件的可视化和恢复客户端，不直接调用底层建模服务。

该机制首期仅用于建模助手。普通问数工具不增加确认门，避免改变查询体验和现有会话行为。

## 理由与权衡

### 选择原生 Middleware 的理由

- 暂停点属于 AgentState，同一会话可由 Harness 既有存储恢复，无需新表、TTL、清理任务和并发协议；
- `ASKING`、`ConfirmResult`、停止原因和恢复消息均是 AgentScope ReAct 循环理解的状态，不需要伪造 tool result；
- 修改后工具调用是官方支持能力，适合“采用推荐”和“调整后采用”共用一个确认请求；
- Middleware 可精确限定到 modeling-agent 和写工具，不污染普通问数 Agent。

### 接受的代价

- Controller 需要理解两类额外事件，并对 `PERMISSION_ASKING` 做正常暂停而非错误处理；
- 恢复前必须读取或核对持久化暂停调用，防止客户端篡改；
- 前端需要保存待确认卡状态，并处理断线、重试和重复提交；
- 一次只确认一个提案，交互轮数可能增加，但审计和参数一致性更强。

## 被拒方案

- **自建 approval 表和 approvalId**：拒绝。它会复制 AgentStateStore 已具备的暂停状态，并产生双状态一致性、过期清理和并发消费问题。
- **继续用文本 A/B/C**：拒绝。自然语言回复无法绑定原工具 ID 和参数，不能可靠防重放或提供图形调整。
- **前端确认后直接调用建模 REST API**：拒绝。会绕过原 ReAct 工具调用和 tool result，Agent 不知道操作是否发生，后续推理上下文不一致。
- **修改 AgentScope core/harness 增加项目专用事件**：拒绝。现有原语已覆盖需求，修改基础模块扩大升级和兼容风险。
- **给所有 Agent 安装确认中间件**：拒绝。普通查询是只读主链路，增加 HITL 会造成不必要的交互回归。

## 安全与一致性

- `AgentStateStore` 必须使用原始 `(userId, sessionId)` 参数，不能在调用层自行拼接；
- userId 只来自认证上下文；groupIds 必须由当前用户可见知识库校验；
- 只接受状态为 `ASKING` 的同 ID、同名、白名单工具；已恢复调用再次提交必须拒绝；
- 确认结果成功进入 Harness 前不消费暂停点，临时失败可重试；
- `ToolNotificationMiddleware` 保留，但暂停调用不得产生“已执行”通知；
- 审计以 `hitl_request → ConfirmResult → tool_result` 串联，拒绝不得伪装成成功工具结果。

## 影响面

- 后端：`DataAgentConfig`、新增 `ModelingHitlMiddleware`、`ChatController`、`HarnessGateway` 必要的恢复/状态查询接口、`ModelingToolkit` 统一关系决策工具；
- 前端：`chat.ts` HITL 类型和确认 SSE、`ModelingChatPanel` 确认卡、只读关系预览组件；
- 查询侧：`wren_describe_model` 的关系投影折叠和问数路由提示，与 HITL 协同但不改变本 ADR 的状态机制；
- 数据库和配置：无新增表、无新增配置项；
- 基础模块：AgentScope core/harness 零修改。

## 后果

建模写操作从“LLM 口头询问后自行判断”变为“同一 ToolUseBlock 在执行前结构化暂停、用户决策后原地恢复”。用户看到的图形卡与实际执行参数绑定，服务端可验证租户、工具身份和重放；AgentScope 仍是唯一的工具状态机和会话恢复来源。
