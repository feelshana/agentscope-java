# spec 054：可选预设问题与最终答案点赞记忆

> 状态：已实现并通过构建与自动化验证（2026-10-09）。ADR 0062 为本规范依据；与旧 specs/047–052 的问题前置、全题确认发布及样例保存约束冲突时，以本规范为准。

## 背景与目标

面向自由问数，采用 Wren 官方建模与查询记忆流程。取消预设问题的必填前置，保留 agent 自动技术验证和重要业务方案审阅。在问数最终答案上收集可选点赞/点踩，只有点赞才保存该答案中的合格成功问答对。

## 方案概述

- 建模：数据准备 → 基础模型可问数 → 可选文档/常用问题或业务目标 → agent 澄清并构建/复用资产 → 业务方案审阅与自动 Wren 验证 → 发布。无题单可直接进入对话；agent 推荐问题仅作建议，无需采纳全部建议才能继续。预设问题保留 SQL/结果审阅入口，不再用全部逐题确认作为发布闸。
- 资产：使用官方技能模板；明确基础粒度、原始指标公式、必要身份/维度/时间字段，避免仅为了月度问题就先聚合丢失明细；已有资产足够则复用。复用现有 files_json/HITL 和副本预检，不新增结构化 DB 建模写工具。
- 反馈：仅 data-agent 最终完成的答案显示醒目 👍/👎 图标、文字标签及「请确认口径与结果，正确请点赞，有问题请点踩」提示；有选择态、提交态和持久化反馈结果，刷新恢复，不阻塞后续问数。点踩原因可选。
- 保存：后端使用答案所属轮次的真实执行记录筛选成功业务查询，排除失败、被替代、探索/诊断调用。不接受前端或 LLM 任意提供 SQL 作为已执行凭据。主查询匹配用户原问题，独立业务子查询匹配对应子问题；不能把辅助查询绑定整轮问题。Cube 取得真实执行 SQL 后保存；多查询/Python 答案不强行合成单 SQL 样例。
- 查询记忆：点赞时按知识库写入独立运行目录，采用官方知识格式并复用安装版 memory store/recall。保留建模确认示例，问数召回纳入点赞示例，重新查询；无需重新发布 MDL，不修改 published 文件。记录最小来源关联支持幂等、撤销及多租户隔离，不增加可信等级或模型版本有效性机制。
- 反馈切换：重复点赞不重复保存；改点踩撤销本次反馈新增样例；不删除其他保存来源。反馈与写入状态分开展示，保存失败可重试。点赞但无合格问答对时提示「反馈已记录，本次无可保存的分析查询」。模型修复仍独立进入建模。
- 新反馈 API 遵循 principal → data-agent RUN 权限 → 会话/答案归属及知识库权限校验；不信任客户端 ownerId/groupId；阻塞调用 boundedElastic。SSE 需要关联字段时前后端同步，复用原生会话与工具生命周期，不另建 agent 循环。

## 影响面

- 后端：ModelingWorkflowService、MdlPublishService、ModelingToolkit、DataAgentConfig；ChatController/现有会话结果存储、WrenToolkit、WrenQueryGateway/WrenCli；新增必要的答案反馈与记忆服务/API。
- 前端：SemanticModelingPage、QuestionIntakeForm、现有验证/发布面板；ChatPanel/ChatPage 最终答案反馈，统一 api 文件。
- 数据：保存最小答案反馈及执行来源关联，复用现有持久化能力；知识库独立官方格式查询记忆。具体存储实现按现有会话代码确定。
- 文档：ARCHITECTURE_zh.md 第 3/11 章、旧 specs 的目标边界同步；ADR 只追加。

## 验收标准（Given-When-Then，可测试）

1. Given 无预设问题，When 进入建模，Then 可开始业务对话并构建文档方案；零问题不阻止工程检查通过后的显式发布。
2. Given 有预设或推荐问题，When 部分未执行/未确认，Then 如实显示状态，但不产生全题确认发布闸；工程校验失败仍拒绝发布。
3. Given 最终答案含主查询、失败尝试及诊断查询，When 未反馈/点踩，Then 不新增问答样例；When 点赞，Then 仅保存匹配业务问题的成功分析查询，反馈及保存结果可见。
4. Given 同轮有多条独立业务查询或 Cube 调用，When 点赞，Then 按实际问题/SQL 配对，Cube SQL 可追溯；无法独立复现的整体答案不伪造单 SQL 示例。
5. Given 已点赞，When 重复提交/改点踩/再点赞，Then 幂等、只撤销本次新增样例、可重新保存；写入失败不显示已保存。
6. Given 其他用户会话或越权知识库，When 提交反馈/保存/召回，Then 拒绝，不能看到或写入他人样例。

