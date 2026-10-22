# 074 禁用建模自动探测

## 行为

- 建模提示明确写“禁止”，不使用“优先复用、必要时查询”的软约束。
- `OfficialWrenToolkit` 在 CLI 执行前识别 DISTINCT、COUNT、MIN、MAX、GROUP BY 和超宽时间范围并返回中文错误。
- 采样阶段生成 `properties.table_role` 和 `properties.temporal_columns`；未知角色按高风险处理。
- 基础建模保留 Model、Relationship、View、Knowledge、validate/build；Cube 仍由独立指标模块处理。
- 不自动把拒绝的 SQL 改写成 100 年时间范围。

## 验证

覆盖无时间 DISTINCT、COUNT DISTINCT、MIN/MAX、GROUP BY、2000-2100 时间范围和普通结构校验命令；确认拒绝发生在 Wren CLI 之前。
