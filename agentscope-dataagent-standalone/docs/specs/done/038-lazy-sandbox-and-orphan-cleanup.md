# spec 038: 懒加载沙箱 + 启动时孤儿容器清理

> 2026-10-08 更新：ADR 0060 / spec 052：本文旧示例与兼容性说明已部分替代，当前使用标签清理、活动租约和资源限制。

> ADR 0047（Accepted）的实施明细。

## 背景与目标

per-user 沙箱方案（ADR 0003）按 `(userId, agentId)` 创建长存 Docker 容器。生产运行发现两个问题：

1. **容器泄漏**：非常规关闭（IDE 停止、崩溃、断电）导致 `@PreDestroy shutdownAll()` 未执行，
   容器残留。每次 JVM 重启 `entries` 内存映射清空，不知道旧容器存在，又创建新的。累积效应：
   开发机出现 35 个残留容器。

2. **不必要的容器创建**：`HarnessGateway#attachUserSandboxContext` 每次消息都调用 `borrow()`，
   纯 SQL 问数（`wren_run_sql`、`wren_query_cube`）也创建容器。纯 SQL 查询不需要沙箱，每次无谓
   创建消耗 3-8 秒 Docker 开销。

**目标**：
- 纯 SQL 问数永远不创建容器（零开销）
- 需要 `run_python` 时首次创建（3-8 秒延迟，仅一次），15 分钟内复用
- 启动时自动清理历史残留容器

## 方案概述

### 1. LazySandbox 包装器

**文件**：`web/workspace/LazySandbox.java`（新增）

实现 `Sandbox` 接口，将容器创建延迟到首次实际操作：

```java
public final class LazySandbox implements Sandbox {
    private final Supplier<Sandbox> supplier;
    private volatile Sandbox delegate;

    public LazySandbox(Supplier<Sandbox> supplier) {
        this.supplier = supplier;
    }

    private Sandbox ensureDelegate() throws Exception {
        Sandbox d = delegate;
        if (d != null) return d;
        synchronized (this) {
            if (delegate != null) return delegate;
            delegate = supplier.get();
            delegate.start();
            return delegate;
        }
    }

    @Override
    public void start() {
        // No-op: 中间件每次 agent 调用都执行，跳过以避免无谓创建
    }

    @Override
    public ExecResult exec(RuntimeContext rc, String command, Integer timeout) throws Exception {
        return ensureDelegate().exec(rc, command, timeout);
    }

    // persistWorkspace(), hydrateWorkspace() 同样调用 ensureDelegate()

    @Override
    public void close() { /* no-op: 注册表管理生命周期 */ }

    @Override
    public void stop() { /* no-op */ }

    @Override
    public void shutdown() { /* no-op */ }
}
```

**关键设计**：
- `start()` 为 no-op：`SandboxLifecycleMiddleware` 每次调用都执行 `sandbox.start()`，跳过以避免
  纯 SQL 问数时创建容器
- `exec()` 等实际操作触发 `ensureDelegate()`：首次调用时通过 supplier 创建真实容器并 start
- `close()`/`stop()`/`shutdown()` 为 no-op：容器生命周期由 `UserSandboxRegistry` 管理（idle TTL 回收）
- `delegate` 为 volatile + synchronized：线程安全，supplier 只调用一次

### 2. HarnessGateway 懒加载注入

**文件**：`runtime/gateway/HarnessGateway.java`（修改）

`attachUserSandboxContext()` 改为注入 `LazySandbox`：

```java
void attachUserSandboxContext(RuntimeContext.Builder builder, String userId, String agentId) {
    if (userSandboxRegistry == null || userId == null || userId.isBlank()) return;
    if (sandboxlessAgents.contains(agentId)) return;
    try {
        Sandbox lazySb = new LazySandbox(() -> userSandboxRegistry.borrow(userId, agentId));
        SandboxContext sandboxCtx = SandboxContext.builder()
                .externalSandbox(lazySb)
                .isolationScope(IsolationScope.USER)
                .build();
        builder.put(SandboxContext.class, sandboxCtx);
    } catch (RuntimeException e) {
        log.warn("[gateway] Failed to attach lazy sandbox for user={}, agent={}", userId, agentId, e);
    }
}
```

