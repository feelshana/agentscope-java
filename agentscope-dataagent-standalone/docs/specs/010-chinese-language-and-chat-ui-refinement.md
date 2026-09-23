# spec 010: 中文语言强制与聊天 UI 优化

## 背景与目标

1. **LLM 英文输出问题**：框架层系统提示词（AgentStateStore Context、Domain Knowledge、Workspace、Available Skills 等）均为英文，导致 LLM 在工具调用过程中用英文叙述（如"I'll help you analyze…"）。需要在不修改框架源码的前提下强制中文输出。
2. **聊天 UI 优化**：执行追踪和工具调用块视觉偏重，Markdown 渲染缺少表格滚动和代码块高度限制，知识库列表缺少编辑功能，导航头部有多余元素。

## 方案概述

### 1. ChineseLanguageMiddleware

- **位置**：`agentscope-dataagent-standalone` 模块（不动框架源码）
- **实现**：`ChineseLanguageMiddleware implements HarnessRuntimeMiddleware`
- **机制**：在 `onSystemPrompt()` 中将中文语言指令追加到系统提示词**最末尾**
- **注册**：`DataAgentConfig` 中在 `DataDynamicContextMiddleware` 之后注册，确保是中间件链最后一环
- **指令内容**：要求所有输出（工具叙述、推理说明、最终回复、图表标签、代码注释）使用简体中文

### 2. WorkBuddy 风格执行追踪

- **TaskTrace**：去掉边框/背景卡片 → 单行内联"状态 时间 ›"
- **ToolCallBlock**：`flex` 全宽卡片 → `inline-flex` 紧凑步骤
- **状态文字简化**："任务执行完成" → "已完成"，"任务进行中…" → "执行中…"
- **ChatPanel**：去掉子工具调用的 `marginLeft: 16 + borderLeft`

### 3. Markdown 渲染增强

- **表格**：外层 `claw-md-table-wrap` 容器，`max-height: 480px` 滚动，圆角边框，斑马纹行
- **代码块**：`max-height: 400px` 防止过长代码撑开页面
- **引用块**：紫色左边框（`#6366f1`）+ 浅紫背景（`#f5f3ff`）
- **标题**：字重 700，颜色 `#111827`，间距加大
- **MarkdownCards**（新增预留组件）：按标题拆分长文本为卡片布局

### 4. 知识库编辑功能

- **后端**：`DatasetGroupService.updateGroup()` + `DatasetGroupController` PUT `/{id}`
- **前端**：卡片右下角"⋯"三点菜单 → "修改名称/描述"弹窗
- **API**：`datasets.ts` 新增 `updateGroup(groupId, name, description)`

### 5. 导航简化

- **BackToChatHeader**：去掉"← 返回 Chat"按钮，改为标题可点击返回（`onTitleClick` 回调）
- **ChatHeader**：移除 session tag（`会话: xxx…`）
- **DatasetGroupPage**：返回按钮改为"‹ 知识库名称"内联样式

## 影响面

- 后端：`ChineseLanguageMiddleware.java`（新增）、`DataAgentConfig.java`（+1 中间件注册）、`DatasetGroupService.java`（+updateGroup）、`DatasetGroupController.java`（+PUT 端点）
- 前端：`TaskTrace.tsx`、`ToolCallBlock.tsx`、`ChatPanel.tsx`、`Markdown.tsx`、`MarkdownCards.tsx`（新增）、`global.css`、`BackToChatHeader.tsx`、`ChatHeader.tsx`、`DatasetGroupPage.tsx`、`DatasetsPage.tsx`、`datasets.ts`
- 数据：无变化
- 文档：ADR-0018、spec-010

## 验收标准

1. Given 用户发送中文问题，When LLM 调用工具并回复，Then 所有叙述和回复均为简体中文
2. Given 工具调用过程中，When 查看聊天流，Then 执行追踪为内联紧凑样式，无卡片边框
3. Given LLM 回复包含表格，When 表格行数较多，Then 表格在 480px 高度内滚动，不撑开页面
4. Given 知识库列表中存在知识库卡片，When 点击"⋯"菜单 → "修改名称/描述"，Then 弹窗可编辑名称和描述并保存成功
5. Given 用户在知识库详情页，When 点击顶部"‹ 知识库名称"，Then 返回知识库列表

## 不做的事

- 不修改 `agentscope-harness` 或 `agentscope-core` 框架源码
- MarkdownCards 组件暂不集成到 Markdown.tsx（预留）
- 不做知识库批量编辑/拖拽排序
- 不做执行追踪的自定义折叠策略设置

## 测试要求

- 中文语言中间件：验证系统提示词末尾包含语言指令
- 知识库编辑：PUT /{id} 接口测试（名称/描述更新、权限校验）
- 前端：Markdown 表格滚动、代码块高度限制、编辑弹窗交互

## 关联

- ADR-0018
