# spec 015: Wren 单通道、基础 MDL 自动发布与建模页业务化展示

> 状态：已实施（2026-10-02）

## 背景与目标

当前未发布知识库通过 `prepare_data_context/query_structured_data` 直查物理表，已发布知识库通过 Wren 查询。该双通道增加路由和参数歧义，也允许问数绕过已定义的语义口径。

本规格将所有结构化问数统一到 Wren：数据上传、批量上传或外部源选表结束时自动生成并发布最小可查询 MDL，后续文档增强和对话建模只完善 Relationship、Cube、View、Term 与 Business Rule。建模页同时停止把 `datasetId` 或 `ds_*` 物理名作为用户主标签，统一显示“表名称 + 中文业务含义”。

历史知识库及旧 Wren 工作目录可在实施前清空，本规格不包含迁移、回填或兼容逻辑。

## 目标流程

```text
上传文件 / 批量上传 / 外部源选表 / 删除数据集
  → 保存数据集及表列描述
  → 关系候选刷新
  → BASELINE assemble
       全部 Model/Column
       CONFIRMED Relationship
       仅 PUBLISHED Cube/View
  → wren validate --strict
  → wren build
  → 原子替换 published snapshot 与 mdl.json
  → 刷新知识库 Wren 实例
  → 返回成功；此时可通过 Wren 问数

上传业务文档 / 对话建模
  → 差异分析与人工确认
  → 写入语义资产草稿并标记 DIRTY
  → 用户执行 SEMANTIC publish
  → 校验、构建并发布新版本
```

完整 MDL 仅由 Wren 实例加载。SystemMessage 保留租户过滤后的轻量语义目录；模型需要字段、关系和 Cube 细节时调用 `wren_describe_model`。

## 后端改造

### 1. 发布模式与状态

在 `MdlPublishService` 引入 `PublishMode.BASELINE | SEMANTIC`：

- `BASELINE`：构建全部当前数据集的 Model/Column；Relationship 仅取 `CONFIRMED`；Cube/View 仅取 `PUBLISHED`；不得改变 DRAFT 状态。
- `SEMANTIC`：维持建模页显式发布语义，包含通过校验的 DRAFT/PUBLISHED Cube/View；成功后把 DRAFT 标记为 `PUBLISHED`。
- 两种模式都执行 Source 选择、连通性检查、strict validate、build、snapshot、`mdl.json` 写入和 Wren 实例刷新。
- 发布文件继续使用 staging + 原子替换；失败不得覆盖上一版可用快照。

`DatasetGroupEntity.mdlState` 支持：

- `NONE`：空知识库或尚无任何发布尝试；
- `INITIALIZING`：首版基础 MDL 正在构建；
- `FAILED`：首版构建失败且无可查询快照；
- `PUBLISHED`：当前草稿与快照一致；
- `DIRTY`：存在未发布语义草稿，或数据变更重建失败但旧快照仍可用。

新增 `mdlLastError` 保存最近一次构建失败的中文摘要。成功发布后清空。Wren 是否可查询以“存在有效发布版本/快照”为准，而不是仅判断 `mdlState`。

基础发布成功后：若不存在 DRAFT Cube/View 等未发布语义资产则置为 `PUBLISHED`，否则保持 `DIRTY`。空知识库清理快照、停止 Wren 实例并回到 `NONE`。

### 2. 数据变更编排

新增 `BaselineMdlService`，提供阻塞方法 `publishAfterDatasetChange(ownerId, groupId)`，只负责一次组级 BASELINE 发布。调用者已经位于 `Schedulers.boundedElastic()`，服务内部不得 `block()` Reactor 事件循环。

调用点：

- `DatasetController#upload`：单个 `DatasetService#ingest` 成功后调用一次；
- `DatasetController#batchUpload`：全部文件保存完成后调用一次，不得逐文件构建；
- 外部源关联端点：`DatasetService#associateTables` 整批成功后调用一次；
- 数据集删除端点：删除完成后调用一次；
- 禁止在 `DatasetService#ingest` 循环内部隐式发布，避免批量竞争和重复构建。

首版构建失败时，数据保留，接口返回可识别错误：`数据已保存，但基础 MDL 初始化失败：<原因>`。已有快照的重建失败时继续服务上一版，并返回“新数据尚不可查询”的错误。所有路径保留原 ownerId/groupId 校验。

新增重试端点：

```text
POST /api/dataset-groups/{groupId}/modeling/mdl/initialize
```

该端点调用 BASELINE 模式，不采纳或发布 DRAFT Cube/View；跨租户访问返回 404。

### 3. 数据源组合前置校验

在写入或创建物理表之前验证知识库能否映射为一个 Wren Source：

- 允许：全部为上传数据集；
- 允许：全部来自同一个 MySQL 外部数据源；
- 拒绝：上传数据集与外部表混合；
- 拒绝：多个外部数据源混合；
- 拒绝：非 MySQL 外部数据源。

