# spec 045：先提交问题，再对话建模，统一结果确认

## 背景与目标

最新 LLM-modeling.log 的「测试123」提交两个分析问题，助手登记 required:false、空 SQL，工具与页面仍引导设置必验，允许未完成需求发布。问题表单与聊天同时出现，用户不清楚输入入口。取消必验/可选分级，以用户提出的问题作为建模和验收主线。

## 方案概述

新知识库没有问题且没有原生待确认提案时，主区域展示业务文档栏及批量问题表单，隐藏聊天。用户显式提交后直接发送到现有 modeling-agent SSE 会话并展示聊天，不再要求带入对话后重复发送。登记同一批 YAML 问题，经原生 HITL 保存；有歧义才对话澄清，随后复用或构建符合口径的 View/Cube，生成每题 SQL、经 Wren 验证并由用户确认结果。已有问题或待处理提案直接恢复对话。追加问题统一从问题清单表单提交，聊天输入只用于澄清与反馈。

MdlQuestionStore.Question 与 workflow 摘要移除 required 字段，读取历史 required 值但忽略，所有未归档问题均纳入确认与发布门禁。旧确认指纹兼容原 required=true/false 序列化，无业务变化时不强制重验。基础自动模型仍可问数，无问题时兼容基础发布；已提交问题不能靠切换标志绕过验证。保持空 SQL 草稿以等待澄清，但不能将其作为完成或可发布。

## 影响面

- 后端：MdlQuestionStore、ModelingWorkflowService、ModelingToolkit、DataAgentConfig；问题与阶段 API 同步；无数据库迁移。
- 前端：SemanticModelingPage、ModelingChatPanel、问题表单/弹窗、ModelingQuestionsPanel、ModelingWorkflowGuide、MdlPublishPanel、API 类型。
- 文档：ADR 0055、ARCHITECTURE_zh.md 第 11 章与问题验收描述。

## 验收标准

1. 新建模场景可填写多个问题，初始看不到聊天输入；提交一次后出现对话并只发送一次，未提交不写入。
2. 有已保存问题或待确认原生调用时恢复对话，不要求重复录入；切换组不带入上一组问题或提交动作。
3. 聊天不再包含批量添加入口，新增问题统一从问题清单；提交后直接交给同一会话。
4. 页面、提示词、工具返回没有必验/可选分类；每个有效问题均需口径、SQL、Wren 完整结果与有效人工确认，缺一不可发布。
5. 历史 required:false 的问题也阻断未确认发布；原有效确认兼容旧指纹，模型或 SQL 改变仍过期。
6. 助手先登记全部问题，基于文档和用户确认的口径复用/构建视图或 Cube，再生成 SQL 与验证，不以空 SQL 草稿结束本轮且没有下一步解释。
7. SQL/视图依据与确认成功反馈沿用 spec 044；未确认、截断、失败和过期不可发布，归档问题不计入。

## 测试要求

更新问题、阶段、发布相关测试覆盖历史 false、全部确认与旧指纹兼容；保持租户隔离用例。构建全通过，浏览器核对初始表单、单次提交、追加入口、原生恢复、统一门禁与确认反馈。

## 不做的事

不绕过 YAML-first/HITL，不重写 Agent 循环，不生成未经用户澄清的假 SQL，不自动确认或发布，不要求每题创建重复资产。

## 关联

- ADR 0055-question-first-unified-acceptance.md
- spec 039–044
