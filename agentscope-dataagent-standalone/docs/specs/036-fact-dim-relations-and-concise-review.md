# spec 036: 关系只生成事实↔维、提案/候选精简、确认一次即生效

## 背景与目标

初始化关系推断会把「事实表之间的共享列」（如两张事实表都有 下单日期）也当作候选关系，LLM 建议同样只看列名对应，产生大量噪声候选；关系提案与候选的展示携带整段业务描述，冗余难读；对话建模中已确认的关系还会被反复提及与二次确认。本 spec 收敛三件事：关系只在事实表↔维表之间生成；提案/候选展示只保留「双方表名（上传表用中文名）+ 关联字段 + 关系」三要素；确认一次即写入生效，不再复述。

## 方案概述

判定口径（两层分工）：

- 规则层 `RelationInferenceService#reinferGroup`：SAME_COLUMN 配对仅保留「恰好一侧为 PK 形态列」（首列或 `<table>_id`）——非键侧为事实表（边 source），键侧为被引用维表（边 target）；双非键（事实-事实共享属性）与双键（方向不明）不生成。SUFFIX/DOC 规则不变。
- LLM 层 `MdlSuggestionService#buildRelationPrompt/#parseRelationSuggestions`：不做形态过滤——维表被引用的列可能不是主键（如业务编码），纯主键形态过滤会误杀。提示词要求模型基于表描述、表行数与示例数据（`DatasetService#preview` 前 5 行）判断事实/维表，source 固定事实表一侧，严禁推荐事实表↔事实表与维表↔维表；解析侧仅做「明显反向」修复——恰好一侧为 PK 形态且被提议为 source 时翻转方向，永不丢弃候选。

展示精简：

- `ModelingHitlCard`：JOIN_LABEL 改「多对一/一对多/一对一」短词；单条卡摘要只保留 `表A.字段 → 表B.字段（基数）`，技术细节收进折叠区；批量卡行删除 reason/note 展示；TOOL_LABEL 移除 suggest_relations；columnLabel 不再回退拼接 description。
- `ModelingToolkit#renderState`：候选行去掉「｜含义：」段；指令改为「每条含 表.字段 → 表.字段与连接基数」。
- `SemanticModelingPage` 的 RelationRow：只展示三要素，去掉描述行与置信度行；表名用数据集中文名，不再拼 description。

一次即生效：

- `ModelingHitlMiddleware#WRITE_TOOL_NAMES` 摘除 suggest_relations（纯重算候选队列，无持久化语义状态，重算不再弹卡）；`decide_relations` 工具描述与 `MODELING_SCRIPT` 删除 note 要求，新增硬约束「已确认/已否决关系为终局，不复述、不再二次确认」。`decideRelations` 执行体向后兼容仍接受 note 字段。

需同步 ARCHITECTURE_zh.md 关系推断与建模工具链章节。

## 影响面

- 后端：dataset/RelationInferenceService、dataset/MdlSuggestionService、tools/data/ModelingToolkit、web/config/DataAgentConfig、web/middleware/ModelingHitlMiddleware
- 前端：components/ModelingHitlCard.tsx、pages/configure/SemanticModelingPage.tsx
- 数据：无迁移（DatasetRelationEntity 结构不变）
- 文档：ARCHITECTURE_zh.md；ADR 0044

## 验收标准

1. Given 同组两张事实表共享非键列（如 下单日期），When reinferGroup，Then 不生成任何 SAME_COLUMN 边。
2. Given 维表主键在首列的事实-维共享列，When reinferGroup，Then 生成一条边且 sourceDatasetId=事实表、targetDatasetId=维表。
3. Given 双方共享列都是各自首列（双 PK 形态），When reinferGroup，Then 不生成边。
4. Given LLM 返回维表→事实表的反向提案（维表侧为 PK 形态首列），When refreshRelations，Then 落库方向为事实→维。
5. Given LLM 返回「事实表.业务键 → 维表.业务键（非主键形态列）」提案，When refreshRelations，Then 候选照常入库（不被形态过滤丢弃）。
6. Given 数据集有描述与可预览数据，When 构建 LLM 关系提示词，Then 提示词包含表描述、行数与示例数据行，并包含「严禁推荐事实表之间关联」指令。
7. Given 建模对话调用 suggest_relations，When 工具执行，Then 不触发 HITL 确认卡，直接返回候选清单。
8. Given 用户在 decide_relations 卡片确认关系，Then 结果直接写入 relationships.yml 并返回校验结论，对话不再出现对该关系的重复确认（剧本硬约束）。
9. Given 上传表（中文名数据集），When 关系提案卡/候选列表渲染，Then 每条只显示「中文名表.字段 → 中文名表.字段（多对一/一对多/一对一）」三要素，无长段描述。

## 不做的事

- 不改 SUFFIX/DOC 规则的生成逻辑。
- 不在确定性代码里按「唯一性探测」丢弃 LLM 提案（唯一性只用于 joinType 探测）。
- 不改 decideRelations 执行体入参契约（note 向后兼容保留，只是不再要求、不再展示）。
- 不动已确认关系的选择性重建语义（survivesRebuild）。

## 测试要求

- RelationInferenceServiceTest：新增 事实-事实不成边、FK→PK 方向归一、双 PK 形态不成边 三用例。
- MdlSuggestionServiceTest：新增 提示词含描述/示例/严禁指令、反向提案翻转方向、非主键形态维表列提案保留 三用例。
- ModelingHitlMiddlewareTest：WRITE_TOOL_NAMES 不含 suggest_relations。

## 关联

- ADR：实施后补 docs/adr/0044（关系只建事实↔维与 LLM 语义判定）
- 前置：specs/024（批量关系确认）、ADR 0033（YAML-first 工作区）
