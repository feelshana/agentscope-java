# spec 019: 语义建模 YAML-first 全量官方化

## 背景与目标

用户拍板「全部调用 wrenAI」并已完成官方能力调研（wrenai 0.15.0 CLI 实测 + docs.getwren.ai 的 CLI/MDL 参考全文 + 官方仓库 README）：OSS 已完整覆盖语义上下文层全链路——5 个官方剧本（generate-mdl / enrich-context / onboarding / dlt-connector / genbi，内容随包分发、无版本漂移）、MDL 全字段面（ref_sql 派生模型、计算列、对象列/投影列、is_hidden 选择性暴露、复合主键、cube hierarchies、knowledge/ 布局）、确定性校验链（context validate 已含视图 dry-plan、build、dry-plan 免库预检、dry-run、cube query --sql-only）、memory 14 子命令、serve mcp（17 工具/5 资源/工作流 SOP）；官方明确不做（商业版边界）：用户/组权限、UI、审计。

平台现状与目标的差距（前序源码核实）：平台自建编译器把语义资产封闭为四元组（cube measure = agg 枚举 + column + caseFilter；投影列名发布期合成为「对端数据集名_列名」），导致三类结构性断点：① cube 自由表达式被忽略（LLM 写 expression 直报「聚合函数非法」）；② 跨表投影列名对生成侧不可见（写物理列名报「引用不存在的列」）；③ cube 无 dry-run 校验断档（坏 caseFilter 穿透至问数期）。三者在「文件为唯一事实源、agent 手写官方 YAML」形态下结构性消失。

三项先决拍板（2026-10-04 用户确认）：

1. **文件所有权＝播种＋项目自持**：平台仅首次播种物理模型文件；此后文件归项目，agent 经 HITL 的编辑永久保留；表结构变化走追加对账，绝不重写。
2. **发布语义＝工作区即真相**：写入经 HITL 确认即生效于工作区；发布＝完整校验＋快照＋版本；上传新表触发的自动发布包含工作区全部已确认内容。
3. **关系写面＝relationships.yml**：确认动作直接写工程文件；数据库仅保留候选建议队列。

目标：语义建模阶段由建模 agent 按官方剧本手写工程 YAML、官方 CLI 做确定性校验、HITL 确认沿用现有卡机制；平台自建语义编译器与结构化写工具退役；问数链路与治理层（多租户/锁/快照/审计）不受影响。

## 方案概述

### 1. 事实源重划

| 资产 | 现状事实源 | 目标事实源 |
| --- | --- | --- |
| 数据集/物理结构 | 数据库 | 数据库不变（作为播种输入） |
| 物理模型文件 | DB → 每次 assemble 全量重渲染 | 文件（平台播种，此后归项目） |
| 计算列/投影列 | 平台发布期合成（addRelationColumns） | agent 写进模型文件 |
| 关系 | DB 状态机 + 渲染 | relationships.yml（DB 仅候选队列） |
| Cube / View | DB 实体 → 编译 | cubes/、views/ 文件 |
| 术语/业务规则 | DB 实体 → prompt 注入 | knowledge/ 文件 → prompt 注入切源 |
| 发布快照/版本 | published/ + group | 不变 |

### 2. 工作区与目录

`<mdlRoot>/<groupId>/workspace/` 为语义事实源（官方 schema_version 5 布局，骨架由 `wren context init --empty` 生成，官方明说该模式为「AI agent 自行填充 models/」设计）；发布时合成 staging `project/`（workspace 拷贝＋播种器补缺）→ 校验 → `published/` 快照；`published.next`/`mdl.json.next` 等既有机制不变。per-group 锁覆盖「写入工作区」与「校验/发布」两类操作。

### 3. 播种器与对账（新增，dataset 包）