批量选表必须全有或全无，不得校验到一半留下部分关联。错误文本面向用户使用简体中文。

### 4. Wren 工具与动态上下文

主问数 Agent：

- 保留 `wren_run_sql`、`wren_query_cube`、知识检索和图表工具；
- 新增 `wren_describe_model(model_names, DatasetScope, RuntimeContext)`；
- 移除 `prepare_data_context`、`query_structured_data` 的注册、说明和所有回退提示；
- 删除两个物理直查方法及仅为其服务的代码、依赖和测试，但保留 `DataAgentToolkit` 中知识检索与图表能力。

`wren_describe_model`：

- 只接受已发布逻辑模型名，不接受 datasetId、sourceId、schema 或物理表名；
- 从当前会话 scope 可见的已发布 `MdlCatalog` 返回列名、类型、中文描述、Relationship 和相关 Cube；
- 支持一次描述多个模型，并限制模型数量和输出长度；
- 未发布、越权或未知模型返回不泄露存在性的中文错误。

`DataDynamicContextMiddleware`：

- `[DATA_SOURCES_OVERVIEW]` 只输出 Wren 可查询知识库及轻量模型目录；
- 不再输出物理表直查指令；
- 无有效快照的知识库标记为“初始化中/初始化失败，不可问数”；
- `DIRTY` 且存在快照时明确“当前查询使用已发布版本，草稿尚未生效”。

`WrenToolkit` 删除 NONE/DIRTY 时改走物理直查的提示。无快照统一拒绝；有快照的 DIRTY 继续查询已发布版本。

## 建模页业务化展示

### 统一标签规则

在 `SemanticModelingPage.tsx` 建立唯一格式化函数，禁止各区域自行拼接：

```text
主标签：Dataset.name
次级含义：Dataset.description
选择器：Dataset.name — Dataset.description
```

若 description 为空、纯空白或等于 name，次级含义显示“业务含义待补充”。`Dataset.id`、`tableName`、`schemaName` 继续作为请求参数和诊断信息，但不作为默认可见名称。

### 覆盖区域

以下区域必须使用统一标签：

- 数据集列表：名称列显示表名，下一行显示中文业务含义；
- 关系候选、已确认关系、已拒绝关系；
- 手工关系的源表/目标表选择器；
- Cube 列表、Cube 建议、Cube 编辑器的基准表；
- View 列表与 View 编辑器的基准表；
- MDL 视图中的 Model、Relationship、Cube 基础模型；
- 文档增强提案中已知的 `datasetId/sourceDatasetId/targetDatasetId/baseDatasetId`；
- 空值和已删除引用显示“未知表”，不得回退显示原始 ID。

列引用仍使用 `originalName` 优先，并在关系/字段选择器中附加 `ColumnSchema.description`；实际提交值保持物理列名。

后端 `SemanticModelingController#overview` 已返回完整 DatasetVO，本次不为关系、Cube、View 重复增加名称字段。MDL 结构视图使用其 `models` 中的 `modelName → datasetName/description` 映射完成业务化展示。

## 前端交互

- `ModelingGroupState` 增加 `mdlLastError`，状态徽标支持 `INITIALIZING/FAILED`；
- 首版失败显示错误卡与“重新生成基础 MDL”按钮；
- `DIRTY` 且存在发布版本时说明当前 Wren 使用旧快照；
- 上传/选表请求因基础发布失败返回错误时，页面刷新知识库状态，不把已保存数据误报为丢失；
- 不新增 UI 组件库。

## 影响面

- 后端：`DatasetController`、外部源关联 Controller、`DatasetService`、`BaselineMdlService`、`MdlPublishService`、`DatasetGroupService`、`WrenToolkit`、`DataAgentToolkit`、`DataDynamicContextMiddleware`、`MdlCatalog`、`WrenInstanceRegistry`、相关配置装配。
- 前端：`frontend/src/api/datasets.ts`、`frontend/src/api/semanticModeling.ts`、上传/选表调用页面、`SemanticModelingPage.tsx`。
- 数据：`DatasetGroupEntity` 增加最近构建错误字段；历史数据不迁移，上线前清空知识库和 Wren 产物。
- 文档：同步更新 `ARCHITECTURE_zh.md` 的工具链、上传/关联、MDL 生命周期和 Wren 运行时章节；若无新增配置项，不修改 README 配置表。

## 实施顺序

1. 增加发布模式、状态/错误字段、BASELINE 过滤和空知识库清理，并补齐服务测试。
2. 在上传、批量上传、选表、删除链路接入一次性基础发布及重试 API。
3. 增加 `wren_describe_model`，切换动态上下文与 Wren 状态判断。
4. 移除物理直查工具注册、提示、实现和测试，清理无用依赖。
5. 统一建模页数据集/模型显示标签及错误状态交互。
6. 更新架构文档，执行全量后端、打包、前端构建和真实 Wren 冒烟验证。

