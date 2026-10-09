# spec 050: 已发布上下文、示例召回与 Cube 问数

> 2026-10-09 修订（ADR 0059）：问数中的 SQL 编辑只修正该答案或问题实例；Cube 使用结构化成员/参数，不能把查询 Cube 错当成自由 SQL。共享语义调整必须进入独立模型修复与发布流程。准确性建设优先提供模型版本、采用规则、查询与执行结果的可追溯证据，以及确认问题的回归验证；召回示例是上下文参考，不是结果正确性保证。

## 背景与目标

让建模成果准确进入问数：消除工作区规则和已发布 MDL 混用，改善中文示例召回，验证 agent 实际使用合适语义资产。保持平台 agent 推理 + Wren 语义执行 + Python 分析的分工。

## 方案概述

- ADR 0058 补充：同一发布版本包含问题查询的完整依赖闭包（模型、SQL 定义模型、视图、Cube、规则、确认示例）；正式问数读取 published，建模验收读取 draft，两者在网关与上下文类型明确区分。新示例不得指向未随版本发布的资产；缺依赖拒绝发布，不能退回物理查询。

- 单次用户问数开始时固定各知识库 publishedVersion；模型目录、字段、规则、示例、检索索引及执行均经同一版本句柄获取，工具调用与 Python 后续分析关联该版本。新发布不改变当前回答；新一轮刷新版本。多知识库分别标识、不得混用同名对象，版本文件在仍被会话引用时保留。
- 改造 DatasetService#semanticBusinessRulesText、MdlWorkspaceReader 与 DataDynamicContextMiddleware，问数只读发布快照；建模继续读取草稿/候选。新建模文档和规则在发布前不能进入问数上下文。
- WrenToolkit#wrenRecallExamples 复用能力探测后的官方 memory recall，限定版本私有索引。未安装语义后端使用中文词法分词/字符片段的确定性相关度，并在词法通道排除零匹配；前 3 条、阈值、后端类型与匹配依据可追溯。相似度阈值按各后端标定，旧 contains 排序不保留为无阈值兜底。低于阈值返回无命中；候选必须是同租户/组/版本确认示例，SQL 仍重新执行。
- 聚合问题优先描述与选择能完整覆盖度量、维度、时间、过滤的 Cube，使用 wren_query_cube；否则选择匹配 View，再生成已发布模型的逻辑 SELECT/WITH。复杂逻辑先 dry-plan，校验展开引用及关联，再执行。保持既有 SQL/权限/行数限制，不提供物理直查。
- 回答展示必要的口径、逻辑 SQL/查询路径、已发布版本和数据限制。Python 仅处理 Wren 返回数据；截断结果不能被宣称全量统计。示例及文档均是上下文，不能覆盖权限和工具约束。
- 用户“结果不对”可生成改进建议卡，含问题、SQL、版本、反馈与执行证据；用户点击加入问题表格后进入下一轮建模。查询成功记录执行历史，但不自动加入受信示例；人工确认的示例通过发布生效。
- 实施前记录本机 Wren 版本、memory 后端、Cube/dry-plan 能力，复用安装版官方 workflow。新增可选能力失败有明确状态；不直接升级 Wren、不更换 LLM，也不把引擎执行描述为官方 AI 服务 Text2SQL。

## 影响面

- 后端：WrenToolkit、MdlCatalog/WorkspaceReader、DatasetService、DataDynamicContextMiddleware、会话版本句柄、Wren gateway/索引调用。
- 前端：问数依据与改进建议、既有 API；协议增加版本证据时同步 SSE 类型。
- 数据/文档：版本私有索引与查询历史分开；实施同步 ARCHITECTURE_zh.md 问数/动态上下文章节，新增配置再同步 README。

## 验收标准（Given-When-Then）

1. Given 草稿新增规则未发布，When 问数，Then 查询与提示词均只使用旧发布版本；发布后新一轮才可见。
2. Given 回答进行中发布新版本，When 连续工具调用，Then 全部使用固定版本且不混用。
3. Given 中文同义改写和完全无关问题，When 召回，Then 前者可命中合适示例，后者返回无命中；不泄漏其他租户。
4. Given Cube 完整覆盖与缺维度两种问题，When 问数，Then 分别使用 Cube 与合适逻辑 SQL，记录实际执行路径。
5. Given 查询失败/用户否认/未确认，When 检查受信示例，Then 不新增；成功查询重放也不复用旧结果。
6. Given 返回截断数据，When Python 分析，Then 答案标注范围且不夸大为全量。

## 不做的事

不在问数中修改已发布模型、不新增 KG 或物理 SQL 回退、不保证仅增加向量召回就能保证准确。

## 测试要求

版本固定、草稿隔离、索引权限、中文召回/无命中、Cube 路由、Python 数据范围用例；与旧实现用相同夹具/问题比较执行结果和召回相关性，记录能力版本及差距。

新增集成检查：草稿验收成功但未发布时正式问数不召回新示例；整体发布后通过真实 Cube/View/SQL 定义模型路径重新查询，结果依据与版本一致。

## 关联

[ADR 0057](../adr/0057-wren-guided-modeling-and-published-query-context.md)；依赖 spec 049 的完整版本发布；实施顺序 047 → 048 → 049 → 050。

补充决策：[ADR 0058](../adr/0058-recommended-questions-and-draft-asset-acceptance.md)。
