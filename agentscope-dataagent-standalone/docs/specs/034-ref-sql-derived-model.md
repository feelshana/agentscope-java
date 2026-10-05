# spec 034: ref_sql 派生模型——复杂口径的一等载体

> 状态更新（2026-10-05，ADR 0043 + specs/035）：本 spec 的定位与关键假设已被取代/证伪——
> 「agent 一等载体」降级为纯人工编辑载体（`propose_derived_model` 工具已删除，复杂口径
> 一律 `create_view` 命名视图）；「引擎自解析内层逻辑名、平台零改写」假设被官方实验证伪，
> 发布链在 `context build` 后把 staging `target/mdl.json` 的 refSql 物化为物理全限定名
> （新增 `RefSqlMaterializer`，未解析引用报 error 中止发布）。读面（refSql/refSqlPath、
> `derivedModels`、派生模型 tab）、发布闸门（逐派生模型 dry-run）、`derived` 标记与
> 缺 metadata.yml 报 issue 均保留；工具面与写面以 specs/035 为准。

## 背景与目标

specs/018 的方言黑名单封死了视图这条路（视图 statement 被 DuckDB 规划期绑定，MySQL
函数一律报 Invalid function），但对话建模脚本仍引导「HAVING/窗口/多表 JOIN 类口径写
views/<name>/」——这是死路。wren 官方本就有复杂口径的正解：**ref_sql 模型**（模型不指
物理表而是一段 SQL，v5 布局 `models/<name>/metadata.yml + ref_sql.sql`），context.py
13 处代码支持、官方技能却零文档（唯一提及是 dlt-connector 的一句否定句），平台代码库
与 docs 此前同样零提及——agent 永远不会自己走到这条路。CLI 探针 A/B 实证：同一段含
DATE_SUB 的口径 SQL，视图布局规划期必死，ref_sql 两种布局（内联/文件式）均完整通过规
划直达 MySQL（引擎在规划期自行把 ref_sql 内的逻辑模型名解析为物理表，平台无需改写
SQL）。目标：把派生模型变成平台的一等公民——读得出、建得了、发得布、看得见。

## 方案概述

1. **读取**（`MdlWorkspaceReader`）：`WorkspaceModel` 增 `refSql`/`refSqlPath`；
   `models/` 循环解析 `ref_sql.sql`（文件优先于 metadata.yml 内联 `ref_sql` 键）；models/
   与 views/ 缺 `metadata.yml` 的静默 `continue` 改为 error issue（目录被跳过必须可见）。
2. **视图暴露**（`MdlPublishService#view`）：`MdlModelView` 增 `refSqlPath`（派生模型为
   `models/<name>/ref_sql.sql`，物理模型为 null）；`MdlView` 增 `derivedModels`，物理
   模型列表不再混入派生模型。
3. **发布链**（`MdlPublishService`）：`validate()`/`doPublish()` 在逐视图 dry-run 旁追加
   逐派生模型 `dry-run -s 'SELECT * FROM "<name>"'`（引擎解析 ref_sql 全链路的兜底）；
   `writeMetaJson` 给派生模型加 `"derived": true` 标记（`MdlCatalog` 顺带解析，提示词
   目录可区分）；快照原样携带逻辑名——**不做平台侧 SQL 改写**（探针实证引擎自解析，
   改写反而引入双事实源）。
4. **建模工具**（`ModelingToolkit#propose_derived_model`，写操作进 HITL 闸门）：入参
   name/description/sql/columns_json/reason → 校验（命名、SELECT/WITH-only、列清单
   JSON、名称冲突）→ 组合 metadata.yml + ref_sql.sql（UTF-8 无 BOM）→ scratch 联合预检
   （validate --strict + build + dry-plan `SELECT * FROM "<name>"`）→ 双文件落盘。
