# spec 004: `LLM.log` 人工可读转录（只含 role / type / text）

## 背景与目标

`logs/LLM.log` 是本仓库提示词治理的主要证据来源（ADR 0006–0009 的量化都出自它），
但格式是调试 dump：`DebugLoggingMiddleware#onModelCall` 每轮把完整 `messages()` 用缩进
JSON 全量序列化，每条消息带 `id` / `name` / `metadata` / `timestamp` / `usage`；
框架每轮重放历史，于是一次提问的 5 轮把 system prompt 写了 5 遍（922 行日志只覆盖一个问题）。
`tool_result` 的文本是 JSON 二次编码字符串，schema 表格挤成带字面 `\n` 的单行；
`tool_use` 的 SQL 同样被压平，无法直接阅读或复制执行。

目标：把这份日志改造成人工可直读的**消息转录**——每条记录只有 `role` / `type` / `text`
三个字段，不含任何其他信息；同一会话内不重复写同一条消息。落地 ADR 0010。

## 方案概述

改动集中在渲染层，不动日志的落盘结构（仍是 `LLM_DEBUG` logger → `LLM_FILE` appender）：

1. **记录格式**（`DebugLoggingMiddleware`）：一个 content block 一条记录，形如

   ```
   role: ASSISTANT
   type: tool_use
   text:
   query_structured_data
     source_id: ea70a5a4-12f9-42b9-adbf-eeef9fde4bb3
     sql:
       WITH leaders AS (
           SELECT DISTINCT account FROM ...
       )
     row_limit: 200
   ```

   - `type` 用框架 JSON 判别名：`text` / `thinking` / `tool_use` / `tool_result` /
     `hint` / `image` / `audio` / `video` / `data`（未知类型退化为类名）。
   - `tool_use`：首行工具名，随后每个入参一行 `  key: value`；值含换行时写成 `  key:`
     换行后接 4 空格缩进原文（SQL 保留换行与自身缩进）；非字符串值用紧凑 JSON。
   - `tool_result`：首行工具名，随后是输出块的真实文本。
   - 媒体块只留占位符 `(image)` / `(audio)` / `(video)` / `(data: name)`，
     不把 base64 写进日志。
   - 空 content 的消息写一条 `type: text` 的空记录（保留「这一轮存在消息」的事实）。
2. **反转义**：文本若首尾是双引号且含反斜杠，按 JSON 字符串解码回真实文本；
   判定不成立或解码失败都原样输出，绝不吞内容。
3. **同会话按 id 去重**：`Map<String, Set<String>> writtenIds`（sessionId → 已写 id）。
   `id == null` 的消息不去重。上限 `MAX_TRACKED_SESSIONS=64`、
   `MAX_TRACKED_MESSAGES=2048`，超限清空对应簿记——后果只是重复一段，不会丢消息。
4. **模型回复来源**：优先 `AgentResultEvent#getResult()`（带真实 id，可参与去重，
   含完整 `tool_use` 块）；该事件未到达时回落到流式 `TextBlockDeltaEvent` 拼接的文本，
   回落前用 sessionId 校验，两条路径互斥不重复。
5. **移除的段**：`Agent invocation started/finished`、`LLM Call #N`、`LLM Response #N`、
   `session=`、`msgCount=`、`textLen=`、`toolCalls=`、`toolNames=`、`RAW INPUT/END INPUT`、
   `RAW OUTPUT/END OUTPUT`、`Tool Execute/End Tool`（`onActing` 覆写一并删除：
   工具入参已由下一轮历史里的 assistant 消息带出）。
6. **logback**：`LLM_FILE` 的 pattern 由 `%d{yyyy-MM-dd HH:mm:ss.SSS} %msg%n` 改为
   `%msg%n`，并在配置里注释说明如何加回时间戳。滚动策略、appender 类型、
   logger 名与 `additivity=false` 全部不变。
7. **文档同步**：`ARCHITECTURE_zh.md` 装配清单里 `DebugLoggingMiddleware` 一行
   改为描述新格式（三字段、按 id 去重、入参展开、结果反转义、媒体占位符）。

## 影响面

- 后端：`web/middleware/DebugLoggingMiddleware`（整体重写）、`logback-spring.xml`
  （一行 pattern + 注释）
- 前端：无
- 数据：无
- 运行态：`logs/LLM.log` 追加写，旧格式内容不会自动清除（需要纯净转录时手动删文件）
- 文档：`ARCHITECTURE_zh.md` 装配清单一行；ADR 0010 与本 spec
- 不受影响：SSE 链路、`ToolNotificationMiddleware`、`DataDynamicContextMiddleware`、
  控制台日志（root appender 与 pattern 未动）、日志滚动与保留策略

## 验收标准（Given-When-Then，可测试）

1. Given 一次包含工具调用的提问，When 查看 `logs/LLM.log`，Then 每条记录只有
   `role:` / `type:` / `text:` 三行开头，文件中不再出现 `id`、`timestamp`、`usage`、
   `metadata`、`session=`、`msgCount=`、`textLen=`、`toolNames=`、`RAW INPUT` 任一字样。
2. Given 模型发起了带多行 SQL 的 `query_structured_data`，When 查看该条 `tool_use` 记录，
   Then SQL 按原始换行与缩进呈现，不含字面 `\n`。
3. Given `prepare_data_context` 返回 JSON 编码的 schema 文本，When 查看该条
   `tool_result` 记录，Then markdown 表格按真实换行呈现，不含 `\"` 与字面 `\n`。
4. Given 同一次提问触发 N 轮 LLM 调用，When 统计 system message 出现次数，
   Then 只出现 1 次（不再是 N 次）。
