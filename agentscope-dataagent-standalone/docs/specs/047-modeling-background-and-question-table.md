# spec 047: 建模业务背景与批量问题表格

> 2026-10-09 补充（ADR 0059）：问题不强制产生 Cube/视图；推荐问题仅在用户采纳后进入验收。现有表单的可增删行、粘贴、去重已由 specs/051 实施，沿用当前 50 题和 1000 字限制；本条的推荐服务、直接批量持久化 API 与候选工程集成仍属后续能力，不把向 agent 提交消息当作已经保存。

## 背景与目标

落实已确认的 ADR 0057。用户完成数据接入和基础模型后，在同一页面上传文档、通过可增删行表格提交问题；消除聊天登记与表单提交重复确认。

## 方案概述

- ADR 0058 补充：agent 根据现有模型和可选文档提供推荐问题，附来源/分析价值/缺失口径。用户可选择、改写、增加，采纳后进入同一表格；未采纳不保存为正式需求、不触发资产创建、不阻塞发布。推荐列表不展示聊天输入框。
- 推荐接口：POST /api/dataset-groups/{groupId}/modeling/question-recommendations，绑定 baseRevision/documentIds，返回 recommendationId、question、reason、sources、openDefinitions；复用建模权限及 owner 校验。采纳提交携带可选 recommendationId/sourceRevision，保留推荐原文与用户最终文本；过期推荐提示重新生成，不静默替换用户内容。

- 页面呈现数据与基础模型、业务背景、agent 建模、验收与发布、问数五阶段。workflow 是状态事实源，显示主操作、阻塞原因与已发布版本。首次接入自动生成、校验并发布基础模型；已有业务版本新增数据只进入草稿。基础模型可问数，关联数据资产不自动建立 JOIN。
- 业务背景页显著展示文档上传和问题表格，默认隐藏聊天。文档状态依次为上传中、待解析、解析成功/失败、已用于方案；解析失败可重试。文档可以跳过，问数生效要经过方案应用和发布。
- 表格默认三行，支持增加/删除、编辑、多行粘贴。列为序号、分析问题（必填）、口径备注（选填）、预期结果（选填）。不要求 SQL 或资产分类；空行忽略、非空无问题行报错；每批最多 100 条，问题最多 2000 字，两个备注各最多 4000 字。
- 新增鉴权的需求保存接口：POST /api/dataset-groups/{groupId}/modeling/question-requests/batch，入参 requestId、baseRevision、rows[{clientRowId,id?,question,notes?,expectedResult?}]，返回逐行 ID/状态、重复提示与 revision。同 requestId 同内容重放不重复写入，不同内容拒绝；revision 冲突返回 409。精确重复提示复用，近似问题不自动合并；批次校验失败不部分保存。
- 原始需求经服务端直接保存为工作区 YAML（复用问题目录及稳定 ID），增加 request 信息并兼容旧 question/definition/sql/modeling。agent 生成前允许没有 modeling/SQL，标记待处理；不能进入验收或示例。agent 后续生成必须满足声明要求。移除走显式归档操作，不以漏传一行隐式删除。
- 点击“开始建模”后展示对话；新增问题始终从同一表格进入。对话中的新需求通过“加入问题表格”显式保存。常驻“查看语义模型”，展示草稿/已发布模型、关系、Cube、视图、MDL。

## 影响面

- 后端：问题 controller/service、MdlQuestionStore、ModelingWorkflowService；新增端点经 principal → AgentAccessGuard → DatasetScope/owner 校验，阻塞操作 boundedElastic。
- 前端：SemanticModelingPage、ModelingQuestionsPanel、modelingQuestions API；复用既有组件，保持 TS 严格类型。
- 数据/文档：不新增物理数据表；平台幂等/版本信息与用户需求分离。实施同步 ARCHITECTURE_zh.md 建模流程章节。

## 验收标准（Given-When-Then）

1. Given 基础模型已发布，When 打开业务背景，Then 能直接填写三行、增删行、上传文档，且聊天不显示。
2. Given 三个有效问题，When 一次保存及相同请求重放，Then 只生成三个稳定需求、无 HITL、无重复聊天登记。
3. Given 行校验失败或 revision 冲突，When 提交，Then 显示对应原因且其他需求不被部分覆盖。
4. Given 文档解析失败或未提供文档，When 继续，Then 能按问题建模；两个输入均为空时可基础问数。
5. Given 已发布业务版本，When 关联新数据，Then 已发布问数仍使用旧版本。
6. Given 其他用户知识库，When 保存、读取或归档需求，Then 拒绝且无文件变化。

## 不做的事

不自动合并近似问题、不让 LLM 伪造预期结果、不绕过 agent 生成内容的方案审批。

补充验收：Given 推荐列表，When 选择两题、改写一题并新增一题，Then 表格保存用户最终文本和可追溯来源，未采纳项不成为发布阻塞；模型/文档变化后推荐显示过期，不能混用其他租户上下文。

## 测试要求

需求保存/幂等/并发/归档及旧 YAML 兼容测试；浏览器覆盖增行、删除、粘贴、刷新恢复与文档状态；包含租户隔离测试。

## 关联

[ADR 0057](../adr/0057-wren-guided-modeling-and-published-query-context.md)；下一步 [spec 048](048-business-plan-review-and-candidate-workspace.md)。

补充决策：[ADR 0058](../adr/0058-recommended-questions-and-draft-asset-acceptance.md)。
