# ADR 0013: TC 式落地页与语义配置页

- 状态：已采纳（从 data-agent-merge 合并）
- 日期：2026-09-22
- 来源：commit b8cb24e3

## 背景

需要复刻腾讯 TC-DataAgent 的落地页风格，提升产品视觉质感。同时需要语义配置页支持术语管理。

## 决策

1. **TC 式落地页**：`ChatPanel.tsx` 重构为流光输入框 + 渐变背景（`@keyframes da-flow-spin`、conic-gradient 流光动画）
2. **语义配置页**：新增 `SemanticConfigPage.tsx`，支持标签式同义词输入、分页（10/20/50）
3. **视觉升级**：`global.css` 新增 `@property --da-flow-angle`、`.da-landing-wrap`、`.da-composer-glow`、`.da-composer-shell` 等样式
4. **子代理开关**：`DataAgentConfig` 新增 `subagentsEnabled` 字段（默认 false），通过 `disableSubagents()` 控制是否启用子代理编排；`application.yml` 新增 `dataagent.agent.subagents-enabled` 配置项
5. **侧栏路由调整**：`SessionsSidebar.tsx` 新增 `/configure/business-terms` → `/configure/semantic` 路由映射；新建聊天跳转 `/chat` 触发落地页

## 理由与权衡

- 视觉质感：对标大厂产品，提升用户第一印象
- 功能完整：语义术语管理是问数准确性的基础
- 安全默认：子代理默认关闭，避免不必要的 token 消耗和编排复杂度
- 代价：前端 bundle 体积增加（已通过代码分割优化）

## 影响面

- 前端：`ChatPanel.tsx`（580 行变更）、`SemanticConfigPage.tsx`（新增）、`SessionsSidebar.tsx`（路由调整）、`global.css`（+140 行动画样式）
- 后端：`DataAgentConfig.java`（subagentsEnabled 字段）、`application.yml`（subagents-enabled 配置）
- 资源：`static/index.html` 更新