- 数据集进入知识库：`models/<name>/metadata.yml` 不存在→按 DB 列元数据播种（table_reference + 物理列 + 描述）；已存在→仅追加缺失列，类型冲突/列删除写入「请人工修复」清单（随发布 issues 呈现），不自动改。
- 数据集移出知识库：移除对应模型文件并检查引用（任何 cube/relationship/view 引用进入修复清单）。

### 4. agent 工具面

- **文件工具**（平台侧 host 执行、路径锁组工作区、禁写 wren_project.yml 等平台文件）：list/read/write/patch；写工具加入 `ModelingHitlMiddleware.WRITE_TOOL_NAMES`；写前三重闸——YAML parse → scratch 副本 `dry-plan` 预检（免 DB）→ 写入后 `context validate`（失败单文件回滚）。
- **官方命令包**：`skills list/get`（剧本按需投递，取代静态剧本）、`context show` / `context instructions`、`dry-plan`、`dry-run`、`cube list/describe/query --sql-only`；memory 子集为可选项。
- **退役**：结构化写工具（create_cube/update_cube/delete_cube/create_view/update_view/delete_view/create_term/delete_term/create_business_rule）；`list_modeling_state` 改读工程文件（或交接 `context show`）；关系类工具保留但写面改为文件。

### 5. HITL 卡 v2（前端）

新增 YAML 变更卡：文件路径＋摘要＋diff（折叠）＋预校验结论＋全文可编辑（沿用现有卡「修改参数后采用」通道）；交互对齐官方五拍（说缺口+来源 → 草案 → 落点 → accept/edit/skip → 写回）。

### 6. 发布链 v2

`validate(workspace)`：assembler 改为「workspace 拷贝 + 播种补缺」→ `context validate --strict` → `context build` → 逐视图 `dry-run`（沿用 ADR 0032）→ **逐 cube `cube query --sql-only`（新增）**；任一失败产生 error issue、中止发布并保留旧快照；自动发布（上传触发）走同一链条并对工作区做全量快照；DIRTY/PUBLISHED 状态机退役。

### 7. 读面切换

- `MdlPublishService.view()/preview()` 改从工程文件解析（优先官方 `context show`/`cube describe` 结构化输出，YAML 解析兜底）；API 形状不变，前端主路径零改动。
- 关系确认 API 改写 relationships.yml（候选队列保留）；`MdlRelationView` 由文件解析产出。
- `DataDynamicContextMiddleware` 知识注入切 `knowledge/rules`（glossary 为可选项）。

### 8. DocEnhance v2

上传文档 → 建模会话「按 enrich-context 剧本增强」（文档入会话上下文）→ 变更走文件工具 + HITL 卡；六类结构化提案的生成/采纳路径退役；官方 cube_proposals 决策树、防重守卫（先 cube list/describe）、双验证（validate + query --sql-only）照搬。

### 9. 版本与技能管理

wren pin 0.15.0（`pip install 'wrenai[mysql,mcp]' 'mcp<2'`）；skill 清单动态化（以已装版本 `wren skills list` 为准，官方文档页与本机清单的 usage/genbi 差异不硬编码）；升级走新 ADR。

### 10. 官方能力调用对照（附录）

| 平台组件（改造前） | 改造后 | 官方对应物 |
| --- | --- | --- |
| 渲染编译器（measureExpression/addRelationColumns/renderCube/闭集校验） | 退役 | agent 手写 YAML；平台仅保留播种器 |
| 发布校验链 | 保留＋补 cube | validate --strict / build / dry-run / cube query --sql-only / dry-plan |
| 9 个结构化写工具 | 退役 | 文件读写（HITL）＋ skills get 剧本 |
| list_modeling_state | 换源 | context show / cube describe |
| DocEnhance 六类提案 | 改造为 enrich 会话 | enrich-context（含 cube_proposals、gap_catalog） |
| DB 术语/规则注入 | 切源 | knowledge/rules（context instructions / memory fetch） |
| 关系建议与确认 | 候选保留、确认写文件 | relationships.yml |
| 查询链 | 维持（已用官方） | serve mcp / query / cube query |
| 快照/版本治理 | 保留（官方无对应物） | —（官方 git sync 不适用多租户） |
| genbi / cloud / OSI / dbt 导入 | 不采纳 | — |

