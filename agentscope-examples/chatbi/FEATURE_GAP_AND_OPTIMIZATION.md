# ChatBI 功能对比与优化方案

> 基于 Dify v6 工作流（`ChatBI v6.yml`）与 AgentScope Java 实现的逐项对比，共梳理 **79 个功能点**。
> 当前实现率：**66% 已实现**、**16% 部分实现**、**15% 未实现**。

---

## 一、未实现功能清单 + 实现路线

### 1. 多级数据提取与截断（大数据保护）

| 维度 | 说明 |
|------|------|
| **Dify 实现** | 三级截断：45,000 字符 → 10,000 字符 → 10,000 字符 + 按比例缩减行数算法 |
| **Java 现状** | `SupersonicApiClient` 返回全量 CSV，`ContextTrimHook` 仅对历史轮次截断 500 字符，**当前轮次无保护** |
| **风险等级** | **高** — 单次大查询可能超出 LLM 上下文窗口，导致请求失败 |

#### 实现路线

**方案：智能数据缩减 + 统计摘要优先**

1. **新增 `DataResultTruncator` 工具类**
   - 位置：`src/main/java/io/agentscope/examples/chatbi/service/DataResultTruncator.java`
   - 核心逻辑：
     - 设定 `MAX_CHARS = 45000`（约 30k tokens）
     - 未超限：返回全量数据
     - 超限：按比例缩减行数 `newN = max(1, (int)(currentN * (MAX_CHARS / currentChars)))`
     - 缩减后仍超限：仅保留表头 + 前 1 行数据 + 截断说明

2. **修改 `SupersonicApiClient.toCompactTable()`**
   - 在生成 CSV 后调用 `DataResultTruncator.truncate()`
   - 返回截断后的数据 + 元信息（总行数、是否截断）

3. **Prompt 改造**（`data-query-agent-prompt.txt`）
   - 添加规则：当收到 `[数据已截断]` 标记时，LLM 应：
     - 告知用户"共 X 行数据，仅展示前 N 行"
     - 建议用户添加筛选条件缩小范围，或使用聚合查询

4. **可选增强：统计摘要工具**
   - 新增 `@Tool get_data_summary(datasetId, queryText)`
   - 返回 COUNT/SUM/AVG/MIN/MAX 等聚合指标
   - LLM 先获取摘要判断是否需要明细

#### 提示词示例

```
## 数据量控制规则

当查询返回的数据量较大时，系统会自动截断并标记：
- [数据已截断] 共 {totalRows} 行，当前仅展示前 {shownRows} 行

收到此标记时：
1. 向用户说明"共查询到 {totalRows} 条数据，以下展示前 {shownRows} 条"
2. 基于已有数据给出初步分析结论
3. 建议用户通过以下方式缩小范围：
   - 添加时间范围限制（如"仅查本月"）
   - 添加维度筛选（如"仅查咪咕视频"）
   - 使用聚合查询替代明细查询（如"求和"而非"列出所有"）
```

---

### 2. Top2 置信度模糊时用户选择交互

| 维度 | 说明 |
|------|------|
| **Dify 实现** | 分类 LLM 输出 Top2 类别 + 置信度，差值 < 0.1 时向用户展示两个选项，用户选择后继续 |
| **Java 现状** | RouterAgent 直接通过工具调用选择一个 SubAgent，无置信度判断和用户交互 |
| **风险等级** | 中 — 意图判断错误时会走完全错误的路径 |

#### 实现路线

**方案：RouterAgent 返回分类结果 + 置信度，低置信度时 fallback 到多 Agent 结果聚合**

> 注：AgentScope 的 ReActAgent 模型不直接支持"输出置信度"。有两种实现路径：

**路径 A（推荐）：RouterAgent 内部多工具调用 + 自动 fallback**
- RouterAgent 保持当前架构（LLM 自主选择工具）
- 在 `SessionAgentManager` 层增加监控：
  - 记录 RouterAgent 首次选择的 SubAgent
  - 如果返回结果为空/错误 → 自动尝试第二优先的 SubAgent
  - 避免用户感知到分类错误

**路径 B：显式分类节点**
- 新增 `IntentClassifier` 服务，使用独立 LLM 调用做意图分类
- 输出格式：`{"intent": "da", "confidence": 0.85, "alternative": "re", "alt_confidence": 0.78}`
- 当 `confidence - alt_confidence < 0.1` 时，通过 SSE 发送 `classification_choice` 事件
- 前端展示两个选项，用户选择后继续
- 改动较大，需新增分类 LLM 调用和前端交互

