# spec 021: 建模对话任务行化（Claude Code 式过程呈现）

> 状态：已实施（2026-10-04）。用户需求：语义建模过程参考 Qoder / Claude Code——只显示各任务
> （执行中/完成/失败），详情默认不打印、点击可查。

## 背景与目标

对话建模抽屉（`ModelingChatPanel`）当前把一轮助手回复渲染为「顶部一列工具折叠行 + 底部一大段
文本」：工具行显示生硬工具名（`write_file` / `wren_context_show`）与 120 字符结果摘要，输入详情
不可见；叙述与工具调用时序错乱（实际交替执行，渲染时工具堆在文本前）；建模助手在对话流中大段
复述草案与预检细节（草案全文已在 write_file 参数与 HITL 卡 diff 中）。目标：对齐编程工具的过程
呈现——每个工具调用一行「状态 + 中文任务标签」，详情默认收起、点击展开；叙述一句话化。

## 方案概述

1. **渲染模型重构（前端）**：`PanelMessage` 从「tools 数组 + 单一 text」改为有序 segments
   （text 段与 tool 段交替），applyEvent 按事件到达顺序追加/更新，修正时序。
2. **任务行组件**：每工具一行——执行中 `da-spinner`、完成 ✓（check）、失败 ✗（warn）、HITL
   等待「待确认」（`hitl_request.call.id` 与 `toolCallId` 同源配对）；点击行展开完整 input
   （pretty JSON，截断防炸）与 result 原文（截断），默认全部收起。
3. **taskLabel 映射**：前端把工具名+参数翻译为中文任务标签——`write_file` →「写入
   `<path>`」、`list_modeling_state` →「盘点建模现状」、`validate_mdl` →「校验工程文件」等；
   path 从 toolInput JSON 解析。
4. **过程汇报纪律（后端 MODELING_SCRIPT）**：每次调工具前最多一句话说明动作；禁止在对话中粘贴
   草案全文/预检报告/issue 清单（HITL 卡与任务行已承载）；完整方案说明只在最终答复。五拍第②拍
   改为「草案直接作为工具参数提交，不在对话中粘贴全文」。
5. **后端 SSE 零改动**：tool_call/tool_result 帧继续全文透传（前端展开详情有数据），不触发
   前后端协议同步；`ChatPanel`（问数面板）不动。

## 影响面

- 前端：`ModelingChatPanel.tsx`（渲染模型与任务行重构）
- 后端：`DataAgentConfig#MODELING_SCRIPT`（提示词纪律段）
- 文档：`ARCHITECTURE_zh.md` 建模章节、specs/021

## 验收标准（Given-When-Then）

1. Given 一轮助手回复含多个工具调用与多段叙述，When SSE 流式渲染，Then 任务行与叙述按实际到达
   时序交替显示。
2. Given 工具执行中，When tool_call 帧到达，Then 行显示 spinner 与中文标签（如「写入
   views/用户活跃度分层/sql.yml」）。
3. Given 工具完成或失败，Then ✓/✗ 状态落定；失败行展开可见错误全文。
4. Given write_file 触发 HITL，When hitl_request 帧到达，Then 对应任务行显示「待确认」，确认
   提交后回到执行中→完成。
5. Given 任意任务行，When 点击，Then 展开完整 input/result 详情（等宽小字、超长截断），再点
   收起；初始全部收起，详情不进对话流。
6. Given 过程汇报纪律生效，Then 建模助手调工具前只有一句话动作说明，对话流不再出现草案全文与
   预检报告复述。

## 不做的事（明确排除项）

- 后端 SSE 帧结构与问数面板 `ChatPanel` / `ToolCallBlock` 不动。
- 不做任务列表的会话历史恢复（`ModelingChatPanel` 本就不加载历史消息，仅恢复 pending HITL，
  无此需求）。
- 不引入新 UI 依赖（继续 inline style + 既有 global.css 动画类）。
- 前端无单测基建，验证以 `npm run build`（TS 编译）与人工验收为准。

## 测试要求

- `npm run build` 通过（类型安全）。
- 人工验收：建模抽屉发起一轮含写文件的操作，核对任务行状态流转与详情展开。

## 关联

- specs/013/017/019（对话建模链路）、ADR 0024/0031/0033
- 2026-10-04 LLM-modeling.log 过程冗长观感诊断