5. **Prompt**（`DataAgentConfig#MODELING_SCRIPT`）：死路指引修正（generate-mdl 无
   references/）；新增派生模型硬规矩——复杂口径一律 `propose_derived_model`，禁止为此
   新建 views/，SQL 只引用逻辑模型名；视图段落改为只读出口表述。
6. **前端**：`MdlModelView`/`MdlView` 类型同步；建模页「表与关系」tab 后新增
   「派生模型」tab（复用 AssetYamlBrowser，右栏直接渲染 ref_sql.sql）。

主流程变更：同步 ARCHITECTURE_zh.md 建模入口与发布链小节。

## 影响面

- 后端：`MdlWorkspaceReader`（解析+skip 改 issue）、`MdlPublishService`（view 过滤、
  dryRunDerivedModels、writeMetaJson 标记、MdlModelView/MdlView 记录）、
  `MdlCatalog`（derived 标记解析）、`ModelingToolkit`（新工具）、
  `ModelingHitlMiddleware#WRITE_TOOL_NAMES`、`DataAgentConfig#MODELING_SCRIPT`
- 前端：`api/semanticModeling.ts`（类型）、`SemanticModelingPage.tsx`（新 tab）、
  `ModelingHitlCard.tsx`（工具中文标签）
- 数据：无（文件即事实源，无新实体无迁移）
- 文档：ARCHITECTURE_zh.md、ADR 0042、specs/018 状态行

## 验收标准（Given-When-Then，可测试）

1. Given `models/abc/{metadata.yml, ref_sql.sql}`，When reader.read，Then 该模型
   refSql=文件内容、refSqlPath=文件路径，且无 issue。
2. Given metadata.yml 同时有内联 `ref_sql` 键与 ref_sql.sql 文件，When reader.read，
   Then 文件内容优先。
3. Given `models/broken/` 目录缺 metadata.yml，When reader.read，Then issues 含
   「models/broken」error 而非静默跳过。
4. Given 派生模型存在，When `GET /mdl/view`，Then `models` 不含它、`derivedModels`
   含它且 refSqlPath 指向 ref_sql.sql。
5. Given 派生模型 staging dry-run 返回 exit 1，When validate/publish，Then issues 含
   「派生模型「x」试跑失败」error 且发布中止。
6. Given 发布含派生模型，Then mdl.json 该模型带 `"derived": true`。
7. Given propose_derived_model 的 sql 以 DELETE 开头，When 调用，Then 拒绝且不写文件；
   Given 名称与既有模型冲突，Then 拒绝。
8. Given 建模页打开「派生模型」tab，Then 左栏列出全部派生模型、点击右栏渲染
   ref_sql.sql，未发布变更带橙点。

## 不做的事（明确排除项）

- 不做平台侧 ref_sql 逻辑名→物理名改写（探针实证引擎规划期自解析；改写会造双事实源）。
- 不支持派生模型嵌套引用未发布对象以外的场景扩展（嵌套引用其他派生模型由引擎决定，
  平台不额外约束）。
- 不迁移存量 views/ 资产到派生模型（存量视图照旧可用可发布）。
- 不给派生模型加 DB 实体/单独管理页（工作区文件即事实源，建模页 tab 即管理面）。

## 测试要求

- MdlWorkspaceReaderTest：文件式/内联/文件优先解析、缺 metadata.yml 改 issue。
- MdlPublishServiceTest：view() 的 models/derivedModels 分离、发布链 dry-run 派生模型
  argv 断言、mdl.json derived 标记。
- ModelingToolkitTest：propose_derived_model 校验分支（空 name、非 SELECT/WITH、
  非法 columns_json、名称冲突），成功路径 FakeWrenCli 全放行。
- 前端 npm run build 零错误。

## 关联

- ADR：0042-ref-sql-derived-model-route.md（实施后）
- 前序：specs/018（视图方言门禁）、specs/019（YAML-first 工作区）、specs/030（建模页
  工作台）、ADR 0032（视图降级为只读出口的定位变更）
