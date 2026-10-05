# ADR 0043 — 回归官方 sink 分工：view 为 agent 首选载体，ref_sql 降级人工载体 + 发布链物化

日期：2026-10-05 · 状态：Accepted（官方独立工程四组实验矩阵 + cte_rewriter.py 源码 +
官方技能剧本三方互证；实施明细见 docs/specs/035）
**本 ADR 取代 ADR 0032 的「view 降级为只读出口」核心决策，并证伪 ADR 0042 的「平台
零改写」假设；0032/0042 保留原文，以此为准。**

## 背景

ADR 0032/0042 将 ref_sql 派生模型定为 agent 复杂口径的一等载体，依据是「ref_sql
豁免 DataFusion 函数绑定 + 引擎规划期自行解析 ref_sql 内逻辑模型名，平台零改写」。
运行时故障（派生模型发布报 MySQL 1146）暴露了该假设的关键缺口。

官方独立实验（target/wren-refsql-verify，官方 init + 手工 yml，零平台代码）四组矩阵：

| ref_sql 表引用 | profile 默认库 | dry-run | 真实查询 |
|---|---|---|---|
| 逻辑名 orders/customers | data_agent | 1146 SQL_DRY_RUN | 1146 SQL_EXECUTION |
| 逻辑名 | test_data（表所在库） | OK | OK（内部账号口径正确排除） |
| 物理全限定名 test_data.orders | data_agent | OK | OK |

源码证据（wren/mdl/cte_rewriter.py）：CTERewriter 只为**用户 SQL 直接引用**的模型
生成 CTE（经 `session_context.transform_sql`）；view 的 statement 有专门的内层模型
引用收集（`_collect_view_model_usage`，为引用到的模型注入物理 CTE）；**ref_sql 模型
无任何对应机制**，其 SQL 原样直通数据源，内层裸名按 profile 连接默认库解析成物理
表。「SQL 里引用逻辑模型名」因此是 view 的引擎内建能力，ref_sql 的官方定位是引擎
直通的原生 SQL（须物理名）。

官方技能剧本进一步确认分工：generate-mdl 只产出 table_reference 模型、enrich-context
sink 决策树把跨模型 JOIN/窗口/CTE 路由到 VIEW、dlt-connector 明确排除 ref_sql——
**官方 agent 不生 ref_sql、会生 view；ref_sql 是手工精修载体**。

## 决策

1. **agent 复杂口径首选 view**：恢复 `create_view` 工具（HITL 写闸门），与官方
   enrich-context sink 决策树对齐。
2. **视图函数兼容走三层机制，不再更换载体**：`@Tool` 描述内嵌函数对照表（疏，
   DataFusion ∩ sqlglot 交集，源自 10-03 十三场景探针实证）；`requireViewSql`
   黑名单命中错误附替代写法（堵）；发布链逐视图 dry-run（保，唯一能拦规划期
   函数绑定的发布前防线）。
3. **`propose_derived_model` 工具彻底删除**：ref_sql 回归纯人工编辑（通用
   write_file/patch_file 面保留），与官方「ref_sql 手工载体」定位一致；存量
   ref_sql 资产保留可用。
4. **发布链物化 ref_sql（手工载体的必需项）**：`context build` 成功后、dry-run
   前，staging `target/mdl.json` 中 ref_sql 模型的 `refSql` 由平台物化为物理全
   限定名（词法提取 FROM/JOIN 引用；CTE/字符串/注释掩码；已限定名跳过；映射取
   workspace 物理模型 schemaName.tableName；未解析引用报 error issue 中止发布）。
   工作区文件保持逻辑名不变。
5. **读面修复**：`isModelingAssetPath` 白名单加 `.sql`；建模页「派生模型」tab
   保留为手工资产只读展示面。

## 理由（trade-off）

- **0032 判断时的信息缺口**：当时否掉 view 路线依据「LLM 在无对照表指引下十次九次
  滑回 MySQL 母语」；但「引擎兼容函数清单」已有探针实证地图，且官方 sink 树的路由
  条件（JOIN/窗口/CTE）本身避开了方言函数区——view 路线的真实成本是「函数表达力
  收窄 + 偶发返工」，有 dry-run 保底线，不是正确性风险。
- **ref_sql 路线的真实成本（本次事故暴露）**：引擎不解析内层引用 → 平台必须永久
  承担物化器维护（词法边界、嵌套模型限制、与 wren-core 版本演进对齐），且与官方
  生态完全脱节（官方剧本零覆盖，agent 无指引可学）。
- **两害相权**：view 成本是一次性的指引建设 + 长期窄函数集；ref_sql 成本是永久性
  平台物化 + 生态孤儿。且回归官方后，官方技能剧本（enrich-context sink 树、
  cube_proposals 模板）直接可用，平台自建引导面收窄。
- **物化不可省略**：手工 ref_sql 引用逻辑名与 agent 生成的同样 1146（实验矩阵第一
  行），故物化是手工载体的生存前提，随本 ADR 一并落地。

## 被否方案

- **保留 propose_derived_model 作「函数逃生舱」**——双入口职责边界靠 agent 自觉
  维持，日志已证明软边界会被逐渐侵蚀；彻底删除后函数表达不了的情况由人工编辑
  ref_sql 兜底（write_file 面本就对 agent 开放，但无专用工具即无引导，符合
  「手工载体」定位）。
- **继续 ref_sql 一等载体 + 物化补丁**——物化把平台钉死在「引擎不解析内层引用」
  的实现细节上，wren-core 任何演进都可能静默打破词法假设；回归官方则把内层解析
  交还引擎（view 路径是官方维护的）。
- **只删工具不物化**——存量/手工 ref_sql 资产发布即 1146，半成品不可用。

## 影响面

- 后端：`ModelingToolkit`（删 propose_derived_model、新增 create_view）、
  `ModelingHitlMiddleware`（写闸门名单）、`DataAgentConfig`（MODELING_SCRIPT）、
  `MdlSuggestionService`（requireViewSql 文案）、`MdlPublishService`（物化插入、
  ADR 0042 注释修正）、新增 `RefSqlMaterializer`、`SemanticModelingController`
  （白名单）
- 前端：`SemanticModelingPage`（派生模型 tab 保留只读、create_view 确认卡）、
  `ModelingHitlCard`（工具标签）、`api/semanticModeling.ts`
- 数据：无（文件即事实源）
- 文档：ARCHITECTURE_zh.md 建模/发布链小节、specs/034 状态行

## 验证

- `mvn test` 绿（RefSqlMaterializerTest / ModelingToolkitTest / MdlPublishServiceTest /
  SemanticModelingControllerTest 新增用例）；
- `cd frontend && npm run build` 绿；
- 实验工程复验：物化后手工 ref_sql 模型 dry-run + 真实查询通过（矩阵第四行）。

## 边界与重评条件

若 wren-core 未来为 ref_sql 增加内层模型引用解析（对齐 view 的
`_collect_view_model_usage` 语义），或 view 的函数绑定环节被移除/放宽（dialect
字段成为真逃生门），应重评载体分工；物化器可随 ref_sql 内层解析能力自然退役。
