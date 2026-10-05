# spec 035: 回归官方 sink 分工——view 为 agent 首选载体，ref_sql 降级人工载体

> 状态（2026-10-05）：已实施。`RefSqlMaterializer` 物化、`create_view` 恢复与
> `propose_derived_model` 删除、门禁文案对照表、`.sql` 白名单、前端标签/文案、测试
> （RefSqlMaterializerTest、ModelingToolkitTest、MdlPublishServiceTest、
> ModelingHitlMiddlewareTest 增删）与 ADR 0043 均已落地。

## 背景与目标

specs/034/ADR 0042 把 ref_sql 派生模型定为 agent 复杂口径的一等载体，其关键假设
「引擎规划期自行把 ref_sql 内逻辑模型名解析为物理表、平台零改写」**已被官方独立实验
证伪**（target/wren-refsql-verify 四组矩阵 + cte_rewriter.py 源码：CTERewriter 只为
直接引用层生成 CTE，view 有专门的内层引用收集 `_collect_view_model_usage`，ref_sql
无任何对应机制——体内裸逻辑名按 profile 默认库解析成物理表，dry-run/真实查询均报
MySQL 1146）。官方剧本（generate-mdl/enrich-context/dlt-connector）本就零 ref_sql
生成分支、sink 决策树把跨模型 JOIN/窗口/CTE 路由到 VIEW——「SQL 里引用逻辑模型名」
是 view 的引擎内建能力，ref_sql 的官方定位是引擎直通的原生 SQL（须物理名，人工精修
逃生舱）。用户拍板回到官方逻辑：agent 走 view，ref_sql 回归纯人工编辑；视图函数不
兼容问题用「指引 + 黑名单 + dry-run」三层机制化解，不再靠更换载体回避。

## 方案概述

1. **删 `propose_derived_model`**（ModelingToolkit）：工具方法删除、
   `ModelingHitlMiddleware#WRITE_TOOL_NAMES` 摘除、`DataAgentConfig#MODELING_SCRIPT`
   派生模型硬规矩改写为「ref_sql 派生模型仅人工编辑工作区文件，不要主动创建；复杂
   口径（跨表 JOIN/窗口/CTE/复合过滤）一律 `create_view`」。
2. **恢复 `create_view`**（ModelingToolkit，写操作进 HITL 闸门）：入参
   name/description/statement/reason → `MdlSuggestionService.requireViewSql` 方言门禁
   → 组合 `views/<name>/{metadata.yml, sql.yml}`（UTF-8 无 BOM）落盘。`@Tool` 描述
   内嵌**函数对照表**（10-03 探针实证的 DataFusion ∩ sqlglot 交集）：可用
   CURRENT_DATE±INTERVAL、DATE_TRUNC、DATE_FORMAT、IFNULL、SUBSTRING_INDEX、
   EXTRACT、CAST、CASE WHEN、STRING_AGG 等；禁用 DATE_SUB/DATE_ADD/CURDATE/YEAR/
   DATEDIFF/TIMESTAMPDIFF/IF/GROUP_CONCAT 等 14 个，并给出替代写法（如「最近 N 天 →
   `CURRENT_DATE - INTERVAL n DAY`」）。
3. **门禁文案升级**（`MdlSuggestionService#requireViewSql`）：黑名单命中错误从
   「函数被禁」升级为「函数被禁 + 替代写法对照」，与工具描述呼应（疏+堵）。
4. **发布链物化**（`MdlPublishService`）：新增 `RefSqlMaterializer`（词法提取
   FROM/JOIN 引用、CTE/字符串/注释掩码、已限定名跳过）；`validate()`/`doPublish()`
   在 `context build` 成功后、dry-run 前，把 staging `target/mdl.json` 中 ref_sql
   模型的 `refSql` 物化为物理全限定名（映射取 workspace 物理模型的
   schemaName.tableName；未解析引用报 error issue 中止发布）；工作区文件保持逻辑名。
   手工 ref_sql 引用逻辑名同样 1146，物化对人工载体是必需项而非可选优化。
