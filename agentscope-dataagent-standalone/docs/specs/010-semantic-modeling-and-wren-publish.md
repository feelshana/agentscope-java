# spec 010: 语义建模与 WrenAI 发布流（Semantic Modeling & Wren Publish）

> 设计依据：ADR 0018（D1~D10）。核心约束（用户拍板）：**不影响当前流程与当前工具**——
> 上传/选表/问数链路与 `DataAgentToolkit` 四工具零改动，未建模组直查能力不降级（D10）。
> 分三个里程碑：M1/M2 纯增量不碰问数链路，M3 才接入问数。

## 背景与目标

知识库（DatasetGroup）目前是 text2sql 直查通道，跨表 JOIN 与聚合口径全靠 LLM 从列名猜测。
ADR 0018 已验证 WrenAI 语义层集成可行（query_cube E2E 20/20），并拍板产品形态：知识库 ==
wren project（D8）、MDL 在「语义建模」页显式发布时生成（D9）、建模是可选增强非必经关卡
（D10）。本 spec 落地该通道：语义建模页（第五 Tab）→ AI 建议 + 人审 → MDL 发布 → 该组
问数引擎切换。

## 方案概述

### M1 建模准备与建议层（纯增量，不碰问数）

1. **数据模型**（JPA 自动建表/加列）：
   - `DatasetGroupEntity` 加列：`mdl_state`（NONE/DIRTY/PUBLISHED）、`mdl_version`、
     `mdl_published_at`；
   - `DatasetRelationEntity` 加列：`join_type`（MANY_TO_ONE 等）、`status`
     （PENDING/CONFIRMED/REJECTED）、`origin` 扩枚举（inferred/doc/llm/**manual**）——2.2..442444535
     关系建模记录与推断边同表内聚，不另建表；
   - 新实体 `SemanticCubeEntity`（表 `dataagent_semantic_cube`）：groupId、name、
     base_object、measures/dimensions/time_dimensions（json）、status。
2. **修两个已知坑（先修，M1 后续改动依赖）**：
   - `RelationInferenceService#reinferGroup` 人工记录保护：只删 `origin IN (inferred,doc)`
     的边，`manual/llm` 与 `CONFIRMED` 记录保留（ADR D2 坑）；
   - SAME_COLUMN 首列盲区修复：跳过规则从「每表首列」改为「跳过本表主键列（首列且对端
     无同名共享）」（ADR D9 实证：维表主键在首列时系统性漏推）。
3. **`MdlSuggestionService`**（新增，dataset 包）：
   - `suggestRelations(groupId)`：规则推断边 + LLM 建议（输入列名/AI 描述/维度样例值 →
     候选关系对+方向+依据，中文提示词）合并去重落 PENDING；
   - joinType 探测：候选外键列跑 `COUNT(DISTINCT fk) vs COUNT(*)`（boundedElastic），
     多端定 MANY_TO_ONE，不靠 LLM 猜（ADR D9）；
   - `suggestCubes(groupId)`：按官方 cube 提议决策树（ADR D2：聚合形态默认 cube、纯行级
     →计算列提示、同表达式去重护栏），AI 从列语义建议（金额列→SUM 指标、类别列→维度、
     时间列→时间维度）。
4. **`SemanticModelingController`**（`/api/dataset-groups/{id}/modeling`，ownerId 校验照
   `DatasetService#get` 模式）：GET 状态与建议 / PUT 关系确认·改向·拒绝 / POST 手工关系
   / cube CRUD / POST 触发建议。
5. **前端**：`SemanticModelingPage.tsx`（`DatasetGroupPage` 第五 Tab，NAV_ITEMS +1）——
   组状态头（版本/脏标记）、模型卡（标注 origin 与待建模状态）、关系候选卡（确认/改向/
   拒绝/手工添加）、cube 候选卡（聚合函数下拉+选列+CASE 条件构造器；表单字段与 cube YAML
   一一映射，配置面不含查询条件）；`api/semanticModeling.ts`。

### M2 MDL 生成与发布服务

1. **`MdlPublishService`**（新增，dataset 包）：
   - `assemble(groupId)`：CONFIRMED 关系 + cube 配置 + `DatasetEntity.columnSchemaJson`
     → MDL YAML（`models/<name>/metadata.yml`、`relationships.yml`、
     `cubes/<name>/metadata.yml`）；类型经 `wren utils parse-types` 归一化（禁手写映射，
     ADR D2）；产物落 `~/.agentscope/dataagent/mdl/{groupId}/`（运行态持久，非 target/）；
   - `validate(groupId)`：拼装后跑 `wren context validate --strict`（子进程，
     PYTHONUTF8=1，参照探针 pk.py 的参数数组语义），错误结构化回传（内联到卡片）；
   - `publish(groupId)`：validate 通过 → `build` → `mdl.json` 落盘 → 组状态置
     PUBLISHED / version+1 / published_at。
2. **脏标记**：`ingest`/`associateTables`/删除数据集处将组置 DIRTY（PUBLISHED 组旧版本
   继续服务）；发布前须先处理 DIRTY 增量。
3. **前端发布交互**：YAML 预览（diff 视图）、验证按钮（错误内联）、发布按钮（状态流转
   展示）。

### M3 问数切换（wren 运行期接入）

1. **`WrenToolkit`**（新增，tools/data 包，参照 `DataAgentToolkit` 模式）：`@Tool` 封装
   `wren_run_sql` / `wren_query_cube`（中文描述；`DatasetScope`+`RuntimeContext` 注入组
   路由）——组 `mdl_state != PUBLISHED` 时返回引导性错误（提示用 `query_structured_data`
   或前往语义建模页发布）；仅 SELECT/WITH 语义（wren 编译边界兜底，ADR D5）。
2. **`WrenInstanceRegistry`**（runtime 包）：groupId → 常驻 `wren serve mcp` 子进程池
   （按需 spawn + 空闲回收；publish 时 respawn 该组实例；发布 = 重建实例，ADR D8）。
3. **预注入扩展**：`DataDynamicContextMiddleware` 渲染 `[DATA_SOURCES_OVERVIEW]` 时按组
   分流——PUBLISHED 组渲染逻辑模型 + cube 成员清单（读 `mdl/{groupId}/mdl.json`，经
   DatasetScope 过滤），未发布组维持现状物理表渲染（D3/D10）；系统提示词补双通道路由
   规则（D7 三层信号落为提示词硬规则）。
4. **建表 DDL 统一 collation**：`TableProvisioner` 显式 `utf8mb4_0900_ai_ci`（ADR D7(e)
   实证风险；存量表不迁移，另行评估）。
5. tools.json 演示态挂载（拓扑 A）在 M3 期间保留；M4 已清理（wren 通道由 WrenInstanceRegistry
   按组服务，全局挂载的探针 MCP 会误导 LLM，见 2026-09-28 诊断）。

### M4 外部数据源组的 wren 连接（运行期动态 profile）——已落地（ADR 0021）

> 背景实证：外部数据源在页面运行期配置（服务启动后才入库），且表在远程实例；M3 的
> 单一 profile（从 `dataagent.dataset.datasource.url` 启动期生成）够不着这些表，运行期
> 必炸 1146（详见 ADR 0021 与 2026-09-28 诊断记录）。

1. **发布期数据源解析**（`MdlPublishService#selectWrenSource`）：按组内数据集的
   `origin`/`externalDataSourceId` 判定 wren 连接目标——
   - 全部上传表 → 默认 profile（`dataagent`，指向平台数据集库，行为与 M3 一致）；
   - 全部关联自**同一**外部数据源（kind=mysql）→ 专属 profile `ext-<dsId>`；
   - 混合（上传+外部，或多个外部源，或源已删除，或 kind≠mysql）→ 发布失败，返回
     结构化中文错误（响亮失败优于运行期 1146）。
2. **发布期连通性预检**（`WrenSourceProber` 接口 + JDBC 实现）：发布前用目标连接验证
   组内每张物理表存在（information_schema 查询），失败即拒绝发布并给出中文原因；把
   「表不可达」从运行期提前到发布期。
3. **profiles.yml 多条目化**（`WrenProfileHome#ensureAll`）：文件内容 = 默认 profile +
   数据库里每个 MySQL 外部源一条 `ext-<dsId>`（幂等全量重建，单一事实源=DB；无库名
   URL 的 database 写空串——wrenai pydantic 接受空串，MySQL 协议允许不选库，重写后的
   物理 SQL 自带 schema 限定）。启动 `ensure()` 与发布 `ensureAll()` 同源，重启不会
   抹掉已发布组的外部条目。
4. **快照携带连接选择**：发布成功后写 `published/wren-source.properties`
   （`profile=...`），`WrenInstanceRegistry#spawn` 读它选 `--profile`；缺失（M3 存量快照）
   回落默认 profile——旧组零迁移。
5. **工具描述补 LIMIT 约束**：`wren_run_sql` 描述明确「SQL 内禁止写显式 LIMIT/OFFSET，
   行数用 limit 参数」——wrenai connector 会无条件尾部追加 LIMIT（叠加即 1064，
   源码实证）。

## 影响面

- 后端：新增 `SemanticModelingController` / `MdlSuggestionService` / `MdlPublishService` /
  `WrenToolkit` / `WrenInstanceRegistry` / `SemanticCubeEntity`；修改
  `RelationInferenceService`（保护+盲区）、`DatasetGroupEntity`/`DatasetRelationEntity`
  （加列）、`DatasetService`（脏标记置位）、`DataDynamicContextMiddleware`（按组渲染）、
  `DataToolkitRegistrar`（注册 WrenToolkit，M3）、`TableProvisioner`（collation，M3）
- 前端：`SemanticModelingPage.tsx`（新增）、`DatasetGroupPage.tsx`（NAV_ITEMS +1 Tab）、
  `api/semanticModeling.ts`（新增）
- 数据：`DatasetGroupEntity`/`DatasetRelationEntity` 加列、新表 `dataagent_semantic_cube`
  （JPA 自动）；MDL 产物 `~/.agentscope/dataagent/mdl/{groupId}/`
- 文档：M3 完成后更新 `ARCHITECTURE_zh.md` 2.6/4.6/5.6（「目标态未实施」→已实施表述）
  与 README 配置表（wren 可执行路径/实例池参数）；主流程变更（预注入分流）同步第 3/4 章

## 验收标准（Given-When-Then）

**M1**
1. Given 两表共享列且维表主键在首列（如订单表.用户id / 用户表.用户id），When 请求关系建议，
   Then 返回该候选且 joinType 为探测值（首列盲区修复 + COUNT DISTINCT 探测生效）
2. Given 存在 CONFIRMED 关系，When 组内再上传新表触发 `reinferGroup`，Then 该记录仍在
3. Given 用户 B 请求用户 A 的组建模建议，When 调 API，Then 404（多租户）
4. Given cube 候选含金额列，When 查看候选卡，Then 建议 SUM 指标且可改聚合函数/加 CASE 条件

**M2**
5. Given 组内 2 表 + 1 CONFIRMED 关系 + 1 cube，When 点发布，Then MDL 产物落
   `mdl/{groupId}/` 且 `validate --strict` 通过、组状态变 PUBLISHED v1
6. Given 已发布组再上传新表，When 查看组状态，Then 变 DIRTY 且旧版本 MDL 不变
7. Given cube 引用不存在的列，When 点验证，Then 结构化错误内联显示且不置 PUBLISHED

**M3**
8. Given 组 PUBLISHED，When 问数，Then `[DATA_SOURCES_OVERVIEW]` 渲染逻辑模型 + cube
   成员清单（非物理表 schema）
9. Given 组未发布，When LLM 调 `wren_query_cube`，Then 返回引导性错误文案
10. Given 组未发布，When 走现有问数流程，Then 四工具行为与改动前完全一致（回归快照）
11. Given 新上传建表，When 查 DDL，Then collation 为 utf8mb4_0900_ai_ci

**M4**
12. Given 组内 3 表全部关联自同一 MySQL 外部源且可达，When 发布，Then 成功、快照带
    `profile=ext-<dsId>`，且问数（run_sql/query_cube）打到外部源实例返回数据
13. Given 组内同时含上传表与外部源表，When 发布，Then 发布失败且错误信息说明跨实例
    不可 JOIN 并给出整改指引
14. Given 外部源表不可达（密码错/表被删），When 发布，Then 发布失败且错误含具体表名
15. Given M3 时期发布的存量组（快照无 wren-source.properties），When 问数，Then 行为
    与升级前一致（默认 profile 回落）

## 不做的事

- 不改现有上传/选表/问数流程与 `DataAgentToolkit` 四工具的任何行为（D10；验收 10 保障）
- 不做 wren 知识库下沉（knowledge/memory 召回留平台，ADR D3）
- 不做实例池高级策略（TTL/上限/预热调优）与热加载验证（发布=重建实例，ADR D8）
- 不做存量 `ds_` 表 collation 迁移（只管新表 DDL）
- 不移除 tools.json 演示态挂载（拓扑 A 保留；M4 已例外清理遗留 wren 探针条目——它在双通道
  上线后持续误导 LLM，详见 2026-09-28 诊断与 ADR 0021 背景）
- 不做发布回滚/多版本管理（仅版本号递增 + 重新发布覆盖）
- 不做 PostgreSQL 外部源的 wren 发布（MDL `data_source`/parse-types 方言与全局类型缓存
  按方言隔离，另立里程碑；ADR 0021 记录）
- 不做跨实例 JOIN（物理不可能：一个 wren 工程一条连接；混合组直接拒绝发布）

## 测试要求

- `RelationInferenceServiceTest`：人工记录保护（reinfer 后 CONFIRMED 边仍在）+ 首列共享
  列推出边用例 + 「看不到别人的组」用例
- `MdlSuggestionServiceTest`：候选合并去重、joinType 探测、LLM 建议解析容错
- `MdlPublishServiceTest`：YAML 拼装对拍 proj5 探针范式（关系列/计算列/cube）、validate
  失败回传、状态流转（NONE→PUBLISHED→DIRTY→PUBLISHED）
- `WrenToolkitTest`：未发布组引导性错误、多租户（B 的 scope 路由不到 A 的组）
- `DataDynamicContextMiddlewareTest`：未发布组 `[DATA_SOURCES_OVERVIEW]` 输出与改动前
  一致（快照断言）；PUBLISHED 组渲染逻辑模型
- `WrenProfileHomeTest`：多条目渲染（默认+外部源）、无库名 URL database 空串、
  非 MySQL 源跳过
- `MdlPublishServiceTest`：混合组拒绝、单一外部源组写 wren-source.properties、
  预检失败拒绝发布（prober 注入 stub）
- `WrenInstanceRegistry` profile 解析：存量快照（无 properties）回落默认、
  新快照读 profile 键（纯静态方法单测）

## 关联

- ADR 0018（全部设计依据，D1~D10）
- 探针产物范式：`target\wren-probe\proj5`（cube YAML/关系列范式）、`pk.py`（子进程参数
  数组语义）、`probe_query_cube.py`（query_cube 行为基线）
