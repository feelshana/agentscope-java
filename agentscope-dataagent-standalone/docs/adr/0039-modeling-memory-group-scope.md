# ADR 0039 — 建模助手长时记忆按知识库隔离（自定义 NamespaceFactory）

日期：2026-10-04 · 状态：Accepted

## 背景

harness 长时记忆（MEMORY.md + memory/ 日账本 + consolidation）的隔离粒度由 filesystem spec
的 `IsolationScope` 决定。modeling-agent 无沙箱、走默认 LocalFilesystemSpec，即 USER 粒度：
`workspace-modeling-agent\<userId>\MEMORY.md` 单文件跨全部知识库共享。取证（specs/029）：A 库
建模术语经 `<memory_context>` 每轮注入 B 库对话；10-01 手工清空后随三条写入通道自动重建，
结构必然复发。harness 四档枚举无 group 维度，但 `NamespaceFactory` 是函数式接口、
`WorkspaceManager` 读写管线统一走它、每轮 rc 已携带 `DatasetScope`——「按库隔离」只需一个
扩展点。

## 决策

1. harness `LocalFilesystemSpec` 新增可选 `namespaceFactory(NamespaceFactory)`；`HarnessAgent`
   build() 中该 factory 优先于枚举派生值（默认 null 时行为零变化）。
2. modeling-agent 装配
   `new LocalFilesystemSpec().namespaceFactory(GroupScopedMemoryNamespace.INSTANCE)`：
   单库 turn → `[owner, "kb-<groupId>"]`；其余回退 USER 语义。
3. 历史用户级记忆文件全部删除（旧路径在新布局下不可达）。

## 理由（trade-off）

- 污染根因是「隔离粒度（用户）粗于污染边界（知识库）」；把粒度精确到知识库即结构性根治，
  且保留同库跨会话、同会话跨轮次的记忆连续性——这是建模记忆真正兑现价值的场景。
- 改动面最小：harness ~20 行透传 + dataagent 一处接线 + 一个纯函数类；读写四端
  （注入/memory_save/flush/consolidator）经同一 nsFactory 自动一致，无三处同步改。
- 反向取舍：一个会话若跨多个知识库（组合场景）或未携带 scope，回退用户级——回退桶与其
  单库桶隔离（有库对话永远看不到回退桶），隔离目标不破；代价是这类边缘对话记忆不细分。

## 被否方案

- **SESSION 级**（`IsolationScope.SESSION`，零 harness 改动）：跨库传染同样归零，但记忆
  随会话消亡，长对话跨会话的建模经验全部丢弃——粒度细于诉求，信息价值受损；且旧会话
  历史文件同样断供，收益与成本都不优于本方案。
- **prompt 打标 + 读端过滤**：`<memory_context>` 是字面拼接，过滤依赖 LLM 遵循度，不可靠。
- **维持 USER + 定期手工清记忆**：10-01 已实证无效，写入通道随使用自动重建。

## 影响面

- harness：`LocalFilesystemSpec`（新增字段/存取器）、`HarnessAgent.Builder.build()`（覆写
  一行逻辑）；默认路径零变化，既有行为不受影响。
- dataagent：新类 `runtime/session/GroupScopedMemoryNamespace` + `DataAgentConfig` 装配
  分支翻转（sandboxless 分支由「不设 spec」变为「设自定义 spec」）；问数 agent 不变。
- 磁盘布局：`workspace-modeling-agent\<owner>\kb-<groupId>\{MEMORY.md, memory\, sessions\}`；
  旧 `<owner>\MEMORY.md` / `<owner>\memory\` 删除；旧会话 workspace 历史不可达（会话上下文
  事实源为 AgentStateStore，不受影响）。
- 会话注册/解析链（SessionAgentManager + SessionRegistryRepository）不依赖 workspace
  布局，零影响。

## 验证

`mvn test` 全绿（含 `GroupScopedMemoryNamespaceTest` 的「两库不共享」用例）；运行时点检
G2-G4（specs/029）。
