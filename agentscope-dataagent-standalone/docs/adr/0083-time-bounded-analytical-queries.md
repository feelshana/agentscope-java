# ADR 0083：时间字段模型的聚合与 DISTINCT 必须带时间条件

> 日期门禁部分已由 [ADR0097](0097-llm-per-table-sql-window.md) 取代；保留历史背景，其余独立约束按当前实现适用。

## 状态

已接受

## 背景

建模和问数都通过 Wren CLI/MCP 执行。对包含时间字段的表做无条件聚合或 `DISTINCT ... LIMIT` 仍可能扫描整张表，导致上传数据和真实业务库出现长时间、不可控的探测查询。MDL 列描述已经承载采样结果，LIMIT 不能作为扫描边界。

## 决策

当 SQL 使用聚合函数或 `SELECT DISTINCT`，且引用时间字段时，必须在时间字段上提供明确的 `WHERE`/`BETWEEN`/比较条件。没有时间条件的查询在进入官方 CLI/MCP 前拒绝；`LIMIT`、`TOP`、分页不能替代时间条件。规则同时应用于建模的 `wren_cli query/dry-run/dry-plan` 和问数的官方 MCP `run_sql/dry_run/dry_plan`。不自动改写 SQL，向模型返回中文修正提示。

## 后果

服务端边界保护数据库，提示词同步告知 Agent；合法的时间范围业务查询仍交给 Wren 官方能力。字段识别采用 SQL 中的时间字段命名和已发布 MDL 描述，后续可在官方解析器提供结构化字段信息时替换为精确解析。