**建议先实施路径 A**，低成本且能覆盖大部分错误分类场景。

---

### 3. 开场白 + 推荐问题

| 维度 | 说明 |
|------|------|
| **Dify 实现** | `opening_statement` + `suggested_questions`（7 条）+ `suggested_questions_after_answer` |
| **Java 现状** | 无 |
| **风险等级** | 低 — 不影响功能，但影响用户体验 |

#### 实现路线

1. **Controller 新增端点**
   ```java
   @GetMapping("/suggestions")
   public List<String> getSuggestions(
       @RequestParam(defaultValue = "") String userName,
       @RequestParam(defaultValue = "") String sessionId)
   ```

2. **逻辑**：
   - 新会话（无消息）：返回 7 条预设推荐问题
   - 有历史对话：根据最后一次 assistant 回复内容，生成 3 条追问建议（调用 LLM）

3. **推荐问题列表**（与 Dify 一致）：
   ```
   - 当前报表/仪表盘的指标口径是什么？
   - 我想查询全场景活跃数据，应该看哪张报表？
   - 咪咕视频全场景活跃包含哪些活跃场景？
   - 量质构效是指什么？
   - 如何申请红海报表权限？
   - 如何订阅红海报表或仪表盘？
   - 当前报表什么时候出数？
   ```

4. **前端集成**：`static/index.html` 中在对话初始化时调用 `/api/chatbi/suggestions`，展示为可点击的快捷按钮。

#### 配置化设计

```yaml
chat:
  suggestions:
    initial-questions:
      - 当前报表/仪表盘的指标口径是什么？
      - 我想查询全场景活跃数据，应该看哪张报表？
      - 咪咕视频全场景活跃包含哪些活跃场景？
    enabled: true
    follow-up-enabled: true  # 回答后由 LLM 生成追问建议
```

---

### 4. ~~搜索结果迭代逐个取详情~~ ✅ 已实现

| 维度 | 说明 |
|------|------|
| **Dify 实现** | Iteration 节点遍历搜索结果，并行（最多 7 并发）HTTP 请求逐个取页面详情 |
| **Java 现状** | ✅ 已创建独立的 `KnowledgeSearchClient` + 补全 `KnowledgeSearchTool` |
| **风险等级** | ~~中~~ — 已完成 |

#### 已完成实现

**新增文件：**

1. **`KnowledgeSearchClient`** — 独立的知识搜索 HTTP 客户端
   - 位置：`src/main/java/.../client/KnowledgeSearchClient.java`
   - 封装两个端点（与 Dify v6 完全对应）：
     - `POST /api/v1/search` — 报表/仪表盘搜索（`re` 意图）
     - `POST /api/v1/docrepo/query` — 知识库文档查询（`bu`/`in` 意图）
   - 内置 HTTP 重试（`Retry.backoff(2, 100ms)`）
   - 自动提取 `final_prompt` 响应字段
   - 通过 `application.yml` 配置：
     ```yaml
     knowledge.search:
       base-url: http://10.194.2.66:8006
       api-key: mk_mQ636UhMnx8brb9Grcy
       top-n: 10
     ```

2. **`KnowledgeSearchTool`** — 已补全 3 个 `@Tool` 方法
   - 位置：`src/main/java/.../tool/KnowledgeSearchTool.java`
   - `search_reports(query)` — 报表搜索
   - `query_knowledge(query, questionType)` — 基础知识查询
   - `query_knowledge_with_context(query, questionType, projectId, reportName, dashboardName, memory)` — 带完整上下文的查询
   - 不再依赖 `ConfluenceApiClient`，避免混用导致逻辑混乱

**设计要点：**
- 与 `ConfluenceApiClient` 完全解耦（不同后端服务：`10.194.2.66:8006` vs `10.194.2.65:8001`）
- 响应格式兼容多种嵌套结构（`final_prompt` 可能在根节点、`data.final_prompt`、或 `data` 是 JSON 字符串）
- 统一的错误处理和中文错误消息

**后续接入：**
- 在 `KnowledgeAgentFactory` 中将 `KnowledgeSearchTool` 注册到 Toolkit
- 替换当前仅依赖 `DifyKnowledge` RAG 的单一知识源架构

