# ADR 0010: `LLM.log` 改为人工可读的消息转录（只记 role / type / text）

- 状态：已采纳（2026-09-22 实施，见 [spec 004](../specs/004-llm-log-readable-transcript.md)）
- 日期：2026-09-22
- 证据：改造前的 `logs/LLM.log`（922 行只覆盖一次提问的 5 轮 LLM 调用：system message
  每轮重放一遍，每条消息带 `id` / `name` / `metadata` / `timestamp` / `usage` 五个与阅读
  无关的字段；`tool_result` 的文本是 JSON 二次编码字符串，schema 表格与 SQL 被压成带字面
  `\n` 的单行）；改造前的 `DebugLoggingMiddleware`（缩进 JSON 全量 dump +
  `LLM Call #N` / `RAW INPUT` / `msgCount` / `textLen` / `toolNames` / `Tool Execute` 段）
- 关系：不改变 ADR 0006–0009 的结论（它们的量化口径是字符数与匹配数，与渲染格式无关，
  证据均取自旧格式日志）；`ARCHITECTURE_zh.md` 装配清单中 `DebugLoggingMiddleware`
  一行同步为新格式描述

## 背景

这份日志是本仓库做提示词治理的主要证据来源——ADR 0006 的 JOIN 优先、ADR 0007 的三层分工、
ADR 0008 的摸底查询与计数不一致、ADR 0009 的 `## Subagents` 段占比，全部由它量化得出。
但它一直是「调试 dump」而不是「可读转录」：

1. **每轮重放整个历史。** `onModelCall` 把 `input.messages()` 用缩进 JSON 全量序列化，
   框架每轮都会把完整历史重放给模型，于是 system prompt 在一次提问里被写了 5 遍；
   922 行中真正的新内容不到四分之一，体积随轮次平方增长。
2. **每条消息携带五个无关字段。** `id` / `name` / `metadata` / `timestamp` / `usage`
   对人工阅读毫无价值，却把正文推远，检索一条 SQL 要滚过大量 JSON 骨架。
3. **工具结果不可读。** `tool_result` 的 `TextBlock` 内容是 JSON 二次编码字符串
   （形如 `"\"## 用户列表\\n\\n| 字段 | 类型 |...\""`），schema 表格、字段描述里的
   取值提示全部挤在一行，恰恰是最需要逐字核对的部分。
4. **工具入参同样被压平。** SQL 的换行变成字面 `\n`，既读不出结构也无法直接复制执行，
   而 SQL 正是人工复核越权与去重门的唯一凭据。
5. **元信息与正文混排。** `session=` / `msgCount=` / `textLen=` / `toolCalls=` /
   `toolNames=` / `RAW INPUT` / `END OUTPUT` / `Tool Execute` 等段头需要人工跳过。

## 决策

1. **只记 message 三要素。** 每个 content block 渲染成一条记录，字段固定为
   `role` / `type` / `text`，`type` 沿用框架 JSON 判别名（`text` / `thinking` /
   `tool_use` / `tool_result` / `hint` / `image` / `audio` / `video` / `data`）。
   其余一律不写——包括时间戳：`logback-spring.xml` 的 `LLM_FILE` pattern 由
   `%d{...} %msg%n` 改为 `%msg%n`，需要时刻时按配置里的注释加回。
2. **工具入参逐行展开。** 首行工具名，随后每个入参一行 `  key: value`；值含换行时写成
   `  key:` 换行后接 4 空格缩进的原文，SQL 因此保留自身换行与缩进。非字符串值用紧凑 JSON。
3. **工具结果反转义。** 文本若形如 JSON 编码字符串（首尾双引号且含反斜杠）则解码回真实
   文本；判定不成立时原样输出，解码失败也原样输出。
4. **媒体块只留占位符。** `(image)` / `(audio)` / `(video)` / `(data: name)`，
   避免 base64 载荷淹没转录。
5. **同会话按消息 id 去重。** 一条消息只在该 session 首次出现时写一次，日志因此是一条
   线性对话；`id` 为 null 时不去重（宁可重复不可丢失）。簿记有上限
   （`MAX_TRACKED_SESSIONS=64`、`MAX_TRACKED_MESSAGES=2048`），超限清空，
   后果只是重复一段，不会丢内容。
6. **模型回复优先取 `AgentResultEvent` 的 `Msg`。** 它带真实 id（可参与去重）且含完整的
   `tool_use` 块；该事件未到达本次调用链时，回落到流式 `TextBlockDeltaEvent` 拼接的文本，
   并用 sessionId 校验后才写，两条路径不会重复。

## 理由与权衡

- **转录而非每轮 dump**：日志的用途是「人读一遍这次会话发生了什么」，不是「重放第 N 轮
  的完整 prompt」。代价是无法直接读出某一轮模型看到的 prompt，需按顺序累加——历史只增，
  累加即等价；发生 compaction 时会看到新 id 的消息重新出现，这正是需要察觉的信号。
- **去重按 id 而非内容哈希**：`Msg` 不可变，同 id ⇒ 同内容，判定精确且零成本。
- **去重范围限会话**：不同 session 各自成篇，跨会话不会互相吞消息——多租户部署下
  这是必须保住的性质。
- **为什么敢删时间戳**：滚动文件名带日期，且诉求明确是「只含 message 信息」；
  需要精确时刻时改一行 pattern 即可恢复，不构成架构约束。
- **保留 `LLM_DEBUG` logger 与独立 appender**：与控制台日志分离的既有结构不变，
  改动只在渲染层，风险面局限在一个中间件加一行 pattern。

## 被否决的替代方案

- **保留 JSON dump，只删无用字段**：正文仍是转义过的单行字符串，SQL 与 schema 表格
  照样不可读，治不了根。
- **输出 JSONL 供工具消费**：与「人工阅读」的目标相反，且本仓库的实际用法是人工通读。
- **每轮全量 dump 但加清晰分隔标记**：体积仍随轮次平方增长，而分隔标记本身就是
  被要求删掉的「其他信息」。
- **靠 logback 的 encoder / pattern 做格式转换**：logback 只能排版字符串，无法把 `Msg`
  对象重排成三字段文本，渲染必须在中间件里完成。
- **把日志搬到前端页面查看**：超出诉求，且离线分析（本仓库提示词治理的主要方式）
  仍然需要文件。

## 适用与失效条件

- 依赖 `Msg` 不可变且 id 唯一。若框架改为复用同一 id 承载不同内容，去重会吞消息，
  届时需改按 (id, 内容哈希) 判定。
- 依赖 `AgentResultEvent` 能穿过 `onAgent` 中间件链（`ReActAgent` 在
  `MiddlewareChain.build(..., MiddlewareBase::onAgent, core)` 之前把它 emit 进流）。
  harness 若改为过滤该事件，会自动走流式回落分支：最终回答仍在，只是那条 assistant
  消息可能在下一轮历史里再出现一次（重复而非丢失）。
- 多会话并发写同一文件时记录会交错（旧格式同样如此）；由于不再写 session 字段，
  交错后无法按会话拆分。将来若需并发排障，应加回时间戳与会话标识，或按会话分文件。
- appender 是追加写：格式切换后旧内容仍留在同一文件里，需要纯净转录时先删除
  `logs/LLM.log`。