**执行流程**：

```
用户发消息 → gateway.attachUserSandboxContext()
  → 创建 LazySandbox(supplier = () -> registry.borrow(userId, agentId))
  → 注入 SandboxContext.externalSandbox = lazySandbox
  → 中间件 acquireForCall() → sandboxManager.acquire() → 返回 lazySandbox
  → 中间件调用 lazySandbox.start() → no-op（不创建容器）
  → agent 执行工具
    → 纯 SQL 问数（wren_run_sql）→ 不调用 sandbox.exec() → 永远不创建容器
    → run_python → 调用 filesystem.execute() → sandbox.exec() → ensureDelegate()
      → 首次：supplier.get() → registry.borrow() → 创建容器（3-8 秒）
      → 后续：直接返回 delegate（零开销）
```

### 3. 启动时孤儿容器清理

**文件**：`web/workspace/UserSandboxRegistry.java`（修改）

增加 `@PostConstruct cleanupOrphanedContainers()` 方法：

```java
@PostConstruct
void cleanupOrphanedContainers() {
    if (!(client instanceof DockerSandboxClient)) return;
    try {
        String image = optionsTemplate != null && optionsTemplate.getImage() != null
                ? optionsTemplate.getImage()
                : "agentscope/dataagent-sandbox:latest";
        log.info("[sandbox-registry] cleaning up orphaned containers for image={}", image);

        // 1. 列出所有匹配镜像的容器
        ProcessBuilder pb = new ProcessBuilder("docker", "ps", "-aq", "--filter", "ancestor=" + image);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes(), UTF_8).strip();
        int exitCode = proc.waitFor();
        if (exitCode != 0) {
            log.warn("[sandbox-registry] docker ps failed (exit={}): {}", exitCode, output);
            return;
        }
        if (output.isEmpty()) {
            log.info("[sandbox-registry] no orphaned containers found");
            return;
        }

        // 2. 批量删除
        String[] containerIds = output.split("\\s+");
        log.info("[sandbox-registry] found {} orphaned container(s), removing", containerIds.length);
        ProcessBuilder rmPb = new ProcessBuilder(
                Stream.concat(Stream.of("docker", "rm", "-f"), Arrays.stream(containerIds))
                        .toArray(String[]::new));
        rmPb.redirectErrorStream(true);
        Process rmProc = rmPb.start();
        int rmExit = rmProc.waitFor();
        if (rmExit != 0) {
            log.warn("[sandbox-registry] docker rm failed (exit={})", rmExit);
        } else {
            log.info("[sandbox-registry] cleaned up {} orphaned container(s)", containerIds.length);
        }
    } catch (Exception e) {
        log.warn("[sandbox-registry] orphan cleanup failed (non-fatal): {}", e.getMessage(), e);
    }
}
```

**执行时机**：Spring Bean 初始化后、第一次 `borrow()` 调用前。

**失败处理**：只 warn 不抛异常，Docker 未启动时不影响应用启动。

## 影响面

### 代码变更

| 文件 | 变更类型 | 行数 | 说明 |
|------|---------|------|------|
| `web/workspace/LazySandbox.java` | 新增 | ~150 | 懒加载包装器 |
| `runtime/gateway/HarnessGateway.java` | 修改 | ~10 | `attachUserSandboxContext` 使用 `LazySandbox` |
| `web/workspace/UserSandboxRegistry.java` | 修改 | ~60 | 增加 `@PostConstruct cleanupOrphanedContainers()` |

### 行为变化

| 场景 | 变更前 | 变更后 |
|------|--------|--------|
| 纯 SQL 问数 | 创建容器（3-8 秒） | **不创建容器**（零开销） |
| 首次 run_python | 创建容器（3-8 秒） | 创建容器（3-8 秒，**无变化**） |
| 后续 run_python（15 分钟内） | 复用容器 | 复用容器（**无变化**） |
| 启动时 | 不清理残留 | **自动清理残留容器** |
| 浏览器工作区首次打开 | 复用已有容器 | 触发容器创建（若有文件操作） |

### 性能影响

