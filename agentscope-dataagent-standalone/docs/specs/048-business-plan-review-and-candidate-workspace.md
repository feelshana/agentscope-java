# spec 048: 关键业务口径确认与隔离候选工程

> 2026-10-09 修订（ADR 0059）：删除首期“平台证明技术修改与已批准语义等价并复用审批覆盖”的实现要求。校验、编译和技术修复在业务审阅前完成；审阅后的应用绑定精确内容与基础版本，内容改变重新审阅。不要实现通用 SQL 等价证明。用户修改问题 SQL 不触发候选资产改写；只有独立模型修复请求才可形成诊断与变更提案。

## 背景与目标

按 ADR 0057/0058 替代逐文件审批。用户确认尚未明确的关键业务定义和变更，不逐个审查模型、关系、视图、Cube 或 SQL 定义模型的技术内容；agent 自动构建、验证和修复依赖资产。

## 方案概述

- 综合现有模型、可选文档与用户采纳的问题，规划共享资产及查询。已有资产可复用为 EXAMPLE，不每题强制创建。候选工程按 ownerId/groupId/runId 隔离，Wren YAML 为语义事实源；不修改 published。
- 依赖图节点为模型/SQL 定义模型、关系、视图、Cube 和查询，按实际依赖拓扑构建。SQL 定义模型采用安装版验证支持的 SQL/ref_sql 定义，不新增假设性格式。缺失引用、循环依赖和不支持的能力自动检查；先修复依赖，再执行查询。
- agent 经受控 write_file/patch_file/create_view 自动写候选、调用 YAML validate/context build/dry-plan/Cube 表达式检查，并最多三次技术重试。平台授予仅候选工程权限，保持白名单及保护路径；模型不能改变权限。先核对 Wren 安装版本及原生 Harness API，不重新实现 ReAct。
- 界面名称为“确认关键业务口径与变更”，默认展示对象/粒度、公式、时间、过滤、关系/归属、来源、不确定项、影响问题；技术差异按需展开。新且未审阅的重要资产业务含义、推断关系、关键定义缺失、冲突和旧口径改变必须暂停。文档明确的普通描述补充、已有资产复用无需询问；共享口径一次集中确认。
- 平台私有方案保存 planId/revision、基准工程哈希、需求/来源版本、semanticScope、requiresBusinessReview/reviewReasons、审批覆盖、文件哈希、依赖图、验证及状态。状态 DRAFT → VALIDATING → READY_FOR_REVIEW → APPROVED → APPLIED；不需人工审阅者走 VALIDATING → READY_TO_APPLY → APPLIED，记录免审依据，不伪造用户 APPROVED；失败/退回/过期/取消分别记录。
- submit_modeling_plan 提交候选应用请求；需要确认时复用原生 HITL，批准绑定具体变更和基准，通过一次业务确认原子应用草稿，不再弹第二个应用批准。无人工审阅项经服务端判定可应用；模型不能自行标记 ALLOWED/免审。文档已明确不代表新重要资产自动免审；已批准且无变化的同一方案复用批准。
- 应用前重验 scope、路径、基准与候选哈希；技术修复在业务审阅前完成，审阅后候选内容或基准变化须重新提交确认。完全相同的方案可幂等应用，不重复确认；不实现 SQL 语义等价证明，LLM 自称等价不构成授权。
- 在组工作区锁内幂等应用，采用可恢复事务日志/快照替换，读者遵守同一锁；失败恢复，无半写入。应用后由 agent 在完整草稿副本执行问题验收，无需先发布。旧会话按旧协议恢复或用户取消，不自动批准。
- Cube 记录可重放规格及生成 SQL；不以引用 base_object 冒充 Cube 查询。PK/关系/归属有证据，否则澄清；不采用任意 MIN/MAX 掩盖维度冲突。文档和对话共用方案，不再并行逐条文档采纳。

## 影响面

- 后端：ModelingToolkit、ModelingHitlMiddleware、候选/方案 store 与应用 service、DocEnhanceService、DataAgentConfig 提示词；鉴权和阻塞调度保持既有纪律。
- 前端：ModelingChatPanel、ModelingHitlCard、方案摘要/进度；协议变化前后端同步。
- 文档/数据：平台审批状态受保护；实施同步 AGENTS 写面规则和 ARCHITECTURE_zh.md，不复活结构化 agent 写工具。

## 验收标准（Given-When-Then）

1. Given 三题共享新口径，When 生成多个依赖文件，Then 只确认一份业务变更，技术详情可展开；候选未应用前草稿/published 不变。
2. Given 普通补充或未变化的已批准方案，When 技术验证通过，Then 自动应用并进入验收，无重复业务确认，无伪造审批。
3. Given 新资产首次出现、推断关系或规则范围不明，When 规划，Then 提供推荐方案并暂停确认，不擅自扩大规则。
4. Given 技术修复，When 尚未提交业务审阅，Then 自动重验后再提交最终方案；审阅后内容或基准变化则旧批准不可直接使用，完全相同方案可幂等应用。
5. Given 缺失/循环依赖或注入应用失败，When 重试/恢复，Then 不执行错误查询、不半写入、不改 published。
6. Given 其他租户/组的候选或审批 ID，When 读写/应用，Then 拒绝且无越权修改。

## 不做的事

不自动批准 HITL、不把已采纳问题当作口径批准、不把一次确认当无限文件授权、不重写 Harness 内核。

## 测试要求

候选隔离、依赖顺序、受保护路径、审批覆盖/免审依据、哈希/版本、幂等及故障恢复测试；浏览器覆盖一次口径确认与无需确认分支，技术修复和业务变化分别验证；包含多租户测试。

## 关联

[ADR 0057](../adr/0057-wren-guided-modeling-and-published-query-context.md)、[ADR 0058](../adr/0058-recommended-questions-and-draft-asset-acceptance.md)；依赖 spec 047，输出 spec 049 的完整可验收草稿。
