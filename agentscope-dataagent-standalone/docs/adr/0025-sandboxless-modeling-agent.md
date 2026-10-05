# ADR 0025: 元数据型 agent 免沙箱（建模助手脱离 Docker）

日期：2026-09-30
状态：已接受（已实施）

## 背景

对话式建模（ADR 0024，specs/013 M1）上线后，Docker 未启动的环境点击「开始建模」硬失败：

```
SandboxException$SandboxRuntimeException: docker run failed (exit=127): docker: error during
connect: Head "http://%2F%2F.%2Fpipe%2FdockerDesktopLinuxEngine/_ping": open
//./pipe/dockerDesktopLinuxEngine: The system cannot find the file specified.
```

根因三层叠加：① `DataAgentConfig#builderBootstrap` 经 `configureAllAgents` 给每个 agent
（含建模助手）声明 `DockerFilesystemSpec`；② 网关 `attachUserSandboxContext` borrow 失败
只 warn 降级；③ harness `HarnessAgent#ensureSessionDefaults` 兜底注入 agent 级
`defaultSandboxContext`（来自①的 spec）→ `SandboxLifecycleMiddleware` →
`SandboxManager#acquire` Priority-4 自建 → `docker run` 硬失败。

建模助手是纯元数据 agent：九个建模工具（ModelingToolkit）全部读写平台实体
（`MdlSuggestionService` / `MdlPublishService`），工具面零文件系统/Shell/Python 依赖。

## 决策

建模助手（及后续同类元数据型 agent）完全脱离 per-user Docker 沙箱，三层实施：

1. `DataAgentBootstrap.Builder#configureAllAgents` 增加 agent-id 感知重载
   （`BiConsumer<String, HarnessAgent.Builder>`；旧 `Consumer` 版委托保留，兼容既有调用）；
2. `DataAgentConfig` 维护 `sandboxlessAgents` 集合（当前 = `{modeling-agent}`）：装配期豁免
   `DockerFilesystemSpec`，并指定独立空工作区
   `~/.agentscope/dataagent/workspace-modeling-agent`（启动时 `Files.createDirectories`）；
3. `HarnessGateway#setSandboxlessAgents`：`attachUserSandboxContext` 对豁免 id 直接 return，
   不 borrow 容器；启动接线 `bootstrap.gateway().setSandboxlessAgents(sandboxlessAgents)`。

免沙箱态文件系统为 harness 默认 `LocalFilesystemSpec`（空工作区）：无 sandbox spec → 无
`SandboxLifecycleMiddleware` → 无兜底 `defaultSandboxContext` → 全链无 Docker。

## 理由与权衡

- 该 agent 工具面零沙箱依赖；对话建模与 MDL 发布（语义建模页）均不涉及文件产物。
- 独立空工作区而非复用主工作区：主工作区（`DEFAULT_WORKSPACE_ROOT`）含 data-agent 的
  AGENTS.md/技能种子，`WorkspaceContextMiddleware` 会注入 system prompt 造成人格错位；
  独立空目录复刻「空容器投影」的既有行为。
- 被拒方案：① 让网关 borrow 失败继续走 harness 兜底——即 bug 本身，Docker 缺失时必炸；
  ② 全局配置开关关闭 Docker spec——会连主 agent 的 run_python/工作区能力一起关掉；
  ③ 工具层懒建沙箱（改 harness 行为）——超出本模块边界。
- 代价：`configureAllAgents` 签名升级（内部 API）；`sandboxless` 是显式名单，
  新增元数据型 agent 需在 `DataAgentConfig` 手工登记。

## 影响面

- 代码：`runtime/DataAgentBootstrap`（configureAllAgents 重载）、
  `web/config/DataAgentConfig`（sandboxlessAgents + 建模工作区 + 网关接线）、
  `runtime/gateway/HarnessGateway`（`setSandboxlessAgents` + `attachUserSandboxContext`
  分支）。
- 行为：Docker 未启动时对话建模照常可用；主问数 agent（run_python、浏览器工作区 API）
  仍按 ADR 0003 依赖 per-user 容器。
- 测试：`HarnessGatewaySandboxlessTest`（豁免 id 免借容器 / 其他 agent 照常 borrow 且复用 /
  null 重置恢复全员借）。
- 文档：ARCHITECTURE_zh 2.3（装配步 4/6）、7.3（免沙箱 agent）、12.3（工作区树）。

## 关联

- ADR 0024（建模助手来源）、ADR 0003（per-user 沙箱注入——本决策是其显式例外）
- specs/013（对话式建模实施规格）、`HarnessGatewaySandboxlessTest`