| 指标 | 变更前 | 变更后 |
|------|--------|--------|
| 启动时间 | 基准 | +1 秒（孤儿清理） |
| 纯 SQL 问数延迟 | 3-8 秒（创建容器） | **0 秒**（不创建容器） |
| 首次 run_python 延迟 | 3-8 秒 | 3-8 秒（**无变化**） |
| 后续 run_python 延迟 | 0 秒 | 0 秒（**无变化**） |
| 内存/CPU 消耗 | 每用户 1 容器 | **纯 SQL 场景零容器** |

### 兼容性

- **向后兼容**：现有功能不受影响（run_python、浏览器工作区、贡献审批 invalidate）
- **配置无变化**：`dataagent.sandbox.image`、`idle-ttl-min` 等配置项保持原样
- **多副本部署**：仍需 sticky LB（ADR 0003 约束不变）
- **sandboxless agent**：modeling-agent 仍跳过沙箱（ADR 0025 逻辑不变）

## 验收标准（Given-When-Then）

1. Given 应用启动，When 检查 Docker 容器，Then 无历史残留容器（日志可见清理数量）
2. Given 用户发送纯 SQL 问数（"上个月销售额多少"），When 检查 Docker 容器，Then 无新容器创建
3. Given 用户发送数据分析请求（"画个趋势图"），When 检查日志，Then 可见 `[lazy-sandbox] first operation — creating container now`
4. Given 容器已创建，When 15 分钟内再次画图，Then 复用同一容器（无新创建日志）
5. Given 容器已创建，When 15 分钟无操作，Then 容器被 idle 回收（日志可见 `closed sandbox for ... (idle)`）
6. Given 直接停止应用（IDE 红叉），When 重启应用，Then 启动日志可见清理残留容器
7. Given Docker 未启动，When 启动应用，Then 孤儿清理失败只 warn，应用正常启动

## 不做的事（明确排除项）

- 不修改 harness 层 `SandboxLifecycleMiddleware`（保持 `start()` 调用，由 `LazySandbox` 内部跳过）
- 不改变 `UserSandboxRegistry` 的 `borrow()` 逻辑（仍按 `(userId, agentId)` 缓存）
- 不改变 idle TTL 机制（仍为 15 分钟）
- 不改变浏览器工作区控制器（`WorkspaceManagerFactory` 仍调用 `borrow()`）
- 不改变贡献审批流程（`invalidate()` 逻辑不变）

## 测试要求

### 单元测试

- **LazySandboxTest**：
  - `start()` 为 no-op（不调用 supplier）
  - `exec()` 首次调用触发 supplier + start
  - `exec()` 后续调用复用 delegate
  - `close()` 不关闭 delegate
  - 并发测试：多线程同时调用 `exec()`，supplier 只执行一次

- **UserSandboxRegistryOrphanCleanupTest**：
  - 模拟残留容器（手动 `docker run`）
  - 启动应用，验证清理日志
  - 验证清理后 `docker ps` 无残留

### 集成测试

- **LazySandboxIntegrationTest**：
  - 启动应用，发送纯 SQL 问数 → 检查 Docker 无新容器
  - 发送数据分析请求 → 检查容器创建
  - 15 分钟内再次画图 → 检查复用
  - 直接停止应用 → 重启 → 检查清理

### 手动验证

1. 启动应用，发送纯 SQL 问数（"上个月销售额多少"）
   - 预期：无 `[lazy-sandbox] first operation` 日志
   - 预期：`docker ps` 无新容器
2. 发送数据分析请求（"画个趋势图"）
   - 预期：日志可见 `[lazy-sandbox] first operation — creating container now`
   - 预期：`docker ps` 可见新容器
3. 15 分钟内再次画图
   - 预期：无新创建日志
   - 预期：复用同一容器
4. 直接停止应用（IDE 红叉）
   - 预期：容器残留
5. 重启应用
   - 预期：启动日志可见 `cleaning up orphaned containers`
   - 预期：日志可见 `cleaned up N orphaned container(s)`
   - 预期：`docker ps` 无残留

## 关联

- ADR 0047（懒加载沙箱 + 孤儿清理决策）
- ADR 0003（per-user 沙箱 + 网关注入——本规格是其优化）
- ADR 0025（建模助手免沙箱——本规格不影响 sandboxless 逻辑）
