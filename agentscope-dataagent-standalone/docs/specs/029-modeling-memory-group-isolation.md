# specs/029 — 建模助手长时记忆按知识库（DatasetGroup）隔离

日期：2026-10-04 ｜ 状态：已实施并运行时验证通过（2026-10-04 重启后 4444/红海两库点检 G2/G3/G4 全绿）

## 背景

1. 取证实锤（`logs/LLM-modeling.log` + 磁盘 MEMORY.md，2026-10-04）：modeling-agent 的长时
   记忆隔离粒度是 harness 的 `IsolationScope.USER`——MEMORY.md 落在
   `~/.agentscope/dataagent/workspace-modeling-agent/<userId>\` 下，同一用户跨全部知识库共享
   一份。红海知识库建模留下的「活跃/高频/低频用户数」等术语记忆，在 4444 知识库建模对话的
   5 轮请求中全部经 `<memory_context>` 注入；且 10-01 手工全清后，三条写入通道
   （memory_save / flush 日账本 / consolidation）随使用自动重建，结构必然复发。
2. 框架调研结论：harness 唯一隔离维度是 `IsolationScope` 四档枚举，`HarnessAgent.Builder`
   不开放自定义 NamespaceFactory，无 group_id 级机制；但 `NamespaceFactory` 是函数式接口、
   `WorkspaceManager` 读写管线统一走它、每轮 `RuntimeContext` 已携带 `DatasetScope`——三个
   条件使「知识库级隔离」可以用一个 ~20 行的 harness 扩展点 + 一处装配接线实现（方案 A）。
   曾评估 SESSION 级隔离（实施成本相同但跨会话记忆价值归零），被否，见 ADR 0039。

## 设计

### 命名空间语义（GroupScopedMemoryNamespace）

| 场景 | 命名空间 | 落盘路径（modeling 工作区内） |
|---|---|---|
| 单库对话（恰好 1 个 groupId） | `[owner, "kb-<groupId>"]` | `<owner>\kb-<groupId>\MEMORY.md` 等 |
| 多库 / 无 scope / 全局数据源 | `[owner]`（回退 USER 语义） | `<owner>\MEMORY.md` |
| rc 无 owner | 回退 `[sessionId]` | `<sessionId>\...` |
| rc 为 null / 全空 | `[]`（对齐框架） | 工作区根 |

- owner 取 `rc.getUserId()`，缺失时回退 `DatasetScope.ownerId()`；`groupId` 以 `kb-` 前缀
  入路径段（UUID 本身，路径安全），运维可直接辨识。
- 单库条件为 `groupIds.size() == 1`——建模对话始终绑定单一知识库（语义建模页入口），
  多库组合不做拼接（组合爆炸且无场景），统一回退用户级。

### 一处接线，四端一致

`DataAgentConfig` 给 modeling-agent 设
`new LocalFilesystemSpec().namespaceFactory(GroupScopedMemoryNamespace.INSTANCE)`；
harness 在 build() 里让 spec 自定义 factory 覆盖枚举派生值。由于
`<memory_context>` 注入（WorkspaceContextMiddleware）、memory_save 工具、flush 中间件
（复用调用时原始 rc）、consolidation（.consolidation_state 走 getMemoryDir(rc)）全部经同一
`WorkspaceManager`/filesystem 命名空间解析，改一处即全部生效。

## 改动清单

1. **harness `LocalFilesystemSpec`**：新增 `namespaceFactory(NamespaceFactory)` /
   `getNamespaceFactory()`（字段默认 null = 行为完全不变）。
2. **harness `HarnessAgent.Builder.build()`**：在 `nsFactory` 枚举派生后加覆写——
   `if (localFilesystemSpec != null && localFilesystemSpec.getNamespaceFactory() != null)
   nsFactory = localFilesystemSpec.getNamespaceFactory();`。
3. **dataagent 新类 `runtime/session/GroupScopedMemoryNamespace`**：上述语义的
   `NamespaceFactory` 实现（无状态单例）。
4. **dataagent `DataAgentConfig`**：modeling-agent 分支（sandboxless）设
   `LocalFilesystemSpec + factory`；问数 agent 的 Docker 沙箱 spec 不动。
5. **存量处置**：删除历史用户级记忆文件——所有
   `workspace-modeling-agent\<ns>\MEMORY.md` 与 `<ns>\memory\`（含 .consolidation_state）。
   旧数据在新布局下本就不可达（路径变 `kb-<groupId>` 子目录），删除使其彻底清零；
   会话历史（agents/、default/events）不是记忆，不波及。
6. **测试**：`GroupScopedMemoryNamespaceTest` 锁定「两库命名空间不同 + 回退语义」。

## 验收

- G1：`mvn test` 全绿（standalone 含新用例；harness 侧枚举默认路径零行为变化，靠回归保障）。
- G2：新建模对话的 system prompt 中 `<memory_context>` 不再出现其它知识库的术语记忆
  （首轮为空；同会话内后续轮可见本会话写入）。
- G3：同用户分别在 A、B 两库建模——A 库对话写下的术语记忆不出现在 B 库对话的
  `<memory_context>`（核心越权断言）。
- G4：磁盘出现 `workspace-modeling-agent\<owner>\kb-<groupId>\MEMORY.md` 新布局；
  历史 `admin\MEMORY.md` / `admin\memory\` 已删除。

## 不做

- 问数 agent（data-agent）的记忆改造——其 per-`(userId, agentId)` Docker 沙箱本就按用户
  隔离，且无跨库注入通道（filesystem 在容器内）。
- 多库组合的拼接命名空间（无建模场景，统一回退用户级）。
- 会话历史迁移（旧 ns 下的 sessions/events 不可达即可，属测试数据）。
- harness 其他 spec（Remote/Sandbox）的 namespaceFactory 入口——本次仅 Local 需要。

## 附录：问数助手（data-agent）记忆结构调研（2026-10-05）

聊天页问数助手与建模助手共用同一套 harness 记忆管线（MEMORY.md + memory/ 日账本 +
consolidation，三通道写入、每轮注入 <memory_context>），但载体完全不同。本调研经源码走读
（UserSandboxRegistry / HarnessGateway / ChatController / DataAgentConfig）确认，结论如下：

1. **落点：容器内可写层。** 问数 agent 装配 DockerFilesystemSpec + IsolationScope.USER，
   记忆文件写在 Docker 容器内；宿主机只投影只读共享层（AGENTS.md/skills/subagents/knowledge，
   见 UserSandboxRegistry#buildWorkspaceSpec 的 __workspace_projection__），无任何可写 volume。
2. **隔离粒度：用户级，跨知识库共享。** 容器按 (userId, agentId) 借用
   （HarnessGateway#attachUserSandboxContext → UserSandboxRegistry#borrow）；聊天请求的
   groupIds 只进 DatasetScope 用于工具可见性与 [DATA_SOURCES_OVERVIEW]/[KNOWLEDGE_BASE_OVERVIEW]
   动态注入（DataDynamicContextMiddleware），不参与容器选择与记忆命名空间。同一用户在 A/B 库
   问数读写的是容器内同一份 MEMORY.md——与建模助手修复前的形态相同。
3. **生命周期：短命记忆，销毁即丢。** 容器空闲 15 分钟自动回收（dataagent.sandbox.idle-ttl-min，
   evictor 每 60s 扫描）、服务重启清空内存 registry、marketplace 贡献审批触发 invalidate；
   三条路径都销毁容器且无持久化，记忆随之清零。

| 维度 | 问数助手 | 建模助手（本 spec 改造后） |
|---|---|---|
| 载体 | Docker 容器可写层 | 宿主机文件系统 |
| 隔离 | (userId, agentId)，USER 级记忆 | owner × kb-<groupId>，知识库级 |
| 跨库行为 | 共享同一份 MEMORY.md | 物理隔离，互不可见 |
| 持久性 | 随容器销毁丢失（≤15min 空闲/重启） | 磁盘持久，跨会话长期有效 |

**结论：维持现状，不改造。** 理由：①问数硬事实（数据源概览、MDL 目录）每轮从平台动态注入，
记忆只是偏好性软背景，跨库污染难以写坏资产；②容器短命使共享窗口有限；③本次 namespaceFactory
扩展点只加在 LocalFilesystemSpec，Docker spec 未开放该入口。后续若需对齐知识库级隔离，路径
为：harness DockerFilesystemSpec 增加同名 namespaceFactory 字段 + build() 同点位覆写，装配处
复用 GroupScopedMemoryNamespace 即可（改动面与本次相当）。