主流程变更：实施后需同步 ARCHITECTURE_zh.md（装配、问数工具链与语义建模章节）。

## 影响面

- 后端：dataset（MdlPublishService 重构、播种器新增、DocEnhanceService 改造、MdlSuggestionService 关系写面）、tools/data（ModelingToolkit 大改：文件/CLI 工具；WrenCli 复用与命令扩展）、web/middleware（WRITE_TOOL_NAMES）、web/config（DataAgentConfig 建模 prompt 与剧本投递改写）、runtime/wren（读面与知识注入）
- 前端：ModelingHitlCard（YAML 变更卡）、semanticModeling 类型与页面微调、文档增强相关页（提案列表改会话入口，壳保留）
- 数据：无新表无迁移（DB 语义实体保留表结构，停止作为事实源写入；关系候选队列沿用）
- 文档：ARCHITECTURE_zh.md、README 配置表（若新增配置项）、ADR 0033

## 验收标准（Given-When-Then，可测试）

1. Given 空组＋test_data orders/customers 上传，When 播种 → agent 按 order.md 推进并逐卡确认 → 发布，Then cube total_valid_revenue=3089.00、paid_customer_count=2、月度 99/2990/0，与手工对账一致（黄金用例）。
2. Given agent 提议修改 models/orders/metadata.yml，When 用户未确认，Then 文件不变；确认后写入且 `context validate` 通过。
3. Given 写工具收到非法 YAML 或越界路径（工作区外、wren_project.yml），Then 拒绝且文件不变。
4. Given cube 引用不存在列或坏表达式，When 写前预检或发布，Then `cube query --sql-only` 拦截并报 error issue。
5. Given 既有文件含 agent 计算列，When 新数据集上传触发对账，Then 既有文件不被重写、仅追加新文件/缺失列。
6. Given 确认一条关系，Then relationships.yml 含该关系、候选状态更新、发布后问数可跨表 JOIN。
7. Given knowledge/rules 写入业务规则，Then 问数侧 [KNOWLEDGE_BASE_OVERVIEW] 注入其内容。
8. Given 他组工作区路径，When 文件工具调用，Then 被租户校验拒绝（看不到别人的数据）。

## 不做的事（明确排除项）

- 不迁移存量 DB 语义实体到文件（用户明确不考虑旧数据迁移）；相关写路径直接退役。
- 不修改 wren 引擎/CLI 本体；不引入 genbi/cloud/OSI/dbt 路径。
- 不做对话内发布；发布仍走页面按钮与自动闸门。
- 数据源维持 MySQL/Doris；不新增 Doris 连接能力。
- 不改变问数链路的 push 预注入分叉（ADR 0020）；不引入新 UI/组件/图表库。

## 测试要求

- 播种器/对账：新增文件、追加列、冲突报告、删除引用检查。
- 文件写工具：HITL 拦截、路径锁、YAML 预检、写后 validate、失败回滚。
- 发布链：cube dry-run 失败中止发布；视图 dry-run 沿用；失败保留旧快照（既有语义回归）。
- 读面：view()/preview() 文件解析与 API 形状回归。
- DocEnhance：文档 → 变更集 → HITL 路径。
- 多租户：跨组读写拒绝、注入不含他家规则（看不到别人的数据）。
- 前端 `npm run build` 零错误；后端 `mvn test` 全绿。

## 关联

- ADR：0033-yaml-first-semantic-modeling.md（实施后）
- 前序：specs/010（语义建模与发布）、013（对话建模）、014（文档增强）、017（HITL 与问数路由）、018（视图门禁）；ADR 0019/0024/0025/0026/0028/0029/0031/0032
