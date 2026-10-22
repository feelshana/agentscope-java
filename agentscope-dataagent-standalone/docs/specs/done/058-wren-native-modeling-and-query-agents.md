# spec 058：官方 Wren 建模与问数的通用 Agent 集成

状态：用户已确认，实施中。详细任务和阶段验收见 [实施计划](../../archive/plans/058-wren-native-modeling-and-query-agents.md)。

## 背景与目标

建模已使用官方技能，但平台仍禁止 Cube；问数仍由平台 SQL 流程及旧工具包装主导。统一为 AgentScope 通用执行器 + 同版本官方 Wren skills/CLI/MCP/MDL/memory，去除项目本体和领域决策流程；降低额外确认并以真实结果验证准确性。

## 方案概述

上传/关联数据 → 基本 Wren 模型 → 可选文档与问题 → 官方技能增强与澄清 → Agent 自动检查/构建/试问 → 显式发布 → 官方 usage/MCP 问数 → Python/图表 → 最终答案反馈。普通模型、ref_sql、关系、视图、Cube 均按官方契约支持，问题无需必填；官方 build 不自动发布。

工具适配只做当前 ownerId/groupId 工程授权，发现并透传官方 MCP schema/arguments；取消平台业务 SQL 剧本、Cube 路由和官方 MDL 二次改写。官方 memory 统一读取已发布问题示例及点赞样例，实测向量中文召回。保留只读查询、strict_mode、权限隔离、显式发布和点赞门槛。

## 影响面

- 后端：DataAgentConfig、ModelingToolkitRegistrar、DataToolkitRegistrar、OfficialWrenToolkit、WrenProjectAccess/Filesystem、ModelingHitlMiddleware、MdlWorkspaceService/Reader、MdlPublishService、WrenInstanceRegistry、AnswerQueryMemory/SqlExampleRecall；新通用 MCP 工程适配。
- 前端：SemanticModelingPage、ModelingChatPanel/HitlCard、BusinessDocumentGuide、问题表格、MDL 展示、发布指引及答案反馈；移除主要流程中的遗留推断/本体入口。
- 数据：保留元数据实体，不迁移旧业务资产；官方编译 MDL 与 knowledge 为线上事实，目录和索引为衍生产物。
- 文档：实施时同步 ARCHITECTURE_zh.md 第 2—5/11—13 章、README、AGENTS.md、Wren 依赖说明及共享记忆。

## 验收标准

1. Given 无预设问题的上传数据，When 完成基本建模与检查，Then 可以发布和问数；上传文档后明确显示保存成功，增强依官方技能执行。
2. Given 已授权的模型/视图/Cube 工程，When Agent 修改并调用官方工具，Then 平台不再禁止 Cube 或重复业务审阅；参数、构建 MDL、返回结果与直接官方调用一致。
3. Given 已发布版本和未发布草稿，When 问数或发布失败，Then 问数只用线上模型/知识且保留旧版；另一 owner 的 project/profile/样例不可读取或修改。
4. Given 跨月重复及内部客户，When 查询全年付费客户与各月客户，Then SQL/Cube 分别与 ground truth 一致，全年不累加月度 DISTINCT；歧义不能擅自确定。
5. Given 最终答案包含业务/诊断/失败查询，When 点赞并换会话中文改写提问，Then 只保存成功采用的业务样例，通过官方召回命中并重新执行；点踩不自动改写资产。
6. Given 实际模型请求和页面，When 验收，Then 工具面为官方 CLI/MCP 与通用工具，主链路无旧平台领域工具，模型/关系/视图/Cube/SQL 与发布状态可查看。

## 不做的事

不部署另一套本体/KG、不复制 Wren 语义算法、不直查物理表回退、不将所有问题转换为 Cube、不强制填写/验收问题、不处理旧数据、不新增资产评分与样例可信等级；不承诺接入即达到官方产品准确率。

## 测试要求

补充路径/命令/租户隔离、HITL 批次错误隔离、官方产物不改写、发布失败与 memory 回执测试；运行真实 CLI/MCP 和向量召回。准备至少 30 个固定问题及标准 SQL/结果，对照直接官方工具、当前基线与新 Agent，并完成前端构建和浏览器关键路径验收。

## 关联

- [ADR 0069](../../adr/0069-wren-native-modeling-and-query-agents.md)
- [既有 spec 057](057-wren-official-capability-boundary.md)：保留其通用建模基础，不重做已经实现的部分。