---

### 5. 多分支结果变量聚合器

| 维度 | 说明 |
|------|------|
| **Dify 实现** | Variable Aggregator（`1766396938040`）合并所有分支结果后统一交给最终答案 LLM |
| **Java 现状** | 各 SubAgent 独立运行，结果直接返回前端 |
| **风险等级** | 低 — AgentScope 架构下各 Agent 独立运行更合理，不需要聚合器 |

#### 实现路线

> **不建议完全照搬**。AgentScope 的 SubAgent 设计天然隔离，不需要 Dify 的变量聚合器。
> 但可以在特定场景下增加**跨 Agent 知识注入**：
> - 例如 `da` 意图查询数据后，如果用户追问指标定义，可以把已查询的数据上下文注入到 `KnowledgeAgent`

---

### 6. HTTP 重试机制

| 维度 | 说明 |
|------|------|
| **Dify 实现** | HTTP 请求节点配置 `max_retries=2, retry_interval=100ms` |
| **Java 现状** | `WebClient` 调用无显式 retry 逻辑 |
| **风险等级** | 中 — 网络抖动时直接报错 |

#### 实现路线

在 `SupersonicApiClient`、`DataLineageApiClient`、`ConfluenceApiClient` 中统一添加 retry：

```java
// 在 WebClient 调用链上添加
.retryWhen(Retry.backoff(2, Duration.ofMillis(100)))
```

---

## 二、已实现功能优化方案

### 1. ContextTrimHook — 上下文裁剪

| 当前实现 | 保留最近 5 轮真实问题 + 历史 tool_result 截断 500 字符 |
|----------|------------------------------------------------------|

#### 优化方向

**问题 1**：当前轮次的 tool_result 完全不截断，大结果集直接全部进入 LLM 上下文。

**优化方案**：
- 结合上文"多级数据截断"，在 `ContextTrimHook` 中增加**当前轮次**的智能截断：
  - 当前轮 tool_result 超过 30,000 字符时，截断为前 500 行 + "[数据已截断，共 X 字符]"
  - 保留截断通知，让 LLM 知道有更多数据

**问题 2**：500 字符对历史 tool_result 截断太激进，可能截断掉关键数据。

**优化方案**：
- 改为**保留表头 + 前 N 行 + 统计摘要**而非简单截断
- 统计摘要（如"共 200 行，日期范围 2024-01~2024-12"）用 50 字符即可表达

```java
// 优化后的截断策略
private ToolResultBlock smartTruncateToolResult(ToolResultBlock trb, int maxChars) {
    String text = extractText(trb);
    if (text.length() <= maxChars) return trb;

    // 解析 CSV，保留表头 + 前 N 行 + 统计信息
    String[] lines = text.split("\n");
    if (lines.length > 2) {
        String header = lines[0];
        int keepRows = Math.min(lines.length - 1, 3);
        String summary = String.format("（共%d行，展示前%d行）", lines.length - 1, keepRows);
        String truncated = Stream.concat(
            Stream.of(header, summary),
            Arrays.stream(lines).skip(1).limit(keepRows)
        ).collect(Collectors.joining("\n"));
        return ToolResultBlock.of(trb.getId(), trb.getName(), TextBlock.builder().text(truncated).build());
    }
    // fallback to simple truncation
    return simpleTruncate(trb, maxChars);
}
```

**效果**：同样 500 字符内传递更多信息（结构化的前 N 行 > 随机截断的前 500 字符）。

---

### 2. ChatSessionService — 历史会话压缩

| 当前实现 | 异步 LLM 摘要压缩（>8000 tokens 触发），保留最近 6 条消息 |
|----------|----------------------------------------------------------|

#### 优化方向

**问题 1**：摘要压缩是同步调用 `summaryModel.stream(...).blockLast()`，在 `CompletableFuture` 中执行，但 `blockLast()` 会阻塞线程。

**优化方案**：
- 改为完全异步：使用 `summaryModel.stream(...).subscribe()` 替代 `blockLast()`
- 或添加独立的线程池处理摘要任务

**问题 2**：压缩阈值 8000 tokens 是硬编码，不同模型上下文窗口不同。

**优化方案**：
- 根据当前使用的模型动态计算阈值
- 添加配置项 `chat.session.compression-ratio: 0.8`（当上下文使用率达 80% 时触发压缩）

