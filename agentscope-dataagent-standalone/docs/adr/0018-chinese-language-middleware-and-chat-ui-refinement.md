# ADR-0018: 中文语言强制指令与聊天 UI 优化

## 状态

已接受 (2026-09-24)

## 背景

### 1. LLM 英文输出问题

AgentScope 框架层系统提示词（AgentStateStore Context、Domain Knowledge、Memory Recall、Workspace、Available Skills、Code Execution 等章节）均为英文。尽管 AGENTS.md 模板已包含简体中文指令，但 predominantly 英文的系统提示词会"启动"LLM 的英文倾向，导致工具调用过程中的中间叙述（如"I'll help you analyze…"、"I have the key definitions…"）以英文输出。

框架源码（`agentscope-harness`、`agentscope-core`）属于上游项目，不应在产品侧直接修改。

### 2. 聊天 UI 需要优化

- 执行追踪（TaskTrace）原为 TC 风格卡片式，视觉偏重，与聊天流不够融合
- 工具调用块（ToolCallBlock）信息密度过高，状态文字冗余
- Markdown 渲染缺少表格包装、代码块高度限制、引用块视觉层次
- 知识库列表缺少编辑功能（只能创建和删除）
- 导航头部（BackToChatHeader、ChatHeader）有多余的 session tag 和硬编码路由

## 决策

### 1. ChineseLanguageMiddleware（standalone 模块内解决）

在 `agentscope-dataagent-standalone` 模块新增 `ChineseLanguageMiddleware`，实现 `HarnessRuntimeMiddleware` 接口，在系统提示词**最末尾**追加中文语言强制指令：

```
## 语言规范（最高优先级）
你的所有输出必须使用简体中文，包括但不限于：
- 工具调用前的叙述与解释
- 中间推理过程的文字说明
- 最终回复的全部内容
- 图表标题、轴标签、图例
- 代码注释
```

**为什么放在最后**：LLM 对系统提示词末尾的内容有更强的注意力（recency bias），放在最后可以覆盖前面框架层英文的倾向影响。

**为什么不改框架源码**：`agentscope-harness` 是上游共享框架，产品侧修改会导致合并冲突和维护负担。中间件模式正是为此类定制设计的扩展点。

**注册顺序**：在 `DataAgentConfig` 中，`ChineseLanguageMiddleware` 注册在 `DataDynamicContextMiddleware` 之后，确保它是中间件链的最后一环。

### 2. WorkBuddy 风格内联执行追踪

将 TaskTrace 和 ToolCallBlock 从 TC 卡片式改为 WorkBuddy 内联式：

- **TaskTrace**：去掉边框和背景卡片，改为单行内联文本"状态 时间 ›"
- **ToolCallBlock**：从 `flex` 全宽卡片改为 `inline-flex` 紧凑步骤
- **状态文字简化**："任务执行完成" → "已完成"，"任务进行中…" → "执行中…"
- **去掉嵌套缩进**：ChatPanel 中子工具调用不再添加左边框和 marginLeft

### 3. Markdown 渲染增强

- **表格**：外层包裹 `claw-md-table-wrap` 容器，支持 `max-height: 480px` 滚动、圆角边框、斑马纹行
- **代码块**：添加 `max-height: 400px` 防止过长代码撑开页面
- **引用块**：紫色左边框 + 浅紫背景，视觉层次更清晰
- **标题**：字重和间距加大，颜色加深至 `#111827`
- **新增 MarkdownCards 组件**：将长文本按标题拆分为卡片式布局，支持洞察高亮

### 4. 知识库编辑功能

补全知识库 CRUD 的 Update 操作：

- **后端**：`DatasetGroupService.updateGroup()` + `DatasetGroupController` PUT `/{id}` 端点
- **前端**：卡片右下角"⋯"菜单 → "修改名称/描述"弹窗
- **API**：`datasets.ts` 新增 `updateGroup(groupId, name, description)`

### 5. 导航与头部简化

- **BackToChatHeader**：去掉硬编码的"← 返回 Chat"按钮，改为标题可点击返回（`onTitleClick` 回调）
- **ChatHeader**：移除 session tag 显示（`会话: xxx…`），简化为纯标题
- **DatasetGroupPage**：返回按钮改为"‹ 知识库名称"内联样式，与 BackToChatHeader 统一

## 影响

### 正面
1. LLM 全中文输出，用户体验一致
2. 聊天流更紧凑，执行追踪不抢占视觉焦点
3. Markdown 表格和代码块可读性显著提升
4. 知识库管理功能完整（CRUD 闭环）
5. 不修改框架源码，保持上游合并兼容性

### 负面/风险
1. 中文语言指令占用少量 token（约 150 字）
2. WorkBuddy 风格去掉了卡片边框，部分用户可能觉得步骤不够醒目
3. MarkdownCards 组件尚未在 Markdown.tsx 中集成使用（预留组件）

## 修改文件

### 后端
- `ChineseLanguageMiddleware.java`（新增）
- `DataAgentConfig.java`（注册中间件）
- `DatasetGroupService.java`（updateGroup 方法）
- `DatasetGroupController.java`（PUT 端点）

### 前端
- `TaskTrace.tsx`（内联风格简化）
- `ToolCallBlock.tsx`（紧凑步骤样式）
- `ChatPanel.tsx`（去掉嵌套缩进）
- `Markdown.tsx`（表格/代码块/引用块增强）
- `MarkdownCards.tsx`（新增，卡片式渲染）
- `global.css`（trace/toolcall/agent 样式全面调整）
- `BackToChatHeader.tsx`（可点击标题返回）
- `ChatHeader.tsx`（移除 session tag）
- `DatasetGroupPage.tsx`（返回导航优化）
- `DatasetsPage.tsx`（编辑弹窗 + 卡片悬停 + 下拉菜单）
- `datasets.ts`（updateGroup API）

### 静态资源
- `static/assets/`（前端构建产物更新）
- `static/index.html`（引用新产物文件名）
