> Source snapshot: data-agent-multiAgent-Mdl-lyf @ 6a75b6cf (2026-10-14). Original numbering and historical validation retained. See ../README.md and current ADR 0078 for applicability; this is not evidence of validation on the current branch.

# ADR 0070: 问答并发容量控制与排队机制

日期：2026-10-14
状态：已接受（已实施）

## 背景

随着沙箱容量从 8 提升至 16、Wren 实例并发上限放开，多用户同时提问时出现三类问题：

1. **沙箱争抢**：16 个容器满额后新请求直接失败，用户看到"沙箱创建失败"而非友好提示
2. **Wren 调用堆积**：同一知识库被多人同时查询时，`synchronized(callGate)` 无限等待，线程阻塞无超时
3. **无排队反馈**：请求要么立即执行、要么直接报错，缺少"排队中"中间态，用户体验差

## 决策

### 1. ChatCapacity — 进程级问答预算

新增 `web/chat/ChatCapacity.java`，在应用层控制并发问答数量：

| 参数 | 默认值 | 含义 |
|------|--------|------|
| `max-active` | 16 | 同时执行的问答数 |
| `max-active-per-user` | 2 | 单用户同时执行的问答数 |
| `max-waiting` | 16 | 排队队列最大长度 |
| `max-waiting-per-user` | 1 | 单用户排队数 |
| `wait-seconds` | 30 | 排队等待超时 |
| `execution-seconds` | 600 | 单次问答执行超时 |

排队请求不占用 worker 线程或沙箱。通过 SSE `capacity` 事件通知前端状态变化（`queued` → `running`）。

`ChatController` 的 `stream()` 和 `send()` 均经 `ChatCapacity.execute()` 准入控制。建模 Agent 跳过容量控制（单用户独占场景）。

### 2. UserSandboxRegistry — 容量提升 + 死锁修复

- 沙箱上限从 8 提升至 16（`max-containers: 16`）
- **死锁修复**：`acquire()` 将跨 key 驱逐逻辑移出 `ConcurrentHashMap.compute()`，避免嵌套 compute 死锁
- **容量压力驱逐**：满额时按 LRU 驱逐空闲沙箱（`active == 0`），而非直接报错
- `borrow()` 改为委托 `acquire()` + `Lease`，统一生命周期管理

### 3. WrenInstanceRegistry — 超时门控 + 容量信号量

- 调用门控从 `synchronized(Object)` 升级为 `ReentrantLock(true)` + `tryLock(callWaitSeconds)`
- 等待超时返回 503「该知识库查询繁忙」而非无限阻塞
- 新增 `Semaphore(maxInstances)` 控制 Wren 实例总数，满额时 LRU 驱逐空闲实例
- `pendingCleanup` 确保关闭时清理待回收实例

### 4. DockerSandbox exec 可取消

- 每次 exec 创建独立进程组（`setsid`），写入 PID 文件
- 取消时通过 `stopExecGroup()` 杀进程组而非 `destroyForcibly()`，精确停止目标命令及其子进程
- `RunPythonTool` 集成 `ChatCapacity.Lease.operation()`，取消问答时同步停止 Python 执行

### 5. OpenAI HTTP/1.1 强制

- 新增 `dataagent.openai.http-version: HTTP_1_1` 配置
- 避免共享 HTTP/2 连接在流式响应期间断连（跨 stream disconnect 问题）

### 6. HarnessGateway 资源清理

- `withGatedTurn()` / `withGatedStream()` 从 `AtomicReference + doFinally` 改为 `Mono.using()`
- 确保 TurnLease 在任何信号（正常/错误/取消）下都正确释放

## 理由与权衡

**排队 vs 直接拒绝**：排队让用户看到"等待中"而非"失败"，减少重试风暴。代价是增加了一层状态管理。

**LRU 驱逐 vs 固定分配**：满额时驱逐最久未用的沙箱/Wren 实例，比固定分配更灵活。风险是驱逐后下次请求需重建（3-8 秒），但比直接失败好。

**进程组取消 vs destroyForcibly**：`setsid` + PID 文件精确停止目标命令及子进程，避免误杀同容器的其他 exec。代价是每次 exec 多一个 PID 文件写入。

## 影响面

### 配置变化

```yaml
dataagent:
  sandbox:
    max-containers: 16  # 原 8
  chat:
    max-active: 16
    max-active-per-user: 2
    max-waiting: 16
    max-waiting-per-user: 1
    wait-seconds: 30
    execution-seconds: 600
  openai:
    http-version: HTTP_1_1  # 新增
```

### 前端变化

`ChatPanel.tsx` 监听 SSE `capacity` 事件，显示排队/执行状态提示。

## 测试要求

- `ChatCapacityTest` — 验证排队、超时、per-user 限制
- `UserSandboxCapacityTest` — 验证满额驱逐、死锁修复
- `WrenInstanceRegistryTest` — 验证超时门控、容量信号量
- `DockerExecCancellationTest` — 验证进程组取消
- `WorkspaceNamespaceLockTest` — 验证命名空间锁

## 关联

- ADR 0047（懒加载沙箱 — 本文扩展其容量和死锁修复）
- ADR 0023（Wren 调用串行化 — 本文升级为超时门控）
- ADR 0060（会话历史 — ChatCapacity 与会话生命周期交互）