## 不做的事（明确排除项）

- 资产适用范围检查、指标边界回归、样例可信状态体系、版本有效性治理、发布能力范围声明。
- 无反馈自动写入查询记忆、点赞自动修复模型、通用多步分析案例平台、Wren/LLM 升级或物理 SQL 回退。

## 测试要求

- 发布/工作流、答案关联筛选、反馈幂等/撤销/失败恢复、多租户用例；保留现有工程预检和 HITL 回归。
- 前端验证无题单进入建模、最终答案反馈强提示与刷新恢复；用真实 Wren 验证点赞保存及后续召回，避免只以 CLI 替身证明集成成功。
- 实现完成后执行本工程 Maven 测试/打包、Spotless 和 frontend 构建。

## 实现说明

- `ModelingWorkflowService#snapshot` 只统计可选问题状态，不用它阻止发布；`MdlPublishService#doPublishLocked` 保留模型校验、编译与发布检查，移除问题全确认闸。页面保留直接对话和可展开的问题表格。
- `WrenToolkit#wrenRunSql` 在成功且有业务问题的返回中给出查询编号。`wren_answer_queries` 在最终回答前声明真正使用的编号；后端 `AnswerQueryMemory#candidates` 对照该轮服务端工具输入和成功结果，拒绝中间回答、未知编号、失败及未声明的查询。同一问题的旧 SQL 被后续成功查询替代。声明工具不保存数据，用户无需逐条确认 SQL。
- SSE `done.answerId` 使用原生 `AgentResultEvent` 的结果消息 ID，与持久化历史 ID 一致。反馈 API 位于 `/api/agents/{agentId}/sessions/{session}/answers/{answerId}/feedback`，提供 GET/POST；执行 RUN 权限、会话归属、知识库归属检查。阻塞操作调度到 boundedElastic。
- 反馈写入租户独立 `.answer-feedback` 状态文件；官方 memory 工程位于知识库 `runtime/answer-memory`。使用 `context init --empty`、`memory store --nl --sql` 和 `memory recall`，不改 published。按有效点赞来源去重，创建新记忆目录后切换指针，撤销不影响其他答案保留的相同样例。失败时保留反馈、显示同步失败，停用受影响旧指针，允许再次点击重试。未安装向量记忆依赖时，由官方 CLI 的 grep 后端召回。
- 当前 `query_cube` 返回不含可追溯的执行 SQL，故不保存纯 Cube 调用为 SQL 样例，不编造 SQL。包含 Wren SQL 查询及 Python 后处理的答案，只保存明确采用的已执行业务 SQL，不将整段分析合成为单一 SQL。

## 验证结果

- 本独立工程执行 `mvn spotless:apply test package`，BUILD SUCCESS；468 项测试，0 失败、0 错误、12 跳过。Maven 同时执行前端 `npm run build`（TypeScript 检查与 Vite 构建）及打包。
- 设置 `WREN_MEMORY_TEST_EXECUTABLE` 指向本机安装版 CLI，`AnswerQueryMemoryTest#officialCliStoresRecallsAndWithdrawsTheLikedAnswer` 实际执行并通过，没有跳过。覆盖中文问题官方保存/召回以及改点踩后的召回停止；测试使用隔离临时目录，不改用户已发布工程。
- 自动化覆盖预设问题不阻止发布、工程失败阻止发布、真实执行凭据筛选、未声明/失败查询不保存、反馈幂等与持久化、重复保存来源保护、写入失败重试、会话与租户越权拒绝。`git diff --check` 通过。
- 未做运行中服务的浏览器完整验收。部署时需重启后端并刷新页面；历史答案缺少新的执行编号和最终采用声明时，只记录反馈，不补造旧样例。

## 关联

- [ADR 0062](../adr/0062-wren-modeling-and-answer-feedback-memory.md)
- specs/049、050、051、052「预检与完整方案」。官方依据见 ADR 0062。
