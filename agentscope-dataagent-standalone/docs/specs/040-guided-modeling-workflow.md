# spec 040：引导式语义建模工作台

## 背景与目标

用户接入数据后已有基础 MDL 可问数，对话建模用于完善业务口径。统一页面和助手的阶段引导，明确问题前期提出、后期验收，以及草稿与发布版本，减少技术资产导航和无效确认造成的混乱。用户已授权方案并开发。

## 方案概述

新增 ModelingWorkflowService/Controller 读取数据集、发布目录、文件变更、问题状态和平台工程校验收据，返回 stage、queryAvailable、publishedVersion、changedAssets、questionSummary、blockers、nextAction。复用所有写入、验证、确认与发布链；list_modeling_state 与 validate_mdl 同步呈现统一状态。前端持续阶段卡，主入口为数据准备/对话建模/验证与确认/发布，技术资产进入模型详情；同一批问题分别按需求或验收模式展示。发布卡展示阻断原因、业务变更摘要与技术差异。修改助手剧本和 HITL 摘要。

## 影响面

- 后端：dataset 工作流快照/工程校验收据，web 新只读接口，ModelingToolkit 与系统提示词；无 JPA 迁移、无新配置。
- 前端：SemanticModelingPage、ModelingQuestionsPanel、MdlPublishPanel、ModelingChatPanel、ModelingHitlCard，API 封装。
- 文档：README 与 ARCHITECTURE_zh.md 第 3/4/5/11 章。

## 验收标准（Given-When-Then）

1. Given 无数据或基础初始化失败，When 进入工作台，Then 数据准备引导准确，不能宣称已可问数。
2. Given 已有有效发布，When 新建语义草稿，Then 普通问数入口仍可用，明确继续使用已发布版本。
3. Given 用户提出问题，When 对话建模，Then 需求页显示同一问题，SQL 由助手负责；后期验收不重新填写，不擅自增加必验问题。
4. Given 草稿/问题未完善、未验证、待确认，When 获取工作流，Then 页面和助手返回一致下一步及具体阻断原因。
5. Given 工程校验通过，When 必验问题未确认，Then 仍引导验收；修改语义文件后工程收据失效，发布重新校验且保留旧快照。
6. Given 状态可发布，When 人员发布，Then 展示新版本和问数入口；高级资产 URL 仍可打开，普通用户无需按技术标签顺序操作。
7. Given bob 的知识库，When alice 读取工作流，Then 无法获取数据；阻塞调用使用 boundedElastic。

## 不做的事

不重建 ReAct/会话/HITL/沙箱，不引入新 UI 库，不恢复 KG 或物理 SQL 回退；不将阶段存为另一份事实源，不用模型自报“完成”代表业务已验收。

## 测试要求

JUnit 覆盖工作流阶段、工程校验指纹失效、无问题兼容、旧发布可用、租户/范围拒绝和工具引导；Spotless、Maven test/package、前端 build；独立本机验证状态与页面/API/SSE 行为，记录未执行项。

## 关联

ADR 0049；spec 039。

## 实施状态

已实施。后端阶段接口、工程指纹收据、统一工具引导、四步骤工作台、需求/验收模式、发布摘要、HITL 文案及问数口径提示已开发，并同步 README 与架构文档。436 项后端测试零失败（12 项按原有条件跳过），格式、打包和前端构建通过；真实 Wren、原生 HITL 和浏览器流程验证通过。

记录、限制与截图见 [开发验证报告](../validation/2026-10-07-guided-modeling-workflow-development.md)。
