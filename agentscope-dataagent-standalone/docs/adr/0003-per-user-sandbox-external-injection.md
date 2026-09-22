# ADR 0003: 每 (userId, agentId) 长存沙箱 + 网关 externalSandbox 注入，多副本需 sticky LB

- 状态：已采纳
- 日期：2026（沙箱方案定型时）

## 背景

`run_python` 需要隔离执行环境，且浏览器端的工作区 API（文件树、产物下载）必须与
agent 执行读写**同一个文件系统**，否则用户看不到 agent 产出的图表/CSV。

## 决策

1. `UserSandboxRegistry` 为每个 `(userId, agentId)` 维护一个长存 Docker 容器
   （镜像 `dataagent.sandbox.image`，`--network=none`），空闲 15 分钟回收。
2. 容器启动时把宿主机共享层 `shared/agents/{agentId}/` 下的
   `AGENTS.md / skills / subagents / knowledge` 只读投影进容器。
3. 每个 agent turn 由 `HarnessGateway#attachUserSandboxContext` 将该容器注入
   `SandboxContext.externalSandbox`，走 SandboxManager 的 Priority-1 acquire 路径——
   而不是让 harness 按会话自起自灭容器。
4. 沙箱生命周期由应用掌控（区别于 codingagent 的 per-session 自管理）。

## 理由与权衡

- 浏览器与 agent 同容器：产物（outputs/*.png）立即可见可下载。
- 容器复用：避免每轮问答都付 Docker 冷启动成本。
- 贡献审批后 `invalidate(null, agentId)` 批量失效，下次 borrow 自动投影最新共享技能。
- **代价/约束**：注册表是 JVM 内存态。多副本部署必须按 userId 做 sticky 负载均衡，
  否则两个 Pod 为同一用户各起一个容器、工作区互相不可见。

## 被否决的替代方案

- harness 默认的 per-session 沙箱：浏览器工作区与 agent 文件系统分裂。
- 共享一个全局沙箱：多用户代码执行互相污染，安全风险不可接受。

## 适用与失效条件

适用于单副本或可按 userId sticky 的部署。若必须无状态水平扩展，需要把
沙箱注册表外置（如独立的沙箱调度服务），届时重审本决策。
