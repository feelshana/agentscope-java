# ADR 0023: wren 实例调用按组串行化（MCP stdio transport 非线程安全）

日期：2026-09-29
状态：已接受（已实施）

## 背景

2026-09-29（服务重启后首次问数）用户报告 `wren_run_sql` / `wren_query_cube` 报
「实例已重置，重试将自动重建」。`logs/LLM.log` 该会话四轮调用呈**确定性**对照：

| 轮次 | 调用形态 | 结果 |
|---|---|---|
| 1 | query_cube + run_sql 并发（同一 assistant 消息两个 tool_use） | ❌ `Failed to enqueue message` + `MCP session with server terminated` 成对出现 |
| 2 | 重试，仍并发两个 | ❌ 同对错误（顺序对调） |
| 3 | 单个 query_cube | ✅ 成功返回数据 |
| 4 | 又并发两个 run_sql | ❌ 同对错误 |

ADR 0021 遗留观察「serve mcp 子进程并发调用偶发死亡，是否串行化待压测定案」——本会话
即定案证据：**不是偶发，是同一实例上并发调用的确定性失败**（此前 09-28 会话模型恰好
逐个调用，未触发）。

## 诊断

依赖链：`WrenInstanceRegistry#call` → `McpSyncClientWrapper#callTool` → MCP Java SDK
0.17.2 `McpSyncClient` → `StdioClientTransport#sendMessage`。反汇编该 SDK（0.17.2）：

```
sendMessage(JSONRPCMessage message) {
    EmitResult result = outboundSink.tryEmitNext(message);   // Reactor Sinks.Many
    if (!result.isSuccess()) {
        return Mono.error(new RuntimeException("Failed to enqueue message"));
    }
}
```

Reactor `Sinks.Many#tryEmitNext` 的契约是**调用方保证串行**（并发调用返回
`FAIL_NON_SERIALIZED`）。故障链：

1. harness 对同一 assistant 消息的两个 tool_use 并行执行，两个线程并发
   `sendMessage` → 并发 `tryEmitNext`；
2. 一方返回 `FAIL_NON_SERIALIZED` → 工具侧看到 `Failed to enqueue message`；
3. 该失败走 `WrenInstanceRegistry` 的传输失败路径 → `invalidate` → **close 整个实例
   （杀子进程）** → 兄弟 in-flight 请求立刻变 `MCP session with server terminated`。

两个错误是同一根因的连锁：**MCP SDK 的 stdio client 非线程安全**。`McpSyncClient`
仅为 async client 的阻塞门面，SDK 未提供任何并发保护。

## 决策

1. **业务层 per-group 调用串行化**（`WrenInstanceRegistry#call` 经 `callGates`
   per-group monitor 进入临界区，原方法体移至 `doCall`）：同一知识库的 wren 调用
   串行执行，spawn 也归入临界区（并发的第二个调用等待 spawn 完成后复用实例）；
   不同组各持独立门、保持并行。锁序单向（callGate → spawnLock），无死锁。
2. **不改 SDK / harness**：框架层修复（serialize per client）影响所有 MCP 挂载且
   属 monorepo agentscope-core 职责，另案处理；实例池本就是平台侧组件，串行化是
   其职责内的自愈防线。
3. `callGates` 条目不清理：每个知识库一个 monitor 对象，相对其守卫的子进程可忽略。

## 后果

- 正面：并发 tool_use 不再杀死实例（此前每轮并发都损失两次工具往返 + 一次实例
  重建）；跨组并发不受影响；实现局部（一个类），无新依赖。
- 负面/权衡：同组吞吐退化为串行——单实例单子进程本来就不具备并发能力（SDK 限制），
  排队是诚实的行为；LLM 一轮至多并发 2-3 个 wren 调用且查询秒级，等待代价小。
- 明确不做：SDK/harness 修复（另案）；调用超时内未完成时的队列上限（等待方最终由
  `props.timeout() + CALL_SLACK` 的 block 超时兜底）；`sweepIdle`/发布期 `invalidate`
  与 in-flight 调用的微小竞态（既有行为：idle 1800s 保守值 + 发布为用户显式操作）。
- 遗留观察：若未来 SDK 修复线程安全（`Sinks.serialize()` 包装），串行化可保留作为
  语义保证（同组顺序性）或移除换吞吐——届时再评估。

## 关联

- ADR 0021（遗留观察定案来源）、ADR 0020（实例池 M3）
- MCP Java SDK 0.17.2：`StdioClientTransport#sendMessage`（反汇编实证）、
  Reactor `Sinks#tryEmitNext` 契约（FAIL_NON_SERIALIZED）
- 诊断记录：`logs/LLM.log`（2026-09-29 会话，四轮对照）
- 守卫：`WrenInstanceRegistryTest`（同组串行不重叠 / 跨组仍并行）