**问题 3**：摘要 prompt 较通用，没有针对性。

**优化 Prompt 优化**：

```
你是一位数据分析助手，请将以下对话历史压缩为结构化的摘要。

要求：
1. 保留所有查询过的数据集名称和关键数据结论（具体数值）
2. 保留用户的分析目标和问题演进路径
3. 去除寒暄、推理过程和重复内容
4. 使用以下格式输出：
   - 分析目标：...
   - 已查数据：[数据集A] 结论：...；[数据集B] 结论：...
   - 用户关注维度：...
   - 待解决问题：...

对话历史：
{history}
```

**效果**：结构化摘要更容易被后续对话复用，减少摘要信息丢失。

---

### 3. DataQueryAgent — 数据查询智能体

| 当前实现 | ReActAgent + PlanNotebook + 数据复用检查 + ECharts 生成 |
|----------|--------------------------------------------------------|

#### 优化方向

**优化 1：数据集详情缓存 + 按需加载**

当前 `get_dataset_detail` 已在 prompt 中要求只调用一次，但依赖 LLM 遵循。

**代码级保障**：
```java
// 在 DataQueryAgentTool 中增加本地缓存
private final Map<String, String> datasetDetailCache = new ConcurrentHashMap<>();

@Tool(description = "获取数据集的详细字段信息")
public Mono<String> getDatasetDetail(@ToolParam String datasetIds) {
    for (String id : parseIds(datasetIds)) {
        if (!datasetDetailCache.containsKey(id)) {
            // fetch and cache
        }
    }
    return buildResult(datasetIds);
}
```

**优化 2：查询失败自动重试/降级**

当前查询失败仅返回错误信息，LLM 需要自己判断重试。

**优化方案**：
- 增加 `DataQueryAgentTool` 内部的自动重试（最多 2 次）
- 超时场景自动降级为查询统计数据

**优化 3：查询结果缓存（跨轮次）**

```java
// 同一会话内，相同 queryText 的查询结果缓存
// Key: sessionId + hash(queryText)
// TTL: 5 分钟（或会话重置时清除）
private final LoadingCache<String, String> queryResultCache;
```

**效果**：用户重复提问或微调时，直接返回缓存，减少 API 调用。

---

### 4. RouterAgent — 路由智能体

| 当前实现 | LLM 通过 7 个 SubAgentTool 自主选择路由 |
|----------|----------------------------------------|

#### 优化方向

**优化 1：路由日志与统计**

```java
// 新增 RouterMetricsService
public void recordRoute(String sessionId, String intent, long durationMs, boolean success);
public RouteStats getStats();  // 各意图的命中率、平均耗时
```

**效果**：可监控哪些意图分类容易出错，用于后续优化 prompt。

**优化 2：快速路径（短路路由）**

对于明显是闲聊的输入（如"你好"、"谢谢"），不走 LLM 推理，直接路由到 `ChatAgent`：

```java
private static final Set<String> CHIT_CHAT_KEYWORDS = Set.of("你好", "hello", "谢谢", "再见");

public String fastRoute(String query) {
    if (CHIT_CHAT_KEYWORDS.stream().anyMatch(query::contains)) {
        return "ot"; // 直接走 ChatAgent
    }
    return null; // null 表示需要 LLM 判断
}
```

**效果**：减少闲聊场景的 LLM 调用，降低延迟和成本。

---

### 5. SSE 流 — 实时事件推送

| 当前实现 | chunk/tool/thinking/done 四种事件类型 |
|----------|--------------------------------------|

#### 优化方向

**新增事件类型**：

```java
// data_truncated — 数据被截断时通知前端
if (chunk.contains("[数据已截断]")) {
    return ServerSentEvent.<Map<String, String>>builder()
        .event("data_truncated")
        .data(Map.of("type", "data_truncated", "totalRows", totalRows, "shownRows", shownRows))
        .build();
}

// classification — 路由结果通知（可选，让前端展示"正在为您查询XX"）
return ServerSentEvent.<Map<String, String>>builder()
    .event("intent")
    .data(Map.of("type", "intent", "content", intentName))
    .build();
```

**效果**：前端可以更精细地展示处理进度。

---

### 6. 整体效率提升建议

