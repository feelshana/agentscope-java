# spec095：业务术语改为知识库工程内的官方 Naming conventions

> 状态：已实施，待真实验收。关联 ADR0115。取代 spec026 的平台术语表做法。

## 背景与目标

"业务术语"页面参照腾讯 TC-DataAgent 设计，术语存平台表 `semantic_term`。现在问数走 Wren 链路，术语只在已停用的旧链路注入，建模助手也没有术语工具，页面配置的术语对问数和建模都不起作用。Wren 官方没有独立术语库：同义词和业务名词写在工程 `knowledge/rules/` 的 `## Naming conventions` 小节（enrich-context 缺口清单第 6 类），问数由官方 `get_instructions` 读取。

目标：术语按知识库维护，存在该库 Wren 工程里，与建模走同一套"草稿 → 发布"流程；页面能看到已发布结果和未发布的修改；查询权限只读，管理权限可编辑。

用户已确认（2026-10-29）：术语只绑定一个知识库，不做多库共用；尽量使用官方方式；问数按官方节奏调用 `get_instructions`，平台不再单独读一次规则；术语管理与建模同一流程，并能看到发布结果；旧术语不做迁移。

## 方案概述

1. **存储**：每个知识库工程固定文件 `knowledge/rules/naming.md`，标题 `## Naming conventions`，一条术语一行，约定写法：
   `- "术语"（同义词："甲"、"乙"）= 解释`。解释写业务含义，能写出对应的表、字段或条件时一并写上（官方推荐写法）。同义词可省略。
2. **解析**：平台按约定写法把条目解析为"术语 / 同义词 / 解释"；解析不了的条目（如建模助手的自由写法）整行按原文显示，仍可编辑、删除。同一库内术语名与同义词不得重复。
3. **编辑走草稿**：新增、修改、删除写入草稿工作区，复用 `DraftItemService` 的 Markdown 条目编辑与 `DraftChangeLog`（记录操作人）；建模助手运行中不可编辑，与现有草稿条目一致。
4. **发布与查看**：术语页嵌入与建模页相同的草稿栏（`DraftBar`），发布即发布整个知识库草稿（含他人修改时按现有流程提示）。页面合并显示已发布版本与草稿：每条标"已发布 / 新增未发布 / 修改未发布 / 删除未发布"，页头显示线上版本号。
5. **权限**：查看需知识库 QUERY——只有 QUERY 的用户看已发布版本、无编辑入口；编辑与查看草稿需 MANAGE。写入在工作区锁内复核 MANAGE，排队期间撤权即拒绝。
6. **问数与建模**：问数不改，术语随 `knowledge/rules/` 由官方 `get_instructions` 返回。建模提示词说明：Naming conventions 一律写入 `knowledge/rules/naming.md` 并遵循约定写法。
7. **下线旧实现**：`SemanticTermController`、旧链路术语注入、`ModelingToolkit` 术语工具与 `semantic_term` 表退役，旧术语数据不迁移。

同步 ARCHITECTURE_zh.md 第 14 章（建模与问数约束）和数据模型章节。

## 影响面

- 后端：新增术语服务与接口（`/api/groups/{groupId}/terms`：读已发布、读草稿、增改删）；`DraftItemService` 条目编辑复用；删除 `SemanticTermController` 及相关注入；建模提示词（`ModelingTurnRules` 或 `DataAgentConfig` 建模脚本）一句。
- 前端：`SemanticConfigPage` 改为按知识库读写工程术语，嵌入 `DraftBar`，按权限只读；`api/semantic.ts` 改路径。
- 数据：`semantic_term` 表删除（`docs/migrations/` 补 SQL），旧数据不迁移；术语数据进入各知识库工程。
- 文档：ADR0115、spec 索引、ARCHITECTURE 第 14 章与数据模型章节；README 无配置变化。

## 验收标准

1. Given 有 MANAGE 的用户，When 在术语页新增"看板 = 仪表盘"，Then 草稿 `knowledge/rules/naming.md` 出现该条，页面标"新增未发布"，线上版本不变。
2. Given 上条已发布，When 新开问数会话问"10 月看板访问量"，Then 助手首个数据问题调用 `get_instructions` 返回的内容含该术语，SQL 使用仪表盘相关模型。
3. Given 只有 QUERY 的用户，When 打开术语页，Then 只看到已发布术语、无编辑与发布入口；直接调用编辑接口返回 403。
4. Given 用户对 A 库无任何权限，When 请求 A 库术语，Then 返回 404，看不到内容。
5. Given 建模助手正在运行，When 编辑术语，Then 提示稍后再试，草稿不变。
6. Given 修改了已发布术语未发布，When 查看页面，Then 同时可见线上内容与草稿修改，发布后状态变为"已发布"、版本号加一。
7. Given 升级前平台表里有旧术语，When 升级后打开术语页，Then 只显示工程 `naming.md` 中的术语，旧术语不出现。
8. Given 同一库已有术语"仪表盘"，When 新增同义词含"仪表盘"的术语，Then 拒绝并说明重复。

## 不做的事

- 不做术语多库共用、术语库独立权限。
- 平台不在每轮问数预读或注入规则，不改写 `recall_queries` 参数。
- 不在页面提供"交给建模助手补充对应字段"，不从文档自动提取术语建议。
- 不读写 `knowledge/glossary/`（官方无程序读取）；不新增 rules 小节标题。

## 测试要求

- 条目解析与生成（约定写法、无同义词、自由格式原样保留、重复检测）。
- 权限：QUERY 只读、MANAGE 可写、无权限 404、排队撤权拒绝；建模运行中拒绝编辑。
- 草稿/已发布状态合并。
- 前端 `npm run build`；真实验收按标准 1、2、6 重启后看日志。

## 关联

- ADR：[0115](../adr/0115-business-terms-in-wren-naming-conventions.md)
- 取代：[spec026](done/026-term-scope-binding.md) 的平台术语表与注入方式
