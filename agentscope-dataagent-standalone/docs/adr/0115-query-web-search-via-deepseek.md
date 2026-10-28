# ADR0115：问数助手经 DeepSeek 官方搜索 API 联网，移除 harness 默认联网工具

## 状态

已接受，2026-10-26。实施 spec095。

## 背景

问数会话中用户会在问完知识库数据后追问外部信息（如"最近有什么体育赛事开赛"）。AgentScope harness 默认给每个 Agent 注册 `web_search`（境外 Tavily，需 `TAVILY_API_KEY`）与 `web_fetch`（抓取任意网址，含内网地址）；本平台的问数与建模助手都带着这两个工具，前者未配置无法使用，后者在多租户服务上存在访问内网的风险。用户要求境内服务、改动最小。

## 决策

问数助手使用平台工具 `WebSearchTool`（工具名仍为 `web_search`），调用 DeepSeek 官方搜索 API `POST /v1/tools/search`。Key 只从环境变量 `DEEPSEEK_API_KEY` 读取，未配置时不注册。请求只发送助手整理的搜索词、条数与 `need_content=false`，不发送 `ori_question`（用户原话），不取网页正文；接口错误转为中文说明，不中断问数。何时搜索由助手判断：只用于知识库数据以外的外部信息，知识库指标与统计仍只来自 SQL，搜索词不得含内部数据。

问数与建模助手都移除 harness 的 `web_search` 与 `web_fetch`；建模助手不联网，联网内容不进入模型、规则或问答对。只调整平台自己的 Toolkit，不修改框架。

## 备选方案

- harness 原生 Tavily：境外服务，用户要求境内。
- 博查等境内搜索 API：同样可行，但需另开账号；DeepSeek 与现用模型同源，接口与计费清晰。
- 通过 MCP 接搜索服务：需额外部署或接入 MCP 服务，改动更大。
- 千问模型内置搜索（DashScope enableSearch）：当前经 model-router 使用 OpenAI 兼容接口，不适用。

## 后果

搜索词会发往 DeepSeek（境内公开检索），需在用户可见文档中说明；"搜索词不含内部数据"是提示词约束而非代码拦截。需要有余额的 DeepSeek 官方 Key，与模型路由的 Key 无关。用户自建 Agent 上的 harness 联网工具未处理，后续如开放需另行收敛。
