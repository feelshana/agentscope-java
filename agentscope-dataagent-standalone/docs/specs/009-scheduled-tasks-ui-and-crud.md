# spec 009: 例行任务（Scheduled Tasks）

## 背景与目标

对标 TC-DataAgent 的"例行任务"功能，允许用户创建定时执行的问数任务。当前阶段完成前端界面和后端 CRUD 接口，调度执行逻辑后续实现。

## 方案概述

1. **侧栏导航**：`SessionsSidebar.tsx` 新增"例行任务"入口（clock 图标），路径 `/configure/scheduled-tasks`
2. **前端页面**：`ScheduledTasksPage.tsx` — 任务卡片列表 + 搜索 + 创建弹窗
   - 卡片展示：标题、频率标签（每日·00:00）、提示词摘要、创建人/日期/执行次数
   - 操作：暂停/恢复、删除、更多菜单
   - 创建弹窗字段：任务标题*、提示词*（5000 字限制）、邮箱推送地址、知识库下拉、Agent 下拉、执行时间（频率+时间）、生效时间
3. **前端 API**：`api/scheduledTasks.ts` — list/create/update/status/delete
4. **后端 Controller**：`ScheduledTaskController`（`/api/scheduled-tasks`）
   - GET `/` — 列表
   - POST `/` — 创建
   - PUT `/{id}` — 更新
   - PUT `/{id}/status` — 状态切换（active/paused）
   - DELETE `/{id}` — 删除
5. **数据模型**：`ScheduledTaskEntity`（表 `dataagent_scheduled_task`）

## 影响面

- 前端：`ScheduledTasksPage.tsx`（新增 ~400 行）、`SessionsSidebar.tsx`（+1 项）、`Icon.tsx`（+clock）、`main.tsx`（+1 路由）、`api/scheduledTasks.ts`（新增）
- 后端：`ScheduledTaskController.java`（新增）、`ScheduledTaskEntity.java`（新增）、`ScheduledTaskRepository.java`（新增）
- 数据：`dataagent_scheduled_task` 表（JPA 自动建表）
- 文档：ADR 0015、spec 009

## 验收标准

1. Given 用户点击侧栏"例行任务"，When 页面加载，Then 显示任务列表（空态或已有任务卡片）
2. Given 用户点击"创建定时任务"，When 填写标题和提示词并保存，Then 新卡片出现在列表中
3. Given 任务状态为 active，When 点击暂停按钮，Then 状态变为 paused 且按钮文案切换
4. Given 任务卡片存在，When 点击删除，Then 卡片从列表消失
5. Given 后端 API 可用，When 调用 GET /api/scheduled-tasks，Then 返回任务列表 JSON

## 不做的事

- 调度执行引擎（Scheduler / Cron 解析 / 定时触发）
- 任务执行历史记录
- 执行结果推送（邮件/消息）
- 知识库/Agent 下拉的动态数据加载（当前为静态占位）

## 测试要求

- 后端 CRUD 接口测试（创建/列表/更新/删除/状态切换）
- 前端页面渲染测试（空态、列表态、弹窗交互）

## 关联

- ADR 0015
