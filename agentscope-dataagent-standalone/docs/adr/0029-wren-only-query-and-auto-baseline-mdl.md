# ADR 0029：Wren 单一问数通道与基础 MDL 自动发布

- 状态：Accepted
- 日期：2026-10-02

## 背景

当前问数同时存在物理表直查通道 `prepare_data_context/query_structured_data` 与 Wren 语义查询通道 `wren_run_sql/wren_query_cube`。双通道使模型需要判断知识库发布状态、区分 groupId、sourceId、datasetId 和逻辑模型名，已经出现参数混用、同一问题口径不一致，以及未建模数据绕过业务语义的问题。

平台当前并未把完整 MDL 放入 SystemMessage。`DataDynamicContextMiddleware` 只注入租户过滤后的语义摘要，完整已发布 `mdl.json` 由 Wren 运行时加载。这个边界应继续保留：完整 MDL 属于查询引擎契约，LLM 只获得轻量目录并按需查询模型细节。

数据上传和外部表关联已经生成表描述、列描述、类型及低基数样例，具备构建最小 MDL 的条件。历史知识库不要求迁移，可以在上线前清空，因此本决策不保留旧的未发布知识库兼容路径。

## 决策

### D1：主问数 Agent 只暴露 Wren 查询通道

从主 Agent 工具注册、提示词和动态路由中移除 `prepare_data_context` 与 `query_structured_data`。结构化数据查询只允许使用 `wren_run_sql` 或 `wren_query_cube`；知识检索和图表工具继续保留。

`DataAgentToolkit` 中与物理表直查相关的实现和测试在调用方迁移完成后删除，不保留“未发布时自动退回物理 SQL”的运行时分支。数据库诊断只能通过服务端受控代码完成，不作为 LLM 工具暴露。

### D2：完整 MDL 只加载到 Wren，SystemMessage 保持轻量

Wren 进程继续加载知识库的完整已发布 `mdl.json`。SystemMessage 只提供当前知识库、发布/构建状态、逻辑模型名称、表业务含义、Cube 和业务规则摘要，不展开完整列清单和完整 MDL JSON。

新增租户安全的 `wren_describe_model` 元数据工具，按一个或多个逻辑模型名返回已发布模型的列、列描述、关系和可用 Cube。工具必须通过 `DatasetScope + RuntimeContext` 解析会话知识库，禁止接受可绕过 scope 的物理数据源 ID。它承担原 `prepare_data_context` 的“按需确认细节”职责，但只读取已发布 MDL，不访问未建模物理表。

### D3：数据资产变更完成后自动发布基础 MDL

以下操作完成后，必须在同一个 WebFlux 请求的 `boundedElastic` 执行链中触发一次基础 MDL 发布：

- 单文件上传：该文件完成入库后；
- 批量上传：整批文件全部完成入库后，只触发一次；
- 外部数据源选表：整批表关联完成后，只触发一次；
- 删除或解除关联：数据集删除完成后。

基础发布执行 `assemble → strict validate → build → snapshot → refresh Wren`。接口在基础发布完成后才返回成功，以保证“上传/选表完成”即表示新表可由 Wren 查询。构建失败不回滚已经成功落库的数据，但接口必须明确返回“数据已保存、MDL 初始化失败”，并保留可重试状态和错误详情。

空知识库删除已发布快照并停止对应 Wren 实例，不生成空 MDL。

### D4：自动发布与人工语义发布采用两种装配模式

`MdlPublishService` 增加明确的发布模式：

- `BASELINE`：包含全部当前 Model/Column、所有 `CONFIRMED` Relationship，以及状态为 `PUBLISHED` 的 Cube/View；排除 `PENDING/REJECTED` Relationship、`DRAFT` Cube/View 和未采纳提案。
- `SEMANTIC`：由用户在建模页显式发布，包含全部已确认 Relationship 与通过校验的 DRAFT/PUBLISHED Cube/View，成功后把相关 Cube/View 标记为 `PUBLISHED`。