## 验收标准（Given-When-Then）

1. Given 新建空知识库，When 上传一个文件且接口成功返回，Then 已生成可通过 Wren 查询的首版 MDL，状态为 PUBLISHED。
2. Given 批量上传多个文件，When 上传完成，Then 只执行一次 validate/build/publish，且首版包含全部文件对应模型。
3. Given 从同一 MySQL 数据源一次选择多表，When 关联完成，Then 只发布一次基础 MDL，所有表可被 `wren_describe_model` 描述。
4. Given 知识库存在 DRAFT Cube/View，When 新增或删除数据集触发 BASELINE 发布，Then 草稿不进入已发布 MDL且状态仍为 DIRTY。
5. Given 基础构建失败且没有旧快照，When 用户问数，Then Wren 工具返回初始化失败而不调用物理 SQL；建模页可查看错误并重试。
6. Given 重建失败但存在旧快照，When 用户查询旧模型，Then 仍使用旧快照；新增模型不可见且页面提示尚未发布。
7. Given 混合源、多外部源或非 MySQL 表选择，When 用户确认关联，Then 在写入前整体拒绝且不产生部分数据集。
8. Given 主 Agent 已启动，When 检查工具清单和 SystemMessage，Then 不存在 `prepare_data_context/query_structured_data` 及其回退说明，也不包含完整 MDL JSON。
9. Given alice 与 bob 拥有不同知识库，When alice 调用 `wren_describe_model` 或初始化 API，Then 只能读取/构建 alice scope 内模型，无法探测 bob 的模型或错误详情。
10. Given 数据集名为 `orders`、描述为“客户订单事实表”，When 打开建模页任一区域，Then 显示 `orders — 客户订单事实表` 或等价两行样式，不以 datasetId/`ds_*` 为主标签。
11. Given description 缺失或等于 name，When 页面显示该表，Then 显示“业务含义待补充”，且未知引用显示“未知表”而不是原始 ID。
12. Given 文档增强或对话建模产生 Relationship/Cube/View 草稿，When 尚未执行 SEMANTIC publish，Then Wren 查询仍使用已发布语义；显式发布后新语义才生效。
13. Given 删除最后一个数据集，When 删除完成，Then 发布快照和 Wren 实例被清理，知识库回到 NONE 且问数明确不可用。

## 测试要求

- `MdlPublishServiceTest`：BASELINE/SEMANTIC 内容差异、DRAFT 过滤、状态转换、失败保留旧快照、空组清理。
- `BaselineMdlServiceTest`：首次成功/失败、已有快照失败、错误清理、ownerId/groupId 校验。
- `DatasetControllerTest`：单文件一次发布、批量一次发布、数据保存但发布失败的错误语义、删除后发布。
- 外部源关联测试：同源 MySQL 成功；混合源、多源、非 MySQL 整体拒绝且无部分写入。
- `WrenToolkitTest`：describe 参数校验、scope 隔离、NONE/FAILED 拒绝、DIRTY 使用已发布快照。
- `DataDynamicContextMiddlewareTest`：只注入 Wren 目录、无物理回退指令、不注入完整 MDL。
- 装配测试：主 Agent 工具清单不含两个旧工具，仍含检索、图表和 Wren 工具。
- `SemanticModelingControllerTest`：初始化重试及跨租户 404。
- 前端构建覆盖所有新增状态和严格类型；人工检查所有建模区域无 `ds_*` 主标签。
- 端到端冒烟：上传 specs/014 fixture 对应表后不手工发布即可执行至少一个 `wren_run_sql`；建立草稿后再次上传，确认草稿未自动生效。

## 不做的事

- 不迁移、回填或兼容历史知识库、历史 NONE 状态和历史 Wren 快照；
- 不把完整 MDL 放入 SystemMessage；
- 不自动采纳文档提案、PENDING Relationship 或 DRAFT Cube/View；
- 不恢复任何 LLM 可调用的物理表 SQL 后门；
- 不在本规格中扩展 Wren 对混合源、多外部源或非 MySQL Source 的支持；
- 不改变 Term、Business Rule、Instructions 和 Query Memory 的独立存储边界。

## 关联

本规格实施后替代 specs/012 中按发布状态选择双通道的行为；该规格的技能文件定位与验证方式仍可复用。

- ADR 0029：Wren 单一问数通道与基础 MDL 自动发布
- ADR 0018：Wren 语义层与 MCP 集成
- ADR 0020：Wren 运行时与提示词分离
- ADR 0028：文档驱动语义差异分析与人工采纳
- specs/010：语义建模与 Wren 发布
- specs/013：对话式 MDL 建模
- specs/014：文档驱动语义差异分析
