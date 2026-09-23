# spec 007: TC 式落地页与语义配置页

## 背景与目标

复刻腾讯 TC-DataAgent 落地页视觉风格，新增语义术语配置页。

## 方案概述

1. `ChatPanel.tsx` 重构：流光输入框（`@keyframes da-flow-spin`、conic-gradient）、渐变背景、落地页
2. 新增 `SemanticConfigPage.tsx`：标签式同义词输入、分页（10/20/50）、样式化错误框
3. `global.css` 新增 `@property --da-flow-angle`、`.da-landing-wrap`、`.da-composer-glow`、`.da-composer-shell`、`.da-landing-title` 等样式（+140 行）
4. `SessionsSidebar.tsx`：新增 `/configure/business-terms` → `/configure/semantic` 路由映射；新建聊天跳转 `/chat` 触发落地页
5. `DataAgentConfig` 新增 `subagentsEnabled` 字段（默认 false），通过 `disableSubagents()` 控制子代理编排
6. `application.yml` 新增 `dataagent.agent.subagents-enabled: ${DATAAGENT_SUBAGENTS_ENABLED:false}`

## 影响面

- 前端：`ChatPanel.tsx`（580 行变更）、`SemanticConfigPage.tsx`（新增 281 行）、`SessionsSidebar.tsx`（路由调整）、`global.css`（+140 行）
- 后端：`DataAgentConfig.java`（subagentsEnabled 字段）
- 配置：`application.yml` 新增 `dataagent.agent.subagents-enabled`
- 资源：`static/index.html`

## 验收标准

1. Given 用户打开聊天页，When 查看输入框，Then 显示流光效果
2. Given 用户进入语义配置页，When 点击新增术语，Then 弹出编辑对话框
3. Given 术语已保存，When 问数时，Then 术语被用于列名映射
4. Given 子代理未启用，When 应用启动，Then `subagentsEnabled` 为 false 且子代理编排被禁用
5. Given 用户新建聊天，When 点击新建按钮，Then 跳转至 `/chat` 显示落地页

## 不做的事

- 术语自动同步到知识库（需手动触发）

## 测试要求

- 语义术语 CRUD 测试
- 术语在问数中的生效测试
- `SubagentOrchestrationSwitchTest`：subagentsEnabled 默认 false、application.yml 声明 subagents-enabled

## 关联

- ADR 0013
