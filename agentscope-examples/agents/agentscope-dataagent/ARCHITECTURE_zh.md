# agentscope-dataagent 架构与流程详解

> 本文档面向开发者，梳理"用户在前端提一个自然语言问题 → 系统一步步执行 → 返回答案"的完整链路，以及所有为这个主流程服务的附属机制。

---

## 目录

1. [分层架构总览](#1-分层架构总览)
2. [启动装配流程](#2-启动装配流程)
3. [主流程：一次提问的完整链路](#3-主流程一次提问的完整链路)
4. [ReAct 循环核心机制](#4-react-循环核心机制)
5. [附属流程](#5-附属流程)
   - [5.1 会话管理](#51-会话管理)
   - [5.2 记忆管理](#52-记忆管理)
   - [5.3 数据源配置管理](#53-数据源配置管理)
   - [5.4 工具事件实时推送](#54-工具事件实时推送)
   - [5.5 Docker 沙箱管理](#55-docker-沙箱管理)
   - [5.6 用户认证与权限](#56-用户认证与权限)
   - [5.7 审计与用量统计](#57-审计与用量统计)
6. [Agent 提示词位置与编辑](#6-agent-提示词位置与编辑)
7. [本地启动指南](#7-本地启动指南)
8. [关键类与文件索引](#8-关键类与文件索引)

---

## 1. 分层架构总览

```
┌─────────────────────────────────────────────────────────────────────┐
│  Web 业务层（agentscope-dataagent 模块）                             │
│  ChatController / JWT认证 / ACL权限 / 审计 / 用量 / ToolEventBus     │
│  DataAgentToolkit（list_data_sources / describe_table / run_sql_preview）│
├─────────────────────────────────────────────────────────────────────┤
│  Harness 网关层（agentscope-harness 模块）                           │
│  ChatUiChannel → ChannelRouter → HarnessGateway → HarnessAgent      │
│  （会话路由 / 并发门 / 沙箱生命周期包装 / 子agent管理）                 │
├─────────────────────────────────────────────────────────────────────┤
│  Agent 核心层（agentscope-core 模块）                                │
│  ReActAgent（reasoning ↔ acting 循环）+ Toolkit + Model              │
│  （模型调用 / 工具schema下发 / 工具反射执行 / 上下文累积 / 记忆持久化）  │
├─────────────────────────────────────────────────────────────────────┤
│  基础设施层                                                          │
│  JDBC（查MySQL）/ Docker沙箱（跑代码画图）/ H2（平台元数据）           │
└─────────────────────────────────────────────────────────────────────┘
```

**设计哲学**：dataagent 模块只写"业务层"（Web API + 数据工具），智能体能力（ReAct循环、沙箱、会话路由）全部复用 harness/core 框架。

---

## 2. 启动装配流程

### 2.1 入口：DataAgentConfig.builderBootstrap()

文件：`web/config/DataAgentConfig.java`

Spring 启动时创建 `DataAgentBootstrap` Bean，装配顺序：

```
1. resolveCwd() → 解析工作目录
2. ensureAgentscopeConfig() → 如果 ~/.agentscope/dataagent/agentscope.json 不存在则自动生成
3. DataAgentBootstrap.builder().cwd(cwd)
4. builder.model(model) → 注入 DashScopeChatModel（qwen-max）
5. builder.configureAllAgents(b -> {
       b.middleware(new ToolNotificationMiddleware(toolEventBus))  // 工具事件推送
       b.stateStore(stateStore)                                     // 会话记忆持久化
       b.filesystem(new DockerFilesystemSpec()                      // Docker沙箱
           .client(sandboxClient)
           .isolationScope(IsolationScope.USER))                    // per-user隔离
   })
6. builder.build() → 三阶段构建（见下）
7. bootstrap.gateway().setUserSandboxRegistry(userSandboxRegistry)  // 沙箱注册表
8. ChatUiChannel.create(chatuiCfg) → 创建聊天通道
9. bootstrap.start(webChannel) → 启动通道
```

### 2.2 DataAgentBootstrap.build() 三阶段

文件：`runtime/DataAgentBootstrap.java`（L501-L647）

#### Phase 1：共享会话基础设施

```java
// 从 ~/.agentscope/dataagent/agentscope.json 读 agent 定义
AgentscopeConfig fileConfig = loadConfigFile(DEFAULT_CONFIG_PATH);

// 提取子agent条目（data-explorer、report-writer等）
List<SubagentEntry> entries = mainEntryBuilder.buildSubagentEntries(mainWorkspace);

// 建工作区管理器
WorkspaceManager wsManager = new WorkspaceManager(mainWorkspace);
DefaultAgentManager dam = new DefaultAgentManager(entries, wsManager);

// 建会话存储（元数据落 sessions.json）
Path storeFile = mainWorkspace.resolve("sessions.json");
SessionStore sessionStore = new SessionStore(storeFile);
sessionStore.load();

// 建会话管理器 + 网关
SessionAgentManager sam = new SessionAgentManager(dam, amCfg, new SubagentRunRegistry(), sessionStore);
ChannelManager channelMgr = new ChannelManager();
HarnessGateway gateway = HarnessGateway.create(sam, channelMgr);

// 建共享工具
TaskRepository taskRepo = new WorkspaceTaskRepository(wsManager, main);
SessionsTool sessionsTool = new SessionsTool(sam, taskRepo, null, 0);  // 子agent派生
OutboundTool outboundTool = new OutboundTool(channelMgr);              // 主动推送
```

#### Phase 2：逐个构建 HarnessAgent

```java
for (String id : ids) {
    HarnessAgent.Builder b = HarnessAgent.builder();
    applyFileEntry(cwd, id, entry, b);       // 从 agentscope.json 读配置
    b.model(model);                           // 注入模型
    b.externalSubagentTool(sessionsTool);     // 注入共享子agent工具

    Toolkit agentToolkit = new Toolkit();
    agentToolkit.registerTool(outboundTool);  // 注入主动推送工具
    b.toolkit(agentToolkit);

    // 应用 customizer（DataAgentConfig 里挂的 ToolNotificationMiddleware 等）
    for (Consumer<HarnessAgent.Builder> gc : globalConfigurators) {
        gc.accept(b);
    }
    built.put(id, b.build());
}
```

#### Phase 3：注册路由

```java
for (Map.Entry<String, HarnessAgent> e : built.entrySet()) {
    gateway.registerAgent(e.getKey(), e.getValue());
}
gateway.bindMainAgent(built.get(main));  // 指定默认agent
```

### 2.3 自动生成的配置文件

**位置**：`~/.agentscope/dataagent/agentscope.json`

**内容**（首次启动自动生成）：
```json
{
  "main": "data-agent",
  "agents": {
    "data-agent": {
      "name": "Data Agent",
      "description": "Tenant-isolated data-analysis assistant...",
      "maxIters": 20
    }
  },
  "channels": {
    "chatui": {
      "defaultAgentId": "data-agent",
      "dmScope": "MAIN"
    }
  }
}
```

**工作区目录**：`~/.agentscope/dataagent/workspace/`
```
workspace/
├── AGENTS.md          ← agent的系统提示词（可编辑）
├── tools.json         ← 工具白名单
├── skills/            ← 技能目录
│   └── example-skill/SKILL.md
├── subagents/         ← 子agent定义
│   └── README.md
├── memory/            ← 长期记忆（运行时管理）
│   └── .gitkeep
└── sessions.json      ← 会话元数据（运行时生成）
```

---

## 3. 主流程：一次提问的完整链路

**示例问题**：用户 bob 在 Web UI 问"昨日活跃用户数是多少？"

### 步骤总览

```
浏览器 POST /api/agents/data-agent/chat/stream
    ↓
[1] ChatController.stream() — JWT认证 + ACL鉴权 + 会话key规范化
    ↓
[2] ChatController.executeChat() — 消息整形 + 构建InboundMessage
    ↓
[3] ChatUiChannel.dispatch() — 通道路由（算出gateKey）
    ↓
[4] HarnessGateway.run() — 选agent + 并发门 + 构建RuntimeContext
    ↓
[5] HarnessAgent.call() — 沙箱acquire + 委托ReActAgent
    ↓
[6] ReActAgent.call() — ReAct循环（reasoning ↔ acting）
    ↓
[7] Toolkit.call() — 反射执行@Tool方法（JDBC查库/沙箱跑代码）
    ↓
[8] ToolNotificationMiddleware — 工具事件推送到ToolEventBus
    ↓
[9] ChatController — SSE帧合并（tool_call + tool_result + token + done）
    ↓
浏览器渲染
```

### 步骤详解

#### 步骤 1：HTTP 入口与前置检查

**文件**：`web/api/ChatController.java`（L156-L240）

```java
@PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<String>> stream(
        @PathVariable String agentId, @RequestBody ChatRequest req, Authentication auth) {

    // 1.1 JWT解用户
    String userId = (String) auth.getPrincipal();  // "bob"

    // 1.2 ACL鉴权（检查bob对data-agent的RUN权限）
    AgentDefinition def = guard.require(userId, agentId, Tier.RUN);
    // 无权 → 抛403

    // 1.3 会话key规范化（ChatGPT式多会话支持）
    String conversationId = normalizedConversationId(req.sessionKey());
    if (conversationId == null) {
        conversationId = UUID.randomUUID().toString();  // 首次对话mint一个
    }

    // 1.4 审计事件（首次进入记一条RUN_SESSION）
    recordRunSession(def, agentId, userId, resolvedConversationId);

    // 1.5 斜杠命令拦截（/new、/reset、/identity、/dock_<channel>）
    CommandResult cmd = handleSlashCommand(userId, agentId, req.message(), conversationId);
    if (cmd != null) {
        // 短路返回合成回复，不进agent
        return Flux.just(sse("token", ...), sse("done", ...));
    }
    // "昨日活跃用户数是多少？" 不是斜杠命令 → cmd = null → 继续
}
```

**数据快照**：
- `userId = "bob"`
- `agentId = "data-agent"`
- `message = "昨日活跃用户数是多少？"`
- `conversationId = "a7c3f1e8-..."（UUID）`

#### 步骤 2：订阅工具事件流 + 执行聊天

**文件**：`web/api/ChatController.java`（L185-L240）

```java
// 2.1 计算网关路由键（用于订阅工具事件）
String gateKey = resolveGateKey(userId, agentId, resolvedConversationId);
// gateKey 形如：chatui::a7c3f1e8-...::data-agent::bob

// 2.2 查找已注册的sessionKey（首次可能为null）
String existingSessionKey = findSessionKeyByGate(userId, gateKey);

// 2.3 订阅工具事件总线
Sinks.One<Boolean> done = Sinks.one();
Flux<ServerSentEvent<String>> toolEvents =
    existingSessionKey != null
        ? toolEventBus.subscribe(existingSessionKey)
              .takeUntilOther(done.asMono().timeout(Duration.ofMinutes(10)))
              .map(this::toToolFrame)
        : Flux.empty();

// 2.4 执行聊天（核心派发）
Mono<Flux<ServerSentEvent<String>>> agentCall =
    executeChat(userId, agentId, req.message(), resolvedConversationId)
        .map(reply -> {
            String text = reply.getTextContent();
            done.tryEmitValue(true);  // 关闭工具事件订阅
            return Flux.just(
                sse("token", Map.of("type", "token", "data", text)),
                sse("done",  Map.of("type", "done", "sessionKey", resolvedConversationId))
            );
        });

// 2.5 合并两条流（工具事件 + 回复）
return Flux.merge(toolEvents, Flux.from(agentCall.flatMapMany(f -> f)));
```

#### 步骤 3：executeChat — 消息整形与派发

**文件**：`web/api/ChatController.java`（L541-L570）

```java
private Mono<Msg> executeChat(String userId, String agentId, String message, String conversationId) {
    long startMs = System.currentTimeMillis();

    // 3.1 消息整形（应用UserBinding偏好）
    List<Msg> msgs = shapeInboundMessages(userBindings.list(userId), "chatui", message);
    // 如果bob设了language="中文"，msgs = [
    //   Msg(SYSTEM, "Reply to the user in 中文."),
    //   Msg(USER,   "昨日活跃用户数是多少？")
    // ]

    // 3.2 构建InboundMessage
    String gatewayAgentId = catalogService.resolveGatewayAgentId(userId, agentId);
    InboundMessage inbound = InboundMessage.builder("chatui", Peer.direct(userId), msgs)
        .preferredAgentId(gatewayAgentId)    // "data-agent"
        .accountId(conversationId)            // "a7c3f1e8-..."
        .build();

    // 3.3 派发
    Mono<Msg> call = chatUiChannel.dispatch(inbound);

    // 3.4 记录用量
    return call.doOnSuccess(reply ->
        usageStore.record(userId, agentId, System.currentTimeMillis() - startMs));
}
```

#### 步骤 4：ChatUiChannel — 通道路由

**文件**：`agentscope-harness/.../ChatUiChannel.java`（L148-L159）

```java
@Override
public Mono<Msg> dispatch(InboundMessage message) {
    // 4.1 ChannelRouter解析路由
    RouteResult route = router.resolveRoute(config, message);
    // 根据 (channelId="chatui", peer="bob", accountId="a7c3f1e8-...", preferredAgentId="data-agent")
    // 算出：
    //   route.context() = MsgContext(userId="bob", extra={agentId="data-agent"}, threadId="a7c3f1e8-...")
    //   route.context().canonicalKey() = gateKey（会话隔离的核心）
    //   route.outboundAddress() = OutboundAddress(...)

    // 4.2 委托网关
    return resolveGateway().run(
        route.context(),
        message.messages(),
        route.outboundAddress(),
        message.runtimeContext(),
        message);
}
```

**关键机制**：`gateKey` 由 `(userId, agentId, conversationId)` 三元组决定——这就是"每个用户每个会话互相看不见"的实现。

#### 步骤 5：HarnessGateway — 选agent + 并发门

**文件**：`agentscope-harness/.../HarnessGateway.java`（L252-L277）

```java
@Override
public Mono<Msg> run(MsgContext context, List<Msg> messages, OutboundAddress outboundAddress,
                     RuntimeContext callerContext, InboundMessage inboundMessage) {

    // 5.1 解析目标agent
    String requestedAgentId = context.extra().get("agentId");  // "data-agent"
    HarnessAgent ha = resolveAgent(requestedAgentId);
    // 从注册表找到data-agent的HarnessAgent实例

    // 5.2 会话ID解析（首次创建，后续沿用）
    String sessionId = resolveSessionId(gateKey);

    // 5.3 构建RuntimeContext
    RuntimeContext runtimeContext = buildRuntimeContext(
        context, outboundAddress, callerContext, inboundMessage, sessionId, gateKey);
    // 包含：sessionId、userId、gateKey、outboundAddress、msgContext

    // 5.4 并发门（SessionTurnGate）
    return withGatedTurn(gateKey, () -> ha.call(messages, runtimeContext));
    // 同一会话同一时刻只允许一个turn执行
    // 忙时抛TurnBusyException → 直接Mono.empty()丢弃
}
```

#### 步骤 6：HarnessAgent — 沙箱acquire + 委托ReActAgent

**文件**：`agentscope-harness/.../HarnessAgent.java`（L1048-L1052, L938-L964）

```java
public Mono<Msg> call(List<Msg> msgs, RuntimeContext ctx) {
    // 6.1 补默认值（sessionId、SandboxContext、WorkspaceManager等）
    RuntimeContext effective = ensureSessionDefaults(ctx);

    // 6.2 包装执行（Mono.using确保acquire/release成对）
    return wrappedCall(msgs, effective, () -> delegate.call(msgs, effective));
}

private Mono<Msg> wrappedCall(List<Msg> msgs, RuntimeContext effective, Supplier<Mono<Msg>> inner) {
    return Mono.using(
        () -> {
            // acquire：拉取bob的专属Docker容器
            if (sandboxLifecycleMw != null) {
                sandboxLifecycleMw.acquireForCall(effective);
            }
            return effective;
        },
        eff -> inner.get(),  // 执行ReActAgent.call
        eff -> {
            // release：持久化沙箱状态、释放容器
            if (sandboxLifecycleMw != null) {
                sandboxLifecycleMw.releaseForCall(eff);
            }
        });
}
```

#### 步骤 7：ReActAgent — ReAct循环（核心）

**文件**：`agentscope-core/.../ReActAgent.java`

详见[第4章](#4-react-循环核心机制)。

#### 步骤 8：工具执行 — JDBC查库

**文件**：`tools/data/DataAgentToolkit.java`

模型调用 `run_sql_preview` 时，框架反射执行：

```java
@Tool(description = "Execute a read-only SQL query and preview results")
public String runSqlPreview(String dataSourceId, String sql, Integer limit) {
    // 1. 校验SQL（SELECT/WITH开头、无分号、单语句）
    // 2. 从DataSourceRegistry取数据源配置
    // 3. JDBC连接（setReadOnly(true), setMaxRows(limit)）
    // 4. 执行查询，返回markdown表格
    return toMarkdownTable(resultSet);
}
```

#### 步骤 9：工具事件推送

**文件**：`web/toolbus/ToolNotificationMiddleware.java`

```java
@Override
public Flux<AgentEvent> onActing(ActingInput input, ...) {
    // 拦截工具调用
    for (ToolUseBlock toolCall : input.toolCalls()) {
        // 发布TOOL_CALL事件
        toolEventBus.publish(new ToolEvent(sessionKey, "TOOL_CALL", toolCall.getName(), toolCall.getInput()));
    }
    // 执行工具...
    // 发布TOOL_RESULT事件
    toolEventBus.publish(new ToolEvent(sessionKey, "TOOL_RESULT", toolName, result));
}
```

ChatController订阅的`toolEventBus`收到事件 → 转成SSE帧 → 推给浏览器。

#### 步骤 10：SSE帧返回浏览器

浏览器按顺序收到：

```
event: tool_call
data: {"type":"tool_call","toolName":"list_data_sources","toolInput":"{}"}

event: tool_result
data: {"type":"tool_result","toolName":"list_data_sources","toolResult":"[{\"id\":\"supersonic\"}]"}

event: tool_call
data: {"type":"tool_call","toolName":"describe_table","toolInput":"{\"tableName\":\"s2_user\"}"}

event: tool_result
data: {"type":"tool_result","toolName":"describe_table","toolResult":"| id | name | last_login_time |..."}

event: tool_call
data: {"type":"tool_call","toolName":"run_sql_preview","toolInput":"{\"sql\":\"SELECT COUNT...\"}"}

event: tool_result
data: {"type":"tool_result","toolName":"run_sql_preview","toolResult":"| active_users |\n|---|\n| 1234 |"}

event: token
data: {"type":"token","data":"昨日（2026年9月8日）supersonic系统的活跃用户数为1234人。"}

event: done
data: {"type":"done","sessionKey":"a7c3f1e8-..."}
```

---

## 4. ReAct 循环核心机制

**文件**：`agentscope-core/.../ReActAgent.java`

### 4.1 循环入口

```java
final class CallExecution {
    private Mono<Msg> coreAgent() {
        return executeIteration(0);  // 从第0轮开始
    }

    private Mono<Msg> executeIteration(int iter) {
        return reasoning(iter, false);  // 进入推理阶段
    }
}
```

### 4.2 Reasoning 阶段（推理）

```java
private Mono<Msg> reasoning(int iter, boolean ignoreMaxIters) {
    // 1. 检查迭代上限
    if (!ignoreMaxIters && iter >= maxIters) {
        return summarizing();  // 强制总结收尾
    }

    // 2. 触发PreReasoning hook
    hookDispatcher.firePreReasoning(state.contextMutable(), systemMsg, model.getModelName())

    // 3. 组装请求
    .flatMap(event -> {
        List<Msg> modelInput = prependSystemMsg(event.getInputMessages(), event.getSystemMessage());
        List<ToolSchema> tools = toolkit.getToolSchemas(state.getToolContext().getActivatedGroups());
        // tools = [list_data_sources, describe_table, run_sql_preview, render_chart, agent_spawn, ...]

        // 4. 流式调模型
        Flux<AgentEvent> stream = reasoningStream(context, modelInput, tools, options);
        // → model.stream(messages, tools, options)
        // → DashScope qwen-max 流式接口

        // 5. 累积返回内容
        return stream.doOnNext(this::publishEvent)
            .then(Mono.defer(() -> {
                Msg finalMsg = context.buildFinalMessage();
                // finalMsg包含：TextBlock（模型说的话）+ ToolUseBlock（要用的工具）
                return Mono.justOrEmpty(finalMsg);
            }));
    })

    // 6. PostReasoning pipeline
    .flatMap(msg -> runPostReasoningPipeline(msg, iter));
}
```

### 4.3 PostReasoning — 判断是否继续

```java
private Mono<Msg> runPostReasoningPipeline(Msg msg, int iter) {
    return hookDispatcher.firePostReasoning(msg, model.getModelName())
        .flatMap(event -> {
            Msg eventMsg = event.getReasoningMessage();

            // 1. HITL停止请求
            if (event.isStopRequested()) {
                return Mono.just(eventMsg.withGenerateReason(REASONING_STOP_REQUESTED));
            }

            // 2. gotoReasoning请求（重新推理）
            if (event.isGotoReasoningRequested()) {
                return reasoning(iter + 1, true);
            }

            // 3. 检查完成条件
            if (isFinished(eventMsg)) {
                return Mono.justOrEmpty(eventMsg);  // 结束循环
            }

            // 4. 空回复兜底
            if (!hasToolCalls(eventMsg)) {
                state.contextMutable().add(buildEmptyResponseReminder());
            }

            // 5. 继续到acting阶段
            return checkInterrupted().then(acting(iter));
        });
}

private boolean isFinished(Msg msg) {
    if (msg == null) return true;
    if (hasToolCalls(msg)) return false;  // 有工具调用 → 继续
    return hasVisibleContent(msg);         // 有文本内容 → 结束
}
```

### 4.4 Acting 阶段（行动）

```java
private Mono<Msg> acting(int iter) {
    // 1. 提取待执行工具
    List<ToolUseBlock> pendingToolCalls = extractPendingToolCalls();

    if (pendingToolCalls.isEmpty()) {
        return executeIteration(iter + 1);  // 没有工具 → 下一轮推理
    }

    // 2. 触发PreActing hook
    return hookDispatcher.firePreActing(pendingToolCalls, toolkit)
        .flatMap(toolCalls -> {
            // 3. 执行工具（通过中间件链）
            Flux<AgentEvent> stream = MiddlewareChain.build(
                middlewares, ReActAgent.this, rc,
                MiddlewareBase::onActing,
                ai -> actingStream(ai.toolCalls(), replyId, resultHolder))
                .apply(new ActingInput(toolCalls));

            return stream.then(Mono.defer(() -> Mono.just(resultHolder.get())));
        })
        .flatMap(results -> {
            // 4. 处理结果
            List<Map.Entry<ToolUseBlock, ToolResultBlock>> successPairs = ...;

            if (successPairs.isEmpty()) {
                return executeIteration(iter + 1);
            }

            // 5. 触发PostActing hook
            return Flux.fromIterable(successPairs)
                .concatMap(this::notifyPostActingHook)
                .last()
                .flatMap(event -> {
                    // 6. 结果追加到上下文
                    state.contextMutable().addAll(event.getToolResults());
                    // 7. 回到下一轮推理
                    return executeIteration(iter + 1);
                });
        });
}
```

### 4.5 工具执行 — actingStream

```java
private Flux<AgentEvent> actingStream(List<ToolUseBlock> toolCalls, String replyId, ...) {
    return Flux.fromIterable(toolCalls)
        .concatMap(toolCall -> {
            // 1. 权限检查（PermissionEngine）
            // 2. 反射调用@Tool方法
            ToolResultBlock result = toolkit.callTool(toolCall.getName(), toolCall.getInput());
            // 3. 发布AgentEvent
            return Flux.just(new ToolResultEvent(toolCall, result));
        });
}
```

### 4.6 循环终止条件

| 条件 | 行为 |
|------|------|
| 模型返回纯文本（无ToolUseBlock） | `isFinished=true` → 返回最终回复 |
| 达到`maxIters`（默认20） | 走`summarizing()`强制总结 |
| 中间件请求停止 | 返回带`GenerateReason`的消息 |
| 上下文溢出 | `compactionHook`压缩/恢复 |

---

## 5. 附属流程

### 5.1 会话管理

#### 5.1.1 会话隔离机制

**核心**：`gateKey` = `(channelId, peerId, accountId, agentId)` 的 canonicalKey

```
用户bob + agent=data-agent + conversationId=abc123
  → gateKey = "chatui::abc123::data-agent::bob"
  → 独立会话（看不到alice的、也看不到bob其他conversation的）
```

**文件**：
- `ChatController.resolveGateKey()` — 计算gateKey
- `ChatUiChannel.dispatch()` → `ChannelRouter.resolveRoute()` — 路由解析
- `HarnessGateway.resolveSessionId(gateKey)` — session映射

#### 5.1.2 并发控制（SessionTurnGate）

**文件**：`agentscope-harness/.../HarnessGateway.java`（L694-L720）

```java
private Mono<Msg> withGatedTurn(String gateKey, Supplier<Mono<Msg>> turn) {
    return Mono.defer(() -> {
        TurnLease lease = sessionTurnGate.acquire(gateKey);
        // 同一gateKey同一时刻只能一个turn执行
        // 忙时抛TurnBusyException → Mono.empty()丢弃
        return turn.get();
    })
    .doFinally(sig -> lease.close())  // 释放锁
    .subscribeOn(Schedulers.boundedElastic());
}
```

**作用**：防止同一会话的并发消息把状态写乱。

#### 5.1.3 会话元数据持久化

**文件**：`~/.agentscope/dataagent/workspace/sessions.json`

```json
{
  "sessions": [
    {
      "sessionKey": "sess_abc123",
      "gateKey": "chatui::abc123::data-agent::bob",
      "userId": "bob",
      "agentId": "data-agent",
      "kind": "MAIN",
      "createdAt": "2026-09-09T10:00:00Z"
    }
  ]
}
```

**管理类**：`SessionAgentManager`、`SessionStore`

#### 5.1.4 斜杠命令

**文件**：`ChatController.handleSlashCommand()`（L406-L485）

| 命令 | 作用 |
|------|------|
| `/new` | 创建新会话（mint新UUID） |
| `/reset` | 清空当前会话历史 |
| `/identity` | 查看身份链接 |
| `/dock_<channel> <id>` | 绑定外部通道身份（如Slack） |

### 5.2 记忆管理

#### 5.2.1 上下文累积（短期记忆）

**机制**：ReActAgent每轮把消息追加到`state.contextMutable()`

```
Round 1: [system, user("昨日活跃用户数")]
Round 2: [..., assistant("调用list_data_sources"), tool("[{id:supersonic}]")]
Round 3: [..., assistant("调用describe_table"), tool("| id | name | ...")]
Round 4: [..., assistant("调用run_sql_preview"), tool("| 1234 |")]
Round 5: [..., assistant("昨日活跃用户数为1234人")]  ← 最终回复
```

**文件**：`ReActAgent.CallExecution.state.contextMutable()`

#### 5.2.2 会话记忆持久化（跨轮记忆）

**机制**：每个turn结束时，ReActAgent自动把完整上下文写入`AgentStateStore`

**文件**：
- `DataAgentConfig.java`（L214-L222）— 选择存储后端
- `agentscope-core/.../AgentStateStore` — 存储接口

```java
AgentStateStore stateStore = sessionOpt.orElseGet(InMemoryAgentStateStore::new);
// 默认：InMemoryAgentStateStore（内存，重启丢失）
// 生产：可替换为Redis等分布式存储
```

**效果**：下次同一会话提问时，模型能从上下文知道之前查过什么，不用重复`list_data_sources`。

#### 5.2.3 长期记忆（memory/目录）

**位置**：`~/.agentscope/dataagent/workspace/memory/`

**机制**：运行时管理，通常不手动编辑。agent可通过`memory_search`、`memory_get`工具访问。

### 5.3 数据源配置管理

#### 5.3.1 配置位置

**文件**：`src/main/resources/application.yml`

```yaml
dataagent:
  data:
    sources:
      - id: supersonic
        label: Supersonic (local MySQL)
        description: Local supersonic database on 127.0.0.1:3306
        kind: mysql
        jdbcUrl: ${DATAAGENT_DS_SUPERSONIC_URL:jdbc:mysql://127.0.0.1:3306/supersonic?...}
        user: ${DATAAGENT_DS_SUPERSONIC_USER:root}
        password: ${DATAAGENT_DS_SUPERSONIC_PASSWORD:1234}
        tags: [mysql, local]
```

#### 5.3.2 配置绑定

**文件**：`tools/data/DataSourcesProperties.java`

```java
@ConfigurationProperties(prefix = "dataagent.data")
public class DataSourcesProperties {
    private List<Entry> sources = new ArrayList<>();

    public static class Entry {
        private String id;
        private String label;
        private String description;
        private String kind;
        private String jdbcUrl;
        private String user;
        private String password;
        private List<String> tags;
    }

    public List<DataSource> toDataSources() {
        // 转成DataSource record列表
    }
}
```

#### 5.3.3 注册表初始化

**文件**：`tools/data/DataToolkitConfig.java`

```java
@Configuration
@EnableConfigurationProperties(DataSourcesProperties.class)
public class DataToolkitConfig {

    @Bean
    @ConditionalOnMissingBean(DataSourceRegistry.class)
    public DataSourceRegistry inMemoryDataSourceRegistry(DataSourcesProperties properties) {
        List<DataSource> seed = properties.toDataSources();
        if (seed.isEmpty()) {
            return new InMemoryDataSourceRegistry(List.of());
        }
        log.info("Seeding {} source(s) from dataagent.data.sources: {}",
                 seed.size(), seed.stream().map(DataSource::id).toList());
        return new InMemoryDataSourceRegistry(seed);
    }
}
```

#### 5.3.4 工具实现

**文件**：`tools/data/DataAgentToolkit.java`

```java
@Component
public class DataAgentToolkit {

    private final DataSourceRegistry registry;

    @Tool(description = "List available data sources")
    public String listDataSources() {
        return registry.getAll().stream()
            .map(ds -> ds.id() + ": " + ds.label())
            .collect(Collectors.joining("\n"));
    }

    @Tool(description = "Describe table schema and sample rows")
    public String describeTable(String dataSourceId, String tableName) {
        // 1. 校验表名（正则^[`\w$.]+$）
        // 2. JDBC连接
        // 3. SELECT * FROM table LIMIT 5
        // 4. 返回列schema + 样例行markdown表
    }

    @Tool(description = "Execute read-only SQL query")
    public String runSqlPreview(String dataSourceId, String sql, Integer limit) {
        // 1. 校验SQL（SELECT/WITH开头、无分号、单语句）
        // 2. JDBC连接（setReadOnly(true), setMaxRows(limit)）
        // 3. 执行查询
        // 4. 返回markdown表格
    }
}
```

#### 5.3.5 添加新数据源

只需在`application.yml`的`dataagent.data.sources`追加条目：

```yaml
dataagent:
  data:
    sources:
      - id: supersonic
        # ... 已有配置
      - id: another_db
        label: Another Database
        kind: postgresql
        jdbcUrl: jdbc:postgresql://localhost:5432/another
        user: postgres
        password: secret
```

重启应用即可生效。

### 5.4 工具事件实时推送

#### 5.4.1 事件总线

**文件**：`web/toolbus/ToolEventBus.java`

```java
@Component
public class ToolEventBus {
    private final Map<String, Sinks.Many<ToolEvent>> sinks = new ConcurrentHashMap<>();

    public void publish(ToolEvent event) {
        Sinks.Many<ToolEvent> sink = sinks.get(event.sessionKey());
        if (sink != null) {
            sink.tryEmitNext(event);
        }
    }

    public Flux<ToolEvent> subscribe(String sessionKey) {
        return sinks.computeIfAbsent(sessionKey, k -> Sinks.many().multicast().onBackpressureBuffer())
                    .asFlux();
    }
}
```

#### 5.4.2 中间件拦截

**文件**：`web/toolbus/ToolNotificationMiddleware.java`

```java
public class ToolNotificationMiddleware implements MiddlewareBase {
    private final ToolEventBus bus;

    @Override
    public Flux<AgentEvent> onActing(ActingInput input, ...) {
        String sessionKey = rc.getSessionId();

        for (ToolUseBlock toolCall : input.toolCalls()) {
            // 发布TOOL_CALL事件
            bus.publish(new ToolEvent(sessionKey, "TOOL_CALL",
                toolCall.getName(), toolCall.getInput()));
        }

        // 执行工具...
        return next.apply(input)
            .doOnNext(event -> {
                if (event instanceof ToolResultEvent tre) {
                    // 发布TOOL_RESULT事件
                    bus.publish(new ToolEvent(sessionKey, "TOOL_RESULT",
                        tre.getToolName(), tre.getResult()));
                }
            });
    }
}
```

#### 5.4.3 SSE帧转换

**文件**：`ChatController.toToolFrame()`（L306-L321）

```java
private ServerSentEvent<String> toToolFrame(ToolEventBus.ToolEvent e) {
    Map<String, Object> data = new LinkedHashMap<>();
    boolean isResult = "TOOL_RESULT".equalsIgnoreCase(e.eventType());
    data.put("type", isResult ? "tool_result" : "tool_call");
    data.put("toolName", e.toolName());
    data.put(isResult ? "toolResult" : "toolInput", MAPPER.writeValueAsString(e.data()));
    return sse(isResult ? "tool_result" : "tool_call", data);
}
```

### 5.5 Docker 沙箱管理

#### 5.5.1 沙箱隔离粒度

**配置**：`IsolationScope.USER` — per-(userId, agentId) 隔离

**文件**：`DataAgentConfig.java`（L228-L231）

```java
b.filesystem(new DockerFilesystemSpec()
    .client(sandboxClient)
    .isolationScope(IsolationScope.USER));
```

#### 5.5.2 沙箱生命周期

**文件**：`agentscope-harness/.../SandboxLifecycleMiddleware.java`

```
acquireForCall(RuntimeContext ctx):
  1. 从ctx取SandboxContext
  2. SandboxManager.acquire() — 获取或创建Docker容器
     优先级：externalSandbox > persisted state > fresh create
  3. sandbox.start() — 初始化工作区（投影共享seed：AGENTS.md、skills/等）
  4. 绑定到ctx（供SandboxBackedFilesystem使用）

releaseForCall(RuntimeContext ctx):
  1. persistState() — 保存沙箱状态
  2. sandbox.release() — 停止/归还容器
  3. 清除ctx绑定
```

#### 5.5.3 共享seed投影

**位置**：`~/.agentscope/dataagent/workspace/`（宿主机）

**投影到**：每个Docker容器的工作区

**内容**：`AGENTS.md`、`skills/`、`subagents/`、`knowledge/`（只读）

**文件**：`web/workspace/UserSandboxRegistry.java`（L72）

```java
List.of("AGENTS.md", "skills", "subagents", "knowledge");
```

### 5.6 用户认证与权限

#### 5.6.1 JWT认证

**文件**：`web/security/JwtAuthFilter.java`

```
请求 → JwtAuthFilter → 解Bearer token → 验证签名/过期 → 设Authentication(principal=userId)
```

**登录端点**：`POST /api/auth/login`（body: `{username, password}`）→ 返回JWT

**Demo账号**：`bob/bob`、`alice/alice`（H2种子数据）

#### 5.6.2 ACL权限

**文件**：`web/share/AgentAccessGuard.java`

```java
public AgentDefinition require(String userId, String agentId, Tier tier) {
    // 检查用户对agent的权限
    // Tier: OWNER / RUN / READ
    // 无权 → 抛AccessDeniedException(403)
}
```

**权限来源**：
- Global agents（`agentscope.json`定义）：所有用户可RUN
- User agents（用户自建）：仅owner可RUN，可分享给其他用户

### 5.7 审计与用量统计

#### 5.7.1 审计事件

**文件**：`web/audit/AgentActivityStore.java`

```java
public void record(String ownerId, String agentId, String actor, Action action, String dedupeKey, String detail) {
    // 记录事件：RUN_SESSION、CREATE_AGENT、SHARE_AGENT等
}
```

**触发点**：`ChatController.recordRunSession()` — 首次进入会话时记一条

#### 5.7.2 用量统计

**文件**：`web/usage/UsageStore.java`

```java
public void record(String userId, String agentId, long durationMs) {
    // 记录每次对话耗时
}
```

**触发点**：`ChatController.executeChat()` — 每次对话结束时记录

---

## 6. Agent 提示词位置与编辑

### 6.1 系统提示词（System Prompt）

**位置**：`~/.agentscope/dataagent/workspace/AGENTS.md`

**内容示例**：
```markdown
# Data Agent

You are a Data Agent built with AgentScope. You help users explore, analyse, visualise and report on data.

## How this folder works

This folder *is* the agent. Anything you put here is picked up at runtime — there is
no separate config to keep in sync.

- **`AGENTS.md`** — this file. Edit the system prompt and behavioral rules in
  place; they are loaded on the next session.
- **`tools.json`** — declare which built-in tools the agent may call.
- **`skills/`** — each subfolder is a skill: a Markdown playbook the agent can
  invoke by name.
- **`subagents/`** — sub-agent definitions for delegated work.
- **`memory/`** — long-term memory store managed by the runtime.

## Authoring tips

- Keep this prompt focused on *what the agent does* and *how it should behave*.
- Push examples, schemas, and one-shot instructions into skills.
- When you change tools or skills, current sessions keep their old wiring until
  reset; new sessions pick up the change immediately.
```

**编辑方式**：
1. 直接编辑文件（下次新会话生效）
2. 通过Web UI的Agent Builder编辑（如果实现了）
3. 修改`application.yml`的`dataagent.agent.sys-prompt`（影响新生成的AGENTS.md）

### 6.2 配置来源优先级

```
1. ~/.agentscope/dataagent/agentscope.json 的 agents.<id>.sysPrompt
2. application.yml 的 dataagent.agent.sys-prompt
3. 默认值："You are a Data Agent built with AgentScope..."
```

**文件**：`DataAgentConfig.java`（L112-L115）

```java
@Value("${dataagent.agent.sys-prompt:You are a Data Agent built with AgentScope."
        + " You help users explore, analyse, visualise and report on data.}")
private String agentSysPrompt;
```

### 6.3 工具白名单

**位置**：`~/.agentscope/dataagent/workspace/tools.json`

```json
{
  "allow": ["read_file", "write_file", "edit_file", "list_files", "grep_files", "glob_files"],
  "deny": []
}
```

**作用**：限制agent可调用的工具（删除此文件则允许所有工具）

### 6.4 技能（Skills）

**位置**：`~/.agentscope/dataagent/workspace/skills/<skill-id>/SKILL.md`

**作用**：命名的playbook，agent可按名调用

**示例**：
```markdown
# Example Skill

A skill is a named playbook the agent can invoke by referring to this file.

## When to use

Describe the situations in which the agent should reach for this skill.

## Steps

1. State the inputs the skill needs.
2. Describe the work.
3. State the expected output format.
```

### 6.5 子Agent定义

**位置**：`~/.agentscope/dataagent/workspace/subagents/<subagent-id>.md`

**格式**：
```markdown
---
name: researcher
description: Investigates a question and returns a written summary.
tools: [read_file, grep_files, glob_files]
---

You are a research specialist. Stay focused on the task you receive; do not
edit files directly.
```

**触发**：主agent调用`agent_spawn`工具派生子agent

---

## 7. 本地启动指南

### 7.1 环境要求

| 组件 | 版本 | 说明 |
|------|------|------|
| Java | 21+ | Oracle OpenJDK 21.0.6 已验证 |
| Maven | 3.9+ | 3.9.9 已验证 |
| Docker Desktop | 最新 | 必须运行（沙箱依赖） |
| Node.js | 20+ | 前端构建用（可跳过，用预构建产物） |

### 7.2 配置API Key

**方式1**：环境变量（推荐）
```bash
set DASHSCOPE_API_KEY=sk-your-key-here
```

**方式2**：`application.yml`
```yaml
dataagent:
  dashscope:
    api-key: sk-your-key-here
    model-name: qwen-max
```

### 7.3 构建

```bash
cd D:\code\lyf_da_V1\agentscope-java

mvn -pl agentscope-examples/agents/agentscope-dataagent -am package \
    -DskipTests \
    -Dspotless.skip=true \
    -Dskip.installnodenpm=true \
    -Dskip.npm=true
```

**参数说明**：
- `-Dspotless.skip=true` — 跳过格式检查（Windows CRLF问题）
- `-Dskip.installnodenpm=true -Dskip.npm=true` — 跳过Node下载（用git内预构建前端）

**产物**：`agentscope-examples/agents/agentscope-dataagent/target/agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar`（~139MB）

### 7.4 启动Docker

```bash
# Windows
start "" "C:\Program Files\Docker\Docker\Docker Desktop.exe"

# 等待引擎就绪（约30秒）
docker ps
```

**拉取沙箱镜像**（首次）：
```bash
docker pull ubuntu:22.04
```

### 7.5 启动应用

#### 方式 A：命令行 java -jar（生产 / 快速验证）

```bash
cd D:\code\lyf_da_V1\agentscope-java\agentscope-examples\agents\agentscope-dataagent

java -jar target/agentscope-dataagent-2.0.3-SNAPSHOT-exec.jar
```

#### 方式 B：在 IDEA 里运行（开发 / 调试推荐）

主类：**`io.agentscope.dataagent.web.DataAgentApp`**（注意是 `DataAgentApp`，不是 `DataAgentApplication`）

1. 打开 `DataAgentApp.java`，右键 → Run 'DataAgentApp'（或点 main 方法左侧绿色三角）
2. 首次运行后，编辑 Run Configuration：
   - **Environment variables**：填 `DASHSCOPE_API_KEY=sk-your-key-here`（核心，模型认证必需）
   - **JRE / SDK**：确认选 JDK 21
   - **Working directory**（可选）：设为模块目录 `...\agentscope-dataagent`；因关键路径（配置、workspace、H2）都基于 `user.home`，此项影响不大
3. 运行前提：**Docker Desktop 已启动**（沙箱依赖）；若要查 supersonic 数据，本地 MySQL（127.0.0.1:3306）也要开着

> IDEA 运行直接走 classpath 里的 `src/main/resources/static/` 前端产物，**不会触发 Maven 的 frontend 插件**，因此不受 Node 下载失败影响，也无需 `-Dspotless.skip` 等构建参数。除 `DASHSCOPE_API_KEY` 外无需再配其它环境变量（数据源、JWT 等在 application.yml 里已有默认值）。

**启动日志关键行**（两种方式相同）：
```
Auto-generated DataAgent config at C:\Users\<you>\.agentscope\dataagent\agentscope.json
Seeding 1 source(s) from dataagent.data.sources: [supersonic]
Building DashScopeChatModel: model=qwen-max
DataAgentBootstrap initialized: cwd=..., chatui dmScope=MAIN
Started DataAgentApp in X.XXX seconds
```

#### 停止应用

| 运行方式 | 停止方法 |
|----------|----------|
| IDEA | 点红色 ■ 停止按钮 |
| 命令行前台 | 切到该终端按 `Ctrl+C`（优雅关闭：释放沙箱、关连接、注销通道） |
| 命令行后台 / 找不到窗口 | `netstat -ano \| findstr :8080` 查 PID → `taskkill /PID <pid> /F` |
| 图形界面 | `Ctrl+Shift+Esc` 任务管理器 → 找对应 `java.exe` → 结束任务 |

### 7.6 访问

**Web UI**：http://localhost:8080

**登录**：
- 用户名：`bob`
- 密码：`bob`

**测试对话**：
1. 点击"data-agent"
2. 输入："supersonic里有哪些表？"
3. 观察：工具调用动画 → 最终回复

### 7.7 API测试

```bash
# 登录获取JWT
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"bob","password":"bob"}'
# 返回：{"token":"eyJhbGciOi..."}

# 发送消息
curl -X POST http://localhost:8080/api/agents/data-agent/chat/send \
  -H "Authorization: Bearer eyJhbGciOi..." \
  -H "Content-Type: application/json" \
  -d '{"message":"昨日活跃用户数是多少？"}'
# 返回：{"reply":"昨日活跃用户数为...","sessionKey":"..."}
```

### 7.8 常见问题

| 问题 | 原因 | 解决 |
|------|------|------|
| 启动报"No model configured" | 未设DASHSCOPE_API_KEY | 设环境变量或application.yml |
| 对话报"Docker not available" | Docker Desktop未运行 | 启动Docker Desktop |
| 工具返回"not implemented" | 用了上游v1的stub | 确认已应用自研JDBC连接器补丁 |
| 前端404 | 未跳过npm构建 | 加`-Dskip.installnodenpm=true -Dskip.npm=true` |
| Spotless格式检查失败 | Windows CRLF | 加`-Dspotless.skip=true` |

---

## 8. 关键类与文件索引

### 8.1 Web层（dataagent模块）

| 类 | 文件 | 职责 |
|----|------|------|
| `ChatController` | `web/api/ChatController.java` | HTTP入口，/stream和/send端点 |
| `DataAgentConfig` | `web/config/DataAgentConfig.java` | Spring配置，装配Bootstrap |
| `AgentAccessGuard` | `web/share/AgentAccessGuard.java` | ACL权限检查 |
| `ToolEventBus` | `web/toolbus/ToolEventBus.java` | 工具事件发布/订阅 |
| `ToolNotificationMiddleware` | `web/toolbus/ToolNotificationMiddleware.java` | 拦截工具调用，推送事件 |
| `UsageStore` | `web/usage/UsageStore.java` | 用量统计 |
| `AgentActivityStore` | `web/audit/AgentActivityStore.java` | 审计事件 |
| `UserBindingStore` | `web/binding/UserBindingStore.java` | 用户偏好（语言等） |
| `IdentityLinkStore` | `web/identity/IdentityLinkStore.java` | 外部通道身份链接 |

### 8.2 工具层（dataagent模块）

| 类 | 文件 | 职责 |
|----|------|------|
| `DataAgentToolkit` | `tools/data/DataAgentToolkit.java` | 数据查询工具（list/describe/run_sql） |
| `DataSourcesProperties` | `tools/data/DataSourcesProperties.java` | 配置绑定（dataagent.data.sources） |
| `DataToolkitConfig` | `tools/data/DataToolkitConfig.java` | 注册表初始化 |
| `DataSourceRegistry` | `tools/data/DataSourceRegistry.java` | 数据源注册表接口 |
| `InMemoryDataSourceRegistry` | `tools/data/InMemoryDataSourceRegistry.java` | 内存实现 |
| `DataSource` | `tools/data/DataSource.java` | 数据源record |

### 8.3 运行时层（dataagent模块）

| 类 | 文件 | 职责 |
|----|------|------|
| `DataAgentBootstrap` | `runtime/DataAgentBootstrap.java` | 启动器，三阶段构建 |
| `SessionAgentManager` | `runtime/session/SessionAgentManager.java` | 会话管理 |
| `SessionStore` | `runtime/session/SessionStore.java` | 会话元数据持久化 |
| `SessionsTool` | `runtime/session/tool/SessionsTool.java` | 子agent派生工具 |
| `UserSandboxRegistry` | `web/workspace/UserSandboxRegistry.java` | per-user沙箱注册表 |
| `WorkspaceScaffolder` | `web/scaffold/WorkspaceScaffolder.java` | 工作区脚手架 |

### 8.4 Harness层（agentscope-harness模块）

| 类 | 文件 | 职责 |
|----|------|------|
| `ChatUiChannel` | `gateway/channel/chatui/ChatUiChannel.java` | 聊天UI通道 |
| `ChannelRouter` | `gateway/channel/ChannelRouter.java` | 消息路由 |
| `HarnessGateway` | `gateway/HarnessGateway.java` | 多agent网关 |
| `HarnessAgent` | `agent/HarnessAgent.java` | ReActAgent企业级包装 |
| `SandboxLifecycleMiddleware` | `agent/middleware/SandboxLifecycleMiddleware.java` | 沙箱生命周期 |
| `SessionTurnGate` | `gateway/SessionTurnGate.java` | 会话并发门 |

### 8.5 Core层（agentscope-core模块）

| 类 | 文件 | 职责 |
|----|------|------|
| `ReActAgent` | `ReActAgent.java` | ReAct循环核心 |
| `Toolkit` | `tool/Toolkit.java` | 工具注册与反射执行 |
| `AgentStateStore` | `state/AgentStateStore.java` | 会话记忆持久化接口 |
| `InMemoryAgentStateStore` | `state/InMemoryAgentStateStore.java` | 内存实现 |
| `Model` | `model/Model.java` | 模型接口 |
| `DashScopeChatModel` | `extensions/.../DashScopeChatModel.java` | DashScope实现 |

### 8.6 配置文件

| 文件 | 位置 | 用途 |
|------|------|------|
| `application.yml` | `src/main/resources/` | Spring配置（数据源、模型等） |
| `agentscope.json` | `~/.agentscope/dataagent/` | Agent定义（自动生成） |
| `AGENTS.md` | `~/.agentscope/dataagent/workspace/` | 系统提示词 |
| `tools.json` | `~/.agentscope/dataagent/workspace/` | 工具白名单 |
| `sessions.json` | `~/.agentscope/dataagent/workspace/` | 会话元数据 |

---

## 附录：完整调用链时序图

```
浏览器                ChatController       ChatUiChannel      HarnessGateway     HarnessAgent      ReActAgent        Toolkit         DataAgentToolkit
  |                        |                    |                  |                 |                |                |                |
  |--POST /stream--------->|                    |                  |                 |                |                |                |
  |                        |--JWT认证----------->|                  |                 |                |                |                |
  |                        |--ACL鉴权----------->|                  |                 |                |                |                |
  |                        |--订阅ToolEventBus-->|                  |                 |                |                |                |
  |                        |--executeChat()----->|                  |                 |                |                |                |
  |                        |                    |--dispatch()------>|                 |                |                |                |
  |                        |                    |                  |--resolveAgent() |                |                |                |
  |                        |                    |                  |--acquireGate()  |                |                |                |
  |                        |                    |                  |--call()-------->|                |                |                |
  |                        |                    |                  |                 |--acquireSandbox()              |                |
  |                        |                    |                  |                 |--call()------->|                |                |
  |                        |                    |                  |                 |                |--reasoning(0)->|                |
  |                        |                    |                  |                 |                |                |--model.stream()->DashScope
  |                        |                    |                  |                 |                |                |<--TextBlock+ToolUseBlock
  |                        |                    |                  |                 |                |--acting(0)---->|                |
  |                        |                    |                  |                 |                |                |--callTool()--->|
  |                        |                    |                  |                 |                |                |                |--JDBC查询
  |                        |                    |                  |                 |                |                |<--ToolResultBlock
  |                        |                    |                  |                 |                |--publishEvent->|                |
  |<--SSE: tool_call-------|<--ToolEventBus-----|                  |                 |                |                |                |
  |<--SSE: tool_result-----|<--ToolEventBus-----|                  |                 |                |                |                |
  |                        |                    |                  |                 |                |--reasoning(1)->|                |
  |                        |                    |                  |                 |                |  ... (多轮循环)  |                |
  |                        |                    |                  |                 |                |--reasoning(N)->|                |
  |                        |                    |                  |                 |                |<--纯文本回复---|                |
  |                        |                    |                  |                 |<--Msg----------|                |                |
  |                        |                    |                  |                 |--releaseSandbox()              |                |
  |                        |                    |                  |<--Msg-----------|                |                |                |
  |                        |                    |                  |--releaseGate()  |                |                |                |
  |                        |                    |<--Msg------------|                 |                |                |                |
  |                        |<--Msg--------------|                  |                 |                |                |                |
  |<--SSE: token-----------|                    |                  |                 |                |                |                |
  |<--SSE: done------------|                    |                  |                 |                |                |                |
  |                        |--recordUsage()---->|                  |                 |                |                |                |
```

---

**文档版本**：v1.0
**最后更新**：2026-09-09
**适用版本**：agentscope-dataagent 2.0.3-SNAPSHOT