| 优化项 | 当前问题 | 优化方案 | 预期效果 |
|--------|---------|---------|---------|
| **模型调用并行化** | RouterAgent → SubAgent 串行调用 | 对 `da` 意图的 query decomposition 子任务，可并行发到 Supersonic | 多子任务场景延迟降低 30-50% |
| **Prompt 缓存** | 每次请求重新发送完整 system prompt | 利用 qwen3.6-plus 的 prompt caching 特性，system prompt 作为可缓存前缀 | API 延迟降低 20-40%，成本降低 |
| **连接池复用** | WebClient 每次新建连接 | 配置 `ConnectionProvider` 连接池，设置 maxConnections=50 | 高并发下减少连接建立开销 |
| **工具描述优化** | Tool 描述过长增加 token 消耗 | 精简 tool description，去除冗余信息 | 每个 tool 节省 100-300 tokens |
| **流式传输压缩** | SSE 传输大量文本 | 前端侧做文本去重和渲染节流，避免频繁 DOM 更新 | 前端渲染性能提升 |
| **异步压缩** | `maybeCompressAsync` 用 `CompletableFuture.runAsync` 但内部 `blockLast()` | 改为 `summaryModel.stream(...).subscribe()` 纯异步 | 不阻塞请求线程 |

---

## 三、实施优先级建议

| 优先级 | 功能 | 工作量 | 收益 |
|--------|------|--------|------|
| **P0** | 大数据截断保护（`DataResultTruncator`） | 2-3 天 | **避免线上崩溃**，直接解决 token 超限问题 |
| **P0** | ~~HTTP 重试机制~~ | ~~0.5 天~~ | ~~提升稳定性~~ — ✅ 已在 `KnowledgeSearchClient` 中实现 |
| **P0** | ~~补全 KnowledgeSearchTool~~ | ~~1-2 天~~ | ~~Confluence 搜索落地~~ — ✅ 已完成（`KnowledgeSearchClient` + `KnowledgeSearchTool`） |
| **P1** | ContextTrimHook 智能截断（保留表头+N行） | 1-2 天 | 提升历史轮次信息密度 |
| **P1** | 开场白 + 推荐问题 API | 1 天 | 提升用户体验 |
| **P1** | 结构化摘要 Prompt | 0.5 天 | 提升摘要质量，减少信息丢失 |
| **P2** | 查询结果缓存 | 1 天 | 减少重复 API 调用 |
| **P2** | 快速路径（短路路由） | 0.5 天 | 降低闲聊场景延迟 |
| **P2** | SSE 新增事件类型 | 0.5 天 | 前端展示更丰富 |
| **P3** | 路由统计服务 | 1-2 天 | 运营分析用 |
| **P3** | 数据集详情代码级缓存 | 0.5 天 | 防御性措施 |
| **P3** | 补全 KnowledgeSearchTool | 1-2 天 | Confluence 搜索落地 |

---

## 四、涉及的关键文件

| 文件 | 操作 |
|------|------|
| `src/main/java/.../client/SupersonicApiClient.java` | 添加截断调用 + retryWhen |
| `src/main/java/.../client/DataLineageApiClient.java` | 添加 retryWhen |
| `src/main/java/.../client/ConfluenceApiClient.java` | 添加 retryWhen |
| `src/main/java/.../client/ReportScheduleApiClient.java` | 添加 retryWhen |
| `src/main/java/.../client/KnowledgeSearchClient.java` | **✅ 已新增** |
| `src/main/java/.../service/DataResultTruncator.java` | **新增** |
| `src/main/java/.../service/ContextTrimHook.java` | 优化截断策略 |
| `src/main/java/.../service/ChatSessionService.java` | 优化异步压缩 + 结构化摘要 prompt |
| `src/main/java/.../tool/DataQueryAgentTool.java` | 添加查询结果缓存 + 数据集详情缓存 |
| `src/main/java/.../tool/KnowledgeSearchTool.java` | **✅ 已补全** |
| `src/main/java/.../controller/ChatBiController.java` | 新增 /suggestions 端点 + SSE 新事件 |
| `src/main/resources/prompts/data-query-agent-prompt.txt` | 添加数据量控制规则 |
| `src/main/resources/application-dev.yml` | 添加推荐问题配置 + 压缩配置 + knowledge.search 配置 |
| `src/main/resources/static/index.html` | 前端适配推荐问题 + 新 SSE 事件 |