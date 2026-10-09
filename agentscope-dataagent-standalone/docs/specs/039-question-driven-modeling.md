# spec 039：对话建模的常用问题与业务验收闭环

## 背景与目标

用户通过对话完善 Wren MDL。建模人员补充常问问题，助手澄清指标/粒度/单位/时间/过滤，用实际 Wren 查询验证，再由人员确认；可信示例发布后用于问数。用户已授权方案及开发。

## 方案概述

- MdlQuestionStore：读取 knowledge/questions/*.yml，管理 .platform/questions 执行凭据、指纹失效、官方 knowledge/sql 确认示例与发布门。
- MdlQuestionService + ModelingQuestionController：ownerId/AgentAccessGuard 双重鉴权，草稿副本查询、结果审阅与确认/退回 API，阻塞操作 boundedElastic。
- ModelingToolkit：只读 list_modeling_questions、validate_modeling_question；需求写入继续走通用 HITL 文件工具。提示词先收集问题，收尾强调执行与业务确认。
- MdlPublishService/WrenQueryGateway：复用发布准备和连接选择，临时 MCP 隔离验证，required 问题未有效确认不能发布，旧发布保留。
- WrenToolkit：按需读取发布快照的可信问题示例，提供 View 定义和列线索，输出模型版本；不直接返回示例旧数值。
- 前端常用问题页签：展示问题/定义/实际结果/SQL/状态，验证、确认、退回、对话完善与阶段指引，复用现有组件和 SSE。
- 可靠性修复：relationships: [] 首次合并及非法 YAML 保护；预览平台路径保护；前导零字段保留 VARCHAR。

## 影响面

后端 dataset/tools/data/runtime/wren/web；前端 api/semanticModeling 与建模工作台；无 JPA 表、无新配置；更新架构第 3/4/5/11 章和用户说明。

## 验收标准

1. Given 已绑定知识库，When 对话收集问题，Then HITL 写入问题文件、页面与下一轮状态可读取；定义不明确时澄清，不假定净/毛额。
2. Given 问题有逻辑 SQL，When 验证，Then 草稿经 Wren 实际查询并保存表格、指纹和执行 ID，已发布模型不改变；执行失败或截断不允许确认。
3. Given 当前成功执行，When 人员确认/退回，Then 状态与可信示例同步；改问题/模型后状态过期；伪造平台凭据和直接写可信示例被拒绝。
4. Given 存在 required 问题，When 发布，Then 全部当前确认后成功；否则返回具体问题并保留旧快照；没有问题兼容现状。
5. Given 已发布确认示例，When 问数按需检索，Then 仅返回作用域内的发布示例和口径，不读取草稿、不复用旧结果。
6. Given 空关系数组批量确认两条，Then YAML 可解析且两条均保留；非法旧文件拒绝覆写；000123 导入推断字符串；有效平台文件预览拒绝。

## 测试要求

JUnit 覆盖租户/范围、验证凭据与失效、确认发布门、可信示例、关系和类型推断；Maven test/package、Spotless、前端 build；本机隔离测试知识库端到端验证新工具/API，记录未执行项。

## 边界

不重建 ReAct/沙箱/会话路由，不恢复 KG 或物理直查；不强制用户填写 20 个问题，不假定 SQL 执行成功等于业务正确，不声称已实现官方同模型对照准确率。

关联：ADR 0048。

## 实施状态

2026-10-07 已完成上述后端、前端、发布门和可靠性修复。426 条后端测试无失败（12 条按原条件跳过），格式检查、打包与前端构建通过。独立 8085 服务完成对话 HITL 写入、真实 Wren 执行、确认与发布、越权/伪造拒绝及问数召回后重新查询。详情与未执行项见 [开发验证报告](../validation/2026-10-07-question-driven-modeling-development.md)。
