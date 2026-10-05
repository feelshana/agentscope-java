# ADR 0042 — 复杂口径走 ref_sql 派生模型：平台侧零改写、发布期逐模型试跑

日期：2026-10-05 · 状态：Accepted（CLI 探针 A/B 实证 + 官方 context.py 源码证据；
实施明细见 docs/specs/034）

## 背景

specs/018 用方言黑名单 + 发布前逐视图 dry-run 封死了「视图承载复杂口径」这条路
（视图 statement 被展开进 DuckDB 规划期函数绑定，MySQL 专有函数一律报
Invalid function）。但 ADR 0032 时代把视图定位为「HAVING/窗口/多表 JOIN 类口径」的
载体，`MODELING_SCRIPT` 至今引导 agent 把这类口径写进 `views/<name>/`——写入即埋雷
（创建能过、发布能过、问数必炸），与 specs/018 的黑名单在创建期就互相打架。

wren 官方其实一直有正解：**ref_sql 模型**（模型不指物理表而是一段 SQL），wren-core
规划时把 ref_sql 内联为子查询/CTE 并把其中的逻辑模型名解析为物理表。但该能力在
官方技能文档中完全沉默（generate-mdl/SKILL.md 全文零提及，唯一提及在
dlt-connector 的一句否定句），平台代码库与 docs 此前也零提及——agent 仅凭官方技能
永远走不到这条路。

## 调研

- **官方代码证据**（WrenAI core/wren context.py，13 处）：`table_reference` 与
  `ref_sql` 严格二选一；v5 布局 `models/<name>/{metadata.yml, ref_sql.sql}` 双文件，
  且文件优先于内联键；导出/升级迁移均支持。
- **CLI 探针 A/B**（target/agent-sim-probe，四轮收敛）：同一段含
  `DATE_SUB(CURRENT_DATE, INTERVAL 30 DAY)` 的口径 SQL——视图布局 dry-run 规划期必死
  （复刻生产事故）；ref_sql 模型内联/文件式两种布局均完整通过 build + dry-run 直达
  MySQL，DRYPLAN 显示 DATE_SUB 原样位于最内层 CTE、逻辑模型引用已被引擎解析。
- **结论**：ref_sql 模型与物理模型同走 sqlglot 直查路径（无 DuckDB 函数绑定），
  方言风险与视图不同级；引擎在规划期自行完成逻辑名→物理名解析，**平台无需也不应
  改写 SQL**。

## 决策

1. **派生模型（ref_sql model）成为复杂口径的一等载体**：读取器解析双文件布局
   （文件优先于内联）、`MdlView` 单列 `derivedModels`、建模页新增「派生模型」tab
   （复用 AssetYamlBrowser，右栏渲染 ref_sql.sql）。
2. **平台侧零改写**：发布快照原样携带逻辑名；发布链职责收窄为「逐派生模型
   `dry-run -s 'SELECT * FROM "<name>"'`」——与逐视图 dry-run 并列的语义兜底，坏口径
   在 validate/publish 期被拦，不进快照。
3. **写入只走 `propose_derived_model`**：一次调用组合 metadata.yml + ref_sql.sql
   （UTF-8 无 BOM——BOM 会进 SQL 解析器报 ParserError），scratch 副本联合预检
   （validate --strict + build + dry-plan 该模型）后双文件落盘；工具进
   `ModelingHitlMiddleware` 写闸门。
4. **views/ 降级为只读出口**：`MODELING_SCRIPT` 移除「复杂口径写 views/」引导，
   存量视图照旧可发布可查询；新建视图不再作为复杂口径的建议路径。
5. **静默跳过改 issue**：models//views/ 缺 metadata.yml 的目录从静默 continue 改为
   error issue——资产消失必须可见（本轮探针中「目录消失」一度被误判为 build 删目录，
   根因是静默跳过掩盖了真相）。

## 理由（trade-off）

- 零改写 = 单一事实源：工作区、快照、引擎看到的是同一份逻辑名 SQL；平台若做改写，
  工作区与快照出现双事实源，dry-run 通过的快照与回滚语义都会复杂化。
- 派生模型进 HITL 闸门而非自由 write_file：双文件成对写入 + 列清单与 SQL 的一致性
  （columns_json 校验）需要原子性，散装 write_file 容易写成半套（有 metadata.yml 无
  ref_sql.sql 的模型会被读取器报缺 ref_sql）。
- 探针实证 > 文档承诺：官方技能沉默的能力，以 context.py 源码 + 本地 CLI 四轮 A/B
  为准纳入平台契约；同时把该能力写进 `MODELING_SCRIPT` 硬规矩，弥补官方技能缺口。

## 被否方案

- **平台侧逻辑名→物理名改写后落盘**（specs/034 早期草案）——探针证明引擎自解析，
  改写徒增双事实源与 token 边界替换风险。
- **继续用视图承载 + 扩大黑名单改写建议**——视图 DuckDB 绑定是架构性约束，
  黑名单是补丁不是出路；DATE_SUB 类需求在派生模型下零成本。
- **教 agent 直接用 write_file 写 ref_sql 双文件**——无列清单/SQL 一致性校验、
  无双文件原子性、HITL 卡片对散装文件 diff 不友好；专用工具一步到位。
- **给派生模型建 DB 实体**（pre-019 思路）——YAML-first 后工作区文件即事实源，
  DB 实体是退化回 v1 编译器的老路。

## 影响面

- 后端：`MdlWorkspaceReader`（ref_sql 解析、skip 改 issue）、`MdlPublishService`
  （derivedModels 暴露、dryRunDerivedModels、mdl.json derived 标记）、`MdlCatalog`
  （derived 标记）、`ModelingToolkit`（propose_derived_model）、
  `ModelingHitlMiddleware`（写闸门名单）、`DataAgentConfig`（MODELING_SCRIPT）
- 前端：`SemanticModelingPage`（派生模型 tab）、`api/semanticModeling.ts`（类型）、
  `ModelingHitlCard`（工具标签）
- 数据：无（无新表无迁移）
- 文档：ARCHITECTURE_zh.md 建模/发布链小节、specs/034、specs/018 状态行

## 验证

- `mvn test` 绿（MdlWorkspaceReaderTest / MdlPublishServiceTest /
  ModelingToolkitTest 新增用例）；
- `cd frontend && npm run build` 绿；
- 探针工程复验：propose_derived_model 写入的派生模型经发布链 build + dry-run 通过。
