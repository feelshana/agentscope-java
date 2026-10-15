# Spec 059: 问答并发容量控制与排队

> 来源兼容入口：data-agent-multiAgent-Mdl-lyf 的 spec 059，与本分支现有 spec 059 编号冲突。当前实施与验收见 [spec 067](067-platform-followup-integration.md)、[ADR 0078](../adr/0078-platform-capacity-followup-integration.md)；下文状态属于来源历史。

对应 ADR：0070
日期：2026-10-14
状态：已实施

## 1. 概述

在应用层实现问答并发控制，包括进程级预算、排队机制、超时保护和资源驱逐。

## 2. ChatCapacity

### 2.1 核心概念

`ChatCapacity` 是 Spring `@Component`，管理进程级问答预算：

- **active**：正在执行的问答（占用 worker + 沙箱）
- **waiting**：排队中的问答（不占用 worker 或沙箱）
- **per-user 限制**：防止单用户垄断资源

### 2.2 准入流程

```
用户提问 → ChatController.stream()
  → ChatCapacity.execute(owner, conversation, work, status)
    → request(owner, conversation)
      → 同会话重复？→ BusyException("当前会话正在回答")
      → active < maxActive && userActive < maxPerUser？
        → 是：立即执行，start(lease)
        → 否：进入排队
          → waiting >= maxWaiting || userWaiting >= maxWaitingPerUser？
            → 是：BusyException("等待名额已满")
            → 否：queued = true，加入 waiting 队列
    → 排队中：发送 SSE capacity{state:"queued"}
    → 等待 ready signal（timeout: waitSeconds）
    → 获得执行权：发送 SSE capacity{state:"running"}
    → 执行 work（timeout: executionSeconds）
    → close()：释放预算，drain() 唤醒下一个排队者
```

### 2.3 Lease 生命周期

```
state 0: queued（等待中）
state 1: active（执行中）
state 2: draining（执行结束但 Python 子进程仍在运行）
state 3: finished（已释放）
```

`operation()` 跟踪 Python 执行数。`close()` 时若有 operation 未完成，进入 state 2 延迟释放。

### 2.4 SSE 事件

前端通过 SSE `capacity` 事件感知状态：

```json
{"type": "capacity", "state": "queued", "message": "排队中，正在等待执行名额…"}
{"type": "capacity", "state": "running", "message": "正在处理你的问题…"}
```

## 3. UserSandboxRegistry 变更

### 3.1 容量提升

`max-containers` 从 8 提升至 16。

### 3.2 死锁修复

原 `acquire()` 在 `ConcurrentHashMap.compute()` 内执行跨 key 驱逐，可能导致嵌套 compute 死锁。

修复：先 `computeIfPresent()` 检查已有 entry，不存在时再 `reserveSlot()` 在外部执行驱逐。

### 3.3 容量压力驱逐

`reserveSlot()` 逻辑：
1. `slots.tryAcquire()` 成功 → 返回
2. 失败 → 按 `lastAccessMs` 排序所有 entry
3. 逐个检查 `active == 0` 的 entry，`closeQuietly()` 关闭
4. 每关闭一个尝试 `tryAcquire()`
5. 全部遍历完仍无空位 → 抛出 `IllegalStateException`

## 4. WrenInstanceRegistry 变更

### 4.1 超时门控

```java
// 旧：无限等待
synchronized (gate) { return doCall(...); }

// 新：超时返回 503
ReentrantLock gate = callGates.computeIfAbsent(groupId, k -> new ReentrantLock(true));
if (!gate.tryLock(callWaitSeconds, TimeUnit.SECONDS))
    throw new DatasetException("该知识库查询繁忙", 503);
```

### 4.2 实例容量信号量

`Semaphore(maxInstances)` 控制 Wren 实例总数。满额时 LRU 驱逐空闲实例（`reserved == false`）。

## 5. DockerSandbox exec 可取消

### 5.1 进程组管理

每次 exec 通过 `setsid` 创建独立进程组，写入 PID 文件 `/tmp/agentscope-exec-<uuid>`。

### 5.2 取消流程

```
turn 取消 → DockerSandbox.stopExecGroup(containerId, jobFile)
  → docker exec sh -c "kill -TERM -$(cat <jobFile>)" 
  → 等待 2 秒
  → 若仍存活：kill -KILL
```

### 5.3 RunPythonTool 集成

`RunPythonTool` 通过 `ChatCapacity.Lease.operation()` 注册执行跟踪。Lease 取消时，`operation()` 的 `AutoCloseable` 触发，配合 DockerSandbox 的进程组取消停止 Python。

## 6. 配置参考

```yaml
dataagent:
  sandbox:
    max-containers: 16
  chat:
    max-active: 16
    max-active-per-user: 2
    max-waiting: 16
    max-waiting-per-user: 1
    wait-seconds: 30
    execution-seconds: 600
  wren:
    max-instances: 8
    call-wait-seconds: 30
  openai:
    http-version: HTTP_1_1
```

## 7. 测试覆盖

| 测试类 | 验证内容 |
|--------|---------|
| `ChatCapacityTest` | 排队、超时、per-user 限制、drain 顺序 |
| `UserSandboxCapacityTest` | 满额驱逐、死锁修复、Lease 生命周期 |
| `WrenInstanceRegistryTest` | 超时门控、信号量驱逐、pendingCleanup |
| `DockerExecCancellationTest` | 进程组创建、取消停止、PID 文件清理 |
| `WorkspaceNamespaceLockTest` | 命名空间锁并发安全 |
