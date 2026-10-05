# spec 017: 建模 HITL 与问数路由优化

## 背景与目标

建模助手当前用文本 A/B/C 询问用户，再依赖下一轮自然语言决定是否调用写工具。该流程缺少结构化暂停点，用户无法直观看到关系方向和字段，重复提交或恢复也没有明确协议。与此同时，关系投影列虽已完整进入 MDL，`wren_describe_model` 会逐列展开，容易挤占 LLM 上下文；问数提示也未把 Cube、关系投影和手写 JOIN 的优先级写成不可歧义的决策树。

本规格实现以下目标：

1. 只给 `modeling-agent` 增加 AgentScope 原生 HITL；普通问数和其他 Agent 行为不变；
2. 建模写工具执行前由 Middleware 暂停，前端用可点击卡片确认、调整或拒绝，再沿原会话恢复；
3. 关系投影列继续完整保存在 MDL，默认按关系折叠展示，需要时按需展开；
4. 问数固定按 Cube → 关系投影自动 JOIN → 逻辑 SQL → 显式 JOIN 兜底的顺序路由；
5. 用指定知识库和真实问题验证最终 MDL、查询结果及日志轨迹。

## AgentScope 原生 HITL 契约

### 拦截范围

新增 `ModelingHitlMiddleware`，仅安装到 `modeling-agent`。读取、盘点和建议类工具直接执行；会改变语义状态的工具进入确认。白名单由后端常量维护，至少覆盖关系、Cube、View、术语、业务规则的新增、更新、删除、确认和拒绝工具。

Middleware 在 `onActing` 中：

1. 从本轮 `ActingInput.toolCalls()` 选取尚未允许的建模写调用；
2. 将 `AgentState.context` 中同 ID 的 `ToolUseBlock.state` 改为 `ToolCallState.ASKING`；
3. 依次发出 `RequireUserConfirmEvent` 和 `RequestStopEvent(..., GenerateReason.PERMISSION_ASKING)`；
4. 不调用 `next`，因此待确认调用不会提前执行，也不会产生伪造的成功通知。

暂停点由 Harness 现有 `AgentStateStore` 按 `(userId, sessionId)` 保存，不新增审批表或独立状态机。

### SSE 请求帧

通用聊天 SSE 增加：

```json
{
  "type": "hitl_request",
  "replyId": "...",
  "toolCalls": [
    {
      "id": "tool-call-id",
      "name": "decide_relation",
      "input": "{...}"
    }
  ]
}
```

同一帧只用于建模助手。Controller 不过滤 `RequireUserConfirmEvent`；`RequestStopEvent(PERMISSION_ASKING)` 表示本轮正常暂停，不发送错误帧。

### 确认恢复

新增建模确认恢复端点，输入必须包含原 `sessionKey`、同一 `groupIds`、`replyId`、工具 ID、工具名、确认结果以及可选调整后的参数。服务端校验：

- agent 必须是 `modeling-agent`；
- group 必须存在且当前用户可见；
- 会话中必须存在状态为 `ASKING` 的同 ID 工具；
- 工具名必须与暂停点一致且属于建模写工具白名单；
- 同一暂停调用只接受一次有效恢复；
- 修改参数时保留工具 ID 和名称，只替换 input。

通过校验后构造 `ConfirmResult(confirmed, toolCall)`，放入 `Msg.METADATA_CONFIRM_RESULTS`，用原 userId、agentId、sessionKey、groupIds 和 RuntimeContext 重新进入 Harness 流。确认后继续执行原 ReAct 循环；拒绝或跳过只终止当前提案，不将工具标为执行成功。

### 关系决策

`ModelingToolkit` 提供统一关系决策工具，复用现有确认/拒绝服务。一个调用可表达：

- `CONFIRM`：采用推荐；
- `ADJUST`：调整方向、基数或对齐字段后确认；
- `REJECT`：明确不建立；
- `SKIP`：暂不处理，不产生确认状态变更。

这样用户在卡片中选择“不建立”时无需让 LLM 再发起第二个拒绝工具调用。

## 图形化确认卡

`ModelingChatPanel` 保存当前待确认请求并暂停普通输入。卡片至少提供“采用推荐”“调整关系”“不建立”“暂不处理”，确认提交期间禁用所有按钮，SSE 恢复完成后才解除。

关系卡使用业务表名和列名显示两张表及有方向的关系边，默认文案为业务语言，例如“多条点击记录可对应同一个项目负责人记录”。`MANY_TO_ONE`、字段对、condition 放在“技术详情”。调整区使用“记录对应方向”和“是否还需日期/地区等条件才能唯一对应”等可理解文案。

Cube、View、术语和业务规则使用统一摘要卡，支持采用、修改要求和跳过。只读关系预览组件由 HITL 卡和完整 MDL 图共同复用，发布视图仍保持只读。

## 关系投影折叠

`wren_describe_model` 新增可选参数 `expand_relation_fields`，默认 `false`：

- 物理字段逐列展示；
- `calculated=true` 且表达式引用关系对端的列，按关系折叠为“关联字段组”；
- 折叠摘要包含对端模型、字段数和“查询这些字段时由 Wren 自动 JOIN”的提示；
- `expand_relation_fields=true` 时返回完整投影列名、类型、说明和表达式；
- 与关系无关的计算列不能被折叠；
- 只改变描述输出，不删除或改写 MDL 内容。

