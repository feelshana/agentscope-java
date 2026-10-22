# spec 056：统一问数规则与点赞业务样例

## 背景与目标

所有用户使用同一问数 Agent。旧工作区规则未更新、成功业务 SQL 因遗漏声明而漏存、自研召回重复。本轮按已确认方案开发，不处理历史数据、不实现撤回。

## 方案概述

主问数 Agent 使用原生 sysPrompt / disableWorkspaceContext，动态知识库与技能保留。wren_run_sql 必填 query_type（BUSINESS/DIAGNOSTIC）及中文 question，成功回执绑定执行参数和类型；移除声明工具。点赞从服务端本轮成功历史取全部 BUSINESS，调用官方 memory store，以 question 为 NL 并保留实际 LIMIT。点踩只记录反馈。使用独立新版本反馈及 owner/group 记忆目录，不迁移旧状态。召回使用官方 recall，不再评分或降级；已发布建模确认示例仍由 CLI 同步。准确显示后端、空库、无匹配和错误。同步架构第 3/4/11/12 章相关描述、README 和问数技能。

## 影响面

- 后端：Agent 装配、WrenToolkit、AnswerQueryMemory、CLI 结果适配。
- 前端：AnswerFeedback、ChatPanel 声明分支，反馈 API 字段形状不变。
- 数据：新版本本地文件，无 JPA 表及历史迁移。
- 文档：ADR 0064—0067、架构、README、共享记忆。

## 验收标准

1. Given 旧工作区，When 重启装配，Then 平台提示词直接生效，旧业务规则不注入。
2. Given 成功中文 BUSINESS，When 点赞，Then 无须声明保存 question、SQL、limit，多条分别保存。
3. Given 诊断、失败、缺类型、非中文/空问题、越权，When 查询/点赞，Then 不存不合格样例。
4. Given 重复点赞、存储失败重试、点赞后点踩，When 反馈，Then 幂等、可重试且不撤回。
5. Given 不同用户/知识库，When store/recall，Then 工程与索引隔离，无跨库召回。
6. Given 空库、官方无结果、CLI 失败，When recall，Then 准确区分状态，不执行自研检索，结果保持官方顺序。

## 不做的事

历史处理、撤回、自定义提示词、自研检索、管理页面和自动反馈。

## 测试要求

JUnit 覆盖装配、工具参数/回执、服务端历史筛选、隔离、反馈及 CLI 响应；固定安装版实测中文 store/recall。standalone 使用根 mvn test、mvn package -DskipTests、frontend npm run build、Spotless，不使用不存在的多模块 -pl。

## 关联

ADR 0064、0065、0066、0067，用户已明确确认方案并授权先文档后开发。