5. Given 同一会话内追问第二个问题，When 查看日志，Then 第一轮已写过的消息不再重复出现，
   新问题与后续消息接着往下写。
6. Given 两个不同会话交替提问，When 查看日志，Then 各自的消息都被完整写出
   （去重不跨会话）。
7. Given 一次正常结束的提问，When 查看日志末尾，Then 模型的最终回答以
   `role: ASSISTANT` / `type: text` 记录出现且只出现一次。

## 不做的事（明确排除项）

- 不改 logger 名（`LLM_DEBUG`）、appender 类型、滚动策略与保留上限。
- 不动控制台日志的 pattern 与级别，不改其他 logger 的配置。
- 不引入配置项（无「切回 JSON dump」的开关；需要时改代码即可，日志格式不是产品面）。
- 不做按会话分文件、不做日志脱敏、不做前端查看页面。
- 不清理 `logs/` 下已有的旧格式内容（属运行态数据，由使用者决定）。
- 不改 harness / core，不新增对框架内部 API 的依赖（只用 `Msg` / `ContentBlock` 的
  public 访问器与 `AgentResultEvent`）。

## 测试要求

- 新增 `src/test/java/io/agentscope/dataagent/web/middleware/DebugLoggingMiddlewareTest.java`：
  - `rendersExactlyRoleTypeAndText`（验收 1：精确断言三字段文本，并断言旧 dump 的
    噪声字段一个都不出现）。
  - `rendersToolUseArgumentsAsReadableLines`（验收 2：多行 SQL 保留换行与缩进，
    标量入参单行展开，且全文不含字面 `\n`）。
  - `decodesJsonEncodedToolResults`（验收 3：JSON 编码的 schema 文本被解码，
    不含 `\n` 与 `\"`）。
  - `replacesMediaPayloadsWithPlaceholder`（媒体块只留 `(image)`，URL 不外泄）。
  - `writesEachMessageOncePerSession`（验收 4/5/6：同会话重放历史不再写、
    不同会话各写各的、sessionId 为 null 时不去重、空列表不写）。
- 既有测试须全绿，尤其 `DataAgentConfigTest`、`SharedSkillContentTest`、
  `SubagentOrchestrationSwitchTest`（它们都以日志分析结论为依据）。
- 本 spec 不涉多租户/越权逻辑，无需新增「看不到别人的数据」用例；验收 6 覆盖
  「去重不跨会话」这一多租户相关性质。
- 验证命令：`mvn -o spotless:apply`、`mvn -o spotless:check`、`mvn -o test`；
  前端零改动，无需 `npm run build`。

## 关联

- ADR：[0010-llm-log-readable-transcript](../adr/0010-llm-log-readable-transcript.md)
- 相关：[ADR 0007](../adr/0007-prompt-layering-and-shared-single-source.md)、
  [ADR 0008](../adr/0008-anti-probe-queries-and-count-consistency.md)、
  [ADR 0009](../adr/0009-subagent-orchestration-off-by-default.md)
  （三者的证据都取自旧格式日志，量化口径是字符数与匹配数，不受本次格式改造影响）
- 证据：改造前的 `logs/LLM.log`（会话 `main-cd465cf5`，2026-09-22 18:23，922 行）

## 实施记录（2026-09-22，已完成）

### 已落地

- 改动 1–6 全部按方案实施：`DebugLoggingMiddleware` 整体重写（`render` / `renderNew`
  为 package-private，便于直接断言渲染结果与去重行为）；`logback-spring.xml` 的
  `LLM_FILE` pattern 改为 `%msg%n` 并补注释说明如何加回时间戳。
- 改动 7（文档同步）完成：`ARCHITECTURE_zh.md` 装配清单中 `DebugLoggingMiddleware`
  一行由「调试日志」扩写为新格式的四条特征。
- 实施中确认的框架事实：`ContentBlock` 是 sealed 类，但本模块以 `-source 17` 编译，
  **不支持 switch 模式匹配**，渲染分支改用 `instanceof` 链（首次编译因此失败一次）。
- 实施中确认的第二处事实：`ImageBlock` 构造器对 `source` 做 `Objects.requireNonNull`，
  测试里必须给真实 `URLSource`，不能传 null。

### 偏差

- 无功能偏差。唯一与原设想不同的是渲染分支写法（`instanceof` 链而非 switch 模式匹配），
  原因是编译目标为 17。

### 验证

- `mvn -o spotless:apply` / `mvn -o spotless:check` 通过；`mvn -o test` →
  **117 tests / 0 failures / 12 skipped**（原 112 + 新增 5 个
  `DebugLoggingMiddlewareTest` 用例，全部执行）。
- 用一组贴近真实结构的消息（system prompt + 用户提问 + `prepare_data_context` 调用与
  JSON 编码结果 + 带多行 CTE SQL 的 `query_structured_data` 调用）跑了一次渲染器，
  逐行核对输出形态符合验收 1–5；该临时用例核对后已删除，不留在测试集里。
- 运行时验收 1–7 **待执行**：重启后提一个问题，核对新日志形态（旧的 922 行内容仍在
  同一文件里，建议先删除 `logs/LLM.log` 再看纯净转录）。

### 遗留提示

- `AgentResultEvent` 是否穿过 harness 的 `onAgent` 链未做运行时确认；若被过滤，
  会自动走流式回落分支（验收 7 仍能通过，但那条 assistant 消息可能在下一轮历史里
  重复出现一次）。运行时验收时留意最终回答是否出现两次即可判定走的是哪条路径。
