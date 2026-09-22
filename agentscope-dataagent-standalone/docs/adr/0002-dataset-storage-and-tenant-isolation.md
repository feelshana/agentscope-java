# ADR 0002: 上传数据集统一落单库 ds_ 前缀表 + 应用层越权校验

- 状态：已采纳
- 日期：2026（数据集功能落地时）

## 背景

用户上传 Excel/CSV 需要变成可 SQL 查询的物理表。可选方案：每用户独立 schema、
每数据集独立库、或所有用户共享一个库用表名前缀隔离。

## 决策

1. 所有上传数据集物理表落在 `dataagent.dataset.datasource` 指向的同一个 MySQL 库，
   表名 `ds_<owner8>_<dataset8>_<name>`。
2. 租户隔离不在数据库层（不分库分 schema），而在应用层强制：
   - 工具可见性：`DataAgentToolkit#visible` 按 `DatasetScope` 过滤（ownerId / groupIds）；
   - SQL 越权：`checkCrossTable` 用正则扫描 SQL 中的 `ds_*` 表引用，不在调用者
     可见集合内直接拒绝；
   - 服务层：`DatasetService#get` 等入口全部 ownerId 校验。
3. 外部数据库已有表不复制数据，以 `origin=datasource` 只读关联（连接信息存
   `ExternalDataSourceEntity`，查询时直连）。
4. 元数据（DatasetEntity/Group/Relation/图谱/术语）走 JPA，与业务数据物理分离
   （默认 H2 落在 `~/.agentscope-dataagent/`，不放 workspace 目录）。

## 理由与权衡

- 跨数据集 JOIN 是普通同库 JOIN，SQL 生成简单、性能好。
- 不分库：MySQL schema 数不受限，运维只有一个库要管。
- 代价：隔离强度依赖应用层校验的完备性——这是必须把 `checkCrossTable` 和
  `visible` 的越权用例作为强制测试的原因（见 AGENTS.md 测试约定）。

## 被否决的替代方案

- 每用户独立 schema：租户数增长后 schema 爆炸，备份/迁移复杂。
- 上传数据进 H2 平台库：业务数据污染平台库，且 H2 不适合生产分析负载。

## 适用与失效条件

适用于分析查询为只读、单库容量足够的场景。若出现超大数据集或需要行级隔离合规要求，
应评估独立分析库（如 ClickHouse/Doris）并重审本决策。