5. **读面白名单**（`SemanticModelingController#isModelingAssetPath`）：扩展名加
   `.sql`（修复派生模型 tab 读 ref_sql.sql 报 400）；建模页「派生模型」tab 保留为
   手工资产的只读展示面。

主流程变更：同步 ARCHITECTURE_zh.md 建模工具面与发布链小节。

## 影响面

- 后端：`ModelingToolkit`（删 propose_derived_model、新增 create_view、描述改写）、
  `ModelingHitlMiddleware#WRITE_TOOL_NAMES`、`DataAgentConfig#MODELING_SCRIPT`、
  `MdlSuggestionService#requireViewSql`（文案）、`MdlPublishService`（物化插入 +
  ADR 0042 注释修正）、新增 `RefSqlMaterializer`、`SemanticModelingController`
  （白名单）
- 前端：`ModelingHitlCard.tsx`（工具中文标签增删）、`SemanticModelingPage.tsx`
  （派生模型 tab 保留只读；create_view 确认卡接入）、`api/semanticModeling.ts` 类型
- 数据：无（文件即事实源）
- 文档：ARCHITECTURE_zh.md、ADR-0043（0032/0042 修正）、specs/034 状态行

## 验收标准（Given-When-Then，可测试）

1. Given 建模会话 agent 欲承载跨表口径，When 工具面枚举，Then 存在 `create_view`、
   不存在 `propose_derived_model`。
2. Given `create_view` 的 statement 含 DATE_SUB，When 调用，Then 拒绝且错误信息含
   替代写法（`CURRENT_DATE - INTERVAL n DAY`），不落盘。
3. Given `create_view` 的 statement 用交集函数（如 DATE_TRUNC + CURRENT_DATE -
   INTERVAL），When scratch 预检全绿，Then 双文件落盘并进 HITL 确认卡。
4. Given 工作区存在手工 ref_sql 模型（models/x/ref_sql.sql 引用逻辑名），When
   validate/publish，Then staging `target/mdl.json` 的 refSql 已物化为
   `schema.table` 全限定名，dry-run 通过，工作区文件仍为逻辑名。
5. Given 手工 ref_sql 引用了不存在的模型名，When validate/publish，Then issues 含
   「派生模型「x」的 SQL 引用了不存在的逻辑模型」error 且发布中止、版本不变。
6. Given 建模页「派生模型」tab 点击某模型，When 读取 ref_sql.sql，Then 内容正常
   渲染（不再 400）。
7. Given 存量已发布 ref_sql 派生模型（如 valid_order_base），When 本修复后发布，
   Then 物化生效、查询正常，无需迁移。

## 不做的事（明确排除项）

- 不迁移/删除存量 ref_sql 派生模型资产（物化修复后其发布与查询即正常）。
- 不做 `wren_describe_model` 支持视图（遗留独立缺口，另行安排）。
- 不恢复 `update_view` 工具（view 修改走通用 write_file/patch_file 人工面）。
- 不删 MdlWorkspaceReader 的 ref_sql 解析（手工载体读面保留）。

## 测试要求

- RefSqlMaterializerTest：JOIN/逗号 FROM 列表/CTE 排除/字符串与注释掩码/别名跳过/
  已限定名不改/大小写不敏感/多处引用位置正确。
- ModelingToolkitTest：create_view 校验分支（非 SELECT/WITH、黑名单函数、名称冲突）、
  propose_derived_model 已不存在；既有派生模型用例删除或改写。
- MdlPublishServiceTest：物化后 staging mdl.json refSql 断言、未知引用发布失败且
  版本不变。
- SemanticModelingControllerTest：`.sql` 白名单放行用例。
- 前端 npm run build 零错误。

## 关联

- ADR：0043-view-first-ref-sql-manual-route.md（实施后）
- 修正：ADR 0032（view 降级决策被取代）、ADR 0042（零改写假设被证伪）
- 前序：specs/034（ref_sql 派生模型全链路，读面/发布闸门保留）
