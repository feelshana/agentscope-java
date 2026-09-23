# ADR 0015: 例行任务（Scheduled Tasks）

- 状态：已采纳
- 日期：2026-09-23

## 背景

对标 TC-DataAgent 的"例行任务"功能，让用户可以创建定时执行的问数任务。当前阶段先完成前端界面和后端 CRUD 接口，调度执行逻辑后续实现。

## 决策

1. **前端页面**：新增 `ScheduledTasksPage.tsx`，位于 `/configure/scheduled-tasks`，包含任务卡片列表、搜索、创建弹窗
2. **侧栏入口**：`SessionsSidebar.tsx` 新增"例行任务"导航项（clock 图标），位于"业务术语"下方
3. **后端 API**：新增 `ScheduledTaskController`（`/api/scheduled-tasks`），提供 CRUD + 状态切换（active/paused）接口
4. **数据模型**：新增 `ScheduledTaskEntity`（表 `dataagent_scheduled_task`），包含 title、prompt、email、knowledgeBaseId、agentId、scheduleFrequency、scheduleTime、effectiveFrom、status、runCount 等字段
5. **样式复用**：沿用 `da-*` 设计系统，卡片网格布局（min 360px），弹窗复用 `da-modal-*` 样式

## 理由与权衡

- 先 UI 后调度：让用户先看到功能全貌，调度引擎可独立迭代
- CRUD 先行：前端需要完整的数据操作能力来验证 UI 交互
- 沿用现有模式：Controller/Entity/Repository 结构与 SemanticTerm 一致，降低认知成本
- 代价：暂时无实际调度能力，需后续补充 Scheduler 实现

## 影响面

- 前端：`ScheduledTasksPage.tsx`（新增）、`SessionsSidebar.tsx`（+1 导航项）、`Icon.tsx`（+clock 图标）、`main.tsx`（+1 路由）、`api/scheduledTasks.ts`（新增）
- 后端：`ScheduledTaskController.java`（新增）、`ScheduledTaskEntity.java`（新增）、`ScheduledTaskRepository.java`（新增）
- 数据：`dataagent_scheduled_task` 表（JPA 自动建表）
