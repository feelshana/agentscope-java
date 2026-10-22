# 073 基础语义建模与 Cube 分阶段

## 基础建模阶段

- 允许写入 `models/`、关系、`views/` 和 `knowledge/`。
- 拒绝写入 `cubes/`，返回中文错误并提示切换指标建模模块。
- 拒绝 `wren_cli cube ...`，不执行 Cube 验证查询。
- 官方 skill 提出的指标保存为待处理提案，不改变已发布 MDL。

## 指标建模阶段

独立模块读取提案，允许创建/编辑 Cube，执行 `context validate`、`context build` 和 `cube query --sql-only`，经显式发布后才对问数可见。

## 问数阶段

已发布 Cube 覆盖问题时优先 `query_cube`；无匹配 Cube 时使用 View 或 Model 的 `run_sql`。两阶段共享租户和发布权限边界。
