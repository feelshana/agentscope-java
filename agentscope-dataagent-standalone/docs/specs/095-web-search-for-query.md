# spec 095: 问数助手联网搜索（DeepSeek 官方搜索 API）

> 状态：已实施，待真实验收（需配置 `DEEPSEEK_API_KEY`）。关联 ADR0115。

## 背景与目标

问数会话中，用户常在问完知识库数据后接着问外部信息，例如"最近有什么体育赛事开赛"、行业新闻、政策、节假日、公开统计口径等。AgentScope harness 已给每个助手注册了 `web_search`（Tavily，境外）和 `web_fetch`（抓取任意网址，可访问内网），但未配置、也不受控。目标：问数助手改用境内的 DeepSeek 官方搜索 API，凡是知识库数据以外的外部信息由助手自行判断联网搜索；建模助手不联网；移除 `web_fetch`。

## 方案概述

- 新增平台工具 `tools/data/WebSearchTool`，工具名仍为 `web_search`，中文描述，调用 `POST https://api.deepseek.com/v1/tools/search`：
  - 请求：`query`（助手整理的搜索词，≤128 字）、`count`（默认 5，上限 10）；不传 `ori_question`（不把用户原话发往外部），不取正文（`need_content=false`）。
  - 返回给模型：每条 标题、网址、站点名、日期、摘要（`summary`，无则 `snippet`），按条数截断。
  - 错误：401/402/429/5xx 等转为中文错误说明（如"联网搜索余额不足，本次未搜索"），不重试、不抛异常中断问数。
- Key 只从环境变量 `DEEPSEEK_API_KEY` 读取（`dataagent.web-search.api-key: ${DEEPSEEK_API_KEY:}`，无默认值）；地址、超时、条数为配置项；`dataagent.web-search.enabled` 默认 true，未配置 Key 时视为关闭。
- 工具挂载（`DataToolkitRegistrar`，在 Agent 构建之后、只改各自 Toolkit，不改框架）：
  - 问数助手：`removeTool("web_fetch")`、`removeTool("web_search")`（harness 的 Tavily 版）；启用时注册平台 `web_search`。
  - 建模助手：移除 `web_search` 与 `web_fetch`。
- 问数提示词（`QUERY_EFFICIENCY_RULES` 增一节"联网搜索"，仅在工具可用时有意义）：
  - 问题涉及知识库数据以外的外部信息（新闻、赛事、活动、政策法规、节假日、行业概念、公开数据等）时联网搜索；知识库内的业务数据、指标与统计只能来自 SQL，不用搜索结果替代或补充统计值。
  - 这类外部问题不走问数流程：不召回问答、不读模型、不执行 SQL，也不适用"受限事实表缺日期先问"；"最近""本周"等按运行上下文的当前日期换算后写进搜索词（可用 `after:`）。
  - 同一会话中前后问题可混合：前一问是知识库数据、后一问是外部信息时，各按其类型处理；需要把两者结合时（如"赛事期间访问量有没有变化"），先搜索得到日期，再按日期执行 SQL。
  - 搜索词只写公开概念，不写内部数据、查询结果、客户/员工名称、知识库名称。
  - 回答中引用网上信息时注明来源网址，并与内部数据结论分开表述。
  - 一个问题最多搜索 2 次。

## 影响面

- 后端：`tools/data/WebSearchTool`（新）、`DataToolkitRegistrar`、`DataAgentConfig#QUERY_EFFICIENCY_RULES`、`application.yml`。
- 前端：无需改动（工具调用按现有工具步骤展示；如需中文标签，在 `wrenToolPresentation` 补 `web_search` → "联网搜索"）。
- 数据：无。
- 文档：README 配置表、ARCHITECTURE 第 12 章（配置）与工具链章节；ADR 一份（新增外部数据流向：搜索词发往 DeepSeek）。

## 验收标准

1. Given 已配置 `DEEPSEEK_API_KEY`，在同一问数会话中先问"10 月仪表盘访问情况"，When 接着问"最近有什么体育赛事开赛"，Then 第二问只调用 `web_search`（不召回、不查 SQL、不追问日期），搜索词含按当前日期换算的时间，回答列出赛事并注明来源网址。
2. Given 已配置 Key，When 问知识库内指标（如"10 月访问次数最多的报表"），Then 不调用 `web_search`，统计值来自 SQL。
3. Given 未配置 Key，Then 问数助手工具列表中没有 `web_search`、`web_fetch`，问数照常。
4. Given 任意配置，Then 建模助手工具列表中没有 `web_search`、`web_fetch`。
5. Given 搜索接口返回 402/429/超时，Then 助手收到中文错误说明并继续用已有信息回答，不中断。
6. 发出的请求体只含 `query`、`count`、`need_content`，不含 `ori_question`。
7. Given 已配置 Key，When 问"世界杯期间我们的报表访问量有没有变化"，Then 先搜索得到赛事日期，再按该日期执行 SQL，统计值来自 SQL。

## 不做的事

- 不接 DeepSeek Fetch（网页全文抓取），不保留 harness `web_fetch`。
- 建模助手不联网；搜索结果不写入模型、规则或问答对。
- 不做页面上的"联网搜索"开关（由助手自行判断，用户已确认）。
- 不处理用户自建 Agent（AgentCatalogService）上的 harness web 工具。

## 测试要求

- `WebSearchTool`：请求体字段（不含 `ori_question`）、结果格式化与截断、空结果、各错误码中文提示（用本地 HTTP 桩，不连外网）。
- 注册：未配置 Key 时问数无 `web_search`；问数与建模均无 `web_fetch`；建模无 `web_search`。
- `DataAgentConfigTest`：问数提示词包含搜索边界（统计只来自 SQL、搜索词不含内部数据、注明来源）。
- 真实调用为条件测试（有 Key 时手动验收），报告中注明。

## 关联

- 官方文档：https://api-docs.deepseek.com/guides/search/
- ADR：实施时新增（外部搜索数据流向与工具收敛）。