## 问数路由决策树

系统提示、动态数据源说明、`sql-analysis/SKILL.md` 和 Wren 工具描述必须保持一致：

1. 已发布 Cube 的成员能回答时，必须用 `wren_query_cube`，不得改写为手工聚合 SQL；
2. 需要跨模型属性时，从 many 侧模型展开关联字段组，以单一逻辑模型和投影列查询，让 Wren 根据 relationship condition 自动 JOIN；
3. 仅当 Cube 和投影都无法表达时使用 `wren_run_sql`；显式 JOIN 前必须确认前两者不可用，并且 SQL 只能使用逻辑模型名。

## 状态、重放与失败语义

- 页面刷新后可以用同一 sessionKey 继续恢复持久化暂停点；
- 重复确认、未知工具 ID、工具名篡改、空确认和跨组恢复返回 4xx，不执行工具；
- SSE 断线不自动重复决策，客户端可安全重连并重新提交同一尚未消费的确认；
- 后端只有在确认结果成功进入 Harness 后才将请求视为已消费；
- 恢复失败保留暂停点，允许用户修正后重试；
- 普通 `tool_call` / `tool_result` / `done` / `error` 帧保持兼容。

## 租户与安全边界

- userId 由现有认证上下文取得，不接受客户端覆盖；
- groupIds 必须经过 `DatasetGroupService#getGroup(ownerId, groupId)` 校验；
- RuntimeContext 继续注入 `DatasetScope(userId, groupIds)`，工具层执行时再做最终校验；
- AgentStateStore 使用原始 `(userId, sessionId)` 寻址，调用方不得自行拼接用户和会话键；
- 不新增配置项，不修改 AgentScope core/harness。

## 真实语义建模验收

目标知识库：`d0640e4a-18e2-4a19-9d2b-b3e99a749532`（红海数据中台）。四张已入库表不复制、不重建。发布前保存当前语义资产和 MDL 快照。

最终已发布 MDL 必须满足：

- 4 个模型；
- 唯一 relationship：`点击详情数据.project_name MANY_TO_ONE 项目简称全称负责人.project_name`；
- 点击模型包含负责人表的全部关联投影列，默认 describe 只显示一个折叠字段组；
- 保留并复核 `报表访问分析`、`点击行为分析` 两个 Cube；
- 新增并发布逻辑视图 `近30天用户行为分层`；
- 术语和业务规则留在动态知识层，不伪装成 `mdl.json` 字段。

关系确认前展示数据证据：负责人表 `project_name` 为 111/111 唯一，点击表 91 个项目名全部命中，MANY_TO_ONE 不放大点击明细。其余已确认关系改为 `REJECTED`，保留审计记录。

业务口径：近 30 天有点击为活跃；近 30 天 `SUM(visit_count)<5` 为低频，`>30` 为高频；用户数按账号去重；仅推广场景排除 `wx` 后缀外协账号和“数智化部”；普通行为分析不得套用推广过滤；租户容量与访问/点击域不建立关系。

## 自动化与端到端验收

后端测试覆盖 Middleware 事件顺序、ASKING 状态、读写工具分流、确认/拒绝/调整恢复、SSE 映射、鉴权、防篡改、防重复、统一关系决策以及 describe 折叠/展开。前端以 TypeScript production build 覆盖事件类型、卡片状态和恢复流，纯映射逻辑可抽取单测。

真实问数：

1. `9月6日访问量TOP10的报表有哪些；访问用户数TOP10的报表有哪些`：按两个排序口径顺序调用 `wren_query_cube(报表访问分析)`，日期使用 `2026-09-06` 到 `2026-09-07` 左闭右开，不出现物理表名或 `wren_run_sql`；
2. `帮忙分析低频用户的行为习惯，对比高频用户的行为偏好差异`：优先使用已发布 `近30天用户行为分层`，可用 `run_python` 二次统计，不关联用户列表、不套推广过滤、不手写跨表 JOIN。

检查 `logs/LLM-modeling.log` 和新增时段的 `logs/LLM.log`，记录 `hitl_request → ConfirmResult → tool_result`、工具选择、参数、日期、去重、逻辑名/物理名边界、自动 JOIN 和错误恢复。最终门禁为后端全量测试、依赖构建、frontend production build、Spotless 与 `git diff --check`。

## 不做的事

- 不给普通问数 Agent 或其他 Agent 增加 HITL；
- 不修改 AgentScope core/harness；
- 不自建审批表、审批 token 或第二套暂停协议；
- 不删除 MDL 中的关系投影列；
- 不让前端直接执行建模服务或绕过工具确认；
- 不为本功能增加配置项；
- 不把术语、业务规则强塞入 Wren MDL schema。

## 关联

- ADR 0031：采用 AgentScope 原生中断/恢复实现建模 HITL
- ADR 0024：对话式 MDL 建模与只读可视化
- ADR 0029：Wren-only 查询与自动 baseline MDL
- spec 013：对话式 MDL 建模
- spec 016：Wren 查询结果文件级交接
