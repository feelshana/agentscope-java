# ADR0094：官方 enrich-context 与基础技术变更自动确认

状态：已接受，2026-10-19；"高影响变更遵循确认流程"条款已被 ADR-0111 取代（2026-10-21）

## 决策

采样完成后继续自动进入现有语义建模工作台。建模 Agent 直接加载安装版官方 `generate-mdl`、`enrich-context`、`usage` 和 `onboarding`，不维护平台派生的精简 skill，也不通过第二个 Agent 切换语义规则。

模型描述、列描述和基础关系属于技术性建模变更，由 AgentScope 自动执行、预检并调用官方校验。Cube、View、业务口径冲突及其它高影响资产继续遵循官方 enrich-context 的确认流程。

## 原因

精简 skill 会丢失官方 Grill、Auto-pilot、Lane、冲突处理和验证细节，Agent 还可能通过 `wren_cli skills get enrich-context` 重新加载官方原文，造成规则来源混杂。保留官方 skill 作为唯一语义流程来源可以避免该问题。

## 影响

自动建模仍保留对话、校验和显式发布；用户不再需要逐项确认模型和基础关系的技术写入。Wren MCP/CLI、问数流程、Cube/View 能力和租户隔离不变。
