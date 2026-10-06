# ADR 0047: 懒加载沙箱 + 启动时孤儿容器清理

日期：2026-10-06
状态：已接受（已实施）

## 背景

per-user 沙箱方案（ADR 0003）按 `(userId, agentId)` 创建长存 Docker 容器，空闲 15 分钟回收。
生产运行后发现两个问题：

1. **容器泄漏**：非常规关闭（IDE 直接停止、进程崩溃、断电）导致 `@PreDestroy shutdownAll()`
   未执行，Docker 容器残留在后台。每次 JVM 重启后 `entries` 内存映射清空，不知道旧容器存在，
   又创建新的。累积效应：开发机出现 35 个残留容器，消耗 CPU/内存资源。

2. **不必要的容器创建**：`HarnessGateway#attachUserSandboxContext` 在每次用户发消息时都调用
   `borrow()`，即使是纯 SQL 问数（`wren_run_sql`、`wren_query_cube`）也创建容器。纯 SQL 查询
   根本不需要沙箱——结果直接返回文本，不执行 Python 代码。每次无谓创建消耗 3-8 秒 Docker 开销
   （本地 Windows/Docker Desktop 更慢，约 5-10 秒）。

## 决策

### 1. 懒加载沙箱（LazySandbox）

引入 `LazySandbox` 包装器，实现 `Sandbox` 接口，将容器创建延迟到首次实际操作：

- `start()` — no-op（中间件每次 agent 调用都执行，跳过以避免无谓创建）
- `exec()`、`persistWorkspace()`、`hydrateWorkspace()` — 首次调用时通过 `Supplier<Sandbox>`
  创建真实容器，后续调用直接委托（15 分钟 TTL 内复用，零开销）
- `close()`、`stop()`、`shutdown()` — no-op（注册表管理生命周期）

`HarnessGateway#attachUserSandboxContext` 改为注入 `LazySandbox` 而非直接 `borrow()`：

```java
Sandbox lazySb = new LazySandbox(() -> userSandboxRegistry.borrow(userId, agentId));
```

效果：
- 纯 SQL 问数 → 永远不创建容器（工具不调用 exec）
- 需要 `run_python` → 首次调用时创建（3-8 秒延迟，仅一次）
- 15 分钟内后续调用 → 复用同一容器，零额外开销
- 15 分钟空闲 → 注册表回收，下次再创建

### 2. 启动时孤儿容器清理

`UserSandboxRegistry` 增加 `@PostConstruct cleanupOrphanedContainers()` 方法，JVM 启动时扫描
所有使用 `dataagent.sandbox.image` 镜像的容器并删除。逻辑：

1. `docker ps -aq --filter "ancestor=<image>"` 列出所有匹配容器
2. 若结果为空，跳过
3. 否则 `docker rm -f <id1> <id2> ...` 批量删除
4. 失败只 warn 不抛异常（Docker 未启动时不影响应用启动）

效果：无论上次如何关闭，本次启动时清理所有残留容器，从零开始。

## 理由与权衡

### 懒加载沙箱

**优点**：
- 纯 SQL 问数（占大多数场景）完全零容器开销
- 需要沙箱时仅首次有 3-8 秒延迟，后续复用无开销
- 不改变 harness 层代码（`SandboxLifecycleMiddleware` 仍调用 `start()`，但 `LazySandbox.start()`
  是 no-op）
- 向后兼容：浏览器工作区、`run_python` 等功能不受影响

**缺点**：
- 首次 `run_python` 有 3-8 秒延迟（本地可能 5-10 秒）
- 需要额外的 `LazySandbox` 类（约 150 行）

**被否决的方案**：
1. 修改 harness 层 `SandboxLifecycleMiddleware` 跳过 `start()` — 侵入性太强，影响其他模块
2. 在工具层（`RunPythonTool`）直接 borrow — 需要改工具构造和 RuntimeContext 传递
3. 保持现状只修复泄漏 — 纯 SQL 问数仍浪费资源

### 孤儿容器清理

**优点**：
- 彻底解决容器泄漏问题
- 启动时一次性清理，运行时无开销
- 失败不阻塞应用启动

**缺点**：
- 启动时多一个 Docker CLI 调用（约 1 秒）

**被否决的方案**：
1. 依赖 `@PreDestroy` — 非常规关闭时不执行
2. 添加 shutdown hook — 仍可能被 `kill -9` 跳过
3. 定期扫描清理 — 复杂度高，且运行中不应删除其他 JVM 的容器

## 影响面

### 代码变更

- **新增**：`web/workspace/LazySandbox.java` — 懒加载包装器（约 150 行）
- **修改**：`runtime/gateway/HarnessGateway.java` — `attachUserSandboxContext` 使用 `LazySandbox`
- **修改**：`web/workspace/UserSandboxRegistry.java` — 增加 `@PostConstruct cleanupOrphanedContainers()`

### 行为变化

- **纯 SQL 问数**：不再创建容器（零开销）
- **数据分析（run_python）**：首次调用时创建容器（3-8 秒延迟），后续复用
- **启动时**：自动清理历史残留容器（日志可见清理数量）
- **浏览器工作区**：首次打开时触发容器创建（若有文件操作）

### 性能影响

- **启动时间**：增加约 1 秒（孤儿清理 Docker CLI 调用）
- **首次 run_python**：增加 3-8 秒（容器创建）
- **后续 run_python**：无变化（复用容器）
- **纯 SQL 问数**：节省 3-8 秒（不创建容器）
- **内存/CPU**：显著降低（纯 SQL 场景无容器运行）

### 兼容性

- **向后兼容**：现有功能（run_python、浏览器工作区、贡献审批 invalidate）不受影响
- **配置无变化**：`dataagent.sandbox.image`、`idle-ttl-min` 等配置项保持原样
- **多副本部署**：仍需 sticky LB（ADR 0003 约束不变）

## 测试要求

- **单元测试**：`LazySandboxTest` — 验证 start() 为 no-op、exec() 触发创建、后续调用复用
- **集成测试**：`UserSandboxRegistryOrphanCleanupTest` — 模拟残留容器，验证启动时清理
- **手动验证**：
  1. 启动应用，发送纯 SQL 问数 → 检查 Docker 无新容器
  2. 发送数据分析请求（画图）→ 检查容器创建（日志 `[lazy-sandbox] first operation`）
  3. 15 分钟内再次画图 → 检查复用同一容器（无新创建日志）
  4. 直接停止应用（不优雅关闭）→ 重启 → 检查启动日志清理残留容器

## 关联

- ADR 0003（per-user 沙箱 + 网关注入——本决策是其懒加载优化）
- ADR 0025（建模助手免沙箱——本决策不影响 sandboxless 逻辑）
- specs/038（懒加载沙箱实施明细）