因此，新增表可自动进入 Wren，但文档增强和对话建模产生的 Cube/View 草稿不会借由后续上传被自动发布。Term 与 Business Rule 仍存放在 MDL 之外，并按知识库范围注入运行时语义上下文。

首次基础发布成功后状态为 `PUBLISHED`。已有已发布快照时，数据变更先进入 `DIRTY`；基础发布成功但仍存在语义草稿时保持 `DIRTY`，同时新快照可查询。首次构建失败为 `FAILED`；已有快照的重建失败保持 `DIRTY` 并继续服务上一版快照。知识库保存最近一次构建错误，成功后清空。

### D5：不支持 Wren 的数据源组合在写入前拒绝

没有物理直查兜底后，知识库必须满足当前 Wren Source 约束：全上传表、或来自同一个 MySQL 外部数据源的表。混合上传表与外部表、多个外部数据源、非 MySQL 外部源在关联前返回中文校验错误，不产生部分关联数据。

### D6：建模页面以业务名称展示数据集

建模页任何面向用户的表引用统一显示：

```text
<table name> — <中文业务含义>
```

`table name` 使用 `DatasetEntity.name`，中文业务含义使用 `DatasetEntity.description`。当描述为空或与名称相同时显示“业务含义待补充”；`datasetId`、上传物理表 `ds_*` 名称和 schema 只作为 API value 或诊断详情，不得作为列表、关系、Cube、View、MDL 视图及提案卡片的主标签。

选择器采用单行标签，普通卡片采用“表名 + 次级描述”两行展示。列仍显示原始列名，并在可用时附带中文列描述。

### D7：不迁移历史知识库

上线前允许通过现有管理能力清空历史知识库及其 Wren 工作目录。本次实现不提供旧 `NONE` 知识库批量补建、旧状态回填、旧快照转换或双通道兼容开关。新流程只保证变更上线后创建或重新导入的知识库。

## 替代关系

本 ADR 自实施起替代 ADR 0018 的 D10“双通道并存”、ADR 0019 中“发布不是问数许可证/只允许显式发布”的产品决策，以及 ADR 0020 中依赖未发布物理表直查的路由部分。上述 ADR 的关系建模、构建、快照和运行时隔离结论继续有效。

## 取舍

- 选择 Wren 单通道，牺牲未发布物理表的即时直查能力，换取统一的语义、安全和审计边界。
- 选择轻量 SystemMessage 加按需 describe，而非完整 MDL 全量注入，避免上下文膨胀并确保模型只看到当前租户的相关语义。
- 选择请求内完成基础发布，而非后台最终一致，保证上传成功与可查询状态一致；代价是上传和选表请求耗时增加。
- 选择 BASELINE/SEMANTIC 双发布模式，而非自动发布所有草稿，避免数据导入动作越过人工语义确认边界。
- 选择清空历史数据，而非维护迁移和兼容分支，降低单通道切换复杂度。

## 影响

- 数据集上传、批量上传、选表关联和删除流程增加基础 MDL 发布编排；
- MDL 装配增加 BASELINE/SEMANTIC 模式、错误状态和空知识库清理；
- 主 Agent 移除两个物理表工具，增加 Wren 模型详情工具；
- 动态上下文和 Wren 错误提示不再包含直查回退建议；
- 语义建模页统一使用表名与中文业务含义，并隐藏 `ds_*` 主标签；
- 需要更新 `ARCHITECTURE_zh.md` 的问数工具链、数据导入、MDL 生命周期与建模页面章节。

## 失效条件

若未来 Wren 原生支持当前被拒绝的混合源、多外部源或非 MySQL Source，可放宽 D5，但不得恢复绕过 MDL 的 LLM 物理 SQL 工具。若完整 MDL 的规模和检索能力发生根本变化，可重新评估 D2 的上下文切分方式。
